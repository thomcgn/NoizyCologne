package de.noizycologne.backend.discovery;

import static java.util.Objects.requireNonNull;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NonNull;
import java.net.URI;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceNavigationTests {
    private final EventPageFetcher fetcher=mock(EventPageFetcher.class);
    private final DiscoveryGenreFilter genres=mock(DiscoveryGenreFilter.class);
    private final EventExtractor extractor=new EventExtractor();
    private final URI root=URI.create("https://new.example/");
    private VenueSourceDiscoveryService service() throws Exception {
        when(genres.terms()).thenReturn(Set.of("Indie"));
        when(fetcher.robotsAllowed(any(),any())).thenReturn(true);
        return new VenueSourceDiscoveryService(fetcher,extractor,genres);
    }
    private void page(URI uri,@NonNull String html) throws Exception {
        when(fetcher.fetch(uri)).thenReturn(new EventPageFetcher.Page(uri,html,"text/html"));
    }
    private @NonNull String widget(String type,String id) {
        return "<h1>Programm</h1><p>"+"Öffentliche Kontaktinformationen und Navigation. ".repeat(12)+"</p>"
                +"<div id='widget'></div><script id='wix-warmup-data' type='application/json'>{\"pages\":{\"compIdToTypeMap\":{\""+id+"\":\""+type+"\"}}}</script>";
    }
    @Test void partyNavigationFindsArbitraryPathAndSkipsConcertNavigation() throws Exception {
        var service=service(); URI live=root.resolve("/live-shows"),party=root.resolve("/about");
        page(root,"<link rel='alternate' hreflang='en' href='/live-shows?lang=en'><a href='/live-shows'>LIVE</a><a href='/about'>PARTY</a>");
        page(live,widget("HtmlComponent","widget")); page(party,widget("HtmlComponent","widget"));
        var found=service.discover(root.toString(),EventExtractor.Profile.defaults());
        assertThat(found).hasSize(2);
        assertThat(requireNonNull(found.get(0)).extraction().status()).isEqualTo("SOURCE_LINKS_FOUND");
        assertThat(found.stream().skip(1).map(f -> f.extraction().status())).containsOnly("JAVASCRIPT_REQUIRED");
        verify(fetcher,never()).fetch(live);verify(fetcher).fetch(party);
        verify(fetcher,never()).fetch(root.resolve("/live-shows?lang=en"));
    }
    @Test void usefulNavigationAfterFortyUnrelatedLinksAndAccessibleImageLabelIsFound() throws Exception {
        var service=service();StringBuilder html=new StringBuilder();
        for(int i=0;i<45;i++) html.append("<a href='/unrelated/").append(i).append("'>Home</a>");
        html.append("<a href='/about' aria-label='PARTY'><img alt=''></a>");
        page(root,requireNonNull(html.toString()));page(root.resolve("/about"),"<p>Keine Veranstaltungen</p>");
        var found=service.discover(root.toString(),EventExtractor.Profile.defaults());
        assertThat(found).hasSize(2); verify(fetcher,times(2)).fetch(any());
    }
    @Test void programmeSectionsAreVisitedBeforeEventDetailBudgetIsSpent() throws Exception {
        var service=service(); URI live=root.resolve("/programme"),party=root.resolve("/about");
        page(root,"<a href='/programme'>EVENTS</a><a href='/about'>PARTY</a>");
        StringBuilder html=new StringBuilder();
        for(int i=0;i<10;i++) {
            html.append("<article><h2>Indie Party ").append(i).append("</h2><a href='/night/").append(i).append("'>Details</a></article>");
            page(root.resolve("/night/"+i),"<h1>Indie Party "+i+"</h1><time datetime='2027-01-09'>Samstag</time>");
        }
        page(live,requireNonNull(html.toString()));page(party,"<p>Keine Veranstaltungen</p>");
        var found=service.discover(root.toString(),EventExtractor.Profile.defaults());
        assertThat(found).hasSize(8);
        var order=inOrder(fetcher);order.verify(fetcher).fetch(root);order.verify(fetcher).fetch(live);order.verify(fetcher).fetch(party);
    }
    @Test void globalComponentCataloguesAndDifferentDomComponentsDoNotImplyRendering() {
        for(String html:List.of(widget("HtmlComponent","absent"),widget("Text","widget"))) {
            var result=extractor.extract(new EventPageFetcher.Page(root,html,"text/html"),EventExtractor.Profile.defaults());
            assertThat(result.status()).isEqualTo("UNINTERPRETABLE");
        }
    }
    @Test void realEventsTakePrecedenceOverEmptyWidgets() {
        String html=widget("HtmlComponent","widget")+"<article><h2>Indie Party</h2><time datetime='2027-01-09'>Samstag</time></article>";
        assertThat(extractor.extract(new EventPageFetcher.Page(root,html,"text/html"),EventExtractor.Profile.defaults()).status()).isEqualTo("EVENTS_FOUND");
    }
    @Test void foreignPartyLinksAndDepthBeyondLimitAreNotFollowed() throws Exception {
        var service=service();
        page(root,"<a href='/level1'>PARTY</a><a href='https://other.example/about'>PARTY</a>");
        page(root.resolve("/level1"),"<a href='/level2'>PROGRAMM</a>");
        page(root.resolve("/level2"),"<a href='/level3'>PARTY</a>");
        assertThat(service.discover(root.toString(),EventExtractor.Profile.defaults())).hasSize(3);
        verify(fetcher,never()).fetch(root.resolve("/level3"));
        verify(fetcher,never()).fetch(URI.create("https://other.example/about"));
    }
    @Test void independentPortalFollowsPublicEventCardsWithoutInventingOverviewDatesOrVenues() throws Exception {
        var service=service();var detail=root.resolve("/eventfinder/koeln/events/indie-night");
        page(root,"<a title='Event: Indie Night' href='/eventfinder/koeln/events/indie-night'><div>SA, 09 JAN</div><div>Indie Night</div></a><a title='Event: Fremdes Event' href='https://other.example/events/one'>Fremd</a><a title='Event: Konzert' href='/konzerte/live-band'>Konzert</a>");
        page(detail,"<script type='application/ld+json'>{\"@type\":\"Event\",\"name\":\"Indie Night\",\"startDate\":\"2027-01-09T22:00:00+01:00\",\"location\":{\"@type\":\"Place\",\"name\":\"Blue Shell\"}}</script>");
        var found=service.discoverSpecial(root.toString(),EventExtractor.Profile.defaults());
        assertThat(found).hasSize(2);
        assertThat(requireNonNull(found.getFirst()).extraction().status()).isEqualTo("SOURCE_LINKS_FOUND");
        var event=requireNonNull(requireNonNull(found.getLast()).extraction().events().getFirst());
        assertThat(event.location()).isEqualTo("Blue Shell");assertThat(event.starts()).containsExactly("2027-01-09T22:00:00+01:00");
        verify(fetcher,never()).fetch(URI.create("https://other.example/events/one"));
        verify(fetcher,never()).fetch(root.resolve("/konzerte/live-band"));
    }
    @Test void independentPortalEventLinksRespectTheExistingPageBudgetAndRobotsChecks() throws Exception {
        var service=service();StringBuilder html=new StringBuilder();
        for(int i=0;i<20;i++) {
            html.append("<a href='/events/").append(i).append("'>Öffentliche Veranstaltung</a>");
            page(root.resolve("/events/"+i),"<h1>Indie Party</h1><time datetime='2027-01-09'>Samstag</time>");
        }
        page(root,requireNonNull(html.toString()));
        var blocked=root.resolve("/events/0");when(fetcher.robotsAllowed(eq(blocked),any())).thenReturn(false);
        assertThat(service.discoverSpecial(root.toString(),EventExtractor.Profile.defaults())).hasSize(8);
        verify(fetcher,never()).fetch(blocked);verify(fetcher,times(8)).robotsAllowed(any(),any());
    }

}
