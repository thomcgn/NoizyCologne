package de.noizycologne.backend.discovery;

import de.noizycologne.backend.json.JsonValues;
import org.jspecify.annotations.NonNull;
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
    public record Rules(List<@NonNull PartySeries> parties, Map<String,List<String>> aliases, Collection<String> genres,List<GenreCoverage.@NonNull Genre> coverageGenres) {
        public Rules(List<@NonNull PartySeries> parties,Map<String,List<String>> aliases,Collection<String> genres) {
            this(parties,aliases,genres,List.of());
        }
    }
    public DiscoveryEvaluationService(DiscoveryStore store,PartySeriesRepository parties,DiscoveryGenreFilter genres,EventMatcher matcher) {
        this.store=store;this.parties=parties;this.genres=genres;this.matcher=matcher;
    }
    public Rules rules() { var coverage=genres.coverageGenres();
        Set<String> terms=new LinkedHashSet<>();
        for (var genre:coverage) for (String term:genre.terms()) terms.add(term);
        return new Rules(parties.findAll(),store.aliases(),terms,coverage); }
    public record Assessment(PartySeriesRecognition.Recognition recognition,EventVerification.Result verification,
                             String lifecycle,boolean automaticImportEligible,List<@NonNull String> importBlockers) { }
    /** Additive API diagnostics; persisted editorial decisions remain authoritative and unchanged. */
    public Map<String,Object> forApi(Map<String,Object> row,Rules rules) {
        var event=event(row);
        var recognition=PartySeriesRecognition.recognize(event.title(),rules.parties(),rules.aliases());
        var verification=EventVerification.assess(event,(String)row.get("venue_name"));
        String lifecycle=EventLifecycle.signal(event);
        List<@NonNull String> blockers=new ArrayList<>();
        if (!List.of("ACTIVE","LINEUP_CHANGE").contains(lifecycle))
            blockers.add("Absage oder Verschiebung zuerst im bestehenden Änderungs- und Absageworkflow klären");
        if (Boolean.TRUE.equals(row.get("has_blocking_change"))) blockers.add("Offene Veranstaltungsänderung zuerst klären");
        if (List.of("REJECTED","DUPLICATE","IGNORED").contains(row.get("status"))) blockers.add("Archivierten Vorschlag zuerst prüfen");
        boolean eligible=blockers.isEmpty() && row.get("calendar_entry_id")==null
            && "AUTOMATIC".equals(row.get("decision_origin")) && "READY".equals(row.get("status"))
            && evaluate(event,(String)row.get("venue_id"),(String)row.get("venue_name"),Boolean.TRUE.equals(row.get("approved")),rules).status().equals("READY");
        var result=DiscoveryStore.forApi(row);
        result.put("genreCoverage",GenreCoverage.assess(event,rules.coverageGenres()));
        result.put("assessment",new Assessment(recognition,verification,lifecycle,eligible,List.copyOf(blockers)));
        return result;
    }
    public String raw(EventExtractor.Event event) { return json.writeValueAsString(EventPrivacy.safe(event)); }
    public EventExtractor.Event event(Map<String,Object> row) {
        if (row.get("raw_event") instanceof String raw && !raw.isBlank())
            return JsonValues.readRequired(json,raw,EventExtractor.Event.class);
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
        var earlier=store.jdbc().queryForList("SELECT * FROM discovery_candidates WHERE venue_id=? AND title=? AND calendar_entry_id IS NOT NULL AND event_date<>?",venueId,event.title(),date);
        if (earlier.stream().anyMatch(row -> event.identity().isBlank() || event(row).identity().isBlank()
                || event.identity().equals(event(row).identity())))
            return new EventMatcher.Match("NEEDS_REVIEW",result.partyId(),"Abweichendes Datum zu früherem Fund; Wiederholung oder Verschiebung prüfen",result.classification());
        return result;
    }
    public EventMatcher.Match evaluate(Map<String,Object> row,Rules rules) {
        return evaluate(event(row),(String)row.get("venue_id"),(String)row.get("venue_name"),Boolean.TRUE.equals(row.get("approved")),rules);
    }
}
