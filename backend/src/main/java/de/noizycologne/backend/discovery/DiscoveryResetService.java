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
    public record GeographicItem(String id,String status,String reason,String venueId) { }
    public record Preview(String token,int affectedCount,int preservedManual,int missingRaw,int enabledLocations,List<@org.jspecify.annotations.NonNull Item> items,List<@org.jspecify.annotations.NonNull GeographicItem> geography,int keywordExcluded,int keywordReview,int protectedRejections,int keywordRestored) { }
    public record Summary(int evaluated,int newPossibleMatches,int stillIgnored,int preservedManual,int reclassified,int failed,int geographicallyClassified) {
        public Summary(int evaluated,int newPossibleMatches,int stillIgnored,int preservedManual,int reclassified,int failed){this(evaluated,newPossibleMatches,stillIgnored,preservedManual,reclassified,failed,0);}
    }
    private final DiscoveryKeywordService keywords;
    private final DiscoveryStore store;
    private final DiscoveryEvaluationService evaluator;
    private final EventImportService importer;
    private final DiscoveryGeographyService geography;
    private final JsonMapper json=JsonMapper.builder().build();
    public DiscoveryResetService(DiscoveryKeywordService keywords,DiscoveryStore store,DiscoveryEvaluationService evaluator,EventImportService importer,DiscoveryGeographyService geography) {
        this.keywords=keywords;this.store=store;this.evaluator=evaluator;this.importer=importer;this.geography=geography;
    }
    static boolean selected(Map<String,Object> row,Selection selection) {
        if (row.get("calendar_entry_id")!=null || "IMPORTED".equals(row.get("status"))) return false;
        if (!"AUTOMATIC".equals(row.get("decision_origin")))
            return selection.includeManualRejected() && "MANUAL".equals(row.get("decision_origin")) && "REJECTED".equals(row.get("status"));
        return selection.mode()!=Mode.IGNORED || List.of("IGNORED","REJECTED").contains(row.get("status"));
    }
    private record Plan(Preview preview,List<@org.jspecify.annotations.NonNull Item> all,List<@org.jspecify.annotations.NonNull GeographicItem> geographical) { }
    private Plan plan(Selection selection) {
        var rows=store.evaluationRows();var rules=evaluator.rules();
        List<@org.jspecify.annotations.NonNull Item> items=new ArrayList<>();List<String> revisions=new ArrayList<>();
        List<@org.jspecify.annotations.NonNull GeographicItem> geographical=new ArrayList<>();
        int preserved=0,keywordExcluded=0,keywordReview=0,protectedRejections=0,keywordRestored=0;
        for (var row:rows) {
            boolean manualRejection="REJECTED".equals(row.get("status")) && !"AUTOMATIC".equals(row.get("decision_origin"));
            if(Boolean.TRUE.equals(row.get("special_source")) && row.get("calendar_entry_id")==null && !"IMPORTED".equals(row.get("status"))) {
                var geo=geography.assess(evaluator.event(row));
                geographical.add(new GeographicItem((String)row.get("id"),geo.status(),geo.reason(),geo.venueId()));
                revisions.add("geo|"+row.get("id")+"|"+row.get("updated_at")+"|"+row.get("raw_event"));
                if(!geo.inside() && keywords.match(evaluator.event(row))==null && !"KEYWORD_FILTER".equals(row.get("filter_origin"))){if(!"AUTOMATIC".equals(row.get("decision_origin")))preserved++;if(manualRejection)protectedRejections++;continue;}
            }
            // An editor's decision on a registered venue is not an automatic suggestion.
            // In particular, reset/re-evaluation must not discard a manually edited date,
            // inquiry or association merely because the keyword configuration changed.
            if(row.get("venue_id")!=null && "MANUAL".equals(row.get("decision_origin"))
                && !"REJECTED".equals(row.get("status"))) {
                preserved++;
                continue;
            }
            boolean changedRelease=keywords.releaseChanged(row);
            // Active exclusions are final also for known venues unless an editor
            // already reviewed that venue manually. Imports and releases stay protected.
            boolean keywordAutoExclude=row.get("calendar_entry_id")==null
                && !(row.get("venue_id")!=null && "MANUAL".equals(row.get("decision_origin")))
                && List.of("NEEDS_REVIEW","NEEDS_INQUIRY","READY").contains(row.get("status"))
                && keywords.match(evaluator.event(row))!=null && !keywords.released(row);
            if (!selected(row,selection) && !changedRelease && !keywordAutoExclude) {
                if(manualRejection) protectedRejections++;
                if (!"AUTOMATIC".equals(row.get("decision_origin"))) preserved++;
                continue;
            }
            var result=evaluator.evaluate(row,rules);
            boolean manual="MANUAL".equals(row.get("decision_origin"));
            String status=manual && !changedRelease && !keywordAutoExclude ? "NEEDS_REVIEW" : result.status();
            if(changedRelease && status.equals("READY")) status="NEEDS_REVIEW";
            var hit=keywords.match(evaluator.event(row));
            if(hit!=null && status.equals("IGNORED")) keywordExcluded++;
            if(hit!=null && row.get("venue_id")!=null && !status.equals("IGNORED")) keywordReview++;
            if("KEYWORD_FILTER".equals(row.get("filter_origin")) && "IGNORED".equals(row.get("status")) && !status.equals("IGNORED")) keywordRestored++;
            String reason=manual && !changedRelease && !keywordAutoExclude ? "Manuell abgelehnt; ausdrücklich erneut zur Prüfung vorgelegt. "+result.reason() : result.reason();
            if (!manual && selection.mode()==Mode.RESCAN && status.equals("READY")) {
                status="NEEDS_REVIEW";
                reason="Neuer Abruf vor automatischer Übernahme erforderlich; "+reason;
            }
            items.add(new Item((String)row.get("id"),(String)row.get("title"),(String)row.get("venue_name"),
                (String)row.get("status"),status,result.classification(),reason,result.reason(),manual,row.get("raw_event")==null,manual ? (String)row.get("party_id") : result.partyId()));
            revisions.add(row.get("id")+"|"+row.get("updated_at")+"|"+row.get("fingerprint")+"|"+row.get("raw_event"));
        }
        String token=EventNormalizer.hash(keywords.version()+EventMatcher.VERSION+json.writeValueAsString(selection)+json.writeValueAsString(items)+json.writeValueAsString(geographical)+json.writeValueAsString(revisions));
        Integer count=store.jdbc().queryForObject("SELECT count(*) FROM venues WHERE discovery_enabled",Integer.class);
        if (count==null) throw new IllegalStateException("Location-Zählung lieferte kein Ergebnis");
        int enabled=count;
        return new Plan(new Preview(token,items.size(),preserved,(int)items.stream().filter(Item::missingRaw).count(),enabled,items.stream().limit(500).toList(),List.copyOf(geographical),keywordExcluded,keywordReview,protectedRejections,keywordRestored),items,geographical);
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
        for(var item:plan.geographical()){var row=store.candidate(item.id());geography.classifyCandidate(row);}
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
            keywords.remember(item.id(),evaluator.event(before));
            if("IGNORED".equals(item.newStatus())
                && "MANUAL".equals(before.get("decision_origin"))
                && List.of("NEEDS_REVIEW","NEEDS_INQUIRY","READY").contains(before.get("status"))
                && !keywords.released(before) && keywords.match(evaluator.event(before))!=null)
                store.jdbc().update("UPDATE discovery_candidates SET decision_origin='AUTOMATIC' WHERE id=?",item.id());
            if(keywords.releaseChanged(before)) store.jdbc().update("UPDATE discovery_candidates SET keyword_release_version=?,decision_origin=? WHERE id=?",keywords.version(),item.newStatus().equals("IGNORED") ? "AUTOMATIC" : "MANUAL",item.id());
            // An explicitly reopened manual rejection stays manual and can never auto-import.
            store.audit(item.id(),item.oldStatus(),request.operationId().toString(),(String)before.get("matching_class"));
            if (item.newStatus().equals("READY") && !item.manual()) {
                var date=EventNormalizer.date(evaluator.event(before).starts().getFirst());
                importer.importCandidate(item.id(),item.partyId(),date,false);
            }
        }
        return new Summary(plan.all().size(),possible,ignored,plan.preview().preservedManual(),changed,0,plan.geographical().size());
    }
    public String hash(Request request) { return EventNormalizer.hash(json.writeValueAsString(request)); }
    public String serialize(Object value) { return json.writeValueAsString(value); }
}
