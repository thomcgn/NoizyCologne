package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
        archiveExistingOpenCandidates();
        return id;
    }
    public record BatchSave(int created,int alreadyPresent,List<@NonNull String> skipped) { }
    /** Bulk entry is atomic; identical normalized terms do not create duplicate rules. */
    @Transactional
    public BatchSave saveBatch(List<@NonNull String> input,@Nullable String commonReason,boolean enabled) {
        if(input.isEmpty() || input.size()>100) throw new IllegalArgumentException("1 bis 100 Schlagwörter erforderlich");
        String shared=commonReason==null ? "" : commonReason.trim();
        if(shared.length()>1000) throw new IllegalArgumentException("Ausschlussgrund ist zu lang");
        Map<String,String> requested=new LinkedHashMap<>();
        List<@NonNull String> skipped=new ArrayList<>();
        for(String value:input) {
            if(value==null || value.isBlank() || value.length()>200 || value.contains(","))
                throw new IllegalArgumentException("Ungültiges Schlagwort: maximal 200 Zeichen, ohne Komma");
            String term=value.trim(),normalized=EventNormalizer.text(term);
            if(normalized.isBlank()) throw new IllegalArgumentException("Leeres Schlagwort");
            // Report duplicates within the submitted list as skipped, too.
            if(requested.putIfAbsent(normalized,term)!=null)
                skipped.add(Objects.requireNonNull(term,"Schlagwort fehlt"));
        }
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        Set<String> known=new HashSet<>();
        for(var row:list()) known.add(EventNormalizer.text((String)row.get("keyword")));
        int created=0;
        for(var entry:requested.entrySet()) {
            if(!known.add(entry.getKey())) {skipped.add(Objects.requireNonNull(entry.getValue(),"Schlagwort fehlt"));continue;}
            String term=entry.getValue(),reason=shared.isBlank() ? "Automatischer Ausschluss: "+term : shared;
            store.jdbc().update("INSERT INTO discovery_keyword_rules(id,keyword,reason,enabled) VALUES (?,?,?,?)",
                UUID.randomUUID().toString(),term,reason,enabled);
            created++;
        }
        if(created>0) archiveExistingOpenCandidates();
        return new BatchSave(created,skipped.size(),List.copyOf(skipped));
    }
    /**
     * Reconcile legacy open proposals when the application starts and whenever
     * keyword rules change. Never delete records or reclassify imported entries,
     * manual decisions at confirmed venues, rejections or explicit releases.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void archiveExistingOnStartup() {
        archiveExistingOpenCandidates();
    }

    private int archiveExistingOpenCandidates() {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        var enabledRules=list().stream().filter(rule -> Boolean.TRUE.equals(rule.get("enabled"))).toList();
        if(enabledRules.isEmpty()) return 0;
        String releaseVersion=version();
        var rows=store.jdbc().queryForList("""
            SELECT id,title,evidence,status,decision_origin,keyword_release_version
            FROM discovery_candidates
            WHERE calendar_entry_id IS NULL
              AND status IN ('NEEDS_REVIEW','NEEDS_INQUIRY','READY')
              AND NOT (venue_id IS NOT NULL AND decision_origin='MANUAL')
            """);
        int archived=0;
        for(var row:rows) {
            if(releaseVersion.equals(row.get("keyword_release_version"))) continue;
            String searchable=" "+EventNormalizer.text(
                Objects.toString(row.get("title"),"")+" "+Objects.toString(row.get("evidence"),""))+" ";
            for(var rule:enabledRules) {
                String keyword=Objects.toString(rule.get("keyword"),"");
                if(!searchable.contains(" "+EventNormalizer.text(keyword)+" ")) continue;
                var hit=new Hit((String)rule.get("id"),keyword,(String)rule.get("reason"),
                    Objects.toString(row.get("title"),"")+"\\n"+Objects.toString(row.get("evidence"),""));
                String reason=hit.explanation();
                int updated=store.jdbc().update("""
                    UPDATE discovery_candidates SET status='IGNORED',decision_origin='AUTOMATIC',
                    previous_status=status,reason=?,automatic_reason=?,
                    filter_origin='KEYWORD_FILTER',filter_rule_id=?,filter_keyword=?,filter_reason=?,filter_evidence=?,
                    updated_at=now()
                    WHERE id=? AND calendar_entry_id IS NULL
                      AND status IN ('NEEDS_REVIEW','NEEDS_INQUIRY','READY')
                      AND NOT (venue_id IS NOT NULL AND decision_origin='MANUAL')
                    """,reason,reason,hit.id(),hit.keyword(),hit.reason(),hit.evidence(),row.get("id"));
                if(updated>0) {
                    store.audit((String)row.get("id"),(String)row.get("status"),null);
                    archived++;
                }
                break;
            }
        }
        return archived;
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
        var row=store.candidate(id);
        boolean excluded="IGNORED".equals(row.get("status")) && "AUTOMATIC".equals(row.get("decision_origin"));
        String origin=hit!=null ? "KEYWORD_FILTER" : excluded ? "EVENT_FILTER" : null;
        String rule=hit==null ? null : hit.id(),keyword=hit==null ? null : hit.keyword();
        String reason=hit!=null ? hit.reason() : excluded ? (String)row.get("reason") : null;
        // Snapshot every exclusion in the existing audit fields, including its source
        // and occurrence. A later crawl may replace the candidate's latest evidence.
        String evidence=hit!=null ? hit.evidence() : excluded ? event.url()+"\n"+event.title()+"\n"+event.starts()+"\n"+event.evidence() : null;
        store.jdbc().update("""
            UPDATE discovery_candidates SET filter_origin=?,filter_rule_id=?,filter_keyword=?,filter_reason=?,filter_evidence=?
            WHERE id=? AND (filter_origin,filter_rule_id,filter_keyword,filter_reason,filter_evidence) IS DISTINCT FROM (?,?,?,?,?)
            """,origin,rule,keyword,reason,evidence,id,origin,rule,keyword,reason,evidence);
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
