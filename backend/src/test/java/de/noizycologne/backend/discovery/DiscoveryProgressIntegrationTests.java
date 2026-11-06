package de.noizycologne.backend.discovery;

import de.noizycologne.backend.venue.*;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"noizy.discovery.enabled=false","noizy.discovery.monitor.enabled=false","logging.level.root=WARN"})
@ActiveProfiles("local")
class DiscoveryProgressIntegrationTests {
    @Autowired DiscoveryStore store;
    @Autowired DiscoveryScheduler scheduler;
    @Autowired DiscoveryController controller;
    @Autowired DiscoveryResetService resets;
    @Autowired SpecialSourceService sources;
    @Autowired VenueRepository venues;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @MockitoBean VenueSourceDiscoveryService discovery;
    final List<String> sourceIds=new ArrayList<>(),venueIds=new ArrayList<>();
    List<String> enabledSources=List.of(),enabledVenues=List.of();
    final CountDownLatch release=new CountDownLatch(1);
    final String a="https://a-progress.example/",b="https://b-progress.example/",location="https://venue-progress.example/";
    @BeforeEach void setup() throws Exception {
        enabledSources=store.jdbc().queryForList("SELECT id FROM discovery_sources WHERE enabled",String.class);
        enabledVenues=store.jdbc().queryForList("SELECT id FROM venues WHERE discovery_enabled",String.class);
        store.jdbc().update("UPDATE discovery_sources SET enabled=false");
        store.jdbc().update("UPDATE venues SET discovery_enabled=false");
        when(discovery.discoverSpecial(anyString(),any())).thenAnswer(call -> empty(call.getArgument(0,String.class)));
        when(discovery.discover(anyString(),any())).thenAnswer(call -> empty(call.getArgument(0,String.class)));
    }
    private List<VenueSourceDiscoveryService.@NonNull Found> empty(String url) {
        return List.of(new VenueSourceDiscoveryService.Found(URI.create(url),new EventExtractor.Extraction(List.of(),"NO_EVENTS",true)));
    }
    private String source(String name,String url,boolean enabled) {
        String id=sources.add(name,url,"","","");sourceIds.add(id);
        store.jdbc().update("UPDATE discovery_sources SET enabled=? WHERE id=?",enabled,id);return id;
    }
    private Venue venue(String name,String url,boolean enabled) {
        var v=new Venue(name,"");v.configureDiscovery(url,"",enabled);v=venues.saveAndFlush(v);venueIds.add(v.getId());return v;
    }
    private Map<String,Object> latest() {return controller.operation(requireNonNull(store.jdbc().queryForObject("SELECT id FROM discovery_operations ORDER BY started_at DESC LIMIT 1",String.class)));}
    private Map<String,Object> finished(String id) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        Map<String,Object> row;
        do {row=controller.operation(id);if (!"RUNNING".equals(row.get("status"))) return row;Thread.sleep(10);} while(System.nanoTime()<deadline);
        throw new AssertionError("Discovery did not finish");
    }
    @AfterEach void cleanup() throws Exception {
        release.countDown();
        for (var row:store.jdbc().queryForList("SELECT id FROM discovery_operations WHERE status='RUNNING'")) finished(Objects.toString(row.get("id")));
        for (String id:venueIds) venues.deleteById(requireNonNull(id));
        for (String id:sourceIds) store.jdbc().update("DELETE FROM discovery_sources WHERE id=?",id);
        for (String id:enabledSources) store.jdbc().update("UPDATE discovery_sources SET enabled=true WHERE id=?",id);
        for (String id:enabledVenues) store.jdbc().update("UPDATE venues SET discovery_enabled=true WHERE id=?",id);
    }
    @Test void progressIsCommittedDuringFetchCountsFailuresAndSurvivesApiReload() throws Exception {
        source("A Fehler",a,true);source("B Wartet",b,true);source("C Aus", "https://disabled-progress.example/",false);
        venue("Aktive Location",location,true);venue("Inaktive Location","https://disabled-venue.example/",false);
        var entered=new CountDownLatch(1);
        when(discovery.discoverSpecial(eq(a),any())).thenThrow(new java.io.IOException("Fixture fetch failure"));
        when(discovery.discoverSpecial(eq(b),any())).thenAnswer(call -> {entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("Fixture release missing");return empty(b);});
        assertThat(scheduler.start()).isTrue();
        try {
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var current=latest();String id=Objects.toString(current.get("id"));
            assertThat(current).containsEntry("status","RUNNING").containsEntry("totalUnits",3).containsEntry("completedUnits",1).containsEntry("failedUnits",1).containsEntry("progressPercent",33).containsEntry("currentLabel","B Wartet").containsEntry("phase","SPECIAL_SOURCES");
            assertThat(current.get("updatedAt")).isNotNull();
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(requireNonNull(context)).build();
            for(int reload=0;reload<2;reload++) mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/discovery/operations/"+id))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.progressPercent").value(33))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.completedUnits").value(1));
            release.countDown();
            assertThat(finished(id)).containsEntry("status","PARTIAL").containsEntry("progressPercent",100).containsEntry("completedUnits",3).containsEntry("failedUnits",1);
            verify(discovery,never()).discoverSpecial(eq("https://disabled-progress.example/"),any());
            verify(discovery,never()).discover(eq("https://disabled-venue.example/"),any());
        } finally {release.countDown();}
    }
    @Test void interruptionKeepsOnlyCompletedUnitsAndNeverBecomesOneHundredPercent() throws Exception {
        source("A Fertig",a,true);source("B Abbruch",b,true);venue("Nicht bearbeitet",location,true);
        when(discovery.discoverSpecial(eq(b),any())).thenThrow(new CancellationException("Fixture interruption"));
        scheduler.scan();
        assertThat(latest()).containsEntry("status","INTERRUPTED").containsEntry("phase","INTERRUPTED").containsEntry("completedUnits",1).containsEntry("failedUnits",0).containsEntry("progressPercent",33);
        verify(discovery,never()).discover(eq(location),any());
    }
    @Test void emptyScanFinishesAndStaleOperationRetainsItsLastRealProgress() {
        String previous=UUID.randomUUID().toString();
        store.jdbc().update("INSERT INTO discovery_operations(id,kind,request_hash,matcher_version,status,total_units,completed_units,failed_units,phase,progress_updated_at) VALUES (?,'SCAN','fixture','fixture','RUNNING',3,1,1,'LOCATIONS',now())",previous);
        scheduler.scan();
        assertThat(latest()).containsEntry("status","COMPLETED").containsEntry("totalUnits",0).containsEntry("completedUnits",0).containsEntry("progressPercent",100);
        assertThat(controller.operation(previous)).containsEntry("status","INTERRUPTED").containsEntry("phase","INTERRUPTED").containsEntry("progressPercent",33).containsEntry("failedUnits",1);
    }
    @Test void monitoringCountsOnlyEnabledVenuesWithRelevantUpcomingCandidates() throws Exception {
        var relevant=venue("Relevant",location,true);venue("Keine Termine","https://no-upcoming.example/",true);
        String source=store.source(relevant.getId(),location,"OFFICIAL",true);
        store.jdbc().update("INSERT INTO discovery_candidates(id,venue_id,source_id,identity_key,fingerprint,title,source_url,event_date,evidence,status,reason) VALUES (?,?,?,'fixture','fixture','Fixture',?,(now() AT TIME ZONE 'Europe/Berlin')::date,'','NEEDS_REVIEW','Fixture')",UUID.randomUUID().toString(),relevant.getId(),source,location);
        assertThat(scheduler.startChanges()).isTrue();
        String id=Objects.toString(latest().get("id"));
        assertThat(finished(id)).containsEntry("kind","MONITOR").containsEntry("totalUnits",1).containsEntry("completedUnits",1).containsEntry("progressPercent",100);
        verify(discovery,never()).discover(eq("https://no-upcoming.example/"),any());
    }
    @Test void reevaluationDoesNotPretendToBeARegularScan() throws Exception {
        var preview=resets.preview(new DiscoveryResetService.Selection(DiscoveryResetService.Mode.ALL,false));
        String id=scheduler.startReset(new DiscoveryResetService.Request(DiscoveryResetService.Mode.ALL,false,"",preview.token(),UUID.randomUUID()));
        assertThat(finished(id)).containsEntry("kind","ALL").containsEntry("totalUnits",null).containsEntry("progressPercent",null);
        verifyNoInteractions(discovery);
    }
}
