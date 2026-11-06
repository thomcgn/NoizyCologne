package de.noizycologne.backend.discovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class DiscoveryStore {
    private static final String CANDIDATE_SELECT="""
        SELECT c.*,v.name AS venue_name,v.instagram,s.approved,s.source_type,
         EXISTS(SELECT 1 FROM event_changes x WHERE x.candidate_id=c.id AND x.blocks_publication) AS has_blocking_change
        FROM discovery_candidates c JOIN venues v ON v.id=c.venue_id JOIN discovery_sources s ON s.id=c.source_id
        """;
    private final JdbcTemplate db;
    public JdbcTemplate jdbc() { return db; }
    public DiscoveryStore(JdbcTemplate db) { this.db = db; }
    public List<Map<String,Object>> candidates() { return candidates("ALL"); }
    public List<Map<String,Object>> candidates(String filter) {
        String where=switch(filter) {
            case "AUTOMATIC_IGNORED" -> "c.status='IGNORED' AND c.decision_origin='AUTOMATIC'";
            case "MANUAL_REJECTED" -> "c.status='REJECTED' AND c.decision_origin='MANUAL'";
            case "UNVERIFIED", "POSSIBLE_MATCH" -> "c.matching_class='"+filter+"' AND c.status NOT IN ('IMPORTED','REJECTED','DUPLICATE','IGNORED')";
            case "REEVALUATED" -> "EXISTS (SELECT 1 FROM discovery_decisions d JOIN discovery_operations o ON o.id=d.operation_id WHERE d.candidate_id=c.id AND o.kind<>'SCAN' AND d.old_status IS NOT NULL)";
            case "OPEN" -> "c.inquiry_status IN ('PREPARED','SENT')";
            case "ARCHIVE" -> "c.status IN ('REJECTED','DUPLICATE','IGNORED')";
            case "NEEDS_REVIEW", "NEEDS_INQUIRY", "READY", "IMPORTED" -> "c.status='"+filter+"'";
            case "ALL" -> "true";
            default -> throw new IllegalArgumentException("Unbekannter Vorschlagsfilter");
        };
        return db.queryForList(CANDIDATE_SELECT+" WHERE "+where+" ORDER BY c.updated_at DESC LIMIT 500")
            .stream().map(DiscoveryStore::forApi).toList();
    }
    public List<Map<String,Object>> sources() {
        return db.queryForList("SELECT s.*, v.name AS venue_name FROM discovery_sources s JOIN venues v ON v.id=s.venue_id ORDER BY v.name,s.url").stream().map(DiscoveryStore::forApi).toList();
    }
    /** SQL DATE is a calendar day, never a timestamp shifted by the JSON timezone. */
    public static Map<String,Object> forApi(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();
        row.forEach((key,value) -> result.put(key,
                value instanceof java.sql.Date date ? date.toLocalDate().toString() :
                value instanceof java.sql.Time time ? time.toLocalTime().toString() :
                value instanceof java.sql.Timestamp timestamp ? timestamp.toInstant().toString() : value));
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
    public void fetched(String id,VenueSourceDiscoveryService.Found found) {
        String diagnostics=String.join("; ",found.diagnostics());
        db.update("""
            UPDATE discovery_sources SET status=?,last_fetched_at=now(),fetch_method=?,scan_complete=?,fetch_diagnostics=?,quality=?,event_count=?,
            last_success_at=CASE WHEN ? OR ?='NO_EVENTS_CONFIRMED' THEN now() ELSE last_success_at END WHERE id=?
            """,found.extraction().status(),found.fetchMethod(),found.extraction().complete(),
            diagnostics.substring(0,Math.min(2000,diagnostics.length())),SourceQuality.assess(found),found.extraction().events().size(),
            !found.extraction().events().isEmpty(),SourceQuality.assess(found),id);
    }
    public void audit(String id,String oldStatus,String operation) {
        audit(id,oldStatus,operation,Objects.toString(candidate(id).get("matching_class"),null));
    }
    public void audit(String id,String oldStatus,String operation,String oldClass) {
        db.update("""
            INSERT INTO discovery_decisions(candidate_id,operation_id,old_status,new_status,origin,matching_class,old_matching_class,reason,matcher_version)
            SELECT id,?,?,status,decision_origin,matching_class,?,reason,matcher_version FROM discovery_candidates WHERE id=?
            """,operation,oldStatus,oldClass,id);
    }
    public void technicalAudit(String id,String oldClass,String operation) {
        db.update("""
            INSERT INTO discovery_decisions(candidate_id,operation_id,old_status,new_status,origin,matching_class,old_matching_class,reason,matcher_version)
            SELECT id,?,status,status,'AUTOMATIC',matching_class,?,automatic_reason,matcher_version FROM discovery_candidates WHERE id=?
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
