package de.noizycologne.backend.discovery;

import de.noizycologne.backend.party.*;
import de.noizycologne.backend.venue.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"noizy.discovery.enabled=false","logging.level.root=WARN"})
@ActiveProfiles("local")
class DiscoveryResetIntegrationTests {
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired DiscoveryStore store;
    @Autowired DiscoveryResetService resets;
    @Autowired DiscoveryScheduler scheduler;
    @Autowired EventCandidateProcessor processor;
    @Autowired EventImportService importer;
    @Autowired VenueRepository venues;
    @Autowired PartySeriesRepository parties;
    @MockitoBean VenueSourceDiscoveryService discovery;
    Venue venue;PartySeries party;String source;
    List<String> operations=new ArrayList<>();
    @BeforeEach void setup() {
        venue=new Venue("Reset Club "+UUID.randomUUID(),"");venue.configureDiscovery("https://reset.example/","",true);venue=venues.saveAndFlush(venue);
        party=parties.saveAndFlush(new PartySeries("Known Night "+UUID.randomUUID(),"","#E11D48","manual",null,null,null,null,Set.of(),Set.of(),Set.of("Emo")));
        source=store.source(venue.getId(),"https://reset.example/","OFFICIAL",true);
    }
    @AfterEach void cleanup() {
        store.jdbc().update("DELETE FROM calendar_entries WHERE location_id=?",venue.getId());
        venues.deleteById(venue.getId());parties.deleteById(party.getId());
        operations.forEach(id -> {store.jdbc().update("DELETE FROM discovery_operations WHERE id=?",id);store.jdbc().update("DELETE FROM discovery_runs WHERE id=?",id);});
    }
    String candidate(String title,String evidence) {
        var event=new EventExtractor.Event(UUID.randomUUID().toString(),title,"https://reset.example/event/"+UUID.randomUUID(),List.of("2027-01-09"),evidence,"","");
        processor.process(venue,source,event,parties.findAll(),Map.of(),List.of("Emo","Metalcore"),true);
        String id=store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE venue_id=? AND title=?",String.class,venue.getId(),title);
        store.jdbc().update("UPDATE discovery_candidates SET status='IGNORED',matching_class='IGNORED',matcher_version='legacy' WHERE id=?",id);
        return id;
    }
    DiscoveryResetService.Request request(DiscoveryResetService.Mode mode,boolean manual) {
        var preview=resets.preview(new DiscoveryResetService.Selection(mode,manual));
        var id=UUID.randomUUID();operations.add(id.toString());
        return new DiscoveryResetService.Request(mode,manual,manual ? DiscoveryResetService.MANUAL_CONFIRMATION : "",preview.token(),id);
    }
    @Test void ignoredCanBeReevaluatedWithoutCreatingDuplicatesAndRepeatedResetIsSafe() {
        String id=candidate("Unknown Emo Night","Emo / Metalcore DJs");
        var first=resets.apply(request(DiscoveryResetService.Mode.IGNORED,false));
        assertThat(first.evaluated()).isEqualTo(1);assertThat(first.newPossibleMatches()).isEqualTo(1);
        assertThat(store.candidate(id)).containsEntry("status","NEEDS_REVIEW").containsEntry("matching_class","POSSIBLE_MATCH");
        assertThat(resets.apply(request(DiscoveryResetService.Mode.IGNORED,false)).evaluated()).isZero();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE venue_id=?",Integer.class,venue.getId())).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_decisions WHERE candidate_id=?",Integer.class,id)).isGreaterThanOrEqualTo(2);
    }
    @Test void rejectedNeedsExplicitConfirmationAndIsReopenedOnlyForManualReview() {
        String id=candidate("Unknown Emo Night","Emo DJs");
        importer.review(id,null,LocalDate.of(2027,1,9),"REJECTED","NONE","");
        assertThat(resets.apply(request(DiscoveryResetService.Mode.ALL,false)).preservedManual()).isEqualTo(1);
        assertThat(store.candidate(id).get("status")).isEqualTo("REJECTED");
        var valid=request(DiscoveryResetService.Mode.ALL,true);
        var invalid=new DiscoveryResetService.Request(valid.mode(),true,"",valid.previewToken(),valid.operationId());
        assertThatThrownBy(() -> resets.apply(invalid)).hasMessageContaining("Bestätigung");
        resets.apply(valid);
        assertThat(store.candidate(id)).containsEntry("status","NEEDS_REVIEW").containsEntry("decision_origin","MANUAL");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
    }
    @Test void everyManualReviewIsProtectedEvenWithoutARejectStatus() {
        String id=candidate("Unknown Emo Night","Emo DJs");
        importer.review(id,party.getId(),LocalDate.of(2027,1,10),"NEEDS_INQUIRY","ANSWERED","Confirmed response");
        resets.apply(request(DiscoveryResetService.Mode.ALL,true));
        assertThat(store.candidate(id)).containsEntry("status","NEEDS_INQUIRY").containsEntry("dm_text","Confirmed response");
        assertThat(store.candidate(id).get("event_date").toString()).isEqualTo("2027-01-10");
    }
    @Test void missingGenresAndLegacyRawDataStayUnverifiedAndNeverAutoImport() {
        String id=candidate("Unknown Night","");
        store.jdbc().update("UPDATE discovery_candidates SET raw_event=NULL WHERE id=?",id);
        var preview=resets.preview(new DiscoveryResetService.Selection(DiscoveryResetService.Mode.ALL,false));
        assertThat(preview.missingRaw()).isEqualTo(1);
        resets.apply(request(DiscoveryResetService.Mode.ALL,false));
        assertThat(store.candidate(id)).containsEntry("matching_class","UNVERIFIED").containsEntry("status","NEEDS_INQUIRY");
        assertThat(store.candidate(id).get("reason").toString()).contains("Rohdaten");
    }
    @Test void importedCalendarEntryAndSourcesAreUnchangedByReset() {
        var event=new EventExtractor.Event("known",party.getName(),"https://reset.example/known",List.of("2027-01-09"),"","","");
        processor.process(venue,source,event,parties.findAll(),Map.of(),List.of(),true);
        var before=store.jdbc().queryForMap("SELECT * FROM calendar_entries WHERE location_id=?",venue.getId());
        assertThat(resets.apply(request(DiscoveryResetService.Mode.ALL,true)).evaluated()).isZero();
        assertThat(store.jdbc().queryForMap("SELECT * FROM calendar_entries WHERE location_id=?",venue.getId())).isEqualTo(before);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_sources WHERE venue_id=?",Integer.class,venue.getId())).isEqualTo(1);
    }
    @Test void stalePreviewCannotOverrideALaterEditorialDecision() {
        String id=candidate("Unknown Emo Night","Emo DJs");
        var r=request(DiscoveryResetService.Mode.ALL,false);
        importer.review(id,null,LocalDate.of(2027,1,9),"REJECTED","NONE","");
        assertThatThrownBy(() -> resets.apply(r)).hasMessageContaining("veraltet");
        assertThat(store.candidate(id).get("status")).isEqualTo("REJECTED");
    }
    @Test void samePostgresLockPreventsConcurrentResetAndSchedulerAcrossConnections() throws Exception {
        candidate("Unknown Emo Night","Emo DJs");var request=request(DiscoveryResetService.Mode.ALL,false);
        try (var connection=store.jdbc().getDataSource().getConnection();var stmt=connection.createStatement()) {
            stmt.execute("SELECT pg_advisory_lock(73624601)");
            try {
                assertThat(scheduler.startReset(request)).isNull();assertThat(scheduler.start()).isFalse();scheduler.scan();
                verifyNoInteractions(discovery);
            } finally {stmt.execute("SELECT pg_advisory_unlock(73624601)");}
        }
    }
    Map<String,Object> await(String id) throws Exception {
        for (int i=0;i<150;i++) {
            var row=store.jdbc().queryForMap("SELECT * FROM discovery_operations WHERE id=?",id);
            if (!"RUNNING".equals(row.get("status"))) return row;
            Thread.sleep(20);
        }
        throw new AssertionError("Reset did not finish");
    }
    @Test void requestIdIsIdempotentAndPartialFetchFailurePreservesManualDecisions() throws Exception {
        String id=candidate("Unknown Emo Night","Emo DJs");
        importer.review(id,null,LocalDate.of(2027,1,9),"REJECTED","NONE","");
        when(discovery.discover(anyString(),any())).thenThrow(new EventPageFetcher.FetchFailure("BLOCKED"));
        var request=request(DiscoveryResetService.Mode.RESCAN,false);
        String operation=scheduler.startReset(request);
        assertThat(operation).isEqualTo(request.operationId().toString());
        assertThat(await(operation).get("status")).isEqualTo("PARTIAL");
        assertThat(scheduler.startReset(request)).isEqualTo(operation);
        verify(discovery,times(1)).discover(anyString(),any());
        assertThat(store.candidate(id)).containsEntry("status","REJECTED").containsEntry("reason","Manuell abgelehnt");
    }
    @Test void rescanNeverImportsOldEvidenceBeforeAFailedRefresh() throws Exception {
        String id=candidate("Earlier Unknown","");
        store.jdbc().update("INSERT INTO party_series_aliases(party_id,alias) VALUES (?,?)",party.getId(),"Earlier Unknown");
        when(discovery.discover(anyString(),any())).thenThrow(new EventPageFetcher.FetchFailure("BLOCKED"));
        String operation=scheduler.startReset(request(DiscoveryResetService.Mode.RESCAN,false));
        assertThat(await(operation).get("status")).isEqualTo("PARTIAL");
        assertThat(store.candidate(id)).containsEntry("status","NEEDS_REVIEW");
        assertThat(store.candidate(id).get("reason").toString()).contains("Neuer Abruf");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
    }
    @Test void activeResetBlocksSchedulerAndASecondResetUntilItFinishes() throws Exception {
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(discovery.discover(anyString(),any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Test release missing");
            return List.of(new VenueSourceDiscoveryService.Found(java.net.URI.create("https://reset.example/"),new EventExtractor.Extraction(List.of(),"NO_EVENTS")));
        });
        String operation=scheduler.startReset(request(DiscoveryResetService.Mode.RESCAN,false));
        try {
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(scheduler.start()).isFalse();
            assertThat(scheduler.startReset(request(DiscoveryResetService.Mode.ALL,false))).isNull();
        } finally {release.countDown();}
        assertThat(await(operation).get("status")).isEqualTo("COMPLETED");
    }
    @Test void ambiguousLegacyReviewsArePreservedAndExistingDuplicateRowsDoNotMultiply() {
        String id=candidate("Legacy Night","Emo DJs");
        store.jdbc().update("UPDATE discovery_candidates SET decision_origin='LEGACY',status='NEEDS_REVIEW' WHERE id=?",id);
        resets.apply(request(DiscoveryResetService.Mode.ALL,true));
        assertThat(store.candidate(id)).containsEntry("status","NEEDS_REVIEW").containsEntry("decision_origin","LEGACY");
        store.jdbc().update("""
            INSERT INTO discovery_candidates(id,venue_id,source_id,identity_key,fingerprint,title,source_url,event_date,evidence,status,reason)
            SELECT ?,venue_id,source_id,?,fingerprint,title,source_url,event_date,evidence,status,reason FROM discovery_candidates WHERE id=?
            """,UUID.randomUUID().toString(),EventNormalizer.hash("another"),id);
        processor.process(venue,source,new EventExtractor.Event("third-identity","Legacy Night","https://reset.example/new",List.of("2027-01-09"),"Emo DJs","",""),parties.findAll(),Map.of(),List.of("Emo"),true);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE venue_id=?",Integer.class,venue.getId())).isEqualTo(2);
    }

    @Test void resetApiReportsRealCountsFiltersAndIdempotentAuditedResult() throws Exception {
        String automatic=candidate("Unknown Emo Night","Emo DJs");
        String manual=candidate("Rejected Emo Night","Emo DJs");
        importer.review(manual,null,LocalDate.of(2027,1,9),"REJECTED","NONE","");
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/discovery"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.metrics.automaticallyIgnored").value(1))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.metrics.manuallyRejected").value(1));
        var r=request(DiscoveryResetService.Mode.ALL,false);
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var invalid=new DiscoveryResetService.Request(r.mode(),true,"",r.previewToken(),UUID.randomUUID());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/discovery/reset")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsString(invalid)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        for (int retry=0;retry<2;retry++) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/discovery/reset")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsString(r)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted());
            assertThat(await(r.operationId().toString()).get("status")).isEqualTo("COMPLETED");
        }
        var operation=store.jdbc().queryForMap("SELECT * FROM discovery_operations WHERE id=?",r.operationId().toString());
        var summary=json.readTree((String)operation.get("summary"));
        assertThat(summary.get("evaluated").asInt()).isEqualTo(1);
        assertThat(summary.get("newPossibleMatches").asInt()).isEqualTo(1);
        assertThat(summary.get("preservedManual").asInt()).isEqualTo(1);
        assertThat(operation.get("request_payload").toString()).contains("previewToken","includeManualRejected");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/discovery?filter=POSSIBLE_MATCH"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.candidates.length()").value(1))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.candidates[0].id").value(automatic));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/discovery?filter=MANUAL_REJECTED"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.candidates[0].id").value(manual));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/discovery?filter=REEVALUATED"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.candidates.length()").value(1))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.metrics.reevaluated").value(1));
    }

    @Test void freshConcertEvidenceBlocksImportWithoutOverwritingTheManualDecision() {
        String id=candidate("Manual Night","Emo DJs");
        importer.review(id,party.getId(),LocalDate.of(2027,1,9),"NEEDS_REVIEW","ANSWERED","Editorial response");
        var original=tools.jackson.databind.json.JsonMapper.builder().build().readValue((String)store.candidate(id).get("raw_event"),EventExtractor.Event.class);
        var changed=new EventExtractor.Event(original.identity(),"Emo Konzert",original.url(),original.starts(),"Emo DJs live","","");
        processor.process(venue,source,changed,parties.findAll(),Map.of(),List.of("Emo"),true);
        assertThat(store.candidate(id)).containsEntry("status","NEEDS_REVIEW").containsEntry("decision_origin","MANUAL")
            .containsEntry("dm_text","Editorial response").containsEntry("matching_class","IGNORED");
        assertThatThrownBy(() -> importer.importCandidate(id,party.getId(),LocalDate.of(2027,1,9),true)).hasMessageContaining("Konzerte");
    }

}
