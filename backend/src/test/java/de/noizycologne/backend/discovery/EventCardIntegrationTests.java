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
class EventCardIntegrationTests {
    @MockitoBean EventPageFetcher http;
    @MockitoBean BrowserPageRenderer browser;
    @Autowired DiscoveryScheduler scheduler;
    @Autowired DiscoveryStore store;
    @Autowired DiscoveryKeywordService keywords;
    @Autowired EventImportService importer;
    @Autowired VenueRepository venues;
    Venue venue;String rule;
    final URI root=URI.create("https://cards.example/club");
    @BeforeEach void setup() throws Exception {
        venue=new Venue("Card fixture","");venue.configureDiscovery(root.toString(),"",true);venue=venues.saveAndFlush(venue);
        rule=keywords.save(null,"Karneval","Karneval-Testausschluss",true);
        when(http.robotsAllowed(any(),any())).thenReturn(true);
        when(http.fetch(root)).thenReturn(new EventPageFetcher.Page(root,EventCardDiscoveryTests.fixture(),"text/html"));
        when(browser.render(root)).thenReturn(new BrowserPageRenderer.Result("BROWSER_PARTIAL",List.of(),List.of("PAGINATION_LIMIT"),false));
        for(String slug:List.of("no-cigar","beats","pulz","karneval")) {
            String title=switch(slug){case "no-cigar"->"NO CIGAR";case "beats"->"BEATSxBASSxWEEKEND SPECIAL";case "pulz"->"PULZ. x lieb";default->"11.11. KARNEVAL";};
            // Deliberately synthetic detail genre declarations exercise enrichment and filters;
            // no claim that these genres were read from the real website.
            String description=switch(slug){case "no-cigar"->"Konzert. Einlass: 19:00 Uhr · Beginn: 20:00 Uhr";case "beats"->"Genres: Hip-Hop";case "pulz"->"Genres: Emo";default->"Karneval";};
            URI url=root.resolve("/events/"+slug);
            when(http.fetch(url)).thenReturn(new EventPageFetcher.Page(url,"<h1>"+title+"</h1><p>"+description+"</p>","text/html"));
        }
    }
    @AfterEach void cleanup() {
        store.jdbc().update("DELETE FROM calendar_entries WHERE location_id=?",venue.getId());venues.deleteById(venue.getId());
        store.jdbc().update("DELETE FROM discovery_keyword_rules WHERE id=?",rule);
    }
    Map<String,Object> candidate(String title) {return store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE venue_id=? AND title=?",venue.getId(),title);}
    @Test void repeatedEnrichedCrawlsArchiveWithReasonsAndProtectManualDecisionsWithoutImport() {
        for(int run=0;run<3;run++) {
            scheduler.scan();
            assertThat(candidate("NO CIGAR")).containsEntry("status","IGNORED");
            assertThat(candidate("NO CIGAR").get("start_time")).isEqualTo(java.sql.Time.valueOf("20:00:00"));
            assertThat(candidate("BEATSxBASSxWEEKEND SPECIAL")).containsEntry("status","IGNORED");
            assertThat(candidate("BEATSxBASSxWEEKEND SPECIAL").get("reason")).asString().contains("Hip-Hop");
            assertThat(candidate("11.11. KARNEVAL")).containsEntry("status","IGNORED");
            assertThat(candidate("11.11. KARNEVAL").get("reason")).asString().containsIgnoringCase("Karneval");
            if(run==0) importer.reject((String)candidate("PULZ. x lieb").get("id"));
            assertThat(candidate("PULZ. x lieb")).containsEntry("status","REJECTED").containsEntry("decision_origin","MANUAL");
            assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE venue_id=?",Integer.class,venue.getId())).isEqualTo(4);
            assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
        }
        String diagnostics=requireNonNull(store.jdbc().queryForObject("SELECT fetch_diagnostics FROM discovery_sources WHERE venue_id=? AND url=?",String.class,venue.getId(),root.toString()));
        assertThat(diagnostics).contains("EVENT_CARDS_RECOGNIZED: 4","FILTERED_EVENTS: 3","NO CIGAR","Hip-Hop","Karneval");
        assertThat(store.jdbc().queryForObject("SELECT fetch_diagnostics FROM discovery_sources WHERE venue_id=? AND url=?",String.class,venue.getId(),root.resolve("/events/beats").toString()))
            .contains("FILTERED_EVENTS: 1","Hip-Hop");
    }
}
