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
    public EventImportService(DiscoveryStore store, CalendarEntryService calendar) { this.store = store; this.calendar = calendar; }
    @Transactional
    public void importCandidate(String id, String party, LocalDate date, boolean manual) {
        // The same lock covers automatic and manual imports, including parallel JVMs.
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        var c = store.candidate(id);
        if (List.of("REJECTED", "DUPLICATE", "IGNORED").contains(c.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Archivierten Vorschlag zuerst prüfen");
        if (c.get("calendar_entry_id") != null) {
            if ("IMPORTED".equals(c.get("status"))) return;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Bereits übernommen: Änderung im Kalender manuell prüfen");
        }
        if (party == null || date == null) throw new IllegalArgumentException("Partyreihe und bestätigtes Datum sind erforderlich");
        if (!manual) {
            Boolean approved = store.jdbc().queryForObject("SELECT approved FROM discovery_sources WHERE id=?", Boolean.class, c.get("source_id"));
            if (!Boolean.TRUE.equals(approved) || !"READY".equals(c.get("status"))) throw new IllegalArgumentException("Automatische Übernahme nicht freigegeben");
        }
        String venue = (String)c.get("venue_id");
        var existing = store.jdbc().queryForList("SELECT id,party_id,entry_type FROM calendar_entries WHERE location_id=? AND event_date=?", venue, date);
        if (!existing.isEmpty()) {
            boolean duplicate = existing.stream().anyMatch(e -> party.equals(e.get("party_id")) && "event".equals(e.get("entry_type")));
            store.jdbc().update("UPDATE discovery_candidates SET status=?,reason=?,updated_at=now() WHERE id=?",
                    duplicate ? "DUPLICATE" : "NEEDS_REVIEW", duplicate ? "Identischer Kalendereintrag vorhanden" : "Konflikt mit bestehendem Termin oder Location-Sperre", id);
            return;
        }
        String time = c.get("start_time") == null ? null : c.get("start_time").toString().substring(0,5);
        var entry = calendar.createEntry(new CalendarEntryRequest(date.toString(), venue, "event", "Quelle: " + c.get("source_url"), party, time, null, Set.of()));
        entityManager.flush();
        store.jdbc().update("UPDATE discovery_candidates SET calendar_entry_id=?,party_id=?,event_date=?,status='IMPORTED',reason=?,updated_at=now() WHERE id=?",
                entry.id(), party, date, manual ? "Manuell geprüft und übernommen" : "Automatisch übernommen", id);
    }
    @Transactional
    public void review(String id, String party, LocalDate date, String status, String inquiry, String dm) {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        store.candidate(id);
        if (!List.of("NEEDS_REVIEW", "NEEDS_INQUIRY", "REJECTED", "DUPLICATE").contains(status)) throw new IllegalArgumentException("Ungültiger Prüfstatus");
        if (!List.of("NONE", "PREPARED", "SENT", "ANSWERED").contains(inquiry)) throw new IllegalArgumentException("Ungültiger Rückfragestatus");
        if (party != null && !Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM party_series WHERE id=?)", Boolean.class, party))) throw new IllegalArgumentException("Unbekannte Partyreihe");
        if (status.equals("DUPLICATE") || status.equals("REJECTED")) store.jdbc().update(
                "UPDATE discovery_candidates SET reason=? WHERE id=?",status.equals("DUPLICATE") ? "Manuell als Dublette markiert" : "Manuell abgelehnt",id);
        store.jdbc().update("UPDATE discovery_candidates SET party_id=?, event_date=?, status=?, inquiry_status=?, dm_text=?, updated_at=now() WHERE id=?",
                party, date, status, inquiry, dm, id);
    }
    public String dm(String id) {
        var c = store.candidate(id);
        return "Hallo liebes " + c.get("venue_name") + "-Team! 👋\n\nWir haben eure „" + c.get("title")
                + "“-Party entdeckt und würden sie gerne bei NoizyCologne einplanen.\n\nLeider ist der Termin für uns noch unklar: "
                + c.get("reason") + "."
                + "\n\nKönnt ihr uns kurz bestätigen, wann die Party tatsächlich stattfindet?\n\nVielen Dank und liebe Grüße\nNoizyCologne";
    }
}
