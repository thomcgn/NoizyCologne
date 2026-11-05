package de.noizycologne.backend.discovery;

import de.noizycologne.backend.venue.*;
import de.noizycologne.backend.party.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"noizy.discovery.enabled=false","logging.level.root=WARN"})
@ActiveProfiles("local")
class DiscoveryIntegrationTests {
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired DiscoveryStore store;
    @Autowired DiscoveryScheduler scheduler;
    @Autowired VenueRepository venues;
    @Autowired PartySeriesRepository parties;
    @Autowired EventImportService importer;
    @MockitoBean VenueSourceDiscoveryService discovery;
    Venue venue; PartySeries party;
    String root="https://new-club.example/";
    @BeforeEach void setup() {
        venue=new Venue("Unbekannter Club " + UUID.randomUUID(), ""); venue.configureDiscovery(root,"",true); venue=venues.saveAndFlush(venue);
        party=parties.saveAndFlush(new PartySeries("Indie Party " + UUID.randomUUID(),"","#E11D48","manual",null,null,null,null,Set.of(),Set.of()));
    }
    @AfterEach void cleanup() {
        store.jdbc().update("DELETE FROM discovery_candidates WHERE venue_id=? OR party_id=?",venue.getId(),party.getId());
        store.jdbc().update("DELETE FROM calendar_entries WHERE party_id=?",party.getId());
        store.jdbc().update("DELETE FROM calendar_entries WHERE location_id=?",venue.getId());
        venues.deleteById(venue.getId()); parties.deleteById(party.getId());
        store.jdbc().update("DELETE FROM discovery_runs");
    }
    EventExtractor.Event event(String identity,String title,String date,String state) {
        return new EventExtractor.Event(identity,title,root+"events/one",date==null ? List.of() : List.of(date),"Indie Party",state,"");
    }
    void found(EventExtractor.Event event) throws Exception {
        when(discovery.discover(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(List.of(event),"EVENTS_FOUND"))));
    }
    @Test void homepageOnlyImportsIdempotentlyAndChangesRequireReview() throws Exception {
        found(event("stable",party.getName(),"2027-01-09T23:00",""));
        scheduler.scan(); scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isEqualTo(1);
        var c=store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE venue_id=?",venue.getId());
        assertThat(c.get("status")).isEqualTo("IMPORTED"); assertThat(c.get("party_id")).isEqualTo(party.getId());
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM editorial_post_drafts WHERE calendar_entry_id=?",Integer.class,c.get("calendar_entry_id"))).isZero();
        found(event("stable",party.getName(),"2027-01-16T23:00","EventRescheduled")); scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT status FROM discovery_candidates WHERE id=?",String.class,c.get("id"))).isEqualTo("NEEDS_REVIEW");
        assertThat(store.jdbc().queryForObject("SELECT event_date FROM calendar_entries WHERE id=?",LocalDate.class,c.get("calendar_entry_id"))).isEqualTo(LocalDate.of(2027,1,9));
    }
    @Test void manualReviewDmAndImportWithoutInventedTime() throws Exception {
        found(event("new","Neue Indie Party",null,"UNCONFIRMED")); scheduler.scan();
        var c=store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE venue_id=?",venue.getId()); String id=(String)c.get("id");
        assertThat(importer.dm(id)).contains(venue.getName(),"Neue Indie Party","Könnt ihr uns kurz bestätigen");
        importer.review(id,party.getId(),LocalDate.of(2027,1,9),"NEEDS_REVIEW","ANSWERED","Manuell bestätigte Antwort");
        importer.importCandidate(id,party.getId(),LocalDate.of(2027,1,9),true);
        importer.importCandidate(id,party.getId(),LocalDate.of(2027,1,9),true);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isEqualTo(1);
        assertThat(store.jdbc().queryForMap("SELECT start_time FROM calendar_entries WHERE location_id=?",venue.getId()).get("start_time")).isNull();
    }
    @Test void existingCalendarConflictAndRejectedCandidates() throws Exception {
        found(event("one",party.getName(),"2027-01-09","")); scheduler.scan();
        found(event("two",party.getName(),"2027-01-09","")); scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isEqualTo(1);
        assertThat(store.jdbc().queryForList("SELECT status FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId())).contains("IMPORTED","DUPLICATE");
        found(event("unknown","Neue Indie Party","2027-01-10","")); scheduler.scan();
        String id=store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE title='Neue Indie Party' AND venue_id=?",String.class,venue.getId());
        importer.review(id,null,LocalDate.of(2027,1,10),"REJECTED","NONE","");
        scheduler.scan(); assertThat(store.candidate(id).get("status")).isEqualTo("REJECTED");
    }
    @Test void externalSourceRequiresExplicitApprovalAndRecheck() throws Exception {
        venue.configureDiscovery("",root,true); venues.saveAndFlush(venue);
        found(event("external",party.getName(),"2027-01-09","")); scheduler.scan();
        var c=store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE venue_id=?",venue.getId());
        assertThat(c.get("status")).isEqualTo("NEEDS_REVIEW");
        store.jdbc().update("UPDATE discovery_sources SET approved=true WHERE venue_id=?",venue.getId());
        scheduler.scan(); assertThat(store.candidate((String)c.get("id")).get("status")).isEqualTo("IMPORTED");
    }
    @Test void failedVenueDoesNotBlockOthersAndRunsArePersisted() throws Exception {
        when(discovery.discover(eq(root),any())).thenThrow(new EventPageFetcher.FetchFailure("BLOCKED"));
        var second=new Venue("Zweiter Club " + UUID.randomUUID(),""); second.configureDiscovery("https://other.example/","",true); second=venues.saveAndFlush(second);
        try {
            when(discovery.discover(eq("https://other.example/"),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create("https://other.example/"),new EventExtractor.Extraction(List.of(),"NO_EVENTS"))));
            scheduler.scan();
            assertThat(venues.findById(second.getId()).orElseThrow().getDiscoveryStatus()).isEqualTo("NO_EVENTS");
            assertThat(store.jdbc().queryForObject("SELECT status FROM discovery_runs ORDER BY started_at DESC LIMIT 1",String.class)).isEqualTo("PARTIAL");
        } finally { venues.deleteById(second.getId()); }
    }
    @Test void concurrentManualImportsCreateOnlyOneCalendarEntry() throws Exception {
        found(event("concurrent","Neue Indie Party","2027-01-09","")); scheduler.scan();
        String id=store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId());
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one=executor.submit(() -> importer.importCandidate(id,party.getId(),LocalDate.of(2027,1,9),true));
            var two=executor.submit(() -> importer.importCandidate(id,party.getId(),LocalDate.of(2027,1,9),true));
            one.get(10,java.util.concurrent.TimeUnit.SECONDS); two.get(10,java.util.concurrent.TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isEqualTo(1);
    }
    @Test void contradictoryBatchIsCheckedBeforeAnyAutomaticImport() throws Exception {
        when(discovery.discover(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(
                List.of(event("same-event",party.getName(),"2027-01-08",""),event("same-event",party.getName(),"2027-01-09","")),"EVENTS_FOUND"))));
        scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
        assertThat(store.jdbc().queryForObject("SELECT status FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId())).isEqualTo("NEEDS_INQUIRY");
        store.jdbc().update("DELETE FROM discovery_candidates WHERE venue_id=?",venue.getId());
        when(discovery.discover(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(
                List.of(event("same-event",party.getName(),"2027-01-09","EventCancelled"),event("same-event",party.getName(),"2027-01-09","EventScheduled")),"EVENTS_FOUND"))));
        scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
        assertThat(store.jdbc().queryForObject("SELECT status FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId())).isEqualTo("NEEDS_INQUIRY");
    }
    @Test void apiSerializesDatesAndValidatesSources() throws Exception {
        found(event("api",party.getName(),"2027-01-09","")); scheduler.scan();
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/discovery"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.candidates[0].event_date").value("2027-01-09"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/venues"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/discovery/sources")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{\"venueId\":\""+venue.getId()+"\",\"url\":\"https://127.0.0.1/\",\"approved\":true}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }
    @Test void navigationStatusDoesNotHideExplicitEmptyCalendar() throws Exception {
        when(discovery.discover(eq(root),any())).thenReturn(List.of(
            new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(List.of(),"SOURCE_LINKS_FOUND")),
            new VenueSourceDiscoveryService.Found(URI.create(root+"programm"),new EventExtractor.Extraction(List.of(),"NO_EVENTS"))));
        scheduler.scan();
        assertThat(venues.findById(venue.getId()).orElseThrow().getDiscoveryStatus()).isEqualTo("NO_EVENTS");
    }
    @Test void concertCannotBeImportedManuallyOrReintroducedAsAProposal() throws Exception {
        found(event("legacy","Neue Indie Party","2027-01-09","")); scheduler.scan();
        String id=store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId());
        store.jdbc().update("UPDATE discovery_candidates SET evidence='Indie Konzert mit DJs' WHERE id=?",id);
        importer.review(id,party.getId(),LocalDate.of(2027,1,9),"NEEDS_REVIEW","NONE","");
        assertThatThrownBy(() -> importer.importCandidate(id,party.getId(),LocalDate.of(2027,1,9),true))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("Konzerte");
        found(new EventExtractor.Event("legacy","Neue Indie Party",root+"events/one",List.of("2027-01-09"),"Indie Konzert mit DJs","",""));
        scheduler.scan(); assertThat(store.candidate(id).get("status")).isEqualTo("IGNORED");
        found(new EventExtractor.Event("new-concert",party.getName(),root+"events/two",List.of("2027-01-10"),"Indie Konzert mit DJs","",""));
        scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE venue_id=?",Integer.class,venue.getId())).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
    }
    @Test void postgresLockPreventsConcurrentScan() throws Exception {
        try(var conn=store.jdbc().getDataSource().getConnection(); var stmt=conn.createStatement()) {
            stmt.execute("SELECT pg_advisory_lock(73624601)");
            try { scheduler.scan(); verifyNoInteractions(discovery); }
            finally { stmt.execute("SELECT pg_advisory_unlock(73624601)"); }
        }
    }
}
