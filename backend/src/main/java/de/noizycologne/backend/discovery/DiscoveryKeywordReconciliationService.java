package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Persist rule decisions for existing candidates, not only for newly crawled events. */
@Service
public class DiscoveryKeywordReconciliationService {
    private final DiscoveryStore store;
    private final DiscoveryKeywordService keywords;
    private final DiscoveryEvaluationService evaluator;

    public DiscoveryKeywordReconciliationService(DiscoveryStore store,DiscoveryKeywordService keywords,DiscoveryEvaluationService evaluator) {
        this.store=store;this.keywords=keywords;this.evaluator=evaluator;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void synchronizeOnStartup(ApplicationReadyEvent event) {
        synchronize();
    }

    @Transactional
    public String save(@Nullable String id,String term,String reason,boolean enabled) {
        String saved=keywords.save(id,term,reason,enabled);
        synchronize();
        return saved;
    }

    @Transactional
    public DiscoveryKeywordService.BatchSave saveBatch(List<@NonNull String> terms,@Nullable String reason,boolean enabled) {
        var saved=keywords.saveBatch(terms,reason,enabled);
        if(saved.created()>0) synchronize();
        return saved;
    }

    /** Caller holds a transaction. The shared advisory lock excludes concurrent scans and resets. */
    private void synchronize() {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        var rules=evaluator.rules();
        for(var row:store.evaluationRows()) {
            if(row.get("calendar_entry_id")!=null || "IMPORTED".equals(row.get("status"))
                || "REJECTED".equals(row.get("status")) || "DUPLICATE".equals(row.get("status"))) continue;

            String status=(String)row.get("status");
            boolean previouslyFiltered="IGNORED".equals(status) && "KEYWORD_FILTER".equals(row.get("filter_origin"))
                && "AUTOMATIC".equals(row.get("decision_origin"));
            boolean open=List.of("NEEDS_REVIEW","NEEDS_INQUIRY","READY").contains(status);
            if(!open && !previouslyFiltered) continue;

            var event=evaluator.event(row);
            var hit=keywords.match(event);
            boolean knownVenue=row.get("venue_id")!=null;
            String id=(String)row.get("id");
            if(hit!=null && !knownVenue && !keywords.released(row)) {
                String reason=hit.explanation();
                if(open || !Objects.equals(row.get("reason"),reason)
                    || !Objects.equals(row.get("filter_rule_id"),hit.id())) {
                    store.jdbc().update("""
                        UPDATE discovery_candidates SET status='IGNORED',decision_origin='AUTOMATIC',
                        reason=?,automatic_reason=?,matching_class='UNVERIFIED',
                        previous_status=?,matcher_version=?,evaluated_at=now(),updated_at=now() WHERE id=?
                        """,reason,reason,status,EventMatcher.VERSION,id);
                    keywords.remember(id,event);
                    store.audit(id,status,null,(String)row.get("matching_class"));
                }
            } else if(previouslyFiltered && (hit==null || knownVenue)) {
                // Removing/deactivating a rule or confirming a portfolio venue restores review,
                // but never starts an automatic import or changes the calendar.
                var match=evaluator.evaluate(row,rules);
                String newStatus="READY".equals(match.status()) ? "NEEDS_REVIEW" : match.status();
                String reason=knownVenue && hit!=null ? hit.explanation() : match.reason();
                store.jdbc().update("""
                    UPDATE discovery_candidates SET status=?,reason=?,automatic_reason=?,matching_class=?,
                    previous_status=?,matcher_version=?,evaluated_at=now(),updated_at=now() WHERE id=?
                    """,newStatus,reason,reason,match.classification(),status,EventMatcher.VERSION,id);
                keywords.remember(id,event);
                store.audit(id,status,null,(String)row.get("matching_class"));
            }
        }
    }
}
