package de.noizycologne.backend.discovery;

import de.noizycologne.backend.party.*;
import de.noizycologne.backend.venue.*;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"noizy.discovery.enabled=false","noizy.discovery.monitor.enabled=false","logging.level.root=WARN"})
@ActiveProfiles("local")
class DiscoveryAssessmentIntegrationTests {
    @Autowired DiscoveryStore store;
    @Autowired DiscoveryScheduler scheduler;
    @Autowired DiscoveryController controller;
    @Autowired DiscoveryOnboardingService onboarding;
    @Autowired EventImportService importer;
    @Autowired VenueRepository venues;
    @Autowired PartySeriesRepository parties;
    @MockitoBean VenueSourceDiscoveryService discovery;
    Venue venue;
    PartySeries paradise;
    PartySeries kult;
    final String root="https://assessment.example/";
    final LocalDate day=LocalDate.of(2026,11,7);
    @BeforeEach void setup() {
        venue=new Venue("Blue Shell","");venue.configureDiscovery(root,"",true);venue=venues.saveAndFlush(venue);
        paradise=parties.saveAndFlush(new PartySeries("NEON PARADISE","","#E11D48","manual",null,null,null,null,Set.of(),Set.of()));
        kult=parties.saveAndFlush(new PartySeries("NEON KULT","","#E11D48","manual",null,null,null,null,Set.of(),Set.of()));
    }
    @AfterEach void cleanup() {
        store.jdbc().update("DELETE FROM calendar_entries WHERE location_id=?",venue.getId());
        venues.deleteById(venue.getId());parties.deleteById(paradise.getId());parties.deleteById(kult.getId());
    }
    private EventExtractor.@NonNull Event event(String title,List<@NonNull String> starts,String state) {
        return new EventExtractor.Event("assessment-occurrence",title,root+"events/neon",starts,"Indie DJs",state,"Blue Shell");
    }
    private void scan(EventExtractor.@NonNull Event event) throws Exception {
        when(discovery.discover(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(List.of(event),"EVENTS_FOUND"))));
        scheduler.scan();
    }
    private Map<String,Object> candidate() { return store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE venue_id=?",venue.getId()); }
    private int calendarCount() { return requireNonNull(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())); }
    @Test void cooperationSurvivesPersistenceAndApiAndRequiresManualSingleAssignment() throws Exception {
        scan(event("NEON PARADISE VS. NEON KULT VOL: 2 THE REVENGE",List.of(requireNonNull(day.toString())),"UNCONFIRMED"));
        var candidate=candidate();
        assertThat(candidate).containsEntry("status","NEEDS_REVIEW").containsEntry("party_id",null);
        assertThat(calendarCount()).isZero();
        var api=controller.candidate((String)candidate.get("id"));
        var assessment=(DiscoveryEvaluationService.Assessment)api.get("assessment");
        assertThat(assessment.recognition().status()).isEqualTo("COOPERATION");
        assertThat(assessment.recognition().parties()).extracting(PartySeriesRecognition.DetectedParty::id).containsExactly(paradise.getId(),kult.getId());
        assertThat(assessment.verification().date()).isEqualTo(day);assertThat(assessment.automaticImportEligible()).isFalse();
        importer.review((String)candidate.get("id"),kult.getId(),day,"NEEDS_REVIEW","NONE","");
        importer.importCandidate((String)candidate.get("id"),kult.getId(),day,true);
        scan(event("NEON PARADISE VS. NEON KULT VOL: 2 THE REVENGE",List.of(requireNonNull(day.toString())),"UNCONFIRMED"));
        assertThat(calendarCount()).isEqualTo(1);
        assertThat(candidate()).containsEntry("party_id",kult.getId()).containsEntry("decision_origin","MANUAL");
    }
    @Test void completeAliasWithUnconfirmedDetailImportsOnceAndReadyRemainsQueryable() throws Exception {
        store.jdbc().update("INSERT INTO party_series_aliases(party_id,alias) VALUES (?,?)",paradise.getId(),"NP Night");
        var event=event("Heute NP Night – Special Edition",List.of(requireNonNull(day.toString())),"UNCONFIRMED Detailseite nicht eindeutig auswertbar");
        scan(event);scan(event);
        assertThat(calendarCount()).isEqualTo(1);assertThat(candidate()).containsEntry("status","IMPORTED").containsEntry("party_id",paradise.getId());
        assertThat(store.candidates("READY")).isEmpty();
    }
    @Test void conflictingApprovedSourcesCannotImportBeforeTheWholeBatchIsCompared() throws Exception {
        var first=event("NEON PARADISE",List.of(requireNonNull(day.toString())),"");
        var other=event("NEON PARADISE",List.of(requireNonNull(day.plusDays(1).toString())),"");
        when(discovery.discover(eq(root),any())).thenReturn(List.of(
            new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(List.of(first),"EVENTS_FOUND")),
            new VenueSourceDiscoveryService.Found(URI.create(root+"programme"),new EventExtractor.Extraction(List.of(other),"EVENTS_FOUND"))));
        scheduler.scan();
        assertThat(calendarCount()).isZero();assertThat(candidate()).containsEntry("status","NEEDS_INQUIRY");
        var assessment=(DiscoveryEvaluationService.Assessment)controller.candidate((String)candidate().get("id")).get("assessment");
        assertThat(assessment.verification().status()).isEqualTo("CONFLICTING_DATA");
    }
    @Test void conflictingDatesRemainUnverifiedWhenOnlyOneSourceHasAStableIdentity() throws Exception {
        var first=event("NEON PARADISE",List.of(requireNonNull(day.toString())),"");
        var other=new EventExtractor.Event("","NEON PARADISE",root+"programme",List.of(requireNonNull(day.plusDays(1).toString())),"Indie DJs","","Blue Shell");
        when(discovery.discover(eq(root),any())).thenReturn(List.of(
            new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(List.of(first),"EVENTS_FOUND")),
            new VenueSourceDiscoveryService.Found(URI.create(root+"programme"),new EventExtractor.Extraction(List.of(other),"EVENTS_FOUND"))));
        scheduler.scan();scheduler.scan();
        assertThat(calendarCount()).isZero();
        assertThat(store.jdbc().queryForList("SELECT status FROM discovery_candidates WHERE venue_id=?",venue.getId()))
            .isNotEmpty().allSatisfy(row -> assertThat(row).containsEntry("status","NEEDS_INQUIRY"));
    }
    @Test void distinctStableRecurringOccurrencesOnDifferentDaysCanBothImport() throws Exception {
        var first=event("NEON PARADISE",List.of(requireNonNull(day.toString())),"");
        var other=new EventExtractor.Event("another-occurrence","NEON PARADISE",root+"events/next",List.of(requireNonNull(day.plusDays(7).toString())),"Indie DJs","","Blue Shell");
        when(discovery.discover(eq(root),any())).thenReturn(List.of(
            new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(List.of(first,other),"EVENTS_FOUND"))));
        scheduler.scan();scheduler.scan();
        assertThat(calendarCount()).isEqualTo(2);
    }
    @Test void rejectedAndCorrectedDecisionsStayProtectedAcrossChangedSourceScans() throws Exception {
        scan(event("Unknown Indie Night",List.of(requireNonNull(day.toString())),""));
        var id=(String)candidate().get("id");
        importer.review(id,kult.getId(),day.plusDays(1),"REJECTED","ANSWERED","Manuell bestätigt");
        scan(event("NEON PARADISE Special",List.of(requireNonNull(day.toString())),"UNCONFIRMED"));
        var saved=candidate();
        assertThat(saved).containsEntry("status","REJECTED").containsEntry("party_id",kult.getId()).containsEntry("decision_origin","MANUAL").containsEntry("dm_text","Manuell bestätigt");
        assertThat(saved.get("event_date").toString()).isEqualTo(day.plusDays(1).toString());assertThat(calendarCount()).isZero();
        assertThatThrownBy(() -> importer.importCandidate(id,paradise.getId(),day,false)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void explicitPostponementAndCancellationUseExistingChangeRecordsAndBlockImport() throws Exception {
        scan(event("NEON PARADISE",List.of(requireNonNull(day.toString())),""));
        var id=(String)candidate().get("id");
        scan(event("NEON PARADISE",List.of(requireNonNull(day.toString())),"POSTPONED"));
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM event_changes WHERE candidate_id=? AND kind='RESCHEDULE_DETECTED'",Integer.class,id)).isEqualTo(1);
        scan(event("NEON PARADISE",List.of(requireNonNull(day.toString())),"CANCELLED"));
        scan(event("NEON PARADISE",List.of(requireNonNull(day.toString())),"CANCELLED"));
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM event_changes WHERE candidate_id=? AND kind='CANCELLATION_DETECTED'",Integer.class,id)).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT event_status FROM calendar_entries WHERE location_id=?",String.class,venue.getId())).isEqualTo("ACTIVE");
        var assessment=(DiscoveryEvaluationService.Assessment)controller.candidate(id).get("assessment");
        assertThat(assessment.lifecycle()).isEqualTo("CANCELLED");assertThat(assessment.importBlockers()).isNotEmpty();
        assertThatThrownBy(() -> importer.importCandidate(id,paradise.getId(),day,true)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void failedRenderingNeverCreatesAbsenceOrCancellationEvidence() throws Exception {
        scan(event("NEON PARADISE",List.of(requireNonNull(day.toString())),""));
        String id=(String)candidate().get("id");
        when(discovery.discover(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),
            new EventExtractor.Extraction(List.of(),"BROWSER_TIMEOUT",false),"BROWSER",List.of("BROWSER_TIMEOUT"))));
        scheduler.scan();scheduler.scan();
        assertThat(calendarCount()).isEqualTo(1);assertThat(candidate()).containsEntry("status","IMPORTED");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM event_changes WHERE candidate_id=?",Integer.class,id)).isZero();
        assertThat(store.jdbc().queryForObject("SELECT event_status FROM calendar_entries WHERE location_id=?",String.class,venue.getId())).isEqualTo("ACTIVE");
    }
    @Test void onboardingSavesOnlyPreviewedSourcesAndRespectsRevokedApproval() throws Exception {
        var main=event("NEON PARADISE",List.of(requireNonNull(day.toString())),"");
        var external=event("NEON KULT",List.of(requireNonNull(day.plusDays(1).toString())),"");
        when(discovery.discover(eq(root),any())).thenReturn(List.of(
            new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(List.of(main),"EVENTS_FOUND"),"BROWSER",List.of()),
            new VenueSourceDiscoveryService.Found(URI.create("https://widget.example/calendar"),new EventExtractor.Extraction(List.of(external),"EVENTS_FOUND"),"BROWSER",List.of())));
        var report=onboarding.preview(root);
        assertThat(report.eventCount()).isEqualTo(2);assertThat(report.knownSeriesCount()).isEqualTo(2);
        assertThat(report.incomplete()).isTrue();assertThat(calendarCount()).isZero();
        store.source(venue.getId(),root,"OFFICIAL",false);
        onboarding.saveSources(venue.getId(),report.previewToken());
        onboarding.saveSources(venue.getId(),report.previewToken());
        var sources=store.jdbc().queryForList("SELECT * FROM discovery_sources WHERE venue_id=?",venue.getId());
        assertThat(sources).hasSize(2);assertThat(sources).allSatisfy(source -> {
            assertThat(source.get("approved")).isEqualTo(false);assertThat(source.get("fetch_method")).isEqualTo("BROWSER");
        });
        assertThatThrownBy(() -> onboarding.saveSources(venue.getId(),"unknown-preview")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        venue.configureDiscovery("https://changed.example/","",true);venues.saveAndFlush(venue);
        assertThatThrownBy(() -> onboarding.saveSources(venue.getId(),report.previewToken())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
