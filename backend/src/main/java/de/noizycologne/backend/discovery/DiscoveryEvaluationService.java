package de.noizycologne.backend.discovery;

import de.noizycologne.backend.party.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Pure matching plus read-only calendar checks, shared by previews, resets and scans. */
@Service
public class DiscoveryEvaluationService {
    private final DiscoveryStore store;
    private final PartySeriesRepository parties;
    private final DiscoveryGenreFilter genres;
    private final EventMatcher matcher;
    private final JsonMapper json=JsonMapper.builder().build();
    public record Rules(List<PartySeries> parties, Map<String,List<String>> aliases, Collection<String> genres) { }
    public DiscoveryEvaluationService(DiscoveryStore store,PartySeriesRepository parties,DiscoveryGenreFilter genres,EventMatcher matcher) {
        this.store=store;this.parties=parties;this.genres=genres;this.matcher=matcher;
    }
    public Rules rules() { return new Rules(parties.findAll(),store.aliases(),genres.terms()); }
    public String raw(EventExtractor.Event event) { return json.writeValueAsString(event); }
    public EventExtractor.Event event(Map<String,Object> row) {
        if (row.get("raw_event") instanceof String raw && !raw.isBlank())
            return json.readValue(raw,EventExtractor.Event.class);
        // Old rows did not retain state/location/conflicting starts. Never invent confirmation.
        String start=Objects.toString(row.get("event_date"),"");
        if (!start.isBlank() && row.get("start_time")!=null) start+="T"+row.get("start_time");
        return new EventExtractor.Event("",(String)row.get("title"),(String)row.get("source_url"),
            start.isBlank() ? List.of() : List.of(start),Objects.toString(row.get("evidence"),""),
            "UNCONFIRMED Rohdaten unvollständig; erneuter Abruf erforderlich","");
    }
    public EventMatcher.Match evaluate(EventExtractor.Event event,String venueId,String venueName,boolean approved,Rules rules) {
        var result=matcher.match(event,venueName,rules.parties(),rules.aliases(),rules.genres(),approved);
        if (!result.status().equals("READY")) return result;
        var date=EventNormalizer.date(event.starts().getFirst());
        var existing=store.jdbc().queryForList("SELECT party_id,entry_type FROM calendar_entries WHERE location_id=? AND event_date=?",venueId,date);
        if (!existing.isEmpty()) {
            boolean duplicate=existing.stream().anyMatch(e -> result.partyId().equals(e.get("party_id")) && "event".equals(e.get("entry_type")));
            return new EventMatcher.Match(duplicate ? "DUPLICATE" : "NEEDS_REVIEW",result.partyId(),
                duplicate ? "Bereits im Kalender vorhanden" : "Konflikt mit bestehendem Termin oder Location-Sperre",result.classification());
        }
        if (!store.jdbc().queryForList("SELECT id FROM discovery_candidates WHERE venue_id=? AND title=? AND calendar_entry_id IS NOT NULL AND event_date<>?",venueId,event.title(),date).isEmpty())
            return new EventMatcher.Match("NEEDS_REVIEW",result.partyId(),"Abweichendes Datum zu früherem Fund; Wiederholung oder Verschiebung prüfen",result.classification());
        return result;
    }
    public EventMatcher.Match evaluate(Map<String,Object> row,Rules rules) {
        return evaluate(event(row),(String)row.get("venue_id"),(String)row.get("venue_name"),Boolean.TRUE.equals(row.get("approved")),rules);
    }
}
