package de.noizycologne.backend.discovery;

import de.noizycologne.backend.venue.VenueRepository;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Global portals have their own source identity; no placeholder venue is created. */
@Service
public class SpecialSourceService {
    private final DiscoveryStore store;
    private final VenueSourceDiscoveryService discovery;
    private final EventCandidateProcessor processor;
    private final EventChangeMonitor monitor;
    private final VenueRepository venues;
    private final DiscoveryProgress progress;
    public SpecialSourceService(DiscoveryStore store,VenueSourceDiscoveryService discovery,EventCandidateProcessor processor,EventChangeMonitor monitor,VenueRepository venues,DiscoveryProgress progress) {
        this.store=store;this.discovery=discovery;this.processor=processor;this.monitor=monitor;this.venues=venues;this.progress=progress;
    }
    public List<Map<String,Object>> list() {
        // External ICS/JSON feeds have a separate, config-gated fetch path; the
        // website/browser crawler must never pick them up as HTML sources.
        return store.jdbc().queryForList("SELECT *,true AS special_source,'' AS venue_name FROM discovery_sources WHERE venue_id IS NULL AND deleted_at IS NULL AND source_kind<>'EXTERNAL_CALENDAR' ORDER BY name,url")
            .stream().map(DiscoveryStore::forApi).toList();
    }
    private static String url(String value) {
        return CanonicalSourceUrl.of(value);
    }
    @Transactional
    public String add(String name,String input,String item,String title,String date) {
        return add(name,input,item,title,date,false);
    }
    @Transactional
    public String add(String name,String input,String item,String title,String date,boolean approved) {
        String address=url(input);
        for (String selector:List.of(item,title,date)) if (!selector.isBlank()) org.jsoup.Jsoup.parse("").select(selector);
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        var previous=store.jdbc().queryForList("SELECT id,deleted_at FROM discovery_sources WHERE venue_id IS NULL AND url=?",address);
        if (!previous.isEmpty()) {
            var old=previous.getFirst();
            if (old.get("deleted_at")==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Diese Sonderquelle ist bereits vorhanden");
            String id=(String)old.get("id");
            store.jdbc().update("UPDATE discovery_sources SET name=?,deleted_at=NULL,enabled=true,approved=?,item_selector=?,title_selector=?,date_selector=? WHERE id=?",name.trim(),approved,item,title,date,id);
            return id;
        }
        String id=UUID.randomUUID().toString();
        store.jdbc().update("INSERT INTO discovery_sources(id,venue_id,name,url,source_type,source_kind,is_seed,approved,item_selector,title_selector,date_selector) VALUES (?,NULL,?,?,'EXTERNAL','PLATFORM',true,?,?,?,?)",id,name.trim(),address,approved,item,title,date);
        return id;
    }
    @Transactional
    public void update(String id,String name,String input,String item,String title,String date) {
        update(id,name,input,item,title,date,null);
    }
    @Transactional
    public void update(String id,String name,String input,String item,String title,String date,@org.jspecify.annotations.Nullable Boolean approved) {
        String address=url(input);
        for (String selector:List.of(item,title,date)) if (!selector.isBlank()) org.jsoup.Jsoup.parse("").select(selector);
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        var rows=store.jdbc().queryForList("SELECT * FROM discovery_sources WHERE id=? AND venue_id IS NULL AND deleted_at IS NULL",id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Sonderquelle nicht gefunden");
        if (Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT EXISTS(SELECT 1 FROM discovery_sources WHERE venue_id IS NULL AND url=? AND id<>?)",Boolean.class,address,id)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Diese Sonderquelle ist bereits vorhanden");
        var old=rows.getFirst();
        if ("EXTERNAL_CALENDAR".equals(old.get("source_kind")))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Sonderquelle nicht gefunden");
        boolean changed=!address.equals(old.get("url")) || !item.equals(old.get("item_selector")) || !title.equals(old.get("title_selector")) || !date.equals(old.get("date_selector"));
        store.jdbc().update("UPDATE discovery_sources SET name=?,url=?,item_selector=?,title_selector=?,date_selector=?,approved=COALESCE(?,approved) WHERE id=?",name.trim(),address,item,title,date,approved,id);
        if (changed) store.jdbc().update("UPDATE discovery_sources SET status='NOT_CHECKED',quality='NOT_CHECKED',event_count=NULL,last_fetched_at=NULL,last_success_at=NULL,fetch_diagnostics='',scan_complete=false WHERE id=?",id);
    }
    @Transactional
    public void delete(String id) {
        store.jdbc().execute("SELECT pg_advisory_xact_lock(73624602)");
        int changed=store.jdbc().update("UPDATE discovery_sources SET deleted_at=COALESCE(deleted_at,now()),enabled=false,approved=false WHERE id=? AND venue_id IS NULL",id);
        if (changed==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Sonderquelle nicht gefunden");
    }
    public record ScanResult(int checked,int failed) { }
    public ScanResult scan(String operation,List<Map<String,Object>> sources,boolean trackProgress) {
        int checked=0,failed=0;
        for (var source:sources) {
            DiscoveryProgress.checkInterrupted();
            int previousFailures=failed;
            if (trackProgress) progress.current(operation,"SPECIAL_SOURCES",Objects.toString(source.get("name"),"").isBlank() ? Objects.toString(source.get("url"),"") : Objects.toString(source.get("name"),""));
            checked++;String id=(String)source.get("id"),root=CanonicalSourceUrl.of((String)source.get("url"));
            try {
                var found=discovery.discoverSpecial(root,new EventExtractor.Profile((String)source.get("item_selector"),(String)source.get("title_selector"),(String)source.get("date_selector")));
                List<EventExtractor.@NonNull Event> events=new ArrayList<>();
                for (var page:found) for (var event:page.extraction().events()) if (events.size()<300) events.add(event);
                // Portals are not authoritative cancellation sources; compare only already assigned events.
                for (var venue:venues.findAll()) {
                    var keys=new HashSet<>(store.jdbc().queryForList("SELECT identity_key FROM discovery_candidates WHERE source_id=? AND venue_id=?",String.class,id,venue.getId()));
                    var observations=events.stream().filter(event -> keys.contains(EventChangeMonitor.identity(EventPrivacy.safe(event))))
                        .map(event -> new EventChangeMonitor.Observation(id,event,false)).toList();
                    if (!observations.isEmpty()) monitor.batch(venue,observations,operation);
                }
                var combined=DiscoveryBatchMerger.merge(events.stream().map(event -> new DiscoveryBatchMerger.Input(id,event,false,false)).toList(),"");
                for (var item:combined) processor.processSpecial(id,item.event(),operation);
                boolean complete=!found.isEmpty() && found.stream().allMatch(page -> page.extraction().complete());
                boolean clean=found.stream().anyMatch(page -> List.of("EVENTS_FOUND","NO_EVENTS","EXCLUDED_CONCERTS").contains(page.extraction().status())) && found.stream().allMatch(page -> List.of("EVENTS_FOUND","NO_EVENTS","SOURCE_LINKS_FOUND","EXCLUDED_CONCERTS").contains(page.extraction().status()));
                String status=!events.isEmpty() ? clean ? "EVENTS_FOUND" : "PARTIAL_FAILURE"
                    : clean && found.stream().anyMatch(page -> page.extraction().status().equals("NO_EVENTS")) ? "NO_EVENTS"
                    : found.isEmpty() ? "UNINTERPRETABLE" : Objects.requireNonNull(found.getLast()).extraction().status();
                if (status.equals("SOURCE_LINKS_FOUND")) status="UNINTERPRETABLE";
                List<@NonNull String> diagnostics=new ArrayList<>();
                for (var page:found) {diagnostics.addAll(page.diagnostics());if (!List.of("EVENTS_FOUND","NO_EVENTS","SOURCE_LINKS_FOUND").contains(page.extraction().status())) diagnostics.add(Objects.requireNonNull(page.extraction().status()));}
                if (found.stream().mapToInt(page -> page.extraction().events().size()).sum()>300) {complete=false;status="PARTIAL_FAILURE";diagnostics.add("EVENT_LIMIT");}
                store.fetched(id,new VenueSourceDiscoveryService.Found(SafeUrls.parse(root),new EventExtractor.Extraction(List.copyOf(events),status,complete),found.stream().anyMatch(page -> page.fetchMethod().equals("BROWSER")) ? "BROWSER" : "HTTP",diagnostics.stream().distinct().limit(20).toList()));
                store.recordFilterDiagnostics(id);
                if (!clean || status.equals("PARTIAL_FAILURE")) failed++;
            } catch (java.io.IOException | RuntimeException e) {
                if (e instanceof java.util.concurrent.CancellationException cancelled) throw cancelled;
                DiscoveryProgress.checkInterrupted();
                failed++;
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Discovery special source {} failed",id,e);
                String status=e instanceof EventPageFetcher.FetchFailure f ? f.status : "SCAN_FAILED";
                store.fetched(id,new VenueSourceDiscoveryService.Found(SafeUrls.parse(root),new EventExtractor.Extraction(List.of(),status),"HTTP",List.of(Objects.requireNonNull(status))));
            }
            DiscoveryProgress.checkInterrupted();
            if (trackProgress) progress.complete(operation,failed>previousFailures);
        }
        return new ScanResult(checked,failed);
    }
}
