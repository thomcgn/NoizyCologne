package de.noizycologne.backend.discovery;

import de.noizycologne.backend.venue.*;
import de.noizycologne.backend.party.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;

@Service
@Profile({"local", "staging"})
@EnableScheduling
public class DiscoveryScheduler {
    private final DiscoveryStore store;
    private final VenueRepository venues;
    private final PartySeriesRepository parties;
    private final DiscoveryGenreFilter genres;
    private final VenueSourceDiscoveryService discovery;
    private final EventCandidateProcessor processor;
    private final DiscoveryWorkLock workLock;
    private final DiscoveryResetService resets;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r,"venue-discovery"); t.setDaemon(true); return t; });
    @Value("${noizy.discovery.enabled:true}") private boolean enabled;
    public DiscoveryScheduler(DiscoveryStore store, VenueRepository venues, PartySeriesRepository parties,
            DiscoveryGenreFilter genres, VenueSourceDiscoveryService discovery, EventCandidateProcessor processor,
            DiscoveryWorkLock workLock, DiscoveryResetService resets) {
        this.store=store; this.venues=venues; this.parties=parties; this.genres=genres;
        this.discovery=discovery; this.processor=processor; this.workLock=workLock; this.resets=resets;
    }
    @jakarta.annotation.PreDestroy void close() { worker.shutdownNow(); }
    @Scheduled(cron="${noizy.discovery.cron:0 0 9 * * TUE}", zone="${noizy.discovery.zone:Europe/Berlin}")
    public void scheduled() { if (enabled) start(); }
    public boolean start() { return enqueue(null)!=null; }
    public String startReset(DiscoveryResetService.Request request) {
        request.validate();
        var previous=store.jdbc().queryForList("SELECT request_hash FROM discovery_operations WHERE id=?",request.operationId().toString());
        if (!previous.isEmpty()) {
            if (!resets.hash(request).equals(previous.getFirst().get("request_hash")))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Vorgangs-ID bereits mit anderen Optionen verwendet");
            return request.operationId().toString();
        }
        return enqueue(request);
    }
    private String enqueue(DiscoveryResetService.Request request) {
        if (!busy.compareAndSet(false,true)) return null;
        DiscoveryWorkLock.Lease lease;
        try { lease=workLock.tryAcquire(); }
        catch (java.sql.SQLException e) { busy.set(false);throw new IllegalStateException("Discovery-Lock nicht verfügbar",e); }
        if (lease==null) { busy.set(false);return null; }
        String id=request==null ? UUID.randomUUID().toString() : request.operationId().toString();
        try {
            String hash=request==null ? EventNormalizer.hash(id) : resets.hash(request);
            int created=store.jdbc().update("INSERT INTO discovery_operations(id,kind,request_hash,request_payload,matcher_version,status) VALUES (?,?,?,?,?,'RUNNING') ON CONFLICT(id) DO NOTHING",
                id,request==null ? "SCAN" : request.mode().name(),hash,
                request==null ? "{}" : resets.serialize(request),EventMatcher.VERSION);
            if (created==0) {
                String existing=store.jdbc().queryForObject("SELECT request_hash FROM discovery_operations WHERE id=?",String.class,id);
                if (!hash.equals(existing)) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Vorgangs-ID bereits mit anderen Optionen verwendet");
                try { lease.close(); } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
                busy.set(false);return id;
            }
            worker.submit(() -> { try { execute(lease,id,request); } finally { busy.set(false); } });
            return id;
        } catch (RuntimeException e) {
            try { lease.close(); } catch (java.sql.SQLException suppressed) { e.addSuppressed(suppressed); }
            busy.set(false);throw e;
        }
    }
    /** Synchronous entry used by local fixture tests, with the same cross-process lock. */
    public void scan() {
        try {
            var lease=workLock.tryAcquire();
            if (lease==null) return;
            String id=UUID.randomUUID().toString();
            try {
                store.jdbc().update("INSERT INTO discovery_operations(id,kind,request_hash,matcher_version,status) VALUES (?,'SCAN',?,?,'RUNNING')",id,EventNormalizer.hash(id),EventMatcher.VERSION);
            } catch (RuntimeException e) {
                try { lease.close(); } catch (java.sql.SQLException suppressed) { e.addSuppressed(suppressed); }
                throw e;
            }
            execute(lease,id,null);
        } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
    }
    private void execute(DiscoveryWorkLock.Lease lease,String id,DiscoveryResetService.Request request) {
        boolean scan=request==null || request.mode()==DiscoveryResetService.Mode.RESCAN;
        try (lease) {
            store.jdbc().update("UPDATE discovery_operations SET status='INTERRUPTED',finished_at=now(),report='Vorheriger Vorgang unterbrochen' WHERE status='RUNNING' AND id<>?",id);
            store.jdbc().update("UPDATE discovery_runs SET status='INTERRUPTED',finished_at=now() WHERE status='RUNNING'");
            try {
                var initial=request==null ? new DiscoveryResetService.Summary(0,0,0,0,0,0) : resets.apply(request);
                store.jdbc().update("UPDATE discovery_operations SET summary=? WHERE id=?",resets.serialize(initial),id);
                int checked=0,failed=0;
                if (scan) {
                    store.jdbc().update("INSERT INTO discovery_runs(id,status) VALUES (?,'RUNNING')",id);
                    for (var venue:venues.findAll()) {
                        if (!venue.isDiscoveryEnabled()) continue;
                        checked++;
                        try { if (!scanVenue(venue,id)) failed++; }
                        catch (Exception e) {
                            failed++;
                            String status=e instanceof EventPageFetcher.FetchFailure f ? f.status : "SCAN_FAILED";
                            store.jdbc().update("UPDATE venues SET discovery_status=? WHERE id=?",status,venue.getId());
                            LoggerFactory.getLogger(getClass()).warn("Discovery location {} failed",venue.getId(),e);
                        }
                    }
                }
                var summary=store.jdbc().queryForMap("""
                    SELECT count(DISTINCT candidate_id) AS evaluated,
                    count(DISTINCT candidate_id) FILTER (WHERE matching_class='POSSIBLE_MATCH' AND old_matching_class IS DISTINCT FROM matching_class AND new_status NOT IN ('REJECTED','DUPLICATE','IGNORED','IMPORTED')) AS "newPossibleMatches",
                    count(DISTINCT candidate_id) FILTER (WHERE old_status IS DISTINCT FROM new_status OR old_matching_class IS DISTINCT FROM matching_class) AS reclassified
                    FROM discovery_decisions WHERE operation_id=?
                    """,id);
                summary.put("stillIgnored",store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE last_evaluation_id=? AND status='IGNORED'",Long.class,id));
                summary.put("preservedManual",initial.preservedManual());summary.put("failed",failed);
                String report="Locations geprüft: "+checked+"; fehlgeschlagen: "+failed;
                store.jdbc().update("UPDATE discovery_operations SET status=?,finished_at=now(),report=?,summary=? WHERE id=?",
                    failed==0 ? "COMPLETED" : "PARTIAL",report,resets.serialize(summary),id);
                if (scan) store.jdbc().update("UPDATE discovery_runs SET status=?,finished_at=now(),report=? WHERE id=?",failed==0 ? "COMPLETED" : "PARTIAL",report,id);
            } catch (Exception e) {
                String report=e instanceof org.springframework.web.server.ResponseStatusException r ? r.getReason() : "Vorgang abgebrochen; bereits protokollierte Teilentscheidungen bleiben erhalten";
                store.jdbc().update("UPDATE discovery_operations SET status='FAILED',finished_at=now(),report=? WHERE id=?",report,id);
                if (scan) store.jdbc().update("UPDATE discovery_runs SET status='FAILED',finished_at=now(),report=? WHERE id=?",report,id);
                LoggerFactory.getLogger(getClass()).error("Discovery operation {} failed",id,e);
            }
        } catch (java.sql.SQLException e) { LoggerFactory.getLogger(getClass()).error("Discovery lock release failed",e); }
    }
    private boolean scanVenue(Venue venue,String operation) throws java.io.IOException {
        LinkedHashMap<String,EventExtractor.Profile> roots = new LinkedHashMap<>();
        if (!venue.getEventsUrl().isBlank()) roots.put(venue.getEventsUrl(),EventExtractor.Profile.defaults());
        if (!venue.getWebsiteUrl().isBlank()) roots.put(venue.getWebsiteUrl(),EventExtractor.Profile.defaults());
        for (var s : store.jdbc().queryForList("SELECT * FROM discovery_sources WHERE venue_id=? AND is_seed=true ORDER BY url LIMIT 3",venue.getId()))
            roots.put((String)s.get("url"),new EventExtractor.Profile((String)s.get("item_selector"),(String)s.get("title_selector"),(String)s.get("date_selector")));
        var knownParties=parties.findAll(); var aliases=store.aliases();
        var knownGenres=List.copyOf(genres.terms());
        record Pending(String sourceId, EventExtractor.Event event, boolean approved) { }
        List<Pending> pending=new ArrayList<>();
        List<String> statuses=new ArrayList<>(); int count=0;
        for (var root : roots.entrySet().stream().limit(3).toList()) {
            String rootId=store.source(venue.getId(),root.getKey(),official(venue,root.getKey()) ? "OFFICIAL" : "EXTERNAL",official(venue,root.getKey()));
            try {
                for (var found : discovery.discover(root.getKey(),root.getValue())) {
                    String sourceId=store.source(venue.getId(),found.url().toString(),official(venue,found.url().toString()) ? "OFFICIAL" : "EXTERNAL",official(venue,found.url().toString()));
                    store.jdbc().update("UPDATE discovery_sources SET status=?,last_fetched_at=now() WHERE id=?", found.extraction().status(),sourceId);
                    statuses.add(found.extraction().status());
                    boolean approved=Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT approved FROM discovery_sources WHERE id=?",Boolean.class,sourceId));
                    for (var event : found.extraction().events()) { if (++count>300) break; pending.add(new Pending(sourceId,event,approved)); }
                }
            } catch (java.io.IOException e) {
                String status=e instanceof EventPageFetcher.FetchFailure f ? f.status : "SCAN_FAILED";
                statuses.add(status); store.jdbc().update("UPDATE discovery_sources SET status=?,last_fetched_at=now() WHERE id=?",status,rootId);
            }
        }
        // Compare the complete bounded batch before importing anything. A contradictory second source
        // must not arrive after an already committed automatic import from the first source.
        Map<String,List<String>> startsByTitle=new HashMap<>();
        Map<String,List<String>> statesByTitle=new HashMap<>();
        for (var item:pending) {
            var e=item.event();
            String state=e.state();
            if (!e.location().isBlank() && !EventNormalizer.text(e.location()).contains(EventNormalizer.text(venue.getName())))
                state+=" UNCONFIRMED Abweichende Location";
            statesByTitle.computeIfAbsent(EventNormalizer.text(e.title()),k -> new ArrayList<>()).add(state);
        }
        for (var item:pending) startsByTitle.computeIfAbsent(EventNormalizer.text(item.event().title()),k -> new ArrayList<>()).addAll(item.event().starts());
        for (var item:pending) {
            var event=item.event();
            var starts=startsByTitle.get(EventNormalizer.text(event.title())).stream().distinct().toList();
            boolean conflictingDates=starts.stream().map(EventNormalizer::date).filter(Objects::nonNull).distinct().count()>1;
            String states=String.join(" ",statesByTitle.get(EventNormalizer.text(event.title())));
            event=new EventExtractor.Event(event.identity(),event.title(),event.url(),conflictingDates ? starts : event.starts(),event.evidence(),states,event.location());
            processor.process(venue,item.sourceId(),event,knownParties,aliases,knownGenres,item.approved(),operation);
        }
        var outcomes=statuses.stream().filter(s -> !List.of("SOURCE_LINKS_FOUND","EXCLUDED_CONCERTS").contains(s)).toList();
        String status=outcomes.contains("EVENTS_FOUND") ? "EVENTS_FOUND"
                : !outcomes.isEmpty() && outcomes.stream().allMatch(s -> s.equals("NO_EVENTS")) ? "NO_EVENTS"
                : outcomes.isEmpty() ? (statuses.contains("EXCLUDED_CONCERTS") ? "EXCLUDED_CONCERTS" : "UNINTERPRETABLE") : outcomes.get(0).equals("NO_EVENTS") ? "UNINTERPRETABLE" : outcomes.get(0);
        if (!statuses.contains("EVENTS_FOUND") && statuses.contains("JAVASCRIPT_REQUIRED")) status="JAVASCRIPT_REQUIRED";
        else if (!statuses.contains("EVENTS_FOUND") && status.equals("SOURCE_LINKS_FOUND")) status="UNINTERPRETABLE";
        if (statuses.stream().anyMatch(s -> s.equals("SCAN_FAILED") || s.contains("BLOCKED"))) status="PARTIAL_FAILURE";
        store.jdbc().update("UPDATE venues SET discovery_status=?,last_discovery_at=CASE WHEN ? IN ('EVENTS_FOUND','NO_EVENTS','EXCLUDED_CONCERTS') THEN now() ELSE last_discovery_at END WHERE id=?",status,status,venue.getId());
        return List.of("EVENTS_FOUND","NO_EVENTS","EXCLUDED_CONCERTS").contains(status);
    }
    private boolean official(Venue venue,String url) {
        return !venue.getWebsiteUrl().isBlank() && SafeUrls.parse(venue.getWebsiteUrl()).getHost().equalsIgnoreCase(SafeUrls.parse(url).getHost());
    }
}
