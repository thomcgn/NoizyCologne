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
import java.net.URI;
import java.util.*;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"noizy.discovery.enabled=false","noizy.discovery.monitor.enabled=false","logging.level.root=WARN"})
@ActiveProfiles("local")
class MultiSourceDiscoveryIntegrationTests {
    @Autowired DiscoveryStore store;
    @Autowired DiscoveryScheduler scheduler;
    @Autowired DiscoveryController controller;
    @Autowired DiscoveryPortfolioService portfolio;
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
    @Test void portfolioApprovalUsesActiveGenreDenominatorAndIsIdempotent() throws Exception {
        String evidence=String.join(", ",genres.findAllByActiveTrueOrderByCanonicalNameAsc().stream().map(g -> g.getDisplayName()).toList());
        var event=event("unknown1","New Alternative Party",ticket,"2026-11-07",evidence,"");
        source(ticket,event);configure(platform,"PLATFORM",false,2);scheduler.scan();
        var c=store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE venue_id=?",venue.getId());
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
        String id=portfolio.approve((String)c.get("id"),"New Alternative Party",null,"23:00");createdParties.add(id);
        assertThat(portfolio.approve((String)c.get("id"),"New Alternative Party")).isEqualTo(id);
        scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isEqualTo(1);
        assertThat(requireNonNull(parties.findById(id).orElseThrow()).getStandardGenres()).isNotEmpty();
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
