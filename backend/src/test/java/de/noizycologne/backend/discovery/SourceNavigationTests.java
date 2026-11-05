package de.noizycologne.backend.discovery;

import org.junit.jupiter.api.Test;
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
    private void page(URI uri,String html) throws Exception {
        when(fetcher.fetch(uri)).thenReturn(new EventPageFetcher.Page(uri,html,"text/html"));
    }
    private String widget(String type,String id) {
        return "<h1>Programm</h1><p>"+"Öffentliche Kontaktinformationen und Navigation. ".repeat(12)+"</p>"
                +"<div id='widget'></div><script id='wix-warmup-data' type='application/json'>{\"pages\":{\"compIdToTypeMap\":{\""+id+"\":\""+type+"\"}}}</script>";
    }
    @Test void mtcStyleLiveAndPartyLabelsLeadToArbitraryPathsAndDiagnoseWidgets() throws Exception {
        var service=service(); URI live=root.resolve("/live-shows"),party=root.resolve("/about");
        page(root,"<link rel='alternate' hreflang='en' href='/live-shows?lang=en'><a href='/live-shows'>LIVE</a><a href='/about'>PARTY</a>");
        page(live,widget("HtmlComponent","widget")); page(party,widget("HtmlComponent","widget"));
        var found=service.discover(root.toString(),EventExtractor.Profile.defaults());
        assertThat(found).hasSize(3);
        assertThat(found.get(0).extraction().status()).isEqualTo("SOURCE_LINKS_FOUND");
        assertThat(found.stream().skip(1).map(f -> f.extraction().status())).containsOnly("JAVASCRIPT_REQUIRED");
        verify(fetcher).fetch(live);verify(fetcher).fetch(party);
        verify(fetcher,never()).fetch(root.resolve("/live-shows?lang=en"));
    }
    @Test void usefulNavigationAfterFortyUnrelatedLinksAndAccessibleImageLabelIsFound() throws Exception {
        var service=service();StringBuilder html=new StringBuilder();
        for(int i=0;i<45;i++) html.append("<a href='/unrelated/").append(i).append("'>Home</a>");
        html.append("<a href='/about' aria-label='PARTY'><img alt=''></a>");
        page(root,html.toString());page(root.resolve("/about"),"<p>Keine Veranstaltungen</p>");
        var found=service.discover(root.toString(),EventExtractor.Profile.defaults());
        assertThat(found).hasSize(2); verify(fetcher,times(2)).fetch(any());
    }
    @Test void programmeSectionsAreVisitedBeforeEventDetailBudgetIsSpent() throws Exception {
        var service=service(); URI live=root.resolve("/live-shows"),party=root.resolve("/about");
        page(root,"<a href='/live-shows'>LIVE</a><a href='/about'>PARTY</a>");
        StringBuilder html=new StringBuilder();
        for(int i=0;i<10;i++) {
            html.append("<article><h2>Indie Party ").append(i).append("</h2><a href='/night/").append(i).append("'>Details</a></article>");
            page(root.resolve("/night/"+i),"<h1>Indie Party "+i+"</h1><time datetime='2027-01-09'>Samstag</time>");
        }
        page(live,html.toString());page(party,"<p>Keine Veranstaltungen</p>");
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
}
