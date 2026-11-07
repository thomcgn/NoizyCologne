package de.noizycologne.backend.discovery;

import de.noizycologne.backend.json.JsonValues;
import de.noizycologne.backend.venue.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.jspecify.annotations.NonNull;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Persists geographical evidence separately from editorial candidate decisions. */
@Service
public class DiscoveryGeographyService {
    private final DiscoveryStore store;
    private final CologneGeography policy;
    private final EventExtractor extractor;
    private final VenueRepository venues;
    private final JsonMapper json=JsonMapper.builder().build();
    public DiscoveryGeographyService(DiscoveryStore store,CologneGeography policy,EventExtractor extractor,VenueRepository venues){this.store=store;this.policy=policy;this.extractor=extractor;this.venues=venues;}
    public List<CologneGeography.@NonNull ConfirmedVenue> known() {
        List<CologneGeography.@NonNull ConfirmedVenue> result=new ArrayList<>();
        for(var row:store.jdbc().queryForList("SELECT g.*,v.name FROM discovery_venue_geography g JOIN venues v ON v.id=g.venue_id")) {
            Set<@NonNull String> names=new HashSet<>();names.add(CologneGeography.name((String)row.get("name")));Set<@NonNull String> keys=new HashSet<>();
            for(var alias:store.jdbc().queryForList("SELECT normalized_name,place_key FROM discovery_place_aliases WHERE venue_id=?",row.get("venue_id"))){names.add(Objects.requireNonNull((String)alias.get("normalized_name")));keys.add(Objects.requireNonNull((String)alias.get("place_key")));}
            result.add(new CologneGeography.ConfirmedVenue((String)row.get("venue_id"),(String)row.get("city"),Set.copyOf(names),Set.copyOf(keys)));
        }
        return List.copyOf(result);
    }
    public EventExtractor.@NonNull Event event(Map<String,Object> row) {
        if(row.get("raw_event") instanceof String raw && !raw.isBlank()) return extractor.recoverPlace(JsonValues.readRequired(json,raw,EventExtractor.Event.class));
        return new EventExtractor.Event("",Objects.toString(row.get("title"),""),Objects.toString(row.get("source_url"),""),List.of(),"","UNCONFIRMED Rohdaten fehlen","");
    }
    public CologneGeography.@NonNull Result assess(EventExtractor.Event event) {return policy.evaluate(extractor.recoverPlace(event),known());}
    private String signature(EventExtractor.Event event){return EventNormalizer.hash(event.location()+event.place());}
    public EventExtractor.@NonNull Event effective(Map<String,Object> row) {
        var event=event(row);
        if(row.get("confirmed_place") instanceof String place && signature(event).equals(row.get("confirmed_fingerprint")))
            return new EventExtractor.Event(event.identity(),event.title(),event.url(),event.starts(),event.evidence(),event.state(),event.location(),JsonValues.readRequired(json,place,EventPlace.class));
        return event;
    }
    private EventExtractor.@NonNull Event canonical(EventExtractor.Event event) {
        String identity=event.identity();
        if(identity.startsWith("https://")) try{identity=CanonicalSourceUrl.reference(identity);}catch(IllegalArgumentException ignored){/* Non-URL IDs remain opaque. */}
        return new EventExtractor.Event(identity,event.title(),CanonicalSourceUrl.of(event.url().split("#",2)[0]),event.starts(),event.evidence(),event.state(),event.location(),event.place());
    }
    public record Finding(String id,String sourceId,EventExtractor.@NonNull Event event,CologneGeography.@NonNull Result result,String candidateId) { }
    @Transactional
    public Finding record(String sourceId,EventExtractor.Event original) {return record(sourceId,original,null);}
    private Finding record(String sourceId,EventExtractor.Event original,String requestedCandidate) {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        var event=canonical(extractor.recoverPlace(EventPrivacy.safe(original)));String identity=EventChangeMonitor.identity(event);
        var rows=store.jdbc().queryForList("SELECT * FROM discovery_geo_events WHERE source_id=? AND identity_key=?",sourceId,identity);
        String id=rows.isEmpty()?UUID.randomUUID().toString():(String)rows.getFirst().get("id");
        String mapped=requestedCandidate!=null?requestedCandidate:rows.isEmpty()?null:(String)rows.getFirst().get("candidate_id");
        var candidates=store.jdbc().queryForList("SELECT id FROM discovery_candidates WHERE (source_id=? AND identity_key=?) OR id=? ORDER BY (calendar_entry_id IS NULL),(decision_origin='AUTOMATIC'),id LIMIT 1",sourceId,identity,mapped);
        if(candidates.isEmpty()) {
            // Old _rsc identities are not rewritten: reuse the protected candidate through a stable mapping.
            candidates=Objects.requireNonNull(store.jdbc().queryForList("SELECT * FROM discovery_candidates WHERE source_id=? AND geo_status='NOT_CHECKED' ORDER BY (calendar_entry_id IS NULL),(decision_origin='AUTOMATIC'),id",sourceId).stream()
                .filter(row -> EventChangeMonitor.identity(canonical(event(row))).equals(identity)).limit(1).toList());
        }
        String candidateId=candidates.isEmpty()?null:(String)candidates.getFirst().get("id");

        var current=new HashMap<String,Object>();if(!rows.isEmpty()) current.putAll(rows.getFirst());current.put("raw_event",json.writeValueAsString(event));
        var effective=effective(current);var result=assess(effective);
        store.jdbc().update("""
            INSERT INTO discovery_geo_events(id,source_id,candidate_id,identity_key,title,source_url,raw_event,status,reason,venue_id) VALUES (?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(source_id,identity_key) DO UPDATE SET candidate_id=COALESCE(discovery_geo_events.candidate_id,excluded.candidate_id),title=excluded.title,source_url=excluded.source_url,raw_event=excluded.raw_event,status=excluded.status,reason=excluded.reason,venue_id=excluded.venue_id,updated_at=now()
            """,id,sourceId,candidateId,identity,event.title(),CanonicalSourceUrl.of(event.url()),json.writeValueAsString(event),result.status(),result.reason(),result.venueId());
        store.jdbc().update("UPDATE discovery_candidates SET geo_status=?,geo_reason=?,raw_event=?,evidence=?,source_url=? WHERE source_id=? AND (identity_key=? OR id=?) AND calendar_entry_id IS NULL AND status<>'IMPORTED' AND NOT (status IN ('REJECTED','IGNORED') AND event_date IS NOT NULL AND event_date IS DISTINCT FROM ?::date)",result.status(),result.reason(),json.writeValueAsString(effective),effective.evidence(),CanonicalSourceUrl.of(effective.url()),sourceId,identity,candidateId,EventChangeMonitor.date(event));
        return new Finding(id,sourceId,effective,result,candidateId);
    }
    @Transactional
    public void classifyCandidate(Map<String,Object> row) {
        if(row.get("calendar_entry_id")!=null || "IMPORTED".equals(row.get("status"))) return;
        var finding=record((String)row.get("source_id"),event(row),(String)row.get("id"));
        store.jdbc().update("UPDATE discovery_candidates SET geo_status=?,geo_reason=?,raw_event=? WHERE id=? AND calendar_entry_id IS NULL AND status<>'IMPORTED'",finding.result().status(),finding.result().reason(),json.writeValueAsString(finding.event()),row.get("id"));
    }
    public Map<String,Object> list() {
        List<Map<String,Object>> items=new ArrayList<>();
        for(var row:store.jdbc().queryForList("SELECT * FROM (SELECT g.*,s.name AS source_name,row_number() OVER (PARTITION BY g.status ORDER BY g.updated_at DESC) AS position FROM discovery_geo_events g JOIN discovery_sources s ON s.id=g.source_id WHERE g.status<>'INSIDE' AND NOT EXISTS(SELECT 1 FROM discovery_candidates c WHERE c.source_id=g.source_id AND (c.identity_key=g.identity_key OR c.id=g.candidate_id) AND (c.calendar_entry_id IS NOT NULL OR c.status='IMPORTED'))) ranked WHERE position<=300 ORDER BY updated_at DESC")) {
            var value=DiscoveryStore.forApi(row);var event=event(row);value.remove("raw_event");value.remove("confirmed_place");value.put("location",event.location());value.put("place",event.place());items.add(value);
        }
        for(var row:store.jdbc().queryForList("SELECT c.* FROM discovery_candidates c JOIN discovery_sources s ON s.id=c.source_id WHERE s.venue_id IS NULL AND c.calendar_entry_id IS NULL AND c.status<>'IMPORTED' AND c.geo_status='NOT_CHECKED' AND NOT EXISTS(SELECT 1 FROM discovery_geo_events g WHERE g.source_id=c.source_id AND (g.identity_key=c.identity_key OR g.candidate_id=c.id)) ORDER BY c.updated_at DESC LIMIT 300")) {
            var value=new LinkedHashMap<String,Object>();value.put("id","candidate:"+row.get("id"));value.put("title",row.get("title"));value.put("source_url",row.get("source_url"));value.put("status","NOT_CHECKED");value.put("reason","Bestandsfund: Standort bei ausdrücklicher Neubewertung prüfen");
            var event=row.get("raw_event")==null?null:event(row);value.put("location",event==null?"":event.location());value.put("place",event==null?EventPlace.empty():event.place());items.add(value);
        }
        Map<String,Long> counts=new HashMap<>();
        for(var row:store.jdbc().queryForList("SELECT g.status,count(*) AS count FROM discovery_geo_events g WHERE g.status<>'INSIDE' AND NOT EXISTS(SELECT 1 FROM discovery_candidates c WHERE c.source_id=g.source_id AND (c.identity_key=g.identity_key OR c.id=g.candidate_id) AND (c.calendar_entry_id IS NOT NULL OR c.status='IMPORTED')) GROUP BY g.status")) counts.put((String)row.get("status"),((Number)Objects.requireNonNull(row.get("count"),"Zählwert fehlt")).longValue());
        counts.put("NOT_CHECKED",store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates c JOIN discovery_sources s ON s.id=c.source_id WHERE s.venue_id IS NULL AND c.calendar_entry_id IS NULL AND c.status<>'IMPORTED' AND c.geo_status='NOT_CHECKED' AND NOT EXISTS(SELECT 1 FROM discovery_geo_events g WHERE g.source_id=c.source_id AND (g.identity_key=c.identity_key OR g.candidate_id=c.id))",Long.class));
        return Map.of("items",items,"counts",counts,"limit",300);
    }
    public void requireInside(Map<String,Object> candidate) {
        if(!Boolean.TRUE.equals(candidate.get("special_source")) || candidate.get("calendar_entry_id")!=null) return;
        var result=assess(event(candidate));
        if(!result.inside()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Standort zuerst klären: "+result.reason());
        String identified=result.venueId();
        if(identified!=null && candidate.get("venue_id")!=null && !identified.equals(candidate.get("venue_id"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Bestätigte Ortsidentität widerspricht der Location-Zuordnung");
    }
    /** Explicit editorial venue selection plus an already proven Cologne event confirms the binding. */
    @Transactional
    public void remember(String venueId,EventExtractor.Event event,String proof) {
        var result=assess(event);if(!result.inside()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Kölner Veranstaltungsort nicht belegt");
        String identified=result.venueId();
        if(identified!=null && !identified.equals(venueId)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Ortsidentität ist bereits einer anderen Location zugeordnet");
        var venue=venues.findById(venueId).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Location fehlt"));
        var previous=store.jdbc().queryForList("SELECT city FROM discovery_venue_geography WHERE venue_id=?",venueId);
        if(!previous.isEmpty() && !CologneGeography.cologne((String)previous.getFirst().get("city"))) throw new ResponseStatusException(HttpStatus.CONFLICT,"Location ist bereits einer anderen Stadt zugeordnet");
        store.jdbc().update("INSERT INTO discovery_venue_geography(venue_id,city,street,country,proof_url) VALUES (?,'Köln',?,'DE',?) ON CONFLICT(venue_id) DO NOTHING",venueId,event.place().street(),CanonicalSourceUrl.of(proof));
        for(String key:CologneGeography.keys(event.place())) for(String name:new HashSet<>(List.of(CologneGeography.name(venue.getName()),CologneGeography.name(event.location())))) if(!name.isBlank())
            store.jdbc().update("INSERT INTO discovery_place_aliases(venue_id,normalized_name,place_key) VALUES (?,?,?) ON CONFLICT DO NOTHING",venueId,name,key);
    }
    @Transactional
    public Finding confirm(String findingId,String venueId,String newName,String city,String street,String proof,boolean confirmed) {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        if(!confirmed || !CologneGeography.cologne(city) || !street.matches(".*\\d.*")) throw new IllegalArgumentException("Kölner Stadtzugehörigkeit und tatsächliche Straße/Hausnummer ausdrücklich bestätigen");
        CanonicalSourceUrl.of(proof);
        Map<String,Object> row;
        if(findingId.startsWith("candidate:")){var candidate=store.candidate(findingId.substring(10));if(!Boolean.TRUE.equals(candidate.get("special_source")) || candidate.get("calendar_entry_id")!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Bestandsfund nicht zur Standortklärung verfügbar");var finding=record((String)candidate.get("source_id"),event(candidate),(String)candidate.get("id"));row=store.jdbc().queryForMap("SELECT * FROM discovery_geo_events WHERE id=?",finding.id());}
        else {var rows=store.jdbc().queryForList("SELECT * FROM discovery_geo_events WHERE id=?",findingId);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Standortfund fehlt");row=rows.getFirst();}
        var original=event(row);if(assess(original).status().equals("OUTSIDE"))throw new ResponseStatusException(HttpStatus.CONFLICT,"Belegter auswärtiger Ort kann nicht als Köln bestätigt werden");
        String selected=venueId;
        if(selected==null || selected.isBlank()){if(newName==null || newName.isBlank())throw new IllegalArgumentException("Location wählen oder neue Location benennen");selected=venues.saveAndFlush(new Venue(newName.trim(),"")).getId();}
        var place=new EventPlace("Köln","DE",street,"",original.place().reference(),original.place().latitude(),original.place().longitude(),original.place().conflicting());
        var event=new EventExtractor.Event(original.identity(),original.title(),original.url(),original.starts(),original.evidence(),original.state(),original.location(),place);
        remember(selected,event,proof);
        store.jdbc().update("UPDATE discovery_geo_events SET confirmed_place=?,confirmed_fingerprint=? WHERE id=?",json.writeValueAsString(place),signature(original),row.get("id"));
        var finding=record((String)row.get("source_id"),original);
        store.jdbc().update("UPDATE discovery_candidates c SET venue_id=? WHERE source_id=? AND (identity_key=? OR id=?) AND venue_id IS NULL AND calendar_entry_id IS NULL AND NOT EXISTS(SELECT 1 FROM discovery_candidates other WHERE other.venue_id=? AND other.identity_key=c.identity_key AND other.id<>c.id)",selected,finding.sourceId(),EventChangeMonitor.identity(original),finding.candidateId(),selected);
        store.jdbc().update("INSERT INTO discovery_candidate_identities(venue_id,identity_key,candidate_id) SELECT venue_id,identity_key,id FROM discovery_candidates WHERE source_id=? AND (identity_key=? OR id=?) AND venue_id=? ON CONFLICT DO NOTHING",finding.sourceId(),EventChangeMonitor.identity(original),finding.candidateId(),selected);
        return new Finding(finding.id(),finding.sourceId(),original,finding.result(),finding.candidateId());
    }
}
