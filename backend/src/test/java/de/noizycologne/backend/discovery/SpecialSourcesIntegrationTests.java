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
    @Autowired DiscoveryGeographyService geography;
    @Autowired DiscoveryResetService resets;
    @Autowired EventCandidateProcessor processor;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean EventMatcher matcher;
    @Autowired SpecialSourceService sources;
    @Autowired DiscoveryScheduler scheduler;
    @Autowired DiscoveryController controller;
    @Autowired EventImportService importer;
    @Autowired de.noizycologne.backend.calendar.CalendarEntryService calendar;
    @Autowired VenueRepository venues;
    @Autowired PartySeriesRepository parties;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @MockitoBean VenueSourceDiscoveryService discovery;
    String source;
    final List<String> extraVenues=new ArrayList<>();
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
        for(String extra:extraVenues)venues.deleteById(extra);extraVenues.clear();
        venues.deleteById(mtc.getId());venues.deleteById(blue.getId());parties.deleteById(party.getId());
    }
    EventExtractor.@org.jspecify.annotations.NonNull Event event(String id,String title,String location,String state) {
        return new EventExtractor.Event(id,title,root+"eventfinder/koeln/events/"+id,List.of("2027-01-09"),"Indie, Emo Party",state,location,new EventPlace("Köln","DE","Luxemburger Straße 32","50674","",null,null,false));
    }
    void found(EventExtractor.@org.jspecify.annotations.NonNull Event event) throws Exception {
        when(discovery.discoverSpecial(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(List.of(event),"EVENTS_FOUND",true))));
    }
    String candidate() {return requireNonNull(store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE source_id=?",String.class,source));}
    void approveSource(boolean approved) throws Exception {
        mvc.perform(requireNonNull(put("/api/v1/discovery/special-sources/"+source).contentType("application/json")
            .content("{\"name\":\"QRUSH\",\"url\":\""+root+"\",\"approved\":"+approved+"}"))).andExpect(status().isOk());
    }
    @Test void confirmedVenueAndApprovedPortalUseRegularImportWithoutDuplicatesOrEditorialApproval() throws Exception {
        geography.remember(blue.getId(),event("one",party.getName(),blue.getName(),""),root);
        approveSource(true);
        scheduler.scan();scheduler.scan();
        var row=store.candidate(candidate());
        assertThat(row).containsEntry("venue_id",blue.getId()).containsEntry("status","IMPORTED").containsEntry("decision_origin","AUTOMATIC");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,blue.getId())).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM editorial_post_drafts WHERE calendar_entry_id=?",Integer.class,row.get("calendar_entry_id"))).isZero();
        assertThat(store.jdbc().queryForObject("SELECT candidate_id FROM discovery_geo_events WHERE source_id=?",String.class,source)).isEqualTo(row.get("id"));
        String other=sources.add("Andere freigegebene Quelle","https://other.example/events","","","",true);
        try {
            processor.processSpecial(other,event("one",party.getName(),blue.getName(),""),null);
            assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE venue_id=?",Integer.class,blue.getId())).isEqualTo(1);
            assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,blue.getId())).isEqualTo(1);
        } finally {store.jdbc().update("DELETE FROM discovery_sources WHERE id=?",other);}
        mvc.perform(requireNonNull(put("/api/v1/discovery/special-sources/"+source).contentType("application/json").content("{\"name\":\"QRUSH umbenannt\",\"url\":\""+root+"\"}"))).andExpect(status().isOk());
        assertThat(store.jdbc().queryForObject("SELECT approved FROM discovery_sources WHERE id=?",Boolean.class,source)).isTrue();
        approveSource(false);
        var next=new EventExtractor.Event("next",party.getName(),root+"events/next",List.of("2027-01-16"),"Indie Party","",blue.getName(),event("one",party.getName(),blue.getName(),"").place());
        processor.processSpecial(source,next,null);
        assertThat(store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE source_id=? AND identity_key=?",source,EventChangeMonitor.identity(next)))
            .containsEntry("venue_id",blue.getId()).containsEntry("status","NEEDS_REVIEW").containsEntry("calendar_entry_id",null);
    }
    @Test void aManualVenueBindingIsReusedForLaterEventsButDoesNotReleaseTheReviewedEvent() throws Exception {
        scheduler.scan();String original=candidate();
        importer.review(original,party.getId(),LocalDate.of(2027,1,9),"REJECTED","ANSWERED","Nicht übernehmen",blue.getId());
        approveSource(true);scheduler.scan();
        var next=new EventExtractor.Event("next",party.getName(),root+"events/next",List.of("2027-01-16"),"Indie Party","",blue.getName(),event("one",party.getName(),blue.getName(),"").place());
        processor.processSpecial(source,next,null);processor.processSpecial(source,next,null);
        assertThat(store.candidate(original)).containsEntry("status","REJECTED").containsEntry("dm_text","Nicht übernehmen").containsEntry("calendar_entry_id",null);
        assertThat(store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE source_id=? AND identity_key=?",source,EventChangeMonitor.identity(next)))
            .containsEntry("venue_id",blue.getId()).containsEntry("status","IMPORTED");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,blue.getId())).isEqualTo(1);
    }
    @Test void aConfirmedVenueAliasAlsoPassesTermVerificationWithoutChangingSourceEvidence() throws Exception {
        String alias="Bestätigter Clubalias "+UUID.randomUUID();
        var observation=event("alias",party.getName(),alias,"");
        geography.remember(blue.getId(),observation,root);approveSource(true);
        found(observation);scheduler.scan();scheduler.scan();
        var row=store.candidate(candidate());
        assertThat(row).containsEntry("venue_id",blue.getId()).containsEntry("status","IMPORTED");
        assertThat(geography.event(row).location()).isEqualTo(alias);
        mvc.perform(get("/api/v1/discovery/candidates/"+row.get("id"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.assessment.verification.status").value("VERIFIED"));
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,blue.getId())).isEqualTo(1);
    }
    @Test void approvingAPortalDoesNotInventOrGuessUnknownVenues() throws Exception {
        long count=venues.count();approveSource(true);
        found(event("unknown",party.getName(),"Unbekannter Club",""));scheduler.scan();
        assertThat(store.candidate(candidate())).containsEntry("venue_id",null).containsEntry("calendar_entry_id",null);
        assertThat(venues.count()).isEqualTo(count);
    }
    @Test void newlyConfirmedGeographyReusesAnExistingAutomaticProposal() throws Exception {
        scheduler.scan();String id=candidate();
        geography.remember(blue.getId(),event("one",party.getName(),blue.getName(),""),root);
        approveSource(true);scheduler.scan();
        assertThat(store.candidate(id)).containsEntry("venue_id",blue.getId()).containsEntry("status","IMPORTED");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE source_id=?",Integer.class,source)).isEqualTo(1);
    }
    @Test void resolvedPortalEventsStillRespectClosuresLifecycleAndExistingDecisions() throws Exception {
        geography.remember(blue.getId(),event("one",party.getName(),blue.getName(),""),root);approveSource(true);
        var closure=calendar.createEntry(new de.noizycologne.backend.calendar.CalendarEntryRequest("2027-01-09",blue.getId(),"closure","",null,null,"Geschlossen",Set.of()));
        scheduler.scan();String id=candidate();
        assertThat(store.candidate(id)).containsEntry("status","NEEDS_REVIEW").containsEntry("calendar_entry_id",null);
        calendar.deleteEntry(closure.id());
        for(String state:List.of("EventCancelled","EventPostponed","EventRescheduled")) {
            found(event("one",party.getName(),blue.getName(),state));scheduler.scan();
            assertThat(store.candidate(id)).containsEntry("calendar_entry_id",null);
        }
        importer.reject(id);found(event("one",party.getName(),blue.getName(),""));scheduler.scan();
        assertThat(store.candidate(id)).containsEntry("status","REJECTED").containsEntry("calendar_entry_id",null);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,blue.getId())).isZero();
    }
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
        var second=new EventExtractor.Event(first.identity(),first.title(),first.url(),List.of("2027-01-10"),first.evidence(),"",first.location(),first.place());
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

    @Test void outsidePortalEventsAreArchivedWithoutGenreMatchingOrNormalReview() throws Exception {
        for(String city:List.of("Bonn","Aachen","Berlin","Frankfurt")) {
            var event=new EventExtractor.Event(city,"Köln Indie Party","https://qrushapp.de/koeln/events/"+city,List.of("2027-01-09"),"Köln Indie Emo Party","","Gleis8",new EventPlace(city,"DE","Echte Straße 12","","",null,null,false));
            processor.processSpecial(source,event,null);
        }
        verify(matcher,never()).match(any(),anyString(),anyList(),anyMap(),anyCollection(),anyBoolean());
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE source_id=? AND status='IGNORED'",Integer.class,source)).isEqualTo(4);
        assertThat(store.jdbc().queryForList("SELECT status FROM discovery_geo_events WHERE source_id=?",String.class,source)).containsOnly("OUTSIDE").hasSize(4);
        mvc.perform(get("/api/v1/discovery?filter=NEEDS_REVIEW")).andExpect(status().isOk()).andExpect(jsonPath("$.candidates.length()").value(0));
    }
    @Test void unresolvedPlaceIsSeparateAndOneConfirmationReusesTheVenueAndAliasForLaterEvents() throws Exception {
        var unknown=new EventExtractor.Event("unknown","Indie Party",root+"events/unknown",List.of("2027-01-09"),"Köln steht im Text","","Blue Shell",new EventPlace("","","","","https://venue.example/blue-shell",null,null,false));
        processor.processSpecial(source,unknown,null);
        verify(matcher,never()).match(any(),anyString(),anyList(),anyMap(),anyCollection(),anyBoolean());
        String id=requireNonNull(store.jdbc().queryForObject("SELECT id FROM discovery_geo_events WHERE source_id=?",String.class,source));
        mvc.perform(get("/api/v1/discovery/geography")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("UNRESOLVED")).andExpect(jsonPath("$.counts.UNRESOLVED").value(1));
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE source_id=?",Integer.class,source)).isZero();
        mvc.perform(requireNonNull(post("/api/v1/discovery/geography/"+id+"/confirm").contentType("application/json")
            .content("{\"venueId\":\""+blue.getId()+"\",\"city\":\"Köln\",\"street\":\"Luxemburger Straße 32\",\"proofUrl\":\"https://venue.example/address\",\"confirmed\":true}"))).andExpect(status().isOk());
        assertThat(store.candidate(candidate())).containsEntry("venue_id",blue.getId()).containsEntry("geo_status","INSIDE").containsEntry("calendar_entry_id",null);
        assertThatThrownBy(()->geography.remember(mtc.getId(),new EventExtractor.Event(unknown.identity(),unknown.title(),unknown.url(),unknown.starts(),unknown.evidence(),unknown.state(),unknown.location(),new EventPlace("Köln","DE","Luxemburger Straße 32","",unknown.place().reference(),null,null,false)),unknown.url())).isInstanceOf(ResponseStatusException.class);
        var next=new EventExtractor.Event("next","Indie Party",root+"events/next",List.of("2027-01-16"),"Indie Party","","BLUE-SHELL",unknown.place());
        processor.processSpecial(source,next,null);
        assertThat(store.jdbc().queryForList("SELECT venue_id FROM discovery_candidates WHERE source_id=?",String.class,source)).containsOnly(blue.getId()).hasSize(2);
        var namesake=new EventExtractor.Event("namesake","Indie Party",root+"events/namesake",List.of("2027-01-23"),"Indie Party","","Blue Shell");
        processor.processSpecial(source,namesake,null);
        assertThat(store.jdbc().queryForObject("SELECT status FROM discovery_geo_events WHERE source_id=? AND identity_key=?",String.class,source,EventChangeMonitor.identity(namesake))).isEqualTo("UNRESOLVED");
    }
    @Test void explicitReevaluationClassifiesLegacyOutsideFindingsWithoutChangingTheirManualDecision() throws Exception {
        scheduler.scan();String id=candidate();importer.reject(id);
        var outside=new EventExtractor.Event("one",party.getName(),root+"events/one",List.of("2027-01-09"),"Indie Party","","Gleis8",new EventPlace("Bonn","DE","Bornheimer Straße 1","","",null,null,false));
        store.jdbc().update("UPDATE discovery_candidates SET raw_event=?,geo_status='NOT_CHECKED' WHERE id=?",tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(outside),id);
        store.jdbc().update("DELETE FROM discovery_geo_events WHERE source_id=?",source);
        assertThat(store.candidate(id)).containsEntry("geo_status","NOT_CHECKED");
        var preview=resets.preview(new DiscoveryResetService.Selection(DiscoveryResetService.Mode.ALL,false));
        assertThat(preview.geography()).anySatisfy(item->assertThat(item.status()).isEqualTo("OUTSIDE"));
        assertThat(store.candidate(id)).containsEntry("geo_status","NOT_CHECKED");
        assertThat(resets.apply(new DiscoveryResetService.Request(DiscoveryResetService.Mode.ALL,false,"",preview.token(),UUID.randomUUID())).geographicallyClassified()).isEqualTo(1);
        assertThat(store.candidate(id)).containsEntry("geo_status","OUTSIDE").containsEntry("status","REJECTED").containsEntry("decision_origin","MANUAL");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_decisions WHERE candidate_id=? AND origin='MANUAL'",Integer.class,id)).isEqualTo(1);
        assertThat(store.candidates("NEEDS_REVIEW")).noneMatch(row->id.equals(row.get("id")));
    }
    @Test void importedSpecialEventsAndTheirCancellationRecordsAreNeverReclassifiedByGeography() throws Exception {
        scheduler.scan();String id=candidate();String calendar=importer.confirm(id,party.getId(),LocalDate.of(2027,1,9),"NONE","","",blue.getId());
        String cancellation=UUID.randomUUID().toString();
        store.jdbc().update("INSERT INTO event_cancellations(id,calendar_entry_id,trigger_type,reason_code,source_url,announcement_caption) VALUES (?,?,'DM_CONFIRMED','UNSPECIFIED',?,'Geprüfte Absage')",cancellation,calendar,root);
        store.jdbc().update("INSERT INTO cancellation_history(cancellation_id,action,note) VALUES (?,'CONFIRMED','Bestand erhalten')",cancellation);
        store.jdbc().update("UPDATE discovery_candidates SET raw_event=? WHERE id=?",tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(new EventExtractor.Event("one",party.getName(),root+"events/one",List.of("2027-01-09"),"Indie Party","EventCancelled","Gleis8",new EventPlace("Bonn","DE","Straße 1","","",null,null,false))),id);
        var preview=resets.preview(new DiscoveryResetService.Selection(DiscoveryResetService.Mode.ALL,false));
        assertThat(preview.geography()).noneMatch(item->item.id().equals(id));
        resets.apply(new DiscoveryResetService.Request(DiscoveryResetService.Mode.ALL,false,"",preview.token(),UUID.randomUUID()));
        assertThat(store.candidate(id)).containsEntry("status","IMPORTED").containsEntry("calendar_entry_id",calendar).containsEntry("geo_status","INSIDE");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE id=?",Integer.class,calendar)).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT announcement_caption FROM event_cancellations WHERE id=?",String.class,cancellation)).isEqualTo("Geprüfte Absage");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM cancellation_history WHERE cancellation_id=?",Integer.class,cancellation)).isEqualTo(1);
    }

    @Test void newCologneVenueRequiresExplicitConfirmationAndOutsideEvidenceCannotBeOverridden() throws Exception {
        var unknown=new EventExtractor.Event("new-place",party.getName(),root+"events/new",List.of("2027-01-09"),"Indie Party","","Neuer Raum");
        processor.processSpecial(source,unknown,null);
        String id=requireNonNull(store.jdbc().queryForObject("SELECT id FROM discovery_geo_events WHERE source_id=?",String.class,source));
        String body="{\"venueName\":\"Neuer Club "+UUID.randomUUID()+"\",\"city\":\"Köln\",\"street\":\"Teststraße 12\",\"proofUrl\":\"https://venue.example/address\",\"confirmed\":false}";
        mvc.perform(requireNonNull(post("/api/v1/discovery/geography/"+id+"/confirm").contentType("application/json").content(body))).andExpect(status().isBadRequest());
        mvc.perform(requireNonNull(post("/api/v1/discovery/geography/"+id+"/confirm").contentType("application/json").content(requireNonNull(body.replace("false","true"))))).andExpect(status().isOk());
        String venue=requireNonNull((String)store.candidate(candidate()).get("venue_id"));extraVenues.add(venue);
        assertThat(store.jdbc().queryForObject("SELECT city FROM discovery_venue_geography WHERE venue_id=?",String.class,venue)).isEqualTo("Köln");
        var outside=new EventExtractor.Event("outside",party.getName(),root+"events/outside",List.of("2027-01-09"),"Indie Party","","Bonn",new EventPlace("Bonn","DE","Teststraße 12","","",null,null,false));
        var rejected=geography.record(source,outside);
        mvc.perform(requireNonNull(post("/api/v1/discovery/geography/"+rejected.id()+"/confirm").contentType("application/json").content(requireNonNull(body.replace("false","true"))))).andExpect(status().isConflict());
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE source_id=?",Integer.class,source)).isEqualTo(1);
    }

    @Test void latestOutsideEvidenceBlocksProtectedManualCandidatesAndCannotBeReplacedByStaleReviewData() throws Exception {
        scheduler.scan();String id=candidate();
        importer.review(id,party.getId(),LocalDate.of(2027,1,10),"NEEDS_INQUIRY","SENT","Geschützter Text",blue.getId());
        clearInvocations(matcher);
        var moved=new EventExtractor.Event("one",party.getName(),root+"events/one",List.of("2027-01-16"),"Indie Party","","Gleis8",new EventPlace("Bonn","DE","Bornheimer Straße 1","","",null,null,false));
        found(moved);scheduler.scan();
        verify(matcher,never()).match(any(),anyString(),anyList(),anyMap(),anyCollection(),anyBoolean());
        assertThat(store.candidate(id)).containsEntry("geo_status","OUTSIDE").containsEntry("status","NEEDS_INQUIRY").containsEntry("inquiry_status","SENT").containsEntry("dm_text","Geschützter Text").containsEntry("venue_id",blue.getId());
        requireNonNull(assertThatThrownBy(()->importer.confirm(id,party.getId(),LocalDate.of(2027,1,10),"ANSWERED","Geschützter Text","",blue.getId())).isInstanceOf(ResponseStatusException.class)).hasMessageContaining("Standort zuerst klären");
        var preview=resets.preview(new DiscoveryResetService.Selection(DiscoveryResetService.Mode.ALL,false));
        assertThat(preview.geography()).anySatisfy(item->assertThat(item.status()).isEqualTo("OUTSIDE"));
        resets.apply(new DiscoveryResetService.Request(DiscoveryResetService.Mode.ALL,false,"",preview.token(),UUID.randomUUID()));
        assertThat(store.candidate(id)).containsEntry("geo_status","OUTSIDE").containsEntry("calendar_entry_id",null).containsEntry("dm_text","Geschützter Text");
    }

    @Test void canonicalTransportUrlsReuseLegacyManualDecisionsInsteadOfCreatingFreshSuggestions() throws Exception {
        String id=UUID.randomUUID().toString(),address=root+"events/legacy";
        var legacy=new EventExtractor.Event(address+"?_rsc=old",party.getName(),address+"?_rsc=old",List.of("2027-01-09"),"Indie Party","",blue.getName(),new EventPlace("Köln","DE","Luxemburger Straße 32","","",null,null,false));
        store.jdbc().update("INSERT INTO discovery_candidates(id,venue_id,source_id,identity_key,fingerprint,title,source_url,evidence,status,reason,decision_origin,raw_event) VALUES (?,NULL,?,?,?,?,?,?,'REJECTED','Manuell abgelehnt','MANUAL',?)",id,source,EventChangeMonitor.identity(legacy),"legacy",legacy.title(),legacy.url(),legacy.evidence(),tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(legacy));
        var current=new EventExtractor.Event(address+"?_rsc=new",legacy.title(),address+"?_rsc=new",legacy.starts(),legacy.evidence(),legacy.state(),legacy.location(),legacy.place());
        processor.processSpecial(source,current,null);processor.processSpecial(source,current,null);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE source_id=?",Integer.class,source)).isEqualTo(1);
        assertThat(store.candidate(id)).containsEntry("status","REJECTED").containsEntry("decision_origin","MANUAL").containsEntry("source_url",address).containsEntry("geo_status","INSIDE");
        assertThat(store.jdbc().queryForObject("SELECT candidate_id FROM discovery_geo_events WHERE source_id=?",String.class,source)).isEqualTo(id);
        assertThat(store.candidates("NEEDS_REVIEW")).noneMatch(row->id.equals(row.get("id")));
    }

}
