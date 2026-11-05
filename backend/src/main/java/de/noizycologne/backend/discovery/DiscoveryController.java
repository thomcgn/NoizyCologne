package de.noizycologne.backend.discovery;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.time.LocalDate;

@RestController
@Profile({"local","staging"})
@RequestMapping("/api/v1/discovery")
public class DiscoveryController {
    private final DiscoveryStore store;
    private final DiscoveryScheduler scheduler;
    private final VenueSourceDiscoveryService discovery;
    private final EventImportService importer;
    private final DiscoveryResetService resets;
    public DiscoveryController(DiscoveryStore store,DiscoveryScheduler scheduler,VenueSourceDiscoveryService discovery,EventImportService importer,DiscoveryResetService resets) {
        this.store=store; this.scheduler=scheduler; this.discovery=discovery; this.importer=importer; this.resets=resets;
    }
    @GetMapping public Map<String,Object> overview(@RequestParam(defaultValue="ALL") String filter) {
        return Map.of("candidates",store.candidates(filter),"sources",store.sources(),
                "counts",store.jdbc().queryForList("SELECT status,count(*) AS count FROM discovery_candidates GROUP BY status"),
                "automaticallyImported",store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE status='IMPORTED' AND reason='Automatisch übernommen'",Long.class),
                "metrics",store.jdbc().queryForMap("""
                    SELECT count(*) FILTER (WHERE decision_origin='AUTOMATIC' AND status='IGNORED') AS "automaticallyIgnored",
                    count(*) FILTER (WHERE decision_origin='MANUAL' AND status='REJECTED') AS "manuallyRejected",
                    count(*) FILTER (WHERE status IN ('NEEDS_REVIEW','NEEDS_INQUIRY','READY')) AS "needsReview",
                    count(*) FILTER (WHERE EXISTS (SELECT 1 FROM discovery_decisions d JOIN discovery_operations o ON o.id=d.operation_id WHERE d.candidate_id=discovery_candidates.id AND o.kind<>'SCAN' AND d.old_status IS NOT NULL)) AS reevaluated,
                    count(*) FILTER (WHERE matching_class='UNVERIFIED' AND status NOT IN ('IMPORTED','REJECTED','DUPLICATE','IGNORED')) AS unverified,
                    count(*) FILTER (WHERE matching_class='POSSIBLE_MATCH' AND status NOT IN ('IMPORTED','REJECTED','DUPLICATE','IGNORED')) AS possible,
                    count(*) FILTER (WHERE inquiry_status IN ('PREPARED','SENT')) AS open,
                    count(*) FILTER (WHERE status IN ('REJECTED','DUPLICATE','IGNORED')) AS archive FROM discovery_candidates
                    """),
                "operations",store.jdbc().queryForList("SELECT * FROM discovery_operations ORDER BY started_at DESC LIMIT 10").stream().map(DiscoveryStore::forApi).toList(),
                "diagnoses",store.jdbc().queryForList("SELECT d.*,c.title,v.name AS venue_name FROM discovery_decisions d JOIN discovery_candidates c ON c.id=d.candidate_id JOIN venues v ON v.id=c.venue_id ORDER BY d.id DESC LIMIT 500").stream().map(DiscoveryStore::forApi).toList(),
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
        return DiscoveryStore.forApi(rows.getFirst());
    }
    public record Probe(@NotBlank @Size(max=2048) String url) { }
    private final java.util.concurrent.Semaphore probeLock=new java.util.concurrent.Semaphore(1);
    @PostMapping("/probe") public List<VenueSourceDiscoveryService.Found> probe(@Valid @RequestBody Probe request) throws java.io.IOException {
        if (!probeLock.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Quellenprüfung läuft bereits");
        try { return discovery.discover(request.url(),EventExtractor.Profile.defaults()); }
        catch (java.io.IOException e) {
            String status=e instanceof EventPageFetcher.FetchFailure f ? f.status : "SCAN_FAILED";
            return List.of(new VenueSourceDiscoveryService.Found(SafeUrls.parse(request.url()),new EventExtractor.Extraction(List.of(),status)));
        }
        finally { probeLock.release(); }
    }
    public record Review(@Size(max=100) String partyId, LocalDate date, @NotBlank String status,
                         @NotBlank String inquiryStatus, @Size(max=12000) String dmText) { }
    @PutMapping("/candidates/{id}") public void review(@PathVariable String id,@Valid @RequestBody Review r) {
        importer.review(id,r.partyId(),r.date(),r.status(),r.inquiryStatus(),Objects.toString(r.dmText(),""));
    }
    public record Import(@NotBlank String partyId,@NotNull LocalDate date) { }
    @PostMapping("/candidates/{id}/import") public void importEvent(@PathVariable String id,@Valid @RequestBody Import r) {
        importer.importCandidate(id,r.partyId(),r.date(),true);
    }
    @GetMapping("/candidates/{id}/dm") public Map<String,String> dm(@PathVariable String id) { return Map.of("text",importer.dm(id)); }
    public record Source(@NotBlank String venueId,@NotBlank @Size(max=2048) String url,boolean approved,
                         @Size(max=300) String itemSelector,@Size(max=300) String titleSelector,@Size(max=300) String dateSelector) { }
    @PostMapping("/sources") public Map<String,String> source(@Valid @RequestBody Source r) {
        SafeUrls.parse(r.url());
        if (!Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM venues WHERE id=?)",Boolean.class,r.venueId()))) throw new IllegalArgumentException("Location fehlt");
        String website=store.jdbc().queryForObject("SELECT website_url FROM venues WHERE id=?",String.class,r.venueId());
        boolean official=website!=null && !website.isBlank() && SafeUrls.parse(website).getHost().equalsIgnoreCase(SafeUrls.parse(r.url()).getHost());
        String id=store.source(r.venueId(),r.url(),official ? "OFFICIAL" : "EXTERNAL",r.approved());
        for (String selector : List.of(Objects.toString(r.itemSelector(),""),Objects.toString(r.titleSelector(),""),Objects.toString(r.dateSelector(),"")))
            if (!selector.isBlank()) org.jsoup.Jsoup.parse("").select(selector);
        store.jdbc().update("UPDATE discovery_sources SET approved=?,is_seed=true,item_selector=?,title_selector=?,date_selector=? WHERE id=?",r.approved(),Objects.toString(r.itemSelector(),""),Objects.toString(r.titleSelector(),""),Objects.toString(r.dateSelector(),""),id);
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
