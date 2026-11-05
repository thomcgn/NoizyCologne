package de.noizycologne.backend.discovery;

import de.noizycologne.backend.venue.*;
import de.noizycologne.backend.party.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;
import java.sql.Connection;
import java.time.*;
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
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r,"venue-discovery"); t.setDaemon(true); return t; });
    @Value("${noizy.discovery.enabled:true}") private boolean enabled;
    public DiscoveryScheduler(DiscoveryStore store, VenueRepository venues, PartySeriesRepository parties,
            DiscoveryGenreFilter genres, VenueSourceDiscoveryService discovery, EventCandidateProcessor processor) {
        this.store=store; this.venues=venues; this.parties=parties; this.genres=genres;
        this.discovery=discovery; this.processor=processor;
    }
    @jakarta.annotation.PreDestroy void close() { worker.shutdownNow(); }
    @Scheduled(cron="${noizy.discovery.cron:0 0 9 * * TUE}", zone="${noizy.discovery.zone:Europe/Berlin}")
    public void scheduled() { if (enabled) start(); }
    public boolean start() {
        if (!busy.compareAndSet(false,true)) return false;
        worker.submit(() -> { try { scan(); } finally { busy.set(false); } }); return true;
    }
    public void scan() {
        String run = UUID.randomUUID().toString();
        // Session lock stays on this dedicated connection until the full scan finishes.
        try (Connection lock = Objects.requireNonNull(store.jdbc().getDataSource()).getConnection()) {
            try (var stmt = lock.createStatement(); var result = stmt.executeQuery("SELECT pg_try_advisory_lock(73624601)")) {
                result.next(); if (!result.getBoolean(1)) return;
            }
            try {
                store.jdbc().update("UPDATE discovery_runs SET status='INTERRUPTED',finished_at=now() WHERE status='RUNNING'");
                store.jdbc().update("INSERT INTO discovery_runs(id,status) VALUES (?,'RUNNING')",run);
                int checked=0, failed=0;
                for (var venue : venues.findAll()) {
                    if (!venue.isDiscoveryEnabled()) continue;
                    try { if (!scanVenue(venue)) failed++; checked++; }
                    catch (Exception e) {
                        failed++;
                        String status = e instanceof EventPageFetcher.FetchFailure f ? f.status : "SCAN_FAILED";
                        store.jdbc().update("UPDATE venues SET discovery_status=? WHERE id=?", status, venue.getId());
                    }
                }
                store.jdbc().update("UPDATE discovery_runs SET status=?,finished_at=now(),report=? WHERE id=?",
                        failed == 0 ? "COMPLETED" : "PARTIAL", "Locations geprüft: " + checked + "; fehlgeschlagen: " + failed, run);
            } catch (Exception e) {
                store.jdbc().update("UPDATE discovery_runs SET status='FAILED',finished_at=now(),report='Scan fehlgeschlagen' WHERE id=?", run);
                LoggerFactory.getLogger(getClass()).error("Discovery scan failed", e);
            } finally {
                try (var stmt=lock.createStatement()) { stmt.execute("SELECT pg_advisory_unlock(73624601)"); }
            }
        } catch (java.sql.SQLException e) { LoggerFactory.getLogger(getClass()).error("Discovery database lock unavailable",e); }
    }
    private boolean scanVenue(Venue venue) throws java.io.IOException {
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
            processor.process(venue,item.sourceId(),event,knownParties,aliases,knownGenres,item.approved());
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
