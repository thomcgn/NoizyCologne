package de.noizycologne.backend.discovery;

import de.noizycologne.backend.venue.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.URI;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static java.util.Objects.requireNonNull;

@SpringBootTest(properties={"noizy.discovery.enabled=false","noizy.discovery.monitor.enabled=false","logging.level.root=WARN"})
@ActiveProfiles("local")
class BrowserOnboardingIntegrationTests {
    @MockitoBean EventPageFetcher http;
    @MockitoBean BrowserPageRenderer browser;
    @Autowired DiscoveryOnboardingService onboarding;
    @Autowired VenueSourceDiscoveryService discovery;
    @Autowired DiscoveryEvaluationService evaluation;
    @Autowired EventCandidateProcessor processor;
    @Autowired EventImportService importer;
    @Autowired VenueRepository venues;
    @Autowired DiscoveryStore store;
    final URI root=URI.create("https://browser-fixture.example/");
    final URI programme=root.resolve("/about");
    Venue venue;
    @BeforeEach void setup() throws Exception {
        venue=venues.saveAndFlush(new Venue("Browser fixture",""));
        when(http.robotsAllowed(any(),any())).thenReturn(true);
        when(http.fetch(root)).thenReturn(new EventPageFetcher.Page(root,"<div id='root'></div><script src='/app.js'></script><a href='/about'>Party</a>","text/html"));
        when(http.fetch(programme)).thenReturn(new EventPageFetcher.Page(programme,"<div id='root'></div><script src='/app.js'></script>","text/html"));
        when(browser.render(programme)).thenReturn(new BrowserPageRenderer.Result("BROWSER_PARTIAL",List.of(new EventPageFetcher.Page(programme,
            "<section><h2>November 2026</h2><article><h3>DARK UNICORN by DJ Martin Heiland</h3><p class='date'>14th Nov | 23:00</p><p>Genres: Dark Electro, EBM, New Wave, Synth, Wave, Post-Punk</p></article></section>","text/html")),List.of("ASYNC_CONTENT_UNSETTLED"),false));
    }
    @AfterEach void cleanup() {
        store.jdbc().update("DELETE FROM calendar_entries WHERE location_id=?",venue.getId());venues.deleteById(venue.getId());
    }
    @Test void locationPreviewUsesTheSameHttpThenBrowserPipelineAndReportsPartialEvidenceWithoutImport() throws Exception {
        var report=onboarding.preview(root.toString());
        assertThat(report.eventCount()).isEqualTo(1);assertThat(report.incomplete()).isTrue();assertThat(report.checkedAt()).isNotBlank();
        assertThat(report.sources()).anySatisfy(source->{
            assertThat(source.fetchMethod()).isEqualTo("BROWSER");assertThat(source.extraction().status()).isEqualTo("BROWSER_PARTIAL");
            var event=requireNonNull(source.extraction().events().getFirst());
            assertThat(event.title()).isEqualTo("DARK UNICORN by DJ Martin Heiland");assertThat(event.starts()).containsExactly("2026-11-14T23:00");
        });
        assertThat(report.problems()).contains("ASYNC_CONTENT_UNSETTLED","BROWSER_PARTIAL");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates",Integer.class)).isZero();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries",Integer.class)).isZero();
        verify(browser,never()).render(root);
    }
    @Test void repeatedDiscoveryPreservesManualRejectionAndSourceDiagnosticsWithoutCalendarImport() throws Exception {
        String source=store.source(venue.getId(),programme.toString(),"OFFICIAL",false);
        for(int run=0;run<3;run++) {
            var rules=evaluation.rules();
            for(var found:discovery.discover(root.toString(),EventExtractor.Profile.defaults())) if(found.url().equals(programme)) {
                store.fetched(source,found);
                for(var event:found.extraction().events()) processor.process(venue,source,event,rules.parties(),rules.aliases(),new ArrayList<>(rules.genres()),false);
            }
            String id=store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId());
            if(run==0)importer.reject(requireNonNull(id));
            assertThat(store.candidate(requireNonNull(id))).containsEntry("status","REJECTED").containsEntry("decision_origin","MANUAL");
        }
        assertThat(store.jdbc().queryForMap("SELECT fetch_method,status,scan_complete FROM discovery_sources WHERE id=?",source))
            .containsEntry("fetch_method","BROWSER").containsEntry("status","BROWSER_PARTIAL").containsEntry("scan_complete",false);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE venue_id=?",Integer.class,venue.getId())).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
    }
}
