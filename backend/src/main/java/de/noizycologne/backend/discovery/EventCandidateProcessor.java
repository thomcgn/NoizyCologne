package de.noizycologne.backend.discovery;

import de.noizycologne.backend.venue.Venue;
import de.noizycologne.backend.party.PartySeries;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** Serializes reconciliation with editorial decisions and calendar imports. */
@Service
@Transactional
public class EventCandidateProcessor {
    private final DiscoveryStore store;
    private final DiscoveryEvaluationService evaluator;
    private final EventImportService importer;
    public EventCandidateProcessor(DiscoveryStore store,DiscoveryEvaluationService evaluator,EventImportService importer) {
        this.store=store;this.evaluator=evaluator;this.importer=importer;
    }
    public void process(Venue venue,String sourceId,EventExtractor.Event event,List<PartySeries> known,
            Map<String,List<String>> aliases,List<String> genres,boolean approved) {
        process(venue,sourceId,event,known,aliases,genres,approved,null);
    }
    public void process(Venue venue,String sourceId,EventExtractor.Event event,List<PartySeries> known,
            Map<String,List<String>> aliases,List<String> genres,boolean approved,String operation) {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        String url;
        try { url=SafeUrls.parse(event.url()).toString(); }
        catch (IllegalArgumentException e) { url=store.jdbc().queryForObject("SELECT url FROM discovery_sources WHERE id=?",String.class,sourceId); }
        String identity=EventNormalizer.hash(event.identity().isBlank() ? EventNormalizer.text(event.title())+"|"+event.starts() : event.identity());
        String fingerprint=EventNormalizer.hash(event.title()+event.starts()+event.state()+event.evidence());
        var previous=store.jdbc().queryForList("""
            SELECT c.* FROM discovery_candidates c WHERE c.venue_id=? AND
            (c.identity_key=? OR c.id IN (SELECT candidate_id FROM discovery_candidate_identities WHERE venue_id=? AND identity_key=?))
            """,venue.getId(),identity,venue.getId(),identity);
        var dates=event.starts().stream().map(EventNormalizer::date).distinct().toList();
        LocalDate date=dates.size()==1 ? dates.getFirst() : null;
        // The same event can be exposed as JSON-LD, a text list and a detail URL.
        if (previous.isEmpty() && date!=null) {
            var sameOccurrence=store.jdbc().queryForList("SELECT * FROM discovery_candidates WHERE venue_id=? AND event_date=?",venue.getId(),date)
                .stream().filter(c -> EventNormalizer.text((String)c.get("title")).equals(EventNormalizer.text(event.title()))).toList();
            if (!sameOccurrence.isEmpty()) previous=List.of(sameOccurrence.stream().min(
                Comparator.<Map<String,Object>,Integer>comparing(c -> "AUTOMATIC".equals(c.get("decision_origin")) ? 1 : 0)
                    .thenComparing(c -> (String)c.get("id"))).orElseThrow());
        }
        Map<String,Object> old=previous.isEmpty() ? null : previous.getFirst();
        if (old!=null) store.jdbc().update("INSERT INTO discovery_candidate_identities(venue_id,identity_key,candidate_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",venue.getId(),identity,old.get("id"));
        // Includes ambiguous legacy reviews: uncertainty about provenance never permits overwriting them.
        if (old!=null && !"AUTOMATIC".equals(old.get("decision_origin"))) {
            if (!fingerprint.equals(old.get("fingerprint")) || !EventMatcher.VERSION.equals(old.get("matcher_version"))) {
                var technical=evaluator.evaluate(event,venue.getId(),venue.getName(),approved,new DiscoveryEvaluationService.Rules(known,aliases,genres));
                store.jdbc().update("""
                    UPDATE discovery_candidates SET raw_event=?,fingerprint=?,evidence=?,source_url=?,automatic_reason=?,matching_class=?,
                    matcher_version=?,evaluated_at=now(),last_evaluation_id=?,updated_at=now() WHERE id=?
                    """,evaluator.raw(event),fingerprint,event.evidence(),url,technical.reason(),technical.classification(),EventMatcher.VERSION,operation,old.get("id"));
                store.technicalAudit((String)old.get("id"),(String)old.get("matching_class"),operation);
            }
            return;
        }
        if (PartyOnlyPolicy.concert(event) && old==null) return;
        if (old!=null && fingerprint.equals(old.get("fingerprint")) &&
            (old.get("calendar_entry_id")!=null || (old.get("raw_event")!=null && EventMatcher.VERSION.equals(old.get("matcher_version"))
                && !Objects.toString(old.get("reason"),"").contains("Quelle nicht")
                && !Objects.toString(old.get("reason"),"").contains("Neuer Abruf vor")))) return;
        var match=evaluator.evaluate(event,venue.getId(),venue.getName(),approved,new DiscoveryEvaluationService.Rules(known,aliases,genres));
        var times=event.starts().stream().map(EventNormalizer::time).distinct().toList();
        LocalTime time=times.size()==1 ? times.getFirst() : null;
        String status=match.status(), reason=match.reason();
        if (old!=null && old.get("calendar_entry_id")!=null) {
            status="NEEDS_REVIEW";reason="Änderung an bereits übernommenem Event: "+reason;
        }
        String id=old==null ? UUID.randomUUID().toString() : (String)old.get("id");
        String stableIdentity=old==null ? identity : (String)old.get("identity_key");
        store.jdbc().update("""
            INSERT INTO discovery_candidates(id,venue_id,source_id,identity_key,fingerprint,title,source_url,event_date,start_time,evidence,status,reason,party_id,
             matching_class,automatic_reason,matcher_version,evaluated_at,previous_status,last_evaluation_id,raw_event)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),?,?,?) ON CONFLICT(venue_id,identity_key) DO UPDATE SET
             source_id=excluded.source_id,fingerprint=excluded.fingerprint,title=excluded.title,source_url=excluded.source_url,event_date=excluded.event_date,
             start_time=excluded.start_time,evidence=excluded.evidence,status=excluded.status,reason=excluded.reason,party_id=excluded.party_id,
             matching_class=excluded.matching_class,automatic_reason=excluded.automatic_reason,matcher_version=excluded.matcher_version,
             evaluated_at=now(),previous_status=excluded.previous_status,last_evaluation_id=excluded.last_evaluation_id,raw_event=excluded.raw_event,updated_at=now()
            """,id,venue.getId(),sourceId,stableIdentity,fingerprint,event.title(),url,date,time,event.evidence(),status,reason,match.partyId(),
             match.classification(),match.reason(),EventMatcher.VERSION,old==null ? null : old.get("status"),operation,evaluator.raw(event));
        store.jdbc().update("INSERT INTO discovery_candidate_identities(venue_id,identity_key,candidate_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",venue.getId(),identity,id);
        store.audit(id,old==null ? null : (String)old.get("status"),operation,old==null ? null : (String)old.get("matching_class"));
        if (status.equals("READY")) importer.importCandidate(id,match.partyId(),date,false);
    }
}
