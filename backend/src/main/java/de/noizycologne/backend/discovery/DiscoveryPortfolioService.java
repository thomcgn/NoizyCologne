package de.noizycologne.backend.discovery;

import de.noizycologne.backend.party.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Explicit one-time series approval uses the existing portfolio and import services. */
@Service
public class DiscoveryPortfolioService {
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entities;
    private final DiscoveryStore store;
    private final DiscoveryEvaluationService evaluation;
    private final PartySeriesService parties;
    private final EventImportService importer;
    private final DiscoveryGeographyService geography;
    public DiscoveryPortfolioService(DiscoveryStore store,DiscoveryEvaluationService evaluation,PartySeriesService parties,EventImportService importer,DiscoveryGeographyService geography) {
        this.store=store;this.evaluation=evaluation;this.parties=parties;this.importer=importer;this.geography=geography;
    }
    @Transactional
    public String approve(String candidateId,String name) {
        return approve(candidateId,name,null);
    }
    @Transactional
    public String approve(String candidateId,String name,String seriesAlias) {
        return approve(candidateId,name,seriesAlias,null);
    }
    @Transactional
    public String approve(String candidateId,String name,String seriesAlias,String defaultStartTime) {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        var candidate=store.candidate(candidateId);geography.requireInside(candidate);var event=evaluation.event(candidate);var rules=evaluation.rules();
        if (List.of("REJECTED","DUPLICATE","IGNORED").contains(candidate.get("status"))) throw conflict("Geschützten Vorschlag zuerst prüfen");
        var recognized=PartySeriesRecognition.recognize(event.title(),rules.parties(),rules.aliases());
        if (recognized.parties().size()==1) return Objects.requireNonNull(recognized.parties().getFirst()).id();
        if (!recognized.parties().isEmpty() || candidate.get("party_id")!=null) throw conflict("Vorhandene Zuordnung zuerst prüfen");
        var coverage=GenreCoverage.assess(event,rules.coverageGenres());
        if (!coverage.eligible()) throw conflict("Weniger als 50 % der erkannten Eventgenres passen zum aktiven Portfolio");
        if (!EventLifecycle.signal(event).equals("ACTIVE") || Boolean.TRUE.equals(candidate.get("has_blocking_change"))) throw conflict("Veranstaltungsänderung zuerst klären");
        Set<String> standardGenres=new LinkedHashSet<>();for (String genre:coverage.names()) standardGenres.add(genre);
        var party=parties.createParty(new PartySeriesRequest(name,"","#E11D48","manual",Set.of(),Set.of(),Set.of(),null,standardGenres,defaultStartTime,null));
        entities.flush();
        importer.rememberAlias(candidate,party.id(),seriesAlias);
        store.jdbc().update("UPDATE discovery_candidates SET party_id=?,decision_origin='MANUAL',reason='Partyreihe ausdrücklich ins Portfolio aufgenommen',updated_at=now() WHERE id=?",party.id(),candidateId);
        store.audit(candidateId,(String)candidate.get("status"),null);
        var verification=evaluation.verification(event,(String)candidate.get("venue_id"),(String)candidate.get("venue_name"));
        if ("AUTOMATIC".equals(candidate.get("decision_origin")) && Boolean.TRUE.equals(candidate.get("approved"))
                && verification.sufficient() && candidate.get("calendar_entry_id")==null)
            importer.importCandidate(candidateId,party.id(),verification.date(),true);
        return party.id();
    }
    private static ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT,reason); }
}
