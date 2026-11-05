package de.noizycologne.backend.discovery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class DiscoveryStore {
    private final JdbcTemplate db;
    public JdbcTemplate jdbc() { return db; }
    public DiscoveryStore(JdbcTemplate db) { this.db = db; }
    public List<Map<String,Object>> candidates() {
        return db.queryForList("SELECT c.*, v.name AS venue_name, v.instagram FROM discovery_candidates c JOIN venues v ON v.id=c.venue_id ORDER BY c.updated_at DESC LIMIT 500").stream().map(DiscoveryStore::forApi).toList();
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
        var rows = db.queryForList("SELECT c.*, v.name AS venue_name, v.instagram FROM discovery_candidates c JOIN venues v ON v.id=c.venue_id WHERE c.id=?", id);
        if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Vorschlag fehlt");
        return rows.get(0);
    }
    public String source(String venue, String url, String type, boolean approved) {
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO discovery_sources(id,venue_id,url,source_type,approved) VALUES (?,?,?,?,?) ON CONFLICT(venue_id,url) DO UPDATE SET source_type=excluded.source_type, approved=CASE WHEN discovery_sources.source_type='OFFICIAL' AND excluded.source_type='EXTERNAL' THEN false ELSE discovery_sources.approved END", id, venue, url, type, approved);
        return db.queryForObject("SELECT id FROM discovery_sources WHERE venue_id=? AND url=?", String.class, venue, url);
    }
    public Map<String,List<String>> aliases() {
        Map<String,List<String>> map = new HashMap<>();
        db.query("SELECT party_id,alias FROM party_series_aliases", (org.springframework.jdbc.core.RowCallbackHandler) r -> map.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(r.getString(2)));
        return map;
    }
}
