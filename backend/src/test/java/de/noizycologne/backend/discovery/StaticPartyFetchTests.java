package de.noizycologne.backend.discovery;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class StaticPartyFetchTests {
    private final URI root=URI.create("https://club.example/parties");
    private final EventExtractor extractor=new EventExtractor(java.time.Clock.fixed(java.time.Instant.parse("2026-11-06T12:00:00Z"),java.time.ZoneOffset.UTC));
    private @org.jspecify.annotations.NonNull String fixture(String name) throws IOException {
        try(var in=getClass().getResourceAsStream("/discovery/"+name)) {
            return new String(requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    private EventPageFetcher.Response response(int status,String location,String body) {
        return new EventPageFetcher.Response(status,location,"text/html; charset=UTF-8",body.getBytes(StandardCharsets.UTF_8));
    }
    @Test void publicIpv6HostingIsAllowedWithoutAllowingSpecialOrPrivateAddresses() throws Exception {
        assertThat(SafeUrls.isPublic(InetAddress.getByName("2001:8d8:100f:f000::241"))).isTrue();
        for(String address:List.of("2001::1","2001:20::1","2001:1ff:ffff::1","2001:db8::1","2002::1","3fff::1","::1","fc00::1","fe80::1"))
            requireNonNull(assertThat(SafeUrls.isPublic(InetAddress.getByName(address))).as(address)).isFalse();
    }
    @Test void sonicOverviewIsExtractedStaticallyWithSeparatePartyEvidenceAndRelativeStarts() throws Exception {
        var page=new EventPageFetcher.Page(URI.create("https://www.sonic-ballroom.de/index.php/parties"),fixture("sonic-ballroom-parties.html"),"text/html; charset=UTF-8");
        var result=extractor.extract(page,EventExtractor.Profile.defaults());
        assertThat(result.status()).isEqualTo("EVENTS_FOUND");
        assertThat(result.events()).hasSize(22);
        assertThat(extractor.needsRendering(page,result)).isFalse();
        var blitz=requireNonNull(result.events().stream().filter(e->e.title().equals("BallroomBlitz! mit ANJA HELLFIRE")).findFirst().orElseThrow());
        var rotten=requireNonNull(result.events().stream().filter(e->e.title().equals("ROTTEN FLOWERS vol.4")).findFirst().orElseThrow());
        var shake=requireNonNull(result.events().stream().filter(e->e.title().equals("Shake Appeal mit DANYELL")).findFirst().orElseThrow());
        assertThat(blitz.starts()).containsExactly("2026-11-06");
        assertThat(rotten.starts()).containsExactly("2026-11-07");
        assertThat(shake.starts()).containsExactly("2026-11-14");
        assertThat(blitz.evidence()).contains("PunkRockRollHitz");
        assertThat(blitz.evidence()).doesNotContain("ROTTEN FLOWERS");
        assertThat(rotten.evidence()).contains("Heavy-Psych");
        assertThat(rotten.evidence()).doesNotContain("Shake Appeal");
        assertThat(shake.evidence()).contains("Rockabilly, Powerpop, Ska");
        for(var event:List.of(blitz,rotten,shake)) {
            assertThat(PartyOnlyPolicy.concert(event)).isFalse();
            assertThat(EventTiming.relativeStart(event)).isEqualTo("nach dem Konzert");
            assertThat(event.starts()).noneMatch(start->EventNormalizer.time(start)!=null);
            assertThat(EventVerification.assess(event,"").sufficient()).isTrue();
            assertThat(event.url()).isEqualTo(page.url().toString());
        }
    }
    @Test void genericH1CardsKeepAftershowAndConcertEvidenceForSeparateEvaluation() {
        var page=new EventPageFetcher.Page(root,"<div><strong class='date'>09. Januar 2027</strong><h1>Rocknacht</h1><p>Nach dem Konzert, Eintritt frei!</p></div>"
            +"<div><strong class='date'>10. Januar 2027</strong><h1>Rockband</h1><p>Konzert und Party nach dem Konzert</p></div>","text/html");
        var result=extractor.extract(page,EventExtractor.Profile.defaults());
        assertThat(result.events()).hasSize(2);
        assertThat(requireNonNull(result.events().getFirst()).starts()).containsExactly("2027-01-09");
        assertThat(PartyOnlyPolicy.concert(requireNonNull(result.events().getFirst()))).isFalse();
        assertThat(PartyOnlyPolicy.concert(requireNonNull(result.events().getLast()))).isTrue();
    }
    @Test void redirectsCheckDestinationRobotsAndExposeFinalUrl() throws Exception {
        EventPageFetcher fetcher=spy(new EventPageFetcher());
        var target=URI.create("https://other.example/calendar");
        doReturn(response(302,target.toString(),"")).when(fetcher).request(root);
        doReturn(response(200,"","User-agent: *\nDisallow: /private")).when(fetcher).request(URI.create("https://other.example/robots.txt"));
        doReturn(response(200,"","<h1>Programm</h1>")).when(fetcher).request(target);
        var page=fetcher.fetch(root);
        assertThat(page.url()).isEqualTo(target);
        assertThat(page.contentType()).isEqualTo("text/html; charset=UTF-8");
        var order=inOrder(fetcher);order.verify(fetcher).request(root);order.verify(fetcher).request(URI.create("https://other.example/robots.txt"));order.verify(fetcher).request(target);
    }
    @Test void redirectsCannotBypassRobotsOrHttpsUrlPolicy() throws Exception {
        for(String target:List.of("http://other.example/party","https://127.0.0.1/party","https://user:pass@other.example/party")) {
            EventPageFetcher fetcher=spy(new EventPageFetcher());
            doReturn(response(301,target,"")).when(fetcher).request(root);
            assertThat(assertThrows(EventPageFetcher.FetchFailure.class,()->fetcher.fetch(root)).status).isEqualTo("UNSAFE_URL");
            verify(fetcher,times(1)).request(any());
        }
        EventPageFetcher fetcher=spy(new EventPageFetcher());var target=root.resolve("/private");
        doReturn(response(307,"/private","")).when(fetcher).request(root);
        doReturn(response(200,"","User-agent: *\nDisallow: /private")).when(fetcher).request(root.resolve("/robots.txt"));
        assertThat(assertThrows(EventPageFetcher.FetchFailure.class,()->fetcher.fetch(root)).status).isEqualTo("ROBOTS_BLOCKED");
        verify(fetcher,never()).request(target);
    }
    @Test void redirectRobotsDnsFailuresAndLoopsStopWithoutRetryOrBrowserFallback() throws Exception {
        EventPageFetcher fetcher=spy(new EventPageFetcher());
        doReturn(response(302,"https://other.example/party","")).when(fetcher).request(root);
        doThrow(new UnknownHostException("Nicht öffentliche Zieladresse gesperrt")).when(fetcher).request(URI.create("https://other.example/robots.txt"));
        var failure=assertThrows(EventPageFetcher.FetchFailure.class,()->fetcher.fetch(root));
        assertThat(failure.status).isEqualTo("DNS_FAILED");assertThat(failure.getMessage()).contains("robots.txt","Zieladresse");
        verify(fetcher,never()).request(URI.create("https://other.example/party"));
        doReturn(response(200,"","")).when(fetcher).request(root.resolve("/robots.txt"));
        doReturn(response(308,"/parties","")).when(fetcher).request(root);
        assertThat(assertThrows(EventPageFetcher.FetchFailure.class,()->fetcher.fetch(root)).getMessage()).contains("Weiterleitungsschleife");
    }
    @Test void transportFailuresAreExplainedAndRootFailureIsReturnedAsSourceResult() throws Exception {
        List<@org.jspecify.annotations.NonNull IOException> causes=List.of(new UnknownHostException("host"),new javax.net.ssl.SSLHandshakeException("certificate"),new SocketTimeoutException("response"));
        EventPageFetcher fetcher=spy(new EventPageFetcher());
        var genres=mock(DiscoveryGenreFilter.class);when(genres.terms()).thenReturn(Set.of("Rock"));
        var browser=mock(BrowserPageRenderer.class);
        var service=new VenueSourceDiscoveryService(fetcher,extractor,genres,null,null,browser);
        for(int i=0;i<causes.size();i++) {
            doThrow(requireNonNull(causes.get(i))).when(fetcher).request(root.resolve("/robots.txt"));
            var found=requireNonNull(service.discover(root.toString(),EventExtractor.Profile.defaults()).getFirst());
            assertThat(found.extraction().status()).isEqualTo(List.of("DNS_FAILED","TLS_FAILED","FETCH_TIMEOUT").get(i));
            assertThat(found.diagnostics()).anyMatch(message->message.contains("robots.txt"));
        }
        verifyNoInteractions(browser);verify(fetcher,never()).request(root);
    }
    @Test void httpAndContentTypeFailuresIncludeStatusAndUrl() throws Exception {
        EventPageFetcher fetcher=spy(new EventPageFetcher());
        for(int code:List.of(403,429,404,500)) {
            doReturn(response(code,"","")).when(fetcher).request(root);
            var failure=assertThrows(EventPageFetcher.FetchFailure.class,()->fetcher.fetch(root));
            assertThat(failure.getMessage()).contains("HTTP "+code,root.toString());
        }
        doReturn(new EventPageFetcher.Response(200,"","image/png",new byte[0])).when(fetcher).request(root);
        assertThat(assertThrows(EventPageFetcher.FetchFailure.class,()->fetcher.fetch(root)).getMessage()).contains("Content-Type","image/png");
    }
    @Test void gzipIsDecodedButExpandedBodyRemainsBounded() throws Exception {
        var fetcher=new EventPageFetcher(65536);
        var out=new ByteArrayOutputStream();
        try(var gzip=new GZIPOutputStream(out)) {gzip.write(new byte[65537]);}
        try(var decoded=fetcher.decoded(new ByteArrayInputStream(out.toByteArray()),"gzip")) {
            assertThat(assertThrows(EventPageFetcher.FetchFailure.class,()->fetcher.readBody(decoded,Long.MAX_VALUE)).status).isEqualTo("RESPONSE_TOO_LARGE");
        }
        out.reset();
        try(var gzip=new GZIPOutputStream(out)) {gzip.write("Köln".getBytes(StandardCharsets.UTF_8));}
        try(var decoded=fetcher.decoded(new ByteArrayInputStream(out.toByteArray()),"gzip")) {
            assertThat(new String(fetcher.readBody(decoded,Long.MAX_VALUE),StandardCharsets.UTF_8)).isEqualTo("Köln");
        }
        assertThat(assertThrows(EventPageFetcher.FetchFailure.class,()->fetcher.decoded(InputStream.nullInputStream(),"br")).getMessage()).contains("Content-Encoding","br");
    }
}
