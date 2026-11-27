package de.noizycologne.backend.discovery.external;

import de.noizycologne.backend.discovery.DiscoveryProgress;
import de.noizycologne.backend.discovery.DiscoveryStore;
import de.noizycologne.backend.discovery.EventCandidateProcessor;
import de.noizycologne.backend.discovery.EventChangeMonitor;
import de.noizycologne.backend.discovery.EventExtractor;
import de.noizycologne.backend.discovery.EventPageFetcher;
import de.noizycologne.backend.discovery.EventPrivacy;
import de.noizycologne.backend.discovery.SafeUrls;
import de.noizycologne.backend.discovery.VenueSourceDiscoveryService;
import de.noizycologne.backend.venue.VenueRepository;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fail-closed fallback for explicitly approved, public ICS/JSON feeds. Nothing is
 * fetched unless the operator enables the feature and marks the row approved,
 * enabled and active. The existing special-source path keeps deduplication,
 * geography, keyword filtering and editorial review unchanged; imported events
 * never inherit automatic approval from this source kind.
 */
@Service
public class ExternalCalendarSourceService {
    private final DiscoveryStore store;
    private final EventPageFetcher fetcher;
    private final EventCandidateProcessor processor;
    private final EventChangeMonitor monitor;
    private final VenueRepository venues;
    private final DiscoveryProgress progress;
    private final boolean enabled;

    public record ExternalSource(String id, String name, String url) { }
    public record ScanResult(int checked, int skipped, int failed) { }

    public ExternalCalendarSourceService(DiscoveryStore store,EventPageFetcher fetcher,
            EventCandidateProcessor processor,EventChangeMonitor monitor,VenueRepository venues,DiscoveryProgress progress,
            @Value("${noizy.discovery.external-calendar.enabled:false}") boolean enabled) {
        this.store=store;this.fetcher=fetcher;this.processor=processor;this.monitor=monitor;
        this.venues=venues;this.progress=progress;this.enabled=enabled;
    }

    public boolean enabled() { return enabled; }

    /** Planning snapshot; approval, activation and a safe URL are rechecked in {@link #scan}. */
    public List<@NonNull ExternalSource> list() {
        if (!enabled) return List.of();
        return store.jdbc().queryForList("""
            SELECT id,name,url FROM discovery_sources
            WHERE source_kind='EXTERNAL_CALENDAR' AND venue_id IS NULL AND deleted_at IS NULL
              AND enabled=true AND approved=true AND external_format IN ('ICS','JSON')
            ORDER BY priority,url
            """).stream().map(row -> new ExternalSource(
                Objects.requireNonNull((String)row.get("id"),"Kalenderquellen-ID fehlt"),
                Objects.toString(row.get("name"),""),
                Objects.toString(row.get("url"),""))).toList();
    }

