package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrowserDiscoveryTests {
    private final EventPageFetcher http=mock(EventPageFetcher.class);
    private final BrowserPageRenderer browser=mock(BrowserPageRenderer.class);
    private final DiscoveryGenreFilter genres=mock(DiscoveryGenreFilter.class);
    private final URI root=URI.create("https://new.example/");
    private VenueSourceDiscoveryService service(@NonNull String body) throws Exception {
        when(http.robotsAllowed(any(),any())).thenReturn(true);
        when(http.fetch(root)).thenReturn(new EventPageFetcher.Page(root,body,"text/html"));
        when(genres.terms()).thenReturn(Set.of("Emo"));
        return new VenueSourceDiscoveryService(http,new EventExtractor(),genres,null,null,browser);
    }
    @Test void staticJsonLdAndHtmlNeverStartABrowser() throws Exception {
        for(@NonNull String body:List.<@NonNull String>of(
            "<article><h2>Emo Party</h2><time datetime='2026-11-07'></time></article><script src='/bundle.js'></script>",
            "<script type='application/ld+json'>{\"@type\":\"Event\",\"name\":\"Emo Party\",\"startDate\":\"2026-11-07\"}</script><div id='root'></div>")) {
            assertThat(requireNonNull(service(body).discover(root.toString(),EventExtractor.Profile.defaults()).getFirst()).fetchMethod()).isEqualTo("HTTP");
        }
        verifyNoInteractions(browser);
    }
    @Test void blockedRobotsNeverTriggerBrowserBypass() throws Exception {
        var service=service("<div id='root'></div><script src='/bundle.js'></script>");
        when(http.robotsAllowed(any(),any())).thenReturn(false);
        assertThat(requireNonNull(service.discover(root.toString(),EventExtractor.Profile.defaults()).getFirst()).extraction().status()).isEqualTo("ROBOTS_BLOCKED");
        verifyNoInteractions(browser);verify(http,never()).fetch(any());
    }
    @Test void renderedArbitraryCardsUseTheSameExtractorAndNeverProveCalendarCompleteness() throws Exception {
        var service=service("<div id='root'></div><script src='/bundle.js'></script>");
        when(browser.render(root)).thenReturn(new BrowserPageRenderer.Result("RENDERED",List.of(new EventPageFetcher.Page(root,
            "<div class='unrelated-layout'><h2>Emo Night Special</h2><p>November 7, 2026 23:00</p></div>","text/html")),List.of(),true));
        var result=requireNonNull(service.discover(root.toString(),EventExtractor.Profile.defaults()).getFirst());
        assertThat(result.fetchMethod()).isEqualTo("BROWSER");assertThat(result.extraction().status()).isEqualTo("EVENTS_FOUND");
        assertThat(result.extraction().complete()).isFalse();
        assertThat(requireNonNull(result.extraction().events().getFirst()).starts()).containsExactly("2026-11-07T23:00");
    }
    @Test void renderFailureIsNeverReportedAsNoEvents() throws Exception {
        var service=service("<div id='app'></div><script src='/app.js'></script>");
        for(String status:List.of("BROWSER_TIMEOUT","BROWSER_FAILED","BLOCKED","BROWSER_BUSY")) {
            when(browser.render(root)).thenReturn(new BrowserPageRenderer.Result(status,List.of(),List.of(requireNonNull(status)),false));
            var result=requireNonNull(service.discover(root.toString(),EventExtractor.Profile.defaults()).getFirst());
            assertThat(result.extraction().status()).isEqualTo(status);assertThat(result.extraction().complete()).isFalse();
            assertThat(result.fetchMethod()).isEqualTo("BROWSER");
        }
    }
    @Test void differentCardLayoutsAndEnglishGermanIsoDatesNeedNoSelectors() {
        for(@NonNull String date:List.<@NonNull String>of("07. November 2026 23:00","November 7, 2026 23:00","7 November 2026 23:00","2026-11-07T23:00")) {
            var page=new EventPageFetcher.Page(root,"<main><div class='anything'><h3>Emo Night</h3><span>"+date+"</span></div></main>","text/html");
            var extraction=new EventExtractor().extract(page,EventExtractor.Profile.defaults());
            assertThat(extraction.events()).hasSize(1);assertThat(requireNonNull(extraction.events().getFirst()).starts()).containsExactly("2026-11-07T23:00");
        }
        var footer=new EventPageFetcher.Page(root,"<main><h2>Emo Night</h2><footer>Updated November 7, 2026</footer></main>","text/html");
        assertThat(new EventExtractor().extract(footer,EventExtractor.Profile.defaults()).events()).isEmpty();
    }
    @Test void internalClientAuthenticatesAndCannotTrustWorkerCompleteness() throws Exception {
        var observed=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/render",exchange -> {
            observed.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            byte[] body="{\"status\":\"RENDERED\",\"pages\":[{\"url\":\"https://new.example/\",\"body\":\"<h1>Party</h1>\",\"contentType\":\"text/html\"}],\"warnings\":[],\"complete\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try {
            String token="isolated-fixture-browser-token-123456789";
            var client=new BrowserPageRenderer(true,"http://127.0.0.1:"+server.getAddress().getPort()+"/render",token);
            var result=client.render(URI.create("https://new.example"));
            assertThat(observed.get()).isEqualTo("Bearer "+token);assertThat(result.complete()).isFalse();
            assertThat(requireNonNull(result.pages().getFirst()).url()).isEqualTo(URI.create("https://new.example"));
        } finally { server.stop(0); }
    }
}
