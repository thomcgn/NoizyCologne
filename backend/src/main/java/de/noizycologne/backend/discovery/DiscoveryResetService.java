package de.noizycologne.backend.discovery;

import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

@Service
public class DiscoveryResetService {
    public enum Mode { IGNORED, ALL, RESCAN }
    public static final String MANUAL_CONFIRMATION="MANUELL ABGELEHNTE ERNEUT PRÜFEN";
    public record Selection(@NotNull Mode mode,boolean includeManualRejected) { }
    public record Request(@NotNull Mode mode,boolean includeManualRejected,@Size(max=100) String confirmation,
                          @NotBlank @Pattern(regexp="[0-9a-f]{64}") String previewToken,@NotNull UUID operationId) {
        public Selection selection() { return new Selection(mode,includeManualRejected); }
        public void validate() {
            if (includeManualRejected && !MANUAL_CONFIRMATION.equals(confirmation))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Zusätzliche Bestätigung für manuelle Ablehnungen fehlt");
        }
    }
    public record Item(String id,String title,String venue,String oldStatus,String newStatus,String classification,String reason,String automaticReason,boolean manual,boolean missingRaw,String partyId) { }
    public record Preview(String token,int affectedCount,int preservedManual,int missingRaw,int enabledLocations,List<@org.jspecify.annotations.NonNull Item> items) { }
    public record Summary(int evaluated,int newPossibleMatches,int stillIgnored,int preservedManual,int reclassified,int failed) { }
    private final DiscoveryStore store;
    private final DiscoveryEvaluationService evaluator;
    private final EventImportService importer;
    private final JsonMapper json=JsonMapper.builder().build();
    public DiscoveryResetService(DiscoveryStore store,DiscoveryEvaluationService evaluator,EventImportService importer) {
        this.store=store;this.evaluator=evaluator;this.importer=importer;
    }
    static boolean selected(Map<String,Object> row,Selection selection) {
        if (row.get("calendar_entry_id")!=null || "IMPORTED".equals(row.get("status"))) return false;
        if (!"AUTOMATIC".equals(row.get("decision_origin")))
            return selection.includeManualRejected() && "MANUAL".equals(row.get("decision_origin")) && "REJECTED".equals(row.get("status"));
        return selection.mode()!=Mode.IGNORED || List.of("IGNORED","REJECTED").contains(row.get("status"));
    }
    private record Plan(Preview preview,List<@org.jspecify.annotations.NonNull Item> all) { }
    private Plan plan(Selection selection) {
        var rows=store.evaluationRows();var rules=evaluator.rules();
        List<@org.jspecify.annotations.NonNull Item> items=new ArrayList<>();List<String> revisions=new ArrayList<>();
        int preserved=0;
        for (var row:rows) {
            if (!selected(row,selection)) {
                if (!"AUTOMATIC".equals(row.get("decision_origin"))) preserved++;
                continue;
            }
            var result=evaluator.evaluate(row,rules);
            boolean manual="MANUAL".equals(row.get("decision_origin"));
            String status=manual ? "NEEDS_REVIEW" : result.status();
            String reason=manual ? "Manuell abgelehnt; ausdrücklich erneut zur Prüfung vorgelegt. "+result.reason() : result.reason();
            if (!manual && selection.mode()==Mode.RESCAN && status.equals("READY")) {
                status="NEEDS_REVIEW";
                reason="Neuer Abruf vor automatischer Übernahme erforderlich; "+reason;
            }
            items.add(new Item((String)row.get("id"),(String)row.get("title"),(String)row.get("venue_name"),
                (String)row.get("status"),status,result.classification(),reason,result.reason(),manual,row.get("raw_event")==null,manual ? (String)row.get("party_id") : result.partyId()));
            revisions.add(row.get("id")+"|"+row.get("updated_at")+"|"+row.get("fingerprint")+"|"+row.get("raw_event"));
        }
        String token=EventNormalizer.hash(EventMatcher.VERSION+json.writeValueAsString(selection)+json.writeValueAsString(items)+json.writeValueAsString(revisions));
        Integer count=store.jdbc().queryForObject("SELECT count(*) FROM venues WHERE discovery_enabled",Integer.class);
        if (count==null) throw new IllegalStateException("Location-Zählung lieferte kein Ergebnis");
        int enabled=count;
        return new Plan(new Preview(token,items.size(),preserved,(int)items.stream().filter(Item::missingRaw).count(),enabled,items.stream().limit(500).toList()),items);
    }
    @Transactional(readOnly=true)
    public Preview preview(Selection selection) { return plan(selection).preview(); }
    @Transactional
    public Summary apply(Request request) {
        request.validate();
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        var plan=plan(request.selection());
        if (!plan.preview().token().equals(request.previewToken()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Vorschau ist veraltet; bitte erneut prüfen");
        int possible=0,ignored=0,changed=0;
        for (var item:plan.all()) {
            var before=store.candidate(item.id());
            if (!item.oldStatus().equals(item.newStatus()) || !item.classification().equals(before.get("matching_class"))) changed++;
            if (item.classification().equals("POSSIBLE_MATCH") && !"POSSIBLE_MATCH".equals(before.get("matching_class"))) possible++;
            if (item.newStatus().equals("IGNORED")) ignored++;
            store.jdbc().update("""
                UPDATE discovery_candidates SET status=?,reason=?,automatic_reason=?,matching_class=?,party_id=?,matcher_version=?,
                evaluated_at=now(),previous_status=?,last_evaluation_id=?,updated_at=now() WHERE id=?
                """,item.newStatus(),item.reason(),item.automaticReason(),item.classification(),item.partyId(),EventMatcher.VERSION,
                item.oldStatus(),request.operationId().toString(),item.id());
            // An explicitly reopened manual rejection stays manual and can never auto-import.
            store.audit(item.id(),item.oldStatus(),request.operationId().toString(),(String)before.get("matching_class"));
            if (item.newStatus().equals("READY") && !item.manual()) {
                var date=EventNormalizer.date(evaluator.event(before).starts().getFirst());
                importer.importCandidate(item.id(),item.partyId(),date,false);
            }
        }
        return new Summary(plan.all().size(),possible,ignored,plan.preview().preservedManual(),changed,0);
    }
    public String hash(Request request) { return EventNormalizer.hash(json.writeValueAsString(request)); }
    public String serialize(Object value) { return json.writeValueAsString(value); }
}
