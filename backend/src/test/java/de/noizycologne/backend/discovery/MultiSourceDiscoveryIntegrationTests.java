package de.noizycologne.backend.discovery;

import de.noizycologne.backend.party.*;
import de.noizycologne.backend.venue.*;
import de.noizycologne.backend.genre.GenreRepository;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import java.net.URI;
import java.util.*;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"noizy.discovery.enabled=false","noizy.discovery.monitor.enabled=false","logging.level.root=WARN"})
@ActiveProfiles("local")
class MultiSourceDiscoveryIntegrationTests {
    @Autowired DiscoveryStore store;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired DiscoveryScheduler scheduler;
    @Autowired DiscoveryController controller;
    @Autowired DiscoveryPortfolioService portfolio;
    @Autowired EventImportService importer;
    @Autowired DiscoveryEvaluationService evaluation;
    @Autowired GenreRepository genres;
    @Autowired VenueRepository venues;
    @Autowired PartySeriesRepository parties;
    @MockitoBean VenueSourceDiscoveryService discovery;
    Venue venue;
    final String root="https://www.mtc-cologne.de/",ticket="https://tickets.example/mtc",platform="https://platform.example/mtc";
    final Set<String> createdParties=new HashSet<>();
    @BeforeEach void setup() throws Exception {
        venue=new Venue("MTC Cologne","");venue.configureDiscovery(root,"",true);venue=venues.saveAndFlush(venue);
        when(discovery.discover(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),
            new EventExtractor.Extraction(List.of(),"BROWSER_PARTIAL",false),"BROWSER",List.of("ROBOTS_BLOCKED","JAVASCRIPT_ERROR"))));
        configure(ticket,"OFFICIAL_TICKETS",true,1);configure(platform,"PLATFORM",true,2);
    }
    private void configure(String url,String kind,boolean enabled,int priority) {
        controller.source(new DiscoveryController.Source(venue.getId(),url,true,"","","",kind,enabled,priority));
    }
    @AfterEach void cleanup() {
        store.jdbc().update("DELETE FROM calendar_entries WHERE location_id=?",venue.getId());venues.deleteById(venue.getId());
        for (String id:createdParties) parties.deleteById(requireNonNull(id));
    }
    private EventExtractor.@NonNull Event event(String id,String title,String source,String day,String evidence,String state) {
        return new EventExtractor.Event(id,title,source,List.of(requireNonNull(day)),evidence,state,"MTC Cologne");
    }
    private void source(String url,EventExtractor.@NonNull Event event) throws Exception {
        when(discovery.discover(eq(url),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(url),new EventExtractor.Extraction(List.of(event),"EVENTS_FOUND"))));
    }
    private void known() {
        var party=parties.saveAndFlush(new PartySeries("NEON PARADISE","","#E11D48","manual",null,null,null,null,Set.of(),Set.of()));createdParties.add(party.getId());
    }
    @Test void changingLineupsReuseOneSeriesPreserveOriginalCalendarTitlesAndManualAssignments() throws Exception {
        String firstTitle="BallroomBlitz! mit DER GRAUE KLAUS",secondTitle="BallroomBlitz! mit ROMAN EMPIRE";
        var first=event("blitz-1",firstTitle,ticket,"2026-12-04","Indie DJs","");
        var second=event("blitz-2",secondTitle,ticket,"2026-12-11","Indie DJs","");
        source(ticket,first);scheduler.scan();
        String firstId=store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE venue_id=? AND title=?",String.class,venue.getId(),firstTitle);
        var api=controller.candidate(firstId);
        assertThat(api.get("suggestedSeriesName")).isEqualTo("BallroomBlitz!");
        source(ticket,second);scheduler.scan();
        String secondId=store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE venue_id=? AND title=?",String.class,venue.getId(),secondTitle);
        String standard=requireNonNull(genres.findAllByActiveTrueOrderByCanonicalNameAsc().getFirst()).getDisplayName();
        String series=portfolio.approve(firstId,(String)api.get("suggestedSeriesName"),null,"23:00",Set.of(standard));createdParties.add(series);
        assertThat(portfolio.approve(secondId,secondTitle,null,"22:00",Set.of())).isEqualTo(series);
        assertThat(store.candidate(secondId)).containsEntry("party_id",series).containsEntry("decision_origin","MANUAL");
        assertThat(parties.findAll().stream().filter(p -> p.getName().startsWith("BallroomBlitz"))).hasSize(1);
        assertThat(requireNonNull(parties.findById(series).orElseThrow()).getStandardGenres()).containsExactly(standard);
        var coverage=(GenreCoverage.Result)controller.candidate(secondId).get("genreCoverage");
        assertThat(requireNonNull(coverage).standardNames()).contains(requireNonNull(standard));
        importer.confirm(firstId,series,java.time.LocalDate.of(2026,12,4),"NONE","",null);
        importer.confirm(secondId,series,java.time.LocalDate.of(2026,12,11),"NONE","",null);
        var entries=store.jdbc().queryForList("SELECT event_date,notes FROM calendar_entries WHERE location_id=? ORDER BY event_date",venue.getId());
        assertThat(entries).hasSize(2);
        assertThat(entries.getFirst()).containsEntry("event_date",java.sql.Date.valueOf("2026-12-04"));
        assertThat(Objects.toString(entries.getFirst().get("notes"))).contains(firstTitle);
        assertThat(Objects.toString(entries.getLast().get("notes"))).contains(secondTitle);
        store.jdbc().update("INSERT INTO party_series_aliases(party_id,alias) VALUES (?,?)",series,"Blitz Clubnacht");
        var third=event("blitz-3","Blitz Clubnacht mit WECHSELNDER GAST",ticket,"2026-12-18","Party","");
        source(ticket,third);scheduler.scan();scheduler.scan();
        var latest=store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE venue_id=? AND title=?",venue.getId(),third.title());
        assertThat(latest).containsEntry("party_id",series).containsEntry("status","IMPORTED");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isEqualTo(3);
        assertThat(store.candidate(firstId)).containsEntry("party_id",series).containsEntry("decision_origin","MANUAL").containsEntry("title",firstTitle);
        // A stale creation request must never replace an explicit assignment to another series.
        var other=parties.saveAndFlush(new PartySeries("Redaktionelle Zuordnung","","#E11D48","manual",null,null,null,null,Set.of(),Set.of()));createdParties.add(other.getId());
        store.jdbc().update("UPDATE discovery_candidates SET party_id=?,decision_origin='MANUAL' WHERE id=?",other.getId(),latest.get("id"));
        assertThatThrownBy(() -> portfolio.approve((String)latest.get("id"),"BallroomBlitz!")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(store.candidate((String)latest.get("id"))).containsEntry("party_id",other.getId());
    }
    @Test void mtcLikeBrowserFailureUsesConfiguredSourcesAndPreservesBothOriginsWithoutDuplicateCalendarOrAlerts() throws Exception {
        known();source(ticket,event("t1","NEON PARADISE",ticket,"2026-11-07","Party",""));
        source(platform,event("p1","NEON PARADISE",platform,"2026-11-07T23:00","Emo, Indie DJs",""));
        scheduler.scan();scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isEqualTo(1);
        var candidate=store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE venue_id=?",venue.getId());
        assertThat(requireNonNull(candidate.get("evidence")).toString()).contains("Emo","Indie");assertThat(candidate).containsEntry("status","IMPORTED");
        assertThat(store.origins((String)candidate.get("id"))).hasSize(2);
        assertThat(candidate.get("last_confirmed_at")).isNotNull();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM event_changes WHERE venue_id=?",Integer.class,venue.getId())).isZero();
        assertThat(store.jdbc().queryForObject("SELECT quality FROM discovery_sources WHERE venue_id=? AND url=?",String.class,venue.getId(),root)).isEqualTo("PARTIAL");
        assertThat(store.jdbc().queryForObject("SELECT fallback_used FROM discovery_sources WHERE venue_id=? AND url=?",Boolean.class,venue.getId(),ticket)).isTrue();
        assertThat(store.jdbc().queryForObject("SELECT discovery_quality FROM venues WHERE id=?",String.class,venue.getId())).isEqualTo("PARTIAL");
    }
    @Test void laterPrimaryFailureKeepsAnImportedOccurrenceWhenFallbackFactsAreUnchanged() throws Exception {
        known();source(root,event("main1","NEON PARADISE",root,"2026-11-07","Primary announcement",""));
        source(ticket,event("t1","NEON PARADISE",ticket,"2026-11-07","Party",""));
        source(platform,event("p1","NEON PARADISE",platform,"2026-11-07T23:00","Emo, Indie DJs",""));scheduler.scan();
        when(discovery.discover(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),
            new EventExtractor.Extraction(List.of(),"BROWSER_PARTIAL",false),"BROWSER",List.of("ROBOTS_BLOCKED"))));
        scheduler.scan();scheduler.scan();
        assertThat(store.jdbc().queryForMap("SELECT status FROM discovery_candidates WHERE venue_id=?",venue.getId())).containsEntry("status","IMPORTED");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM event_changes WHERE venue_id=?",Integer.class,venue.getId())).isZero();
    }
    @Test void conflictingFallbackDatesAreNotSilentlyOverwrittenAndManualRejectionIsPreserved() throws Exception {
        source(ticket,event("shared","New Indie Party",ticket,"2026-11-07","Indie",""));
        source(platform,event("shared","New Indie Party",platform,"2026-11-08","Emo",""));scheduler.scan();
        var c=store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE venue_id=?",venue.getId());
        assertThat(c).containsEntry("status","NEEDS_INQUIRY");
        controller.review((String)c.get("id"),new DiscoveryController.Review(null,null,"REJECTED","ANSWERED","Manuell geklärt"));
        source(platform,event("shared","New Indie Party",platform,"2026-11-07","Emo",""));scheduler.scan();
        assertThat(store.candidate((String)c.get("id"))).containsEntry("status","REJECTED").containsEntry("dm_text","Manuell geklärt");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
    }
    @Test void disabledSourcesStayUnusedAndOneFailedSourceDoesNotStopAnother() throws Exception {
        configure(platform,"PLATFORM",false,2);known();
        source(ticket,event("t1","NEON PARADISE",ticket,"2026-11-07","Emo",""));scheduler.scan();
        verify(discovery,never()).discover(eq(platform),any());
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isEqualTo(1);
    }
    @Test void manualPortfolioApprovalSavesSelectedStandardsAndWaitsForCalendarConfirmation() throws Exception {
        String evidence=String.join(", ",genres.findAllByActiveTrueOrderByCanonicalNameAsc().stream().map(g -> g.getDisplayName()).toList());
        var event=event("unknown1","New Alternative Party",ticket,"2026-11-07",evidence,"");
        source(ticket,event);configure(platform,"PLATFORM",false,2);scheduler.scan();
        var c=store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE venue_id=?",venue.getId());
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
        String id=portfolio.approve((String)c.get("id"),"New Alternative Party",null,"23:00",Set.of(requireNonNull(genres.findAllByActiveTrueOrderByCanonicalNameAsc().getFirst()).getDisplayName()));createdParties.add(id);
        assertThat(portfolio.approve((String)c.get("id"),"New Alternative Party")).isEqualTo(id);
        scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
        assertThat(requireNonNull(parties.findById(id).orElseThrow()).getStandardGenres()).containsExactly(requireNonNull(genres.findAllByActiveTrueOrderByCanonicalNameAsc().getFirst()).getDisplayName());
        assertThat(store.candidate((String)c.get("id"))).containsEntry("party_id",id).containsEntry("decision_origin","MANUAL");
    }
    private @NonNull MockHttpServletRequestBuilder postJson(String path,Map<String,?> body) {
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        return requireNonNull(MockMvcRequestBuilders.post(requireNonNull(path)).contentType("application/json").content(requireNonNull(json.writeValueAsString(body))));
    }
    @Test void manualPortfolioApiAllowsLowCoverageAndUndatedEventsButCannotImportWithoutDateOrLocation() throws Exception {
        configure(platform,"PLATFORM",false,2);
        var active=requireNonNull(genres.findAllByActiveTrueOrderByCanonicalNameAsc().getFirst()).getDisplayName();
        var unknown=new EventExtractor.Event("manual-genres","Neue manuelle Reihe",ticket,List.of(),"Genres: "+active+", Unbekannt A, Unbekannt B","","MTC Cologne");
        source(ticket,unknown);scheduler.scan();
        var candidate=store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE venue_id=?",venue.getId());
        String candidateId=Objects.toString(candidate.get("id"));
        assertThat(GenreCoverage.assess(unknown,evaluation.rules().coverageGenres()).percent()).isEqualTo(33);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(requireNonNull(context)).build();
        var body=Map.of("name","Neue manuelle Reihe","defaultStartTime","22:30","standardGenres",Set.of(active));
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        String response=mvc.perform(postJson("/api/v1/discovery/candidates/"+candidateId+"/portfolio",body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
        String id=requireNonNull(json.readTree(response).path("partyId").asString());createdParties.add(id);
        assertThat(requireNonNull(parties.findById(id).orElseThrow()).getStandardGenres()).containsExactly(active);
        assertThat(store.candidate(candidateId)).containsEntry("party_id",id).containsEntry("calendar_entry_id",null).containsEntry("event_date",null);
        mvc.perform(postJson("/api/v1/discovery/candidates/"+candidateId+"/confirm",Map.of("partyId",id,"inquiryStatus","NONE")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        store.jdbc().update("DELETE FROM discovery_candidate_identities WHERE candidate_id=?",candidateId);
        store.jdbc().update("UPDATE discovery_candidates SET venue_id=NULL,geo_status='INSIDE' WHERE id=?",candidateId);
        try {
            mvc.perform(postJson("/api/v1/discovery/candidates/"+candidateId+"/confirm",Map.of("partyId",id,"date","2026-11-07","inquiryStatus","NONE")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
            assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE party_id=?",Integer.class,id)).isZero();
        } finally {store.jdbc().update("UPDATE discovery_candidates SET venue_id=? WHERE id=?",venue.getId(),candidateId);}
    }
    @Test void manualPortfolioRejectsUnknownGenresWithoutCreatingOrAssigningASeries() throws Exception {
        configure(platform,"PLATFORM",false,2);
        source(ticket,event("invalid-genres","Manuelle Genreprüfung",ticket,"2026-11-07","Emo",""));scheduler.scan();
        var candidate=store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE venue_id=?",venue.getId());
        var failure=requireNonNull(assertThatThrownBy(()->portfolio.approve((String)candidate.get("id"),"Manuelle Genreprüfung",null,"22:00",Set.of("Nicht im Katalog"))));
        failure.isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        failure.hasMessageContaining("inaktives Standardgenre");
        assertThat(store.candidate((String)candidate.get("id"))).containsEntry("party_id",null);
        assertThat(parties.findAll().stream().filter(party->party.getName().equals("Manuelle Genreprüfung"))).isEmpty();
    }
    @Test void confirmedOfficialTicketCancellationUsesExistingChangeWorkflow() throws Exception {
        known();configure(platform,"PLATFORM",false,2);
        source(ticket,event("t1","NEON PARADISE",ticket,"2026-11-07","Emo",""));scheduler.scan();
        source(ticket,event("t1","NEON PARADISE",ticket,"2026-11-07","Abgesagt","CANCELLED"));scheduler.scan();scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM event_changes WHERE venue_id=? AND kind='CANCELLATION_DETECTED'",Integer.class,venue.getId())).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT event_status FROM calendar_entries WHERE location_id=?",String.class,venue.getId())).isEqualTo("ACTIVE");
    }
    @Test void separateAftershowRetainsRelativeStartWithoutInventingClockTime() throws Exception {
        var party=parties.saveAndFlush(new PartySeries("Indie Aftershowparty","","#E11D48","manual",null,null,null,null,Set.of(),Set.of()));createdParties.add(party.getId());
        configure(platform,"PLATFORM",false,2);
        source(ticket,event("after1","Indie Aftershowparty",ticket,"2026-11-07","Emo DJs; Beginn nach dem Konzert",""));scheduler.scan();
        var entry=store.jdbc().queryForMap("SELECT start_time,notes FROM calendar_entries WHERE location_id=?",venue.getId());
        assertThat(entry).containsEntry("start_time",null);assertThat(requireNonNull(entry.get("notes")).toString()).contains("Beginn: nach dem Konzert");
    }
}
