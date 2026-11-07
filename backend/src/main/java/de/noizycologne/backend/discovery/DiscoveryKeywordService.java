package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Explicit team rules; matching never uses a guessed venue or seeds example rules. */
@Service
public class DiscoveryKeywordService {
    private final DiscoveryStore store;
    private final tools.jackson.databind.json.JsonMapper json=tools.jackson.databind.json.JsonMapper.builder().build();
    private record Configuration(String id,String keyword,String reason,boolean enabled) { }
    public DiscoveryKeywordService(DiscoveryStore store) {this.store=store;}
    public record Hit(String id,String keyword,String reason,String evidence) {
        public String explanation() {return "Schlagwortfilter „"+keyword+"“: "+reason;}
    }
    public List<Map<String,Object>> list() {
        return store.jdbc().queryForList("SELECT * FROM discovery_keyword_rules ORDER BY id").stream().map(DiscoveryStore::forApi).toList();
    }
    public String version() {
        var configuration=list().stream().map(row -> new Configuration(Objects.toString(row.get("id"),""),Objects.toString(row.get("keyword"),""),Objects.toString(row.get("reason"),""),Boolean.TRUE.equals(row.get("enabled")))).toList();
        return EventNormalizer.hash(json.writeValueAsString(configuration));
    }
    @Transactional
    public String save(@Nullable String id,String keyword,String reason,boolean enabled) {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        if(EventNormalizer.text(keyword).isBlank() || reason.isBlank()) throw new IllegalArgumentException("Schlagwort und Ausschlussgrund fehlen");
        if(id==null) {
            id=UUID.randomUUID().toString();
            store.jdbc().update("INSERT INTO discovery_keyword_rules(id,keyword,reason,enabled) VALUES (?,?,?,?)",id,keyword.trim(),reason.trim(),enabled);
        } else if(store.jdbc().update("UPDATE discovery_keyword_rules SET keyword=?,reason=?,enabled=?,revision=revision+1,updated_at=now() WHERE id=?",keyword.trim(),reason.trim(),enabled,id)==0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Schlagwortregel fehlt");
        return id;
    }
    public @Nullable Hit match(EventExtractor.Event event) {
        String text=" "+EventNormalizer.text(event.title()+" "+event.evidence())+" ";
        for(var row:list()) {
            String term=(String)row.get("keyword");
            if(Boolean.TRUE.equals(row.get("enabled")) && text.contains(" "+EventNormalizer.text(term)+" "))
                return new Hit((String)row.get("id"),term,(String)row.get("reason"),event.title()+"\n"+event.evidence());
        }
        return null;
    }
    public boolean released(Map<String,Object> row) {return version().equals(row.get("keyword_release_version"));}
    public boolean releaseChanged(Map<String,Object> row) {
        return row.get("keyword_release_version")!=null && !released(row) && "MANUAL".equals(row.get("decision_origin"))
            && List.of("NEEDS_REVIEW","NEEDS_INQUIRY").contains(row.get("status")) && row.get("calendar_entry_id")==null;
    }
    public boolean unchangedDecision(@Nullable Map<String,Object> before,EventExtractor.Event event,String status,String reason) {
        if(before==null || !"KEYWORD_FILTER".equals(before.get("filter_origin"))) return false;
        var hit=match(event);
        return hit!=null && hit.id().equals(before.get("filter_rule_id")) && status.equals(before.get("status")) && reason.equals(before.get("reason"));
    }
    public void remember(String id,EventExtractor.Event event) {
        var hit=match(event);
        store.jdbc().update("""
            UPDATE discovery_candidates SET filter_origin=?,filter_rule_id=?,filter_keyword=?,filter_reason=?,filter_evidence=?
            WHERE id=? AND (filter_origin,filter_rule_id,filter_keyword,filter_reason,filter_evidence) IS DISTINCT FROM (?,?,?,?,?)
            """,hit==null?null:"KEYWORD_FILTER",hit==null?null:hit.id(),hit==null?null:hit.keyword(),hit==null?null:hit.reason(),hit==null?null:hit.evidence(),id,
            hit==null?null:"KEYWORD_FILTER",hit==null?null:hit.id(),hit==null?null:hit.keyword(),hit==null?null:hit.reason(),hit==null?null:hit.evidence());
    }
    @Transactional
    public void reopen(String id) {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        var row=store.candidate(id);
        if(row.get("calendar_entry_id")!=null || !List.of("IGNORED","REJECTED","DUPLICATE").contains(row.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Nur archivierte Vorschläge ohne Kalendertermin wiederfreigeben");
        store.jdbc().update("UPDATE discovery_candidates SET status='NEEDS_REVIEW',decision_origin='MANUAL',keyword_release_version=?,previous_status=?,reason='Manuell wiederfreigegeben; Veranstaltung ausdrücklich prüfen',updated_at=now() WHERE id=?",version(),row.get("status"),id);
        store.audit(id,(String)row.get("status"),null);
    }
    public record Conflict(String id,String reason) { }
    public record Batch(int requested,int rejected,int alreadyRejected,List<@NonNull String> rejectedIds,List<@NonNull String> alreadyRejectedIds,List<@NonNull Conflict> conflicts) { }
    @Transactional
    public Batch rejectBatch(List<@NonNull String> candidateIds,@Nullable Map<String,String> expected,EventImportService importer) {
        var ids=new LinkedHashSet<>(candidateIds);
        if(ids.isEmpty() || ids.size()>500) throw new IllegalArgumentException("1 bis 500 unterschiedliche Kandidaten erforderlich");
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        List<@NonNull String> rejected=new ArrayList<>(),already=new ArrayList<>();List<@NonNull Conflict> conflicts=new ArrayList<>();
        for(String id:ids) {
            var rows=store.jdbc().queryForList("SELECT * FROM discovery_candidates WHERE id=?",id);
            if(rows.isEmpty()){conflicts.add(new Conflict(id,"Kandidat nicht mehr vorhanden"));continue;}
            var row=Objects.requireNonNull(rows.getFirst());
            if("REJECTED".equals(row.get("status")) && row.get("calendar_entry_id")==null){already.add(id);continue;}
            String revision=row.get("updated_at") instanceof java.sql.Timestamp time ? time.toInstant().toString() : "";
            if(row.get("calendar_entry_id")!=null || !List.of("NEEDS_REVIEW","NEEDS_INQUIRY","READY").contains(row.get("status"))
                || expected!=null && !revision.equals(expected.get(id))) {
                conflicts.add(new Conflict(id,"Inzwischen geändert, archiviert oder bereits übernommen; erneut prüfen"));continue;
            }
            importer.reject(id);rejected.add(id);
        }
        return new Batch(ids.size(),rejected.size(),already.size(),List.copyOf(rejected),List.copyOf(already),List.copyOf(conflicts));
    }
}
