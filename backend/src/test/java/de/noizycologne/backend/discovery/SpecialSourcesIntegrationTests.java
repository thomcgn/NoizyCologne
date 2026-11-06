package de.noizycologne.backend.discovery;

import de.noizycologne.backend.party.*;
import de.noizycologne.backend.venue.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"noizy.discovery.enabled=false","noizy.discovery.monitor.enabled=false","logging.level.root=WARN"})
@ActiveProfiles("local")
class SpecialSourcesIntegrationTests {
    @Autowired DiscoveryStore store;
    @Autowired SpecialSourceService sources;
    @Autowired DiscoveryScheduler scheduler;
    @Autowired DiscoveryController controller;
    @Autowired EventImportService importer;
    @Autowired VenueRepository venues;
    @Autowired PartySeriesRepository parties;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @MockitoBean VenueSourceDiscoveryService discovery;
    String source;
    Venue mtc,blue;
    PartySeries party;
    MockMvc mvc;
    final String root="https://qrushapp.de/";
    @BeforeEach void setup() throws Exception {
        mtc=venues.saveAndFlush(new Venue("MTC "+UUID.randomUUID(),""));
        blue=venues.saveAndFlush(new Venue("Blue Shell "+UUID.randomUUID(),""));
        party=parties.saveAndFlush(new PartySeries("Middance Crisis "+UUID.randomUUID(),"","#E11D48","manual",null,null,null,null,Set.of(),Set.of()));
        source=sources.add("QRUSH",root,"","","");
        mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(requireNonNull(context)).build();
        found(event("one",party.getName(),blue.getName(),""));
    }
    @AfterEach void cleanup() {
        store.jdbc().update("DELETE FROM calendar_entries WHERE location_id IN (?,?)",mtc.getId(),blue.getId());
        store.jdbc().update("DELETE FROM discovery_sources WHERE id=?",source);
        venues.deleteById(mtc.getId());venues.deleteById(blue.getId());parties.deleteById(party.getId());
    }
    EventExtractor.@org.jspecify.annotations.NonNull Event event(String id,String title,String location,String state) {
        return new EventExtractor.Event(id,title,root+"eventfinder/koeln/events/"+id,List.of("2027-01-09"),"Indie, Emo Party",state,location);
    }
    void found(EventExtractor.@org.jspecify.annotations.NonNull Event event) throws Exception {
        when(discovery.discoverSpecial(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(List.of(event),"EVENTS_FOUND",true))));
    }
    String candidate() {return requireNonNull(store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE source_id=?",String.class,source));}
    @Test void addIsIndependentUniqueAndDeleteIsIdempotentWithoutAPlaceholderLocation() throws Exception {
        assertThat(sources.list()).anySatisfy(row -> assertThat(row).containsEntry("id",source).containsEntry("venue_id",null).containsEntry("special_source",true));
        requireNonNull(assertThatThrownBy(()->sources.add("Duplicate","https://QRUSHAPP.DE","","","")).isInstanceOf(ResponseStatusException.class)).hasMessageContaining("bereits vorhanden");
        mvc.perform(delete("/api/v1/discovery/special-sources/"+source)).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/discovery/special-sources/"+source)).andExpect(status().isOk());
        assertThat(sources.list()).noneMatch(row -> source.equals(row.get("id")));
        scheduler.scan();verifyNoInteractions(discovery);
        assertThat(venues.findById(requireNonNull(mtc.getId()))).isPresent();
    }
    @Test void scanKeepsReportedLocationSeparateAndNeverCreatesMtcCalendarEntries() throws Exception {
        scheduler.scan();scheduler.scan();
        var candidate=store.candidate(candidate());
        assertThat(candidate).containsEntry("venue_id",null).containsEntry("venue_name","Veranstaltungsort offen").containsEntry("special_source",true).containsEntry("calendar_entry_id",null);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,mtc.getId())).isZero();
        mvc.perform(get("/api/v1/discovery/candidates/"+candidate.get("id")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reported_location").value(blue.getName()))
            .andExpect(jsonPath("$.assessment.verification.status").value("VERIFIED"));
    }
    @Test void sourceWithMissingLocationRemainsReviewableAndCannotBeImportedWithoutExplicitVenue() throws Exception {
        found(event("one",party.getName(),"",""));scheduler.scan();String id=candidate();
        requireNonNull(assertThatThrownBy(()->importer.confirm(id,party.getId(),LocalDate.of(2027,1,9),"NONE","",""))
            .isInstanceOf(ResponseStatusException.class)).hasMessageContaining("Veranstaltungsort zuerst");
        assertThat(store.candidate(id)).containsEntry("venue_id",null).containsEntry("calendar_entry_id",null);
    }
    @Test void confirmedVenueFeedsExistingCalendarWorkflowAndSourceRemovalPreservesImportedEntry() throws Exception {
        scheduler.scan();String id=candidate();
        String calendar=importer.confirm(id,party.getId(),LocalDate.of(2027,1,9),"ANSWERED","Ort geprüft","",blue.getId());
        assertThat(store.jdbc().queryForObject("SELECT location_id FROM calendar_entries WHERE id=?",String.class,calendar)).isEqualTo(blue.getId());
        mvc.perform(post("/api/v1/discovery/candidates/"+id+"/reject")).andExpect(status().isConflict());
        sources.delete(source);scheduler.scan();
        assertThat(store.candidate(id)).containsEntry("status","IMPORTED").containsEntry("calendar_entry_id",calendar);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE id=?",Integer.class,calendar)).isEqualTo(1);
    }
    @Test void instantRejectionPersistsThroughChangedScansDeleteAndReaddingTheSource() throws Exception {
        found(event("one","Karnevalsnacht","",""));scheduler.scan();String id=candidate();
        mvc.perform(post("/api/v1/discovery/candidates/"+id+"/reject")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/discovery/candidates/"+id+"/reject")).andExpect(status().isOk());
        found(event("one","Karnevalsnacht Special","",""));scheduler.scan();
        assertThat(store.candidate(id)).containsEntry("status","REJECTED").containsEntry("decision_origin","MANUAL");
        assertThat(store.candidates("NEEDS_REVIEW")).noneMatch(row->id.equals(row.get("id")));
        assertThat(store.candidates("MANUAL_REJECTED")).anyMatch(row->id.equals(row.get("id")));
        sources.delete(source);assertThat(sources.add("Qrush wieder hinzugefügt",root,"","","")).isEqualTo(source);
        scheduler.scan();assertThat(store.candidate(id)).containsEntry("status","REJECTED");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_decisions WHERE candidate_id=? AND new_status='REJECTED' AND origin='MANUAL'",Integer.class,id)).isEqualTo(1);
    }
    @Test void rejectionPreservesEditedFieldsAndDoesNotRejectOtherEventsOfTheSource() throws Exception {
        scheduler.scan();String id=candidate();
        importer.review(id,party.getId(),LocalDate.of(2027,1,10),"NEEDS_INQUIRY","SENT","Manuell bearbeiteter Text");
        importer.reject(id);
        assertThat(store.candidate(id)).containsEntry("party_id",party.getId()).containsEntry("dm_text","Manuell bearbeiteter Text");
        assertThat(store.candidates("OPEN")).noneMatch(row->id.equals(row.get("id")));
        found(event("two","Andere Indie Party","",""));scheduler.scan();
        assertThat(store.jdbc().queryForList("SELECT status FROM discovery_candidates WHERE source_id=?",String.class,source)).containsExactlyInAnyOrder("REJECTED","NEEDS_REVIEW");
    }
    @Test void savedVenueIsAProtectedManualChoiceAndCancelledPortalEventsCannotBeConfirmed() throws Exception {
        scheduler.scan();String id=candidate();
        importer.review(id,party.getId(),LocalDate.of(2027,1,9),"NEEDS_REVIEW","NONE","",blue.getId());
        scheduler.scan();assertThat(store.candidate(id)).containsEntry("venue_id",blue.getId());
        store.jdbc().update("DELETE FROM discovery_candidates WHERE source_id=?",source);
        found(event("cancelled",party.getName(),blue.getName(),"EventCancelled"));scheduler.scan();String cancelled=candidate();
        requireNonNull(assertThatThrownBy(()->importer.confirm(cancelled,party.getId(),LocalDate.of(2027,1,9),"ANSWERED","","",blue.getId()))
            .isInstanceOf(ResponseStatusException.class)).hasMessageContaining("Absage oder Verschiebung");
    }
    @Test void specialSourceApiValidatesInputsAndNeverDeletesAVenueSource() throws Exception {
        mvc.perform(requireNonNull(post("/api/v1/discovery/special-sources").contentType("application/json").content("{\"name\":\"\",\"url\":\"https://example.org/\"}"))).andExpect(status().isBadRequest());
        mvc.perform(requireNonNull(post("/api/v1/discovery/special-sources").contentType("application/json").content("{\"name\":\"X\",\"url\":\"https://127.0.0.1/\"}"))).andExpect(status().isBadRequest());
        String venueSource=store.source(mtc.getId(),"https://mtc.example/","OFFICIAL",true);
        mvc.perform(delete("/api/v1/discovery/special-sources/"+venueSource)).andExpect(status().isNotFound());
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_sources WHERE id=?",Integer.class,venueSource)).isEqualTo(1);
    }
    @Test void contradictoryDatesInOnePortalBatchRemainVisibleInsteadOfLastPageWinning() throws Exception {
        var first=event("one",party.getName(),blue.getName(),"");
        var second=new EventExtractor.Event(first.identity(),first.title(),first.url(),List.of("2027-01-10"),first.evidence(),"",first.location());
        when(discovery.discoverSpecial(eq(root),any())).thenReturn(List.of(
            new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(List.of(first),"EVENTS_FOUND",true)),
            new VenueSourceDiscoveryService.Found(URI.create(first.url()),new EventExtractor.Extraction(List.of(second),"EVENTS_FOUND",true))));
        scheduler.scan();
        var row=store.candidate(candidate());
        assertThat(row).containsEntry("event_date",null).containsEntry("venue_id",null).containsEntry("status","NEEDS_REVIEW");
        mvc.perform(get("/api/v1/discovery/candidates/"+row.get("id"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.assessment.verification.status").value("CONFLICTING_DATA"));
    }
    @Test void assigningAnExistingIdentityReturnsConflictAndRollsBackTheVenueChoice() throws Exception {
        scheduler.scan();String id=candidate();
        String other=sources.add("Andere Quelle","https://other.example/events","","","");
        try {
            store.jdbc().update("INSERT INTO discovery_candidates(id,venue_id,source_id,identity_key,fingerprint,title,source_url,status,reason,evidence) SELECT ?,?,?,identity_key,fingerprint,title,source_url,status,reason,evidence FROM discovery_candidates WHERE id=?",other,blue.getId(),other,id);
            mvc.perform(requireNonNull(post("/api/v1/discovery/candidates/"+id+"/confirm").contentType("application/json")
                .content("{\"partyId\":\""+party.getId()+"\",\"venueId\":\""+blue.getId()+"\",\"date\":\"2027-01-09\",\"inquiryStatus\":\"NONE\",\"dmText\":\"\"}")))
                .andExpect(status().isConflict());
            assertThat(store.candidate(id)).containsEntry("venue_id",null).containsEntry("calendar_entry_id",null);
        } finally {store.jdbc().update("DELETE FROM discovery_sources WHERE id=?",other);}
    }
    @Test void blockedPortalIsReportedAndNeverChangesTheMtcSourceStatus() throws Exception {
        String before=venues.findById(requireNonNull(mtc.getId())).orElseThrow().getDiscoveryStatus();
        when(discovery.discoverSpecial(eq(root),any())).thenThrow(new EventPageFetcher.FetchFailure("ROBOTS_BLOCKED"));
        scheduler.scan();
        assertThat(sources.list()).anySatisfy(row->assertThat(row).containsEntry("id",source).containsEntry("quality","BLOCKED").containsEntry("status","ROBOTS_BLOCKED"));
        assertThat(venues.findById(requireNonNull(mtc.getId())).orElseThrow().getDiscoveryStatus()).isEqualTo(before);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE source_id=?",Integer.class,source)).isZero();
    }

    @Test void changedPortalLifecycleReachesManualReviewsWithoutOverwritingTheTeamDecision() throws Exception {
        scheduler.scan();String id=candidate();
        importer.review(id,party.getId(),LocalDate.of(2027,1,10),"NEEDS_INQUIRY","SENT","Geschützter Text");
        found(event("one",party.getName(),blue.getName(),"EventCancelled"));scheduler.scan();
        assertThat(store.candidate(id)).containsEntry("status","NEEDS_INQUIRY").containsEntry("inquiry_status","SENT").containsEntry("dm_text","Geschützter Text");
        mvc.perform(get("/api/v1/discovery/candidates/"+id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.assessment.lifecycle").value("CANCELLATION_UNCONFIRMED"));
        requireNonNull(assertThatThrownBy(()->importer.confirm(id,party.getId(),LocalDate.of(2027,1,10),"ANSWERED","Geschützter Text","",blue.getId()))
            .isInstanceOf(ResponseStatusException.class)).hasMessageContaining("Absage oder Verschiebung");
        assertThat(store.candidate(id)).containsEntry("venue_id",null).containsEntry("calendar_entry_id",null);
    }

    @Test void editKeepsSourceIdentityAndDecisionsWhileResettingChangedExtractionStatus() throws Exception {
        scheduler.scan();String id=candidate();importer.reject(id);
        mvc.perform(requireNonNull(put("/api/v1/discovery/special-sources/"+source).contentType("application/json")
            .content("{\"name\":\"QRUSH Köln\",\"url\":\"https://qrushapp.de/eventfinder/koeln\",\"itemSelector\":\".event\",\"titleSelector\":\"h2\",\"dateSelector\":\"time\"}"))).andExpect(status().isOk());
        assertThat(sources.list()).anySatisfy(row->assertThat(row).containsEntry("id",source).containsEntry("name","QRUSH Köln").containsEntry("url",root+"eventfinder/koeln").containsEntry("venue_id",null).containsEntry("status","NOT_CHECKED").containsEntry("event_count",null).containsEntry("item_selector",".event"));
        assertThat(store.candidate(id)).containsEntry("source_id",source).containsEntry("status","REJECTED").containsEntry("decision_origin","MANUAL");
    }
    @Test void editsValidateDuplicatesAndNeverModifyDeletedOrVenueSources() throws Exception {
        scheduler.scan();sources.update(source,"Neuer Name",root,"","","");
        assertThat(sources.list()).anySatisfy(row->assertThat(row).containsEntry("status","EVENTS_FOUND"));
        String other=sources.add("Andere Quelle","https://other.example/","","","");
        try {
            mvc.perform(requireNonNull(put("/api/v1/discovery/special-sources/"+source).contentType("application/json")
                .content("{\"name\":\"Dublette\",\"url\":\"https://other.example/\"}"))).andExpect(status().isConflict());
            mvc.perform(requireNonNull(put("/api/v1/discovery/special-sources/"+source).contentType("application/json")
                .content("{\"name\":\"\",\"url\":\"https://qrushapp.de/\"}"))).andExpect(status().isBadRequest());
            mvc.perform(requireNonNull(put("/api/v1/discovery/special-sources/"+source).contentType("application/json")
                .content("{\"name\":\"Unsicher\",\"url\":\"https://127.0.0.1/\"}"))).andExpect(status().isBadRequest());
            String venueSource=store.source(mtc.getId(),"https://mtc.example/","OFFICIAL",true);
            mvc.perform(requireNonNull(put("/api/v1/discovery/special-sources/"+venueSource).contentType("application/json")
                .content("{\"name\":\"Location\",\"url\":\"https://qrushapp.de/\"}"))).andExpect(status().isNotFound());
            assertThat(sources.list()).anySatisfy(row->assertThat(row).containsEntry("id",source).containsEntry("name","Neuer Name").containsEntry("url",root));
            sources.delete(source);
            mvc.perform(requireNonNull(put("/api/v1/discovery/special-sources/"+source).contentType("application/json")
                .content("{\"name\":\"Gelöscht\",\"url\":\"https://qrushapp.de/\"}"))).andExpect(status().isNotFound());
        } finally {store.jdbc().update("DELETE FROM discovery_sources WHERE id=?",other);}
    }

}
