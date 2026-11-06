package de.noizycologne.backend.discovery;

import de.noizycologne.backend.calendar.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;

@Service
public class EventImportService {
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private final DiscoveryStore store;
    private final CalendarEntryService calendar;
    private final DiscoveryEvaluationService evaluator;
    private final DiscoveryGeographyService geography;
    public EventImportService(DiscoveryStore store, CalendarEntryService calendar,DiscoveryEvaluationService evaluator,DiscoveryGeographyService geography) { this.store = store; this.calendar = calendar; this.evaluator=evaluator;this.geography=geography; }
    @Transactional
    public void importCandidate(String id, String party, LocalDate date, boolean manual) {
        // The same lock covers automatic and manual imports, including parallel JVMs.
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        var c = store.candidate(id);
        if (PartyOnlyPolicy.concert((String)c.get("title"),(String)c.get("evidence")) || PartyOnlyPolicy.concert(evaluator.event(c)))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,"Konzerte sind von der Discovery-Übernahme ausgeschlossen");
        String lifecycle=EventLifecycle.signal(evaluator.event(c));
        if (List.of("CANCELLED","CANCELLATION_UNCONFIRMED","POSTPONED","RESCHEDULED").contains(lifecycle))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Absage oder Verschiebung zuerst im Änderungsworkflow klären");
        if (Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM event_changes WHERE candidate_id=? AND blocks_publication)",Boolean.class,id))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Offene Veranstaltungsänderung zuerst klären");
        if (List.of("REJECTED", "DUPLICATE", "IGNORED").contains(c.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Archivierten Vorschlag zuerst prüfen");
        if (c.get("calendar_entry_id") != null) {
            if ("IMPORTED".equals(c.get("status"))) return;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bereits übernommen: Änderung im Kalender manuell prüfen");
        }
        geography.requireInside(c);
        if (party == null || date == null) throw new IllegalArgumentException("Partyreihe und bestätigtes Datum sind erforderlich");
        if (!manual) {
            if (!"AUTOMATIC".equals(c.get("decision_origin")) || !Boolean.TRUE.equals(c.get("approved")) || !"READY".equals(c.get("status")))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Automatische Übernahme nicht freigegeben oder manuelle Entscheidung geschützt");
            var current=evaluator.evaluate(c,evaluator.rules());
            var verified=evaluator.verification(evaluator.event(c),(String)c.get("venue_id"),(String)c.get("venue_name"));
            if (!"READY".equals(current.status()) || !party.equals(current.partyId()) || !date.equals(verified.date()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Automatische Übernahme nach erneuter Prüfung gesperrt: "+current.reason());
        }
        String venue = (String)c.get("venue_id");
        if (venue==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Veranstaltungsort zuerst ausdrücklich zuordnen");
        var conflict=evaluator.calendarConflict(evaluator.event(c),venue,party,date,id);
        if (conflict!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,conflict.reason());
        String time = c.get("start_time") == null ? null : c.get("start_time").toString().substring(0,5);
        String relative=EventTiming.relativeStart(evaluator.event(c));
        String notes="Quelle: "+c.get("source_url")+(time==null && !relative.isBlank() ? "\nBeginn: "+relative : "");
        var entry = calendar.createEntry(new CalendarEntryRequest(date.toString(), venue, "event", notes, EventAdmission.price(evaluator.event(c)), party, time, null, Set.of()));
        entityManager.flush();
        store.jdbc().update("UPDATE discovery_candidates SET calendar_entry_id=?,party_id=?,event_date=?,status='IMPORTED',reason=?,updated_at=now() WHERE id=?",
                entry.id(), party, date, manual ? "Manuell geprüft und übernommen" : "Automatisch übernommen", id);
        store.jdbc().update("UPDATE discovery_candidates SET decision_origin=?,previous_status=?,evaluated_at=now() WHERE id=?",
                manual ? "MANUAL" : "AUTOMATIC",c.get("status"),id);
        store.audit(id,(String)c.get("status"),manual ? null : (String)c.get("last_evaluation_id"));
    }
    @Transactional
    public String confirm(String id,String party,LocalDate date,String inquiry,String dm,String seriesAlias) {
        return confirm(id,party,date,inquiry,dm,seriesAlias,null);
    }
    @Transactional
    public String confirm(String id,String party,LocalDate date,String inquiry,String dm,String seriesAlias,String venueId) {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        var candidate=store.candidate(id);
        if ("IMPORTED".equals(candidate.get("status")) && candidate.get("calendar_entry_id") instanceof String entryId) {
            if ((venueId!=null && !venueId.equals(candidate.get("venue_id"))) || !party.equals(candidate.get("party_id")) || !date.equals(((java.sql.Date)candidate.get("event_date")).toLocalDate()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Bereits übernommen; Änderungen direkt im Kalender prüfen");
            return entryId;
        }
        if (List.of("REJECTED","DUPLICATE","IGNORED").contains(candidate.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Archivierten Vorschlag zuerst prüfen");
        geography.requireInside(candidate);
        assignVenue(id,candidate,venueId,true);
        review(id,party,date,"NEEDS_REVIEW",inquiry,dm);
        importCandidate(id,party,date,true);
        rememberAlias(candidate,party,seriesAlias);
        return Objects.requireNonNull((String)store.candidate(id).get("calendar_entry_id"),"Kalenderimport nicht abgeschlossen");
    }
    private void assignVenue(String id,Map<String,Object> candidate,String venueId,boolean required) {
        if (!required && venueId==null) return;
        if (candidate.get("venue_id")==null) {
            if (venueId==null || !Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM venues WHERE id=?)",Boolean.class,venueId)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Veranstaltungsort zuerst ausdrücklich zuordnen");
            if (Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM discovery_candidates WHERE venue_id=? AND identity_key=? AND id<>?)",Boolean.class,venueId,candidate.get("identity_key"),id)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Diese Veranstaltung ist der Location bereits zugeordnet; vorhandenen Vorschlag prüfen");
            if(Boolean.TRUE.equals(candidate.get("special_source"))) geography.remember(venueId,evaluator.event(candidate),(String)candidate.get("source_url"));
            store.jdbc().update("UPDATE discovery_candidates SET venue_id=? WHERE id=?",venueId,id);
            store.jdbc().update("INSERT INTO discovery_candidate_identities(venue_id,identity_key,candidate_id) VALUES (?,?,?) ON CONFLICT DO NOTHING",venueId,candidate.get("identity_key"),id);
        } else if (venueId!=null && !venueId.equals(candidate.get("venue_id"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Der Veranstaltungsort wurde inzwischen zugeordnet; Vorschlag erneut laden");
        }
    }
    void rememberAlias(Map<String,Object> candidate,String party,String seriesAlias) {
        if (seriesAlias==null || seriesAlias.isBlank()) return;
        var rules=evaluator.rules();
        var chosen=Objects.requireNonNull(rules.parties().stream().filter(p -> party.equals(p.getId())).findFirst().orElseThrow());
        String alias=seriesAlias.trim();
        var names=new ArrayList<String>();names.add(chosen.getName());names.addAll(rules.aliases().getOrDefault(party,List.of()));
        if (names.stream().anyMatch(name -> PartySeriesRecognition.containsName(alias,name))) return;
        String normalized=EventNormalizer.text(alias);
        if (alias.length()>200 || normalized.length()<3 || !PartySeriesRecognition.containsName((String)candidate.get("title"),alias)
                || normalized.matches(".*\\b(special|edition|vol|volume|ausgabe|feat|featuring|with|mit|dj|djs|vs|versus|meets|presents|lineup)\\b.*")
                || normalized.matches(".*\\p{N}.*") || alias.matches(".*[@+|].*"))
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,"Nur den belegten wiederverwendbaren Reihennamen als Alias bestätigen, ohne Special, Datum oder Line-up");
        if (!PartySeriesRecognition.recognize(alias,rules.parties(),rules.aliases()).parties().isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Namensvariante gehört bereits zu einer anderen Partyreihe");
        if (Objects.requireNonNull(store.jdbc().queryForObject("SELECT count(*) FROM party_series_aliases WHERE party_id=?",Integer.class,party))>=30)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Die Partyreihe hat bereits 30 Aliase; bitte zuerst im Portfolio prüfen");
        store.jdbc().update("INSERT INTO party_series_aliases(party_id,alias) VALUES (?,?) ON CONFLICT DO NOTHING",party,alias);
    }
    @Transactional
    public void review(String id, String party, LocalDate date, String status, String inquiry, String dm) {
        review(id,party,date,status,inquiry,dm,null);
    }
    @Transactional
    public void review(String id, String party, LocalDate date, String status, String inquiry, String dm,String venueId) {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        var previous=store.candidate(id);
        if (!List.of("NEEDS_REVIEW", "NEEDS_INQUIRY", "REJECTED", "DUPLICATE").contains(status)) throw new IllegalArgumentException("Ungültiger Prüfstatus");
        if (!List.of("NONE", "PREPARED", "SENT", "ANSWERED").contains(inquiry)) throw new IllegalArgumentException("Ungültiger Rückfragestatus");
        if (party != null && !Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM party_series WHERE id=?)", Boolean.class, party))) throw new IllegalArgumentException("Unbekannte Partyreihe");
        assignVenue(id,previous,venueId,false);
        String reason=status.equals("REJECTED") ? "Manuell abgelehnt" : status.equals("DUPLICATE") ? "Manuell als Dublette markiert" : "Manuell geprüft; "+Objects.toString(previous.get("automatic_reason"),"");
        store.jdbc().update("UPDATE discovery_candidates SET party_id=?,event_date=?,status=?,inquiry_status=?,dm_text=?,reason=?,decision_origin='MANUAL',previous_status=?,evaluated_at=now(),updated_at=now() WHERE id=?",
                party,date,status,inquiry,dm,reason,previous.get("status"),id);
        store.audit(id,(String)previous.get("status"),null);
    }
    @Transactional
    public void reject(String id) {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        var candidate=store.candidate(id);
        if (candidate.get("calendar_entry_id")!=null || "IMPORTED".equals(candidate.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Bereits übernommene Veranstaltung im Kalender oder Absageworkflow prüfen");
        if ("REJECTED".equals(candidate.get("status")) && "MANUAL".equals(candidate.get("decision_origin"))) return;
        store.jdbc().update("UPDATE discovery_candidates SET previous_status=status,status='REJECTED',decision_origin='MANUAL',reason='Vom Team sofort abgelehnt: nicht passend zum Portfolio',updated_at=now(),evaluated_at=now() WHERE id=?",id);
        store.audit(id,(String)candidate.get("status"),null);
    }
    public String dm(String id) {
        var c = store.candidate(id);
        return "Hallo liebes " + c.get("venue_name") + "-Team! 👋\n\nWir haben eure „" + c.get("title")
                + "“-Party entdeckt und würden sie gerne bei NoizyCologne einplanen.\n\nLeider ist der Termin für uns noch unklar: "
                + c.get("reason") + "."
                + "\n\nKönnt ihr uns kurz bestätigen, wann die Party tatsächlich stattfindet?\n\nVielen Dank und liebe Grüße\nNoizyCologne";
    }
}
