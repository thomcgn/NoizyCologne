package de.noizycologne.backend.discovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class DiscoveryStore {
    private static final String CANDIDATE_SELECT="""
        SELECT c.*,COALESCE(v.name,'Veranstaltungsort offen') AS venue_name,COALESCE(v.instagram,'') AS instagram,s.approved,s.source_type,s.name AS source_name,s.venue_id IS NULL AS special_source,
         EXISTS(SELECT 1 FROM event_changes x WHERE x.candidate_id=c.id AND x.blocks_publication) AS has_blocking_change
        FROM discovery_candidates c LEFT JOIN venues v ON v.id=c.venue_id JOIN discovery_sources s ON s.id=c.source_id
        """;
    private final JdbcTemplate db;
    public JdbcTemplate jdbc() { return db; }
    public DiscoveryStore(JdbcTemplate db) { this.db = db; }
    public List<Map<String,Object>> candidates() { return candidates("ALL"); }
    public List<Map<String,Object>> candidates(String filter) {
        String where=switch(filter) {
            case "RELEVANT" -> "c.status IN ('NEEDS_REVIEW','NEEDS_INQUIRY','READY') AND NOT (c.matching_class='UNCLASSIFIABLE' AND c.decision_origin='AUTOMATIC' AND c.party_id IS NULL AND c.inquiry_status NOT IN ('PREPARED','SENT'))";
            case "UNCLEAR" -> "c.status IN ('NEEDS_REVIEW','NEEDS_INQUIRY','READY') AND c.matching_class='UNCLASSIFIABLE' AND c.decision_origin='AUTOMATIC' AND c.party_id IS NULL AND c.inquiry_status NOT IN ('PREPARED','SENT')";
            case "KEYWORD_FILTER" -> "c.status='IGNORED' AND c.filter_origin='KEYWORD_FILTER'";
            case "OTHER_IGNORED" -> "c.status='IGNORED' AND c.filter_origin IS DISTINCT FROM 'KEYWORD_FILTER'";
            case "DUPLICATE" -> "c.status='DUPLICATE'";
            case "AUTOMATIC_IGNORED" -> "c.status='IGNORED' AND c.decision_origin='AUTOMATIC'";
            case "MANUAL_REJECTED" -> "c.status='REJECTED' AND c.decision_origin='MANUAL'";
            case "UNVERIFIED", "POSSIBLE_MATCH" -> "c.matching_class='"+filter+"' AND c.status NOT IN ('IMPORTED','REJECTED','DUPLICATE','IGNORED')";
            case "REEVALUATED" -> "EXISTS (SELECT 1 FROM discovery_decisions d JOIN discovery_operations o ON o.id=d.operation_id WHERE d.candidate_id=c.id AND o.kind<>'SCAN' AND d.old_status IS NOT NULL)";
            case "OPEN" -> "c.inquiry_status IN ('PREPARED','SENT') AND c.status NOT IN ('IMPORTED','REJECTED','DUPLICATE','IGNORED')";
            case "ARCHIVE" -> "c.status IN ('REJECTED','DUPLICATE','IGNORED')";
            case "NEEDS_REVIEW", "NEEDS_INQUIRY", "READY", "IMPORTED" -> "c.status='"+filter+"'";
            case "ALL" -> "true";
            default -> throw new IllegalArgumentException("Unbekannter Vorschlagsfilter");
        };
        return db.queryForList(CANDIDATE_SELECT+" WHERE ("+where+") AND (s.venue_id IS NOT NULL OR c.geo_status='INSIDE' OR c.calendar_entry_id IS NOT NULL OR c.status IN ('IMPORTED','REJECTED','DUPLICATE','IGNORED')) ORDER BY c.updated_at DESC LIMIT 500")
            .stream().map(DiscoveryStore::forApi).toList();
    }
    public List<Map<String,Object>> sources() {
        return db.queryForList("SELECT s.*,COALESCE(v.name,'') AS venue_name,s.venue_id IS NULL AS special_source FROM discovery_sources s LEFT JOIN venues v ON v.id=s.venue_id WHERE s.deleted_at IS NULL ORDER BY v.name,s.name,s.url").stream().map(DiscoveryStore::forApi).toList();
    }
    /** SQL DATE is a calendar day, never a timestamp shifted by the JSON timezone. */
    public static Map<String,Object> forApi(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();
        row.forEach((key,value) -> result.put(key,
                value instanceof java.sql.Date date ? date.toLocalDate().toString() :
                value instanceof java.sql.Time time ? time.toLocalTime().toString() :
                value instanceof java.sql.Timestamp timestamp ? timestamp.toInstant().toString() : value));
        if(Boolean.TRUE.equals(row.get("special_source"))) for(String key:List.of("url","source_url")) if(result.get(key) instanceof String address) {
            try {result.put(key,CanonicalSourceUrl.reference(address));}catch(IllegalArgumentException ignored){/* Historical invalid URLs are never repaired into geographical evidence. */}
        }
        return result;
    }
    public Map<String,Object> candidate(String id) {
        var rows = db.queryForList(CANDIDATE_SELECT+" WHERE c.id=?", id);
        if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Vorschlag fehlt");
        return rows.get(0);
    }
    public String source(String venue, String url, String type, boolean approved) {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO discovery_sources(id,venue_id,url,source_type,approved,source_kind) VALUES (?,?,?,?,?,?) ON CONFLICT(venue_id,url) DO UPDATE SET source_type=excluded.source_type, approved=CASE WHEN discovery_sources.source_type='OFFICIAL' AND excluded.source_type='EXTERNAL' THEN false ELSE discovery_sources.approved END", id, venue, url, type, approved,type.equals("OFFICIAL") ? "WEBSITE" : "CUSTOM");
        return db.queryForObject("SELECT id FROM discovery_sources WHERE venue_id=? AND url=?", String.class, venue, url);
    }
    public List<Map<String,Object>> origins(String candidate) {
        return db.queryForList("""
            SELECT DISTINCT ON (o.source_id) s.url,s.source_kind,s.approved,o.event_data,o.observed_at
            FROM discovery_observations o JOIN discovery_sources s ON s.id=o.source_id
            WHERE o.candidate_id=? ORDER BY o.source_id,o.observed_at DESC,o.id DESC
            """,candidate).stream().map(DiscoveryStore::forApi).toList();
    }
    /** Post-enrichment editorial outcomes; source diagnostics do not change any decision. */
    public void recordFilterDiagnostics(String sourceId) {
        int count=Objects.requireNonNull(db.queryForObject("SELECT count(*) FROM discovery_candidates c WHERE c.status='IGNORED' AND (c.source_id=? OR EXISTS(SELECT 1 FROM discovery_observations o WHERE o.candidate_id=c.id AND o.source_id=?))",Integer.class,sourceId,sourceId));
        var excluded=db.queryForList("SELECT title,reason FROM discovery_candidates c WHERE c.status='IGNORED' AND (c.source_id=? OR EXISTS(SELECT 1 FROM discovery_observations o WHERE o.candidate_id=c.id AND o.source_id=?)) ORDER BY title,id LIMIT 6",sourceId,sourceId);
        StringBuilder summary=new StringBuilder("FILTERED_EVENTS: "+count);
        for(var event:excluded) {
            String text=Objects.toString(event.get("title"),"")+": "+Objects.toString(event.get("reason"),"");
            summary.append("; ").append(text,0,Math.min(180,text.length()));
        }
        db.update("UPDATE discovery_sources SET fetch_diagnostics=left(split_part(coalesce(fetch_diagnostics,''),'; FILTERED_EVENTS:',1),800)||'; '||? WHERE id=?",summary.toString(),sourceId);
    }
    public void fetched(String id,VenueSourceDiscoveryService.Found found) {
        String diagnostics=String.join("; ",found.diagnostics());
        db.update("""
            UPDATE discovery_sources SET status=?,last_fetched_at=now(),fetch_method=?,scan_complete=?,fetch_diagnostics=?,quality=?,event_count=?,
            last_success_at=CASE WHEN ? OR ?='NO_EVENTS_CONFIRMED' THEN now() ELSE last_success_at END WHERE id=? AND deleted_at IS NULL
            """,found.extraction().status(),found.fetchMethod(),found.extraction().complete(),
            diagnostics.substring(0,Math.min(2000,diagnostics.length())),SourceQuality.assess(found),found.extraction().events().size(),
            !found.extraction().events().isEmpty(),SourceQuality.assess(found),id);
    }
    public void audit(String id,String oldStatus,String operation) {
        audit(id,oldStatus,operation,Objects.toString(candidate(id).get("matching_class"),null));
    }
    public void audit(String id,String oldStatus,String operation,String oldClass) {
        db.update("""
            INSERT INTO discovery_decisions(candidate_id,operation_id,old_status,new_status,origin,matching_class,old_matching_class,reason,matcher_version,filter_origin,filter_rule_id,filter_keyword,filter_reason,filter_evidence)
            SELECT id,?,?,status,decision_origin,matching_class,?,reason,matcher_version,filter_origin,filter_rule_id,filter_keyword,filter_reason,filter_evidence FROM discovery_candidates WHERE id=?
            """,operation,oldStatus,oldClass,id);
    }
    public void technicalAudit(String id,String oldClass,String operation) {
        db.update("""
            INSERT INTO discovery_decisions(candidate_id,operation_id,old_status,new_status,origin,matching_class,old_matching_class,reason,matcher_version,filter_origin,filter_rule_id,filter_keyword,filter_reason,filter_evidence)
            SELECT id,?,status,status,'AUTOMATIC',matching_class,?,automatic_reason,matcher_version,filter_origin,filter_rule_id,filter_keyword,filter_reason,filter_evidence FROM discovery_candidates WHERE id=?
            """,operation,oldClass,id);
    }
    public List<Map<String,Object>> evaluationRows() {
        return db.queryForList(CANDIDATE_SELECT+" ORDER BY c.id");
    }
    public Map<String,List<String>> aliases() {
        Map<String,List<String>> map = new HashMap<>();
        db.query("SELECT party_id,alias FROM party_series_aliases", (org.springframework.jdbc.core.RowCallbackHandler) r -> map.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(r.getString(2)));
        return map;
    }
}
