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
    private final DiscoveryGeographyService geography;
    private final JsonMapper json=JsonMapper.builder().build();
    public record Rules(List<@NonNull PartySeries> parties, Map<String,List<String>> aliases, Collection<String> genres,List<GenreCoverage.@NonNull Genre> coverageGenres) {
        public Rules(List<@NonNull PartySeries> parties,Map<String,List<String>> aliases,Collection<String> genres) {
            this(parties,aliases,genres,List.of());
        }
    }
    public DiscoveryEvaluationService(DiscoveryStore store,PartySeriesRepository parties,DiscoveryGenreFilter genres,EventMatcher matcher,DiscoveryGeographyService geography) {
        this.store=store;this.parties=parties;this.genres=genres;this.matcher=matcher;this.geography=geography;
    }
    public Rules rules() { var coverage=genres.coverageGenres();
        Set<String> terms=new LinkedHashSet<>();
        for (var genre:coverage) if (genre.active()) for (String term:genre.terms()) terms.add(term);
        return new Rules(parties.findAll(),store.aliases(),terms,coverage); }
    public record Assessment(PartySeriesRecognition.Recognition recognition,EventVerification.Result verification,
                             String lifecycle,boolean automaticImportEligible,List<@NonNull String> importBlockers) { }
    /** Additive API diagnostics; persisted editorial decisions remain authoritative and unchanged. */
    public Map<String,Object> forApi(Map<String,Object> row,Rules rules) {
        var event=event(row);
        if(Boolean.TRUE.equals(row.get("special_source")) && row.get("calendar_entry_id")==null) {
            var geo=geography.assess(event);
            if(!geo.inside()) {
                var excluded=DiscoveryStore.forApi(row);excluded.put("reported_location",event.location());excluded.put("geography",geo);
                excluded.put("assessment",new Assessment(new PartySeriesRecognition.Recognition("NONE",List.of()),EventVerification.assess(event,""),EventLifecycle.signal(event),false,List.of("Standortklärung: "+geo.reason())));
                return excluded;
            }
        }
        var recognition=PartySeriesRecognition.recognize(event.title(),rules.parties(),rules.aliases());
        var verification=EventVerification.assess(event,row.get("venue_id")==null ? "" : (String)row.get("venue_name"));
        String lifecycle=EventLifecycle.signal(event);
        List<@NonNull String> blockers=new ArrayList<>();
        if (!List.of("ACTIVE","LINEUP_CHANGE").contains(lifecycle))
            blockers.add("Absage oder Verschiebung zuerst im bestehenden Änderungs- und Absageworkflow klären");
        if (Boolean.TRUE.equals(row.get("has_blocking_change"))) blockers.add("Offene Veranstaltungsänderung zuerst klären");
        if (List.of("REJECTED","DUPLICATE","IGNORED").contains(row.get("status"))) blockers.add("Archivierten Vorschlag zuerst prüfen");
        if(Boolean.TRUE.equals(row.get("special_source")) && row.get("calendar_entry_id")==null) {var geo=geography.assess(event);if(!geo.inside())blockers.add("Standortklärung: "+geo.reason());}
        boolean eligible=blockers.isEmpty() && row.get("calendar_entry_id")==null
            && "AUTOMATIC".equals(row.get("decision_origin")) && "READY".equals(row.get("status"))
            && evaluate(event,(String)row.get("venue_id"),(String)row.get("venue_name"),Boolean.TRUE.equals(row.get("approved")),rules).status().equals("READY");
        var result=DiscoveryStore.forApi(row);
        Set<String> standards=new LinkedHashSet<>();
        for (var detected:recognition.parties()) for (var party:rules.parties()) if (party.getId().equals(detected.id())) standards.addAll(party.getStandardGenres());
        if (row.get("party_id") instanceof String selected) for (var party:rules.parties()) if (party.getId().equals(selected)) standards.addAll(party.getStandardGenres());
        result.put("reported_location",event.location());
        result.put("genreCoverage",GenreCoverage.assess(event,rules.coverageGenres(),standards));
        result.put("assessment",new Assessment(recognition,verification,lifecycle,eligible,List.copyOf(blockers)));
        return result;
    }
    public String raw(EventExtractor.Event event) { return json.writeValueAsString(EventPrivacy.safe(event)); }
    public EventExtractor.Event event(Map<String,Object> row) {
        if (row.get("raw_event") instanceof String raw && !raw.isBlank())
            return new EventExtractor().recoverPlace(JsonValues.readRequired(json,raw,EventExtractor.Event.class));
        // Old rows did not retain state/location/conflicting starts. Never invent confirmation.
        String start=Objects.toString(row.get("event_date"),"");
        if (!start.isBlank() && row.get("start_time")!=null) start+="T"+row.get("start_time");
        return new EventExtractor.Event("",(String)row.get("title"),(String)row.get("source_url"),
            start.isBlank() ? List.of() : List.of(start),Objects.toString(row.get("evidence"),""),
            "UNCONFIRMED Rohdaten unvollständig; erneuter Abruf erforderlich","");
    }
    public EventMatcher.Match evaluate(EventExtractor.Event event,String venueId,String venueName,boolean approved,Rules rules) {
        var result=matcher.match(event,venueId==null ? "" : venueName,rules.parties(),rules.aliases(),rules.genres(),venueId!=null && approved);
        if (venueId==null && !result.status().equals("IGNORED")) return new EventMatcher.Match("NEEDS_REVIEW",result.partyId(),"Veranstaltungsort aus der Sonderquelle redaktionell zuordnen; "+result.reason(),result.classification());
        if (!result.status().equals("READY")) return result;
        var date=EventNormalizer.date(event.starts().getFirst());
        var conflict=calendarConflict(event,venueId,result.partyId(),date,null);
        if (conflict!=null) return new EventMatcher.Match(conflict.status(),result.partyId(),conflict.reason(),result.classification());
        var earlier=store.jdbc().queryForList("SELECT * FROM discovery_candidates WHERE venue_id=? AND title=? AND calendar_entry_id IS NOT NULL AND event_date<>?",venueId,event.title(),date);
        if (earlier.stream().anyMatch(row -> event.identity().isBlank() || event(row).identity().isBlank()
                || event.identity().equals(event(row).identity())))
            return new EventMatcher.Match("NEEDS_REVIEW",result.partyId(),"Abweichendes Datum zu früherem Fund; Wiederholung oder Verschiebung prüfen",result.classification());
        return result;
    }
    public record CalendarConflict(String status,String reason,String entryId) { }
    /** One policy for scan previews and the locked import. A shared day is not event identity. */
    public CalendarConflict calendarConflict(EventExtractor.Event event,String venueId,String party,java.time.LocalDate date,String candidateId) {
        var entries=store.jdbc().queryForList("""
            SELECT e.id,e.party_id,e.entry_type,e.start_time,e.event_status,c.raw_event,c.title,c.source_url,c.event_date,c.evidence,
             EXISTS(SELECT 1 FROM event_changes x WHERE x.calendar_entry_id=e.id AND x.blocks_publication) AS blocked,
             EXISTS(SELECT 1 FROM event_cancellations x WHERE x.calendar_entry_id=e.id AND x.replacement_candidate_id=?) AS replaceable,
             EXISTS(SELECT 1 FROM event_changes x WHERE x.calendar_entry_id=e.id AND x.state='SEPARATE' AND x.after_data::jsonb->>'url'=?) AS separate
            FROM calendar_entries e LEFT JOIN discovery_candidates c ON c.calendar_entry_id=e.id
            WHERE e.location_id=? AND e.event_date=? ORDER BY e.id
            """,candidateId,event.url(),venueId,date);
        CalendarConflict duplicate=null;
        for (var entry:entries) {
            String entryId=(String)entry.get("id");
            if ("closure".equals(entry.get("entry_type"))) return new CalendarConflict("NEEDS_REVIEW","Ganztägige Location-Sperre am bestätigten Datum",null);
            if (Boolean.TRUE.equals(entry.get("separate")) || Boolean.TRUE.equals(entry.get("replaceable"))) continue;
            var before=entry.get("raw_event") instanceof String raw && !raw.isBlank() ? event(entry) : null;
            boolean identity=before!=null && !event.identity().isBlank() && event.identity().equals(before.identity());
            boolean sameParty=party.equals(entry.get("party_id"));
            if (!sameParty && !identity) continue;
            if ("CANCELLED".equals(entry.get("event_status")) || Boolean.TRUE.equals(entry.get("blocked")))
                return new CalendarConflict("NEEDS_REVIEW","Zugehöriger Kalendereintrag ist abgesagt oder wegen einer Veranstaltungsänderung gesperrt",sameParty ? entryId : null);
            var times=event.starts().stream().map(EventNormalizer::time).filter(Objects::nonNull).toList();
            java.time.@org.jspecify.annotations.Nullable LocalTime time=times.isEmpty() ? null : times.getFirst();
            var existingTime=entry.get("start_time") instanceof java.sql.Time sqlTime ? sqlTime.toLocalTime() : null;
            boolean differentTime=time!=null && existingTime!=null && !time.equals(existingTime);
            boolean distinct=before!=null && !before.identity().isBlank() && !event.identity().isBlank() && !identity;
            if (sameParty && distinct && differentTime) continue;
            if (!sameParty || differentTime) return new CalendarConflict("NEEDS_REVIEW","Widersprüchliche Partyreihe oder Startzeit zur selben Veranstaltung; Änderung zuerst klären",sameParty ? entryId : null);
            duplicate=new CalendarConflict("DUPLICATE","Identischer Kalendereintrag bereits vorhanden",entryId);
        }
        return duplicate;
    }
    public EventMatcher.Match evaluate(Map<String,Object> row,Rules rules) {
        if(Boolean.TRUE.equals(row.get("special_source"))) {var geo=geography.assess(event(row));if(!geo.inside())return new EventMatcher.Match("NEEDS_REVIEW",(String)row.get("party_id"),"Standortklärung: "+geo.reason(),"UNVERIFIED");}
        return evaluate(event(row),(String)row.get("venue_id"),(String)row.get("venue_name"),Boolean.TRUE.equals(row.get("approved")),rules);
    }
}
