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
    public EventImportService(DiscoveryStore store, CalendarEntryService calendar,DiscoveryEvaluationService evaluator) { this.store = store; this.calendar = calendar; this.evaluator=evaluator; }
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
        if (party == null || date == null) throw new IllegalArgumentException("Partyreihe und bestätigtes Datum sind erforderlich");
        if (!manual) {
            if (!"AUTOMATIC".equals(c.get("decision_origin")) || !Boolean.TRUE.equals(c.get("approved")) || !"READY".equals(c.get("status")))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Automatische Übernahme nicht freigegeben oder manuelle Entscheidung geschützt");
            var current=evaluator.evaluate(c,evaluator.rules());
            var verified=EventVerification.assess(evaluator.event(c),(String)c.get("venue_name"));
            if (!"READY".equals(current.status()) || !party.equals(current.partyId()) || !date.equals(verified.date()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Automatische Übernahme nach erneuter Prüfung gesperrt: "+current.reason());
        }
        String venue = (String)c.get("venue_id");
        var existing = store.jdbc().queryForList("""
            SELECT e.id,e.party_id,e.entry_type FROM calendar_entries e WHERE e.location_id=? AND e.event_date=?
            AND NOT (e.event_status='CANCELLED' AND EXISTS(SELECT 1 FROM event_cancellations x WHERE x.calendar_entry_id=e.id AND x.replacement_candidate_id=?))
            AND NOT EXISTS(SELECT 1 FROM event_changes x WHERE x.calendar_entry_id=e.id AND x.state='SEPARATE' AND x.after_data::jsonb->>'url'=?)
            """,venue,date,id,c.get("source_url"));
        if (!existing.isEmpty()) {
            boolean duplicate = existing.stream().anyMatch(e -> party.equals(e.get("party_id")) && "event".equals(e.get("entry_type")));
            store.jdbc().update("UPDATE discovery_candidates SET status=?,reason=?,updated_at=now() WHERE id=?",
                    duplicate ? "DUPLICATE" : "NEEDS_REVIEW", duplicate ? "Identischer Kalendereintrag vorhanden" : "Konflikt mit bestehendem Termin oder Location-Sperre", id);
            store.jdbc().update("UPDATE discovery_candidates SET decision_origin=?,previous_status=?,evaluated_at=now() WHERE id=?",
                manual ? "MANUAL" : "AUTOMATIC",c.get("status"),id);
            store.audit(id,(String)c.get("status"),manual ? null : (String)c.get("last_evaluation_id"));
            return;
        }
        String time = c.get("start_time") == null ? null : c.get("start_time").toString().substring(0,5);
        String relative=EventTiming.relativeStart(evaluator.event(c));
        String notes="Quelle: "+c.get("source_url")+(time==null && !relative.isBlank() ? "\nBeginn: "+relative : "");
        var entry = calendar.createEntry(new CalendarEntryRequest(date.toString(), venue, "event", notes, party, time, null, Set.of()));
        entityManager.flush();
        store.jdbc().update("UPDATE discovery_candidates SET calendar_entry_id=?,party_id=?,event_date=?,status='IMPORTED',reason=?,updated_at=now() WHERE id=?",
                entry.id(), party, date, manual ? "Manuell geprüft und übernommen" : "Automatisch übernommen", id);
        store.jdbc().update("UPDATE discovery_candidates SET decision_origin=?,previous_status=?,evaluated_at=now() WHERE id=?",
                manual ? "MANUAL" : "AUTOMATIC",c.get("status"),id);
        store.audit(id,(String)c.get("status"),manual ? null : (String)c.get("last_evaluation_id"));
    }
    @Transactional
    public void review(String id, String party, LocalDate date, String status, String inquiry, String dm) {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        var previous=store.candidate(id);
        if (!List.of("NEEDS_REVIEW", "NEEDS_INQUIRY", "REJECTED", "DUPLICATE").contains(status)) throw new IllegalArgumentException("Ungültiger Prüfstatus");
        if (!List.of("NONE", "PREPARED", "SENT", "ANSWERED").contains(inquiry)) throw new IllegalArgumentException("Ungültiger Rückfragestatus");
        if (party != null && !Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM party_series WHERE id=?)", Boolean.class, party))) throw new IllegalArgumentException("Unbekannte Partyreihe");
        String reason=status.equals("REJECTED") ? "Manuell abgelehnt" : status.equals("DUPLICATE") ? "Manuell als Dublette markiert" : "Manuell geprüft; "+Objects.toString(previous.get("automatic_reason"),"");
        store.jdbc().update("UPDATE discovery_candidates SET party_id=?,event_date=?,status=?,inquiry_status=?,dm_text=?,reason=?,decision_origin='MANUAL',previous_status=?,evaluated_at=now(),updated_at=now() WHERE id=?",
                party,date,status,inquiry,dm,reason,previous.get("status"),id);
        store.audit(id,(String)previous.get("status"),null);
    }
    public String dm(String id) {
        var c = store.candidate(id);
        return "Hallo liebes " + c.get("venue_name") + "-Team! 👋\n\nWir haben eure „" + c.get("title")
                + "“-Party entdeckt und würden sie gerne bei NoizyCologne einplanen.\n\nLeider ist der Termin für uns noch unklar: "
                + c.get("reason") + "."
                + "\n\nKönnt ihr uns kurz bestätigen, wann die Party tatsächlich stattfindet?\n\nVielen Dank und liebe Grüße\nNoizyCologne";
    }
}