    public ScanResult scan(String operation,List<@NonNull ExternalSource> sources,boolean trackProgress) {
        if (!enabled) return new ScanResult(0,0,0);
        int checked=0,skipped=0,failed=0;
        for (var source:sources) {
            checkInterrupted();
            checked++;
            if (trackProgress) progress.current(operation,"EXTERNAL_CALENDARS",source.name().isBlank() ? source.url() : source.name());
            var rows=store.jdbc().queryForList("""
                SELECT enabled,approved,deleted_at,source_kind,external_format,url FROM discovery_sources WHERE id=?
                """,source.id());
            if (rows.isEmpty()) { skipped++; continue; }
            var row=rows.getFirst();
            if (!Boolean.TRUE.equals(row.get("enabled")) || !Boolean.TRUE.equals(row.get("approved"))
                    || row.get("deleted_at")!=null || !"EXTERNAL_CALENDAR".equals(row.get("source_kind"))) { skipped++; continue; }
            URI uri;
            try { uri=SafeUrls.parse(Objects.toString(row.get("url"),"")); }
            catch (IllegalArgumentException e) {
                skipped++;recordGateFailure(source.id(),"UNSAFE_URL","Nur öffentliche HTTPS-URLs sind erlaubt");continue;
            }
            String format=Objects.toString(row.get("external_format"),"");
            if (!List.of("ICS","JSON").contains(format)) {
                skipped++;recordGateFailure(source.id(),"UNSUPPORTED_FORMAT","Kalenderformat fehlt");continue;
            }
            int previousFailures=failed;
            try {
                Map<String,String> robots=new HashMap<>();
                if (!fetcher.robotsAllowed(uri,robots))
                    throw new EventPageFetcher.FetchFailure("ROBOTS_BLOCKED","robots.txt untersagt den Abruf von "+uri);
                // GET only. The shared fetcher validates DNS via SafeUrls, disables redirects,
                // proxies, retries and cookies, and bounds size and response time.
                var page=fetcher.fetch(uri);
                if (!contentType(format,page.contentType()))
                    throw new EventPageFetcher.FetchFailure("UNSUPPORTED_CONTENT","Content-Type „"+page.contentType()+"“ passt nicht zu "+format);
                String feedFormat=Objects.requireNonNull(format);
                URI feedUrl=Objects.requireNonNull(uri);
                var parsed=ExternalCalendarParser.parse(feedFormat,page.body(),feedUrl);
                List<EventExtractor.@NonNull Event> events=parsed.extraction().events().stream()
                    .limit(ExternalCalendarParser.MAX_EVENTS).toList();
                List<@NonNull String> diagnostics=new ArrayList<>(parsed.diagnostics());
                for (var venue:venues.findAll()) {
                    var keys=new HashSet<>(store.jdbc().queryForList(
                        "SELECT identity_key FROM discovery_candidates WHERE source_id=? AND venue_id=?",String.class,source.id(),venue.getId()));
                    var observations=events.stream()
                        .filter(event -> keys.contains(EventChangeMonitor.identity(EventPrivacy.safe(event))))
                        .map(event -> new EventChangeMonitor.Observation(source.id(),event,false)).toList();
                    if (!observations.isEmpty()) monitor.batch(venue,observations,operation);
                }
                // A single approved document has no cross-page contradiction to merge;
                // the shared processor still owns identity, dedupe and review decisions.
                for (var event:events) processor.processSpecial(source.id(),event,operation);
                store.recordFilterDiagnostics(source.id());
                boolean complete=parsed.extraction().complete();
                String status=Objects.requireNonNull(parsed.extraction().status());
                boolean clean=List.of("EVENTS_FOUND","NO_EVENTS","EXCLUDED_CONCERTS").contains(status) && complete;
                if (events.size()>=ExternalCalendarParser.MAX_EVENTS) {
                    status="PARTIAL_FAILURE";complete=false;diagnostics.add("EVENT_LIMIT");
                }
                store.fetched(source.id(),new VenueSourceDiscoveryService.Found(uri,
                    new EventExtractor.Extraction(List.copyOf(events),status,complete),"HTTP",List.copyOf(diagnostics)));
                if (clean) recordSuccess(source.id()); else recordFailure(source.id(),status);
                if (!clean) failed++;
            } catch (IOException | RuntimeException e) {
                if (e instanceof java.util.concurrent.CancellationException cancelled) throw cancelled;
                checkInterrupted();
                failed++;
                String status=e instanceof EventPageFetcher.FetchFailure f ? f.status : "SCAN_FAILED";
                String detail=Objects.requireNonNull(Objects.toString(e.getMessage(),status));
                store.fetched(source.id(),new VenueSourceDiscoveryService.Found(uri,
                    new EventExtractor.Extraction(List.of(),status),"HTTP",List.of(detail)));
                recordFailure(source.id(),status+": "+Objects.toString(e.getMessage(),""));
            }
            checkInterrupted();
            if (trackProgress) progress.complete(operation,failed>previousFailures);
        }
        return new ScanResult(checked,skipped,failed);
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
    }

    private static boolean contentType(String format,String value) {
        String type=Objects.toString(value,"").toLowerCase(java.util.Locale.ROOT);
        return "ICS".equals(format) ? type.contains("calendar") : type.contains("json");
    }

    private void recordGateFailure(String id,String status,String detail) {
        store.jdbc().update("UPDATE discovery_sources SET status=?,quality='BLOCKED',external_last_error=? WHERE id=?",
            status,clip(status+": "+detail),id);
    }

    private void recordSuccess(String id) {
        store.jdbc().update("UPDATE discovery_sources SET external_last_success_at=now(),external_last_error='' WHERE id=?",id);
    }

    private void recordFailure(String id,@NonNull String error) {
        store.jdbc().update("UPDATE discovery_sources SET external_last_error=? WHERE id=?",clip(error),id);
    }

    private static @NonNull String clip(@NonNull String value) {
        return value.length()<=2000 ? value : Objects.requireNonNull(value.substring(0,2000));
    }
}
