package de.noizycologne.backend.discovery;

import de.noizycologne.backend.venue.Venue;
import de.noizycologne.backend.party.PartySeries;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** Serializes scan reconciliation with editorial decisions and calendar imports. */
@Service
public class EventCandidateProcessor {
    private final DiscoveryStore store;
    private final EventMatcher matcher;
    private final EventImportService importer;
    public EventCandidateProcessor(DiscoveryStore store, EventMatcher matcher, EventImportService importer) {
        this.store=store; this.matcher=matcher; this.importer=importer;
    }
    @org.springframework.transaction.annotation.Transactional
    public void process(Venue venue,String sourceId,EventExtractor.Event event,List<PartySeries> known,
                 Map<String,List<String>> aliases,List<String> genres,boolean approved) {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        String url;
        try { url=SafeUrls.parse(event.url()).toString(); } catch (IllegalArgumentException e) { url=store.jdbc().queryForObject("SELECT url FROM discovery_sources WHERE id=?",String.class,sourceId); }
        String identity=EventNormalizer.hash(event.identity().isBlank() ? EventNormalizer.text(event.title()) + "|" + event.starts() : event.identity());
        String fingerprint=EventNormalizer.hash(event.title()+event.starts()+event.state()+event.evidence());
        var previous=store.jdbc().queryForList("SELECT * FROM discovery_candidates WHERE venue_id=? AND identity_key=?",venue.getId(),identity);
        if (!previous.isEmpty()) {
            var p=previous.get(0);
            if ("REJECTED".equals(p.get("status")) || ("DUPLICATE".equals(p.get("status"))
                    && p.get("reason").toString().startsWith("Manuell"))) return;
            if (fingerprint.equals(p.get("fingerprint")) && !"READY".equals(p.get("status"))
                    && !("NEEDS_REVIEW".equals(p.get("status")) && p.get("reason").toString().contains("Quelle nicht"))) return;
        }
        var match=matcher.match(event,venue.getName(),known,aliases,genres,approved);
        var dates=event.starts().stream().map(EventNormalizer::date).distinct().toList();
        LocalDate date=dates.size()==1 ? dates.get(0) : null;
        var times=event.starts().stream().map(EventNormalizer::time).distinct().toList();
        LocalTime time=times.size()==1 ? times.get(0) : null;
        String status=match.status(),reason=match.reason();
        if (!previous.isEmpty() && previous.get(0).get("calendar_entry_id")!=null) { status="NEEDS_REVIEW"; reason="Änderung an bereits übernommenem Event: " + reason; }
        // A listing without stable IDs may change date: flag instead of importing a second occurrence silently.
        if (status.equals("READY") && !store.jdbc().queryForList("SELECT id FROM discovery_candidates WHERE venue_id=? AND title=? AND calendar_entry_id IS NOT NULL AND event_date<>?",venue.getId(),event.title(),date).isEmpty()) {
            status="NEEDS_REVIEW"; reason="Abweichendes Datum zu früherem Fund; Wiederholung oder Verschiebung prüfen";
        }
        String id=previous.isEmpty() ? UUID.randomUUID().toString() : (String)previous.get(0).get("id");
        store.jdbc().update("""
            INSERT INTO discovery_candidates(id,venue_id,source_id,identity_key,fingerprint,title,source_url,event_date,start_time,evidence,status,reason,party_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(venue_id,identity_key) DO UPDATE SET
            source_id=excluded.source_id,fingerprint=excluded.fingerprint,title=excluded.title,source_url=excluded.source_url,event_date=excluded.event_date,
            start_time=excluded.start_time,evidence=excluded.evidence,status=excluded.status,reason=excluded.reason,
            party_id=excluded.party_id,updated_at=now()
            """,id,venue.getId(),sourceId,identity,fingerprint,event.title(),url,date,time,event.evidence(),status,reason,match.partyId());
        if (status.equals("READY")) importer.importCandidate(id,match.partyId(),date,false);
    }
}
