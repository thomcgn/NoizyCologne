package de.noizycologne.backend.discovery;

import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NonNull;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventCardDiscoveryTests {
    final URI root=URI.create("https://cards.example/club");
    final EventPageFetcher http=mock(EventPageFetcher.class);
    final BrowserPageRenderer browser=mock(BrowserPageRenderer.class);
    final DiscoveryGenreFilter genres=mock(DiscoveryGenreFilter.class);
    static @NonNull String fixture() throws Exception {
        try(var input=requireNonNull(EventCardDiscoveryTests.class.getResourceAsStream("/discovery/garagen-november-2026.html"))) {
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    void page(URI url,@NonNull String body) throws Exception {when(http.fetch(url)).thenReturn(new EventPageFetcher.Page(url,body,"text/html"));}
    VenueSourceDiscoveryService service() throws Exception {
        when(http.robotsAllowed(any(),any())).thenReturn(true);when(genres.terms()).thenReturn(Set.of("Indie"));
        return new VenueSourceDiscoveryService(http,new EventExtractor(),genres,null,null,browser);
    }
    @Test void hiddenDomIsReadWithoutInteractionAndDoorTimeIsNotPerformanceStart() throws Exception {
        var extraction=new EventExtractor().extract(new EventPageFetcher.Page(root,fixture(),"text/html"),EventExtractor.Profile.defaults());
        assertThat(extraction.events()).hasSize(4);
        var concert=requireNonNull(extraction.events().getFirst());
        assertThat(concert.title()).isEqualTo("NO CIGAR");assertThat(concert.starts()).containsExactly("2026-11-12T20:00");
        assertThat(concert.evidence()).contains("Einlass: 19:00", "Beginn: 20:00");assertThat(PartyOnlyPolicy.concert(concert)).isTrue();
        assertThat(concert.url()).isEqualTo(root.resolve("/events/no-cigar").toString());
        assertThat(extraction.complete()).isFalse();
    }
    @Test void datedOverviewStillRendersAndUnknownEventsReceiveDetailEnrichmentBeforeFiltering() throws Exception {
        var service=service();page(root,fixture());
        String expanded=fixture().replace("<div id=\"pulz-description\"></div>","<div id='pulz-description'><p>Beschreibung dynamisch geladen.</p></div>");
        when(browser.render(root)).thenReturn(new BrowserPageRenderer.Result("BROWSER_PARTIAL",List.of(new EventPageFetcher.Page(root,requireNonNull(expanded),"text/html")),List.of("PAGINATION_LIMIT","COLLAPSIBLE_ELEMENTS_OPENED: 1"),false));
        page(root.resolve("/events/no-cigar"),"<h1>NO CIGAR</h1><p>Konzert · Einlass: 19:00 Uhr · Beginn: 20:00 Uhr</p>");
        page(root.resolve("/events/beats"),"<h1>BEATSxBASSxWEEKEND SPECIAL</h1><p>Genres: Hip-Hop</p>");
        page(root.resolve("/events/pulz"),"<h1>PULZ. x lieb</h1><time datetime='2026-11-14T23:00'></time><p>Genres: Techno</p>");
        page(root.resolve("/events/karneval"),"<h1>11.11. KARNEVAL</h1><p>Feiern am 11.11.</p>");
        var sources=service.discover(root.toString(),EventExtractor.Profile.defaults());
        verify(browser).render(root);verify(http).fetch(root.resolve("/events/beats"));
        var listing=requireNonNull(sources.getFirst());assertThat(listing.extraction().events()).hasSize(4);
        assertThat(listing.diagnostics()).contains("PAGINATION_LIMIT","EVENT_CARDS_RECOGNIZED: 4","DETAIL_PAGES_READ: 4");
        assertThat(listing.extraction().events()).anySatisfy(event->{
            assertThat(event.title()).isEqualTo("BEATSxBASSxWEEKEND SPECIAL");
            assertThat(event.evidence()).contains("Genres: Hip-Hop");assertThat(event.starts()).containsExactly("2026-11-13T23:00");
            assertThat(GenreCoverage.assess(event,List.of(new GenreCoverage.Genre("indie","Indie",Set.of("Indie")))) .sourceNames()).containsExactly("Hip-Hop");
        });
        assertThat(listing.extraction().events()).anySatisfy(event->{assertThat(event.title()).isEqualTo("PULZ. x lieb");assertThat(event.evidence()).contains("dynamisch geladen","Techno");});
        assertThat(sources).allSatisfy(source->assertThat(source.extraction().complete()).isFalse());
    }
    @Test void staticPaginationIsBoundedAndDoesNotTurnFirstEventIntoCompletenessProof() throws Exception {
        var service=service();
        for(int index=0;index<4;index++) {
            URI url=index==0?root:root.resolve("/club?page="+index);
            page(url,"<article><h2>Ausgabe "+index+"</h2><time datetime='2026-11-14T23:00'></time></article><a rel='next' href='/club?page="+(index+1)+"'>Weiter</a>");
        }
        var sources=service.discover(root.toString(),EventExtractor.Profile.defaults());
        assertThat(sources).hasSize(3);verify(http,never()).fetch(root.resolve("/club?page=3"));
        assertThat(sources).allSatisfy(source->assertThat(source.extraction().complete()).isFalse());
    }
    @Test void duplicateRepresentationsRetainDeclaredUnknownGenresAndConflictingDates() {
        String json="<script type='application/ld+json'>{\"@type\":\"Event\",\"@id\":\"same\",\"name\":\"Separate Party\",\"genre\":\"Hip-Hop\",\"url\":\"/events/same\",\"startDate\":\"2026-11-14T23:00\"}</script>";
        String card="<article data-event-id='same'><h2>Separate Party</h2><time datetime='2026-11-15T23:00'></time><a href='/events/same'>Details</a></article>";
        var events=new EventExtractor().extract(new EventPageFetcher.Page(root,json+card+card,"text/html"),EventExtractor.Profile.defaults()).events();
        assertThat(events).hasSize(1);var event=requireNonNull(events.getFirst());
        assertThat(event.starts()).containsExactly("2026-11-14T23:00","2026-11-15T23:00");assertThat(event.state()).contains("SOURCE_CONFLICT");
        assertThat(GenreCoverage.assess(event,List.of()).sourceNames()).containsExactly("Hip-Hop");
    }
    @Test void availableHiddenDescriptionsAndNavigationMenusDoNotRequireBrowser() {
        var page=new EventPageFetcher.Page(root,"<nav><button aria-expanded='false' aria-controls='menu'>Menu</button></nav><article><h2>Party</h2><time datetime='2026-11-14T23:00'></time><button aria-expanded='false' aria-controls='description'>Info</button><p id='description' hidden>Genres: Emo</p></article>","text/html");
        var extractor=new EventExtractor();
        assertThat(extractor.needsRendering(page,extractor.extract(page,EventExtractor.Profile.defaults()))).isFalse();
    }
    @Test void concertAndIndependentlyTitledAftershowRemainSeparateAndConflictingTimesRemainVisible() {
        String body="<main><article><h2>NO CIGAR</h2><time datetime='2026-11-12T21:00'></time><p>Konzert. Einlass: 19:00 · Beginn: 20:00</p></article><article><h2>Aftershow Party</h2><time datetime='2026-11-12T23:00'></time><p>Genres: Emo</p></article></main>";
        var events=new EventExtractor().extract(new EventPageFetcher.Page(root,body,"text/html"),EventExtractor.Profile.defaults()).events();
        assertThat(events).hasSize(2);var concert=requireNonNull(events.getFirst());
        assertThat(concert.starts()).containsExactly("2026-11-12T21:00","2026-11-12T20:00");
        assertThat(EventVerification.assess(concert,"").conflicting()).isTrue();
        assertThat(PartyOnlyPolicy.concert(requireNonNull(events.getLast()))).isFalse();
    }

}
