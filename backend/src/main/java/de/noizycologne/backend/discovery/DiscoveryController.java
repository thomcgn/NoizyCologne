package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/discovery")
public class DiscoveryController {
    private final DiscoveryKeywordService keywords;
    private final DiscoveryStore store;
    private final DiscoveryScheduler scheduler;
    private final VenueSourceDiscoveryService discovery;
    private final EventImportService importer;
    private final DiscoveryResetService resets;
    private final EventChangeService changes;
    private final DiscoveryEvaluationService evaluator;
    private final DiscoveryOnboardingService onboarding;
    private final DiscoveryPortfolioService portfolio;
    public DiscoveryController(DiscoveryKeywordService keywords,DiscoveryStore store,DiscoveryScheduler scheduler,VenueSourceDiscoveryService discovery,EventImportService importer,DiscoveryResetService resets,EventChangeService changes,DiscoveryEvaluationService evaluator,DiscoveryOnboardingService onboarding,DiscoveryPortfolioService portfolio) {
        this.keywords=keywords;this.store=store; this.scheduler=scheduler; this.discovery=discovery; this.importer=importer; this.resets=resets; this.changes=changes;this.evaluator=evaluator;this.onboarding=onboarding;this.portfolio=portfolio;
    }
    @GetMapping("/changes") public List<Map<String,Object>> changes() { return changes.list(); }
    @GetMapping("/changes/{id}/replacements") public List<Map<String,Object>> replacements(@PathVariable String id) { return changes.replacements(id); }
    @GetMapping("/candidates/{id}") public Map<String,Object> candidate(@PathVariable String id) { return evaluator.forApi(store.candidate(id),evaluator.rules()); }
    @GetMapping("/changes/{id}/history") public List<Map<String,Object>> changeHistory(@PathVariable String id) { return changes.history(id); }
    @GetMapping("/changes/{id}/dm") public Map<String,String> changeDm(@PathVariable String id) { return Map.of("text",changes.dm(id)); }
    @PutMapping("/changes/{id}") public void decide(@PathVariable String id,@Valid @RequestBody EventChangeService.Decision decision) { changes.decide(id,decision); }
    @PostMapping("/monitor") @ResponseStatus(HttpStatus.ACCEPTED) public Map<String,Boolean> monitor() {
        if(!scheduler.startChanges()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Discovery-Auftrag läuft bereits");
        return Map.of("accepted",true);
    }
    @GetMapping public Map<String,Object> overview(@RequestParam(defaultValue="ALL") String filter) {
        var rules=evaluator.rules();
        return Map.of("candidates",store.candidates(filter).stream().map(row -> evaluator.forApi(row,rules)).toList(),"sources",store.sources(),
                "counts",store.jdbc().queryForList("SELECT status,count(*) AS count FROM discovery_candidates WHERE (geo_status='INSIDE' OR source_id IN (SELECT id FROM discovery_sources WHERE venue_id IS NOT NULL)) OR calendar_entry_id IS NOT NULL OR status IN ('REJECTED','DUPLICATE','IGNORED') GROUP BY status"),
                "automaticallyImported",store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates c JOIN calendar_entries e ON e.id=c.calendar_entry_id WHERE c.status='IMPORTED' AND c.reason='Automatisch übernommen'",Long.class),
                "metrics",store.jdbc().queryForMap("""
                    SELECT count(*) FILTER (WHERE decision_origin='AUTOMATIC' AND status='IGNORED') AS "automaticallyIgnored",
                    count(*) FILTER (WHERE decision_origin='MANUAL' AND status='REJECTED') AS "manuallyRejected",
                    count(*) FILTER (WHERE status IN ('NEEDS_REVIEW','NEEDS_INQUIRY','READY') AND (geo_status='INSIDE' OR source_id IN (SELECT id FROM discovery_sources WHERE venue_id IS NOT NULL))) AS "needsReview",
                    count(*) FILTER (WHERE EXISTS (SELECT 1 FROM discovery_decisions d JOIN discovery_operations o ON o.id=d.operation_id WHERE d.candidate_id=discovery_candidates.id AND o.kind<>'SCAN' AND d.old_status IS NOT NULL)) AS reevaluated,
                    count(*) FILTER (WHERE matching_class='UNVERIFIED' AND status NOT IN ('IMPORTED','REJECTED','DUPLICATE','IGNORED') AND (geo_status='INSIDE' OR source_id IN (SELECT id FROM discovery_sources WHERE venue_id IS NOT NULL))) AS unverified,
                    count(*) FILTER (WHERE matching_class='POSSIBLE_MATCH' AND status NOT IN ('IMPORTED','REJECTED','DUPLICATE','IGNORED') AND (geo_status='INSIDE' OR source_id IN (SELECT id FROM discovery_sources WHERE venue_id IS NOT NULL))) AS possible,
                    count(*) FILTER (WHERE inquiry_status IN ('PREPARED','SENT') AND status NOT IN ('IMPORTED','REJECTED','DUPLICATE','IGNORED') AND (geo_status='INSIDE' OR source_id IN (SELECT id FROM discovery_sources WHERE venue_id IS NOT NULL))) AS open,
                    count(*) FILTER (WHERE status IN ('REJECTED','DUPLICATE','IGNORED')) AS archive FROM discovery_candidates
                    """),
                "operations",store.jdbc().queryForList("SELECT * FROM discovery_operations ORDER BY started_at DESC LIMIT 10").stream().map(DiscoveryProgress::forApi).toList(),
                "diagnoses",store.jdbc().queryForList("SELECT d.*,c.title,v.name AS venue_name FROM discovery_decisions d JOIN discovery_candidates c ON c.id=d.candidate_id LEFT JOIN venues v ON v.id=c.venue_id ORDER BY d.id DESC LIMIT 500").stream().map(DiscoveryStore::forApi).toList(),
                "runs",store.jdbc().queryForList("SELECT * FROM discovery_runs ORDER BY started_at DESC LIMIT 10").stream().map(DiscoveryStore::forApi).toList());
    }
    @PostMapping("/scan") @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Boolean> scan() {
        if (!scheduler.start()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Suchlauf läuft bereits");
        return Map.of("accepted",true);
    }
    @PostMapping("/reset/preview")
    public DiscoveryResetService.Preview preview(@Valid @RequestBody DiscoveryResetService.Selection selection) {
        return resets.preview(selection);
    }
    @PostMapping("/reset") @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,String> reset(@Valid @RequestBody DiscoveryResetService.Request request) {
        String id=scheduler.startReset(request);
        if (id==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Suchlauf oder Neubewertung läuft bereits");
        return Map.of("operationId",id);
    }
    @GetMapping("/operations/{id}") public Map<String,Object> operation(@PathVariable String id) {
        var rows=store.jdbc().queryForList("SELECT * FROM discovery_operations WHERE id=?",id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Vorgang fehlt");
        return DiscoveryProgress.forApi(rows.getFirst());
    }
    @PostMapping("/onboard") public DiscoveryOnboardingService.Report onboard(@Valid @RequestBody Probe request) throws java.io.IOException {
        return onboarding.preview(request.url());
    }
    public record SourcePreview(@NotBlank @Size(max=100) String previewToken) { }
    @PostMapping("/onboard/{venueId}/sources") public void saveSources(@PathVariable String venueId,@Valid @RequestBody SourcePreview request) {
        onboarding.saveSources(venueId,request.previewToken());
    }
    public record Probe(@NotBlank @Size(max=2048) String url) { }
    private final java.util.concurrent.Semaphore probeLock=new java.util.concurrent.Semaphore(1);
    @PostMapping("/probe") public List<VenueSourceDiscoveryService.@NonNull Found> probe(@Valid @RequestBody Probe request) throws java.io.IOException {
        if (!probeLock.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Quellenprüfung läuft bereits");
        try { return discovery.discover(request.url(),EventExtractor.Profile.defaults()); }
        catch (java.io.IOException e) {
            String status=e instanceof EventPageFetcher.FetchFailure f ? f.status : "SCAN_FAILED";
            return List.of(new VenueSourceDiscoveryService.Found(SafeUrls.parse(request.url()),new EventExtractor.Extraction(List.of(),status)));
        }
        finally { probeLock.release(); }
    }
    public record Review(@Size(max=100) String partyId, LocalDate date, @NotBlank String status,
                         @NotBlank String inquiryStatus, @Size(max=12000) String dmText,@Size(max=100) String venueId) {
        public Review(String partyId,LocalDate date,String status,String inquiryStatus,String dmText) {this(partyId,date,status,inquiryStatus,dmText,null);}
    }
    @PutMapping("/candidates/{id}") public void review(@PathVariable String id,@Valid @RequestBody Review r) {
        importer.review(id,r.partyId(),r.date(),r.status(),r.inquiryStatus(),Objects.toString(r.dmText(),""),r.venueId());
    }
    public record Import(@NotBlank String partyId,@NotNull LocalDate date) { }
    @PostMapping("/candidates/{id}/import") public void importEvent(@PathVariable String id,@Valid @RequestBody Import r) {
        importer.importCandidate(id,r.partyId(),r.date(),true);
    }
    public record Confirmation(@NotBlank @Size(max=100) String partyId,@NotNull LocalDate date,
                               @NotBlank String inquiryStatus,@Size(max=12000) String dmText,@Size(max=200) String seriesAlias,@Size(max=100) String venueId) { }
    @PostMapping("/candidates/{id}/confirm") public Map<String,String> confirm(@PathVariable String id,@Valid @RequestBody Confirmation r) {
        return Map.of("calendarEntryId",importer.confirm(id,r.partyId(),r.date(),r.inquiryStatus(),Objects.toString(r.dmText(),""),r.seriesAlias(),r.venueId()));
    }
    public record KeywordRule(@NotBlank @Size(max=200) String keyword,@NotBlank @Size(max=1000) String reason,boolean enabled) { }
    public record KeywordBatch(@NotNull @Size(min=1,max=100) List<@NotBlank @Size(max=200) String> keywords,
                               @Size(max=1000) String reason,boolean enabled) { }
    @PostMapping("/keyword-rules/batch") public DiscoveryKeywordService.BatchSave addKeywords(@Valid @RequestBody KeywordBatch batch) {
        return keywords.saveBatch(batch.keywords(),batch.reason(),batch.enabled());
    }
    @GetMapping("/keyword-rules") public List<Map<String,Object>> keywordRules() {return keywords.list();}
    @PostMapping("/keyword-rules") public Map<String,String> addKeyword(@Valid @RequestBody KeywordRule rule) {return Map.of("id",keywords.save(null,rule.keyword(),rule.reason(),rule.enabled()));}
    @PutMapping("/keyword-rules/{id}") public void editKeyword(@PathVariable String id,@Valid @RequestBody KeywordRule rule) {keywords.save(id,rule.keyword(),rule.reason(),rule.enabled());}
    public record RejectBatch(@NotNull @Size(min=1,max=500) List<@NonNull @NotBlank @Size(max=100) String> candidateIds,
                              @Size(max=500) Map<String,String> expectedRevisions) { }
    @PostMapping("/candidates/reject-batch") public DiscoveryKeywordService.Batch rejectBatch(@Valid @RequestBody RejectBatch request) {return keywords.rejectBatch(request.candidateIds(),request.expectedRevisions(),importer);}
    @PostMapping("/candidates/{id}/reopen") public void reopen(@PathVariable String id) {keywords.reopen(id);}
    @PostMapping("/candidates/{id}/reject") public void reject(@PathVariable String id) { importer.reject(id); }
    @GetMapping("/candidates/{id}/dm") public Map<String,String> dm(@PathVariable String id) { return Map.of("text",importer.dm(id)); }
    public record PortfolioApproval(@NotBlank @Size(max=200) String name,@Size(max=200) String seriesAlias,
                                   @jakarta.validation.constraints.Pattern(regexp="(?:[01][0-9]|2[0-3]):[0-5][0-9]") String defaultStartTime,
                                   @NotNull @Size(max=50) Set<@NotBlank @Size(max=100) String> standardGenres,
                                   @Valid de.noizycologne.backend.party.PartyRecurrenceRequest recurrence) { }
    @PostMapping("/candidates/{id}/portfolio") public Map<String,String> portfolio(@PathVariable String id,@Valid @RequestBody PortfolioApproval request) {
        return Map.of("partyId",portfolio.approve(id,request.name(),request.seriesAlias(),request.defaultStartTime(),request.standardGenres(),request.recurrence()));
    }
    @GetMapping("/sources") public List<Map<String,Object>> sources(@RequestParam String venueId) {
        return store.jdbc().queryForList("SELECT s.*,v.name AS venue_name FROM discovery_sources s JOIN venues v ON v.id=s.venue_id WHERE venue_id=? ORDER BY priority,url",venueId).stream().map(DiscoveryStore::forApi).toList();
    }
    @GetMapping("/candidates/{id}/sources") public List<Map<String,Object>> origins(@PathVariable String id) {
        store.candidate(id);return store.origins(id);
    }
    public record Source(@NotBlank String venueId,@NotBlank @Size(max=2048) String url,boolean approved,
                         @Size(max=300) String itemSelector,@Size(max=300) String titleSelector,@Size(max=300) String dateSelector,
                         @jakarta.validation.constraints.Pattern(regexp="WEBSITE|OFFICIAL_ORGANIZER|OFFICIAL_TICKETS|PLATFORM|CUSTOM") String sourceKind,
                         Boolean enabled,@Min(0) @Max(999) Integer priority) {
        public Source(String venueId,String url,boolean approved,String item,String title,String date) {
            this(venueId,url,approved,item,title,date,null,null,null);
        }
    }
    @PostMapping("/sources") @org.springframework.transaction.annotation.Transactional
    public Map<String,String> source(@Valid @RequestBody Source r) {
        SafeUrls.parse(r.url());
        if (!Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM venues WHERE id=?)",Boolean.class,r.venueId()))) throw new IllegalArgumentException("Location fehlt");
        String website=store.jdbc().queryForObject("SELECT website_url FROM venues WHERE id=?",String.class,r.venueId());
        boolean official=website!=null && !website.isBlank() && SafeUrls.parse(website).getHost().equalsIgnoreCase(SafeUrls.parse(r.url()).getHost());
        for (String selector : List.of(Objects.toString(r.itemSelector(),""),Objects.toString(r.titleSelector(),""),Objects.toString(r.dateSelector(),"")))
            if (!selector.isBlank()) org.jsoup.Jsoup.parse("").select(selector);
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        String id=store.source(r.venueId(),r.url(),official ? "OFFICIAL" : "EXTERNAL",r.approved());
        store.jdbc().update("""
            UPDATE discovery_sources SET approved=?,is_seed=true,item_selector=?,title_selector=?,date_selector=?,
             source_kind=COALESCE(?,source_kind),enabled=COALESCE(?,enabled),priority=COALESCE(?,priority) WHERE id=?
            """,r.approved(),Objects.toString(r.itemSelector(),""),Objects.toString(r.titleSelector(),""),Objects.toString(r.dateSelector(),""),
            r.sourceKind(),r.enabled(),r.priority(),id);
        return Map.of("id",id);
    }
    @GetMapping("/parties/{id}/aliases") public List<String> aliases(@PathVariable String id) {
        return store.jdbc().queryForList("SELECT alias FROM party_series_aliases WHERE party_id=? ORDER BY alias",String.class,id);
    }
    public record Aliases(@NotNull @Size(max=30) List<@NotBlank @Size(max=200) String> aliases) { }
    @PutMapping("/parties/{id}/aliases") @org.springframework.transaction.annotation.Transactional
    public void aliases(@PathVariable String id,@Valid @RequestBody Aliases r) {
        if (!Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM party_series WHERE id=?)",Boolean.class,id))) throw new IllegalArgumentException("Partyreihe fehlt");
        store.jdbc().update("DELETE FROM party_series_aliases WHERE party_id=?",id);
        for (String alias : new HashSet<>(r.aliases())) store.jdbc().update("INSERT INTO party_series_aliases(party_id,alias) VALUES (?,?)",id,alias.trim());
    }
}
