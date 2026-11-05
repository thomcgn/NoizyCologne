package de.noizycologne.backend.discovery;

import de.noizycologne.backend.party.PartySeries;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscoveryTests {
    private final EventExtractor extractor=new EventExtractor();
    private DiscoveryGenreFilter genres() {
        var genres=mock(DiscoveryGenreFilter.class); when(genres.terms()).thenReturn(Set.of("Indie")); return genres;
    }
    private EventPageFetcher.Page page(String body) { return new EventPageFetcher.Page(URI.create("https://unknown.example/programm"),body,"text/html"); }
    private String fixture(String name) throws Exception { try(var in=getClass().getResourceAsStream("/discovery/"+name)) { return new String(Objects.requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); } }
    @Test void fiveRepresentativeStructuresAndUnknownVenue() throws Exception {
        for(String file:List.of("mtc.html","blue-shell.html","tsunami.ics","garagen.html","live-music-hall.html","unknown.html")) {
            var result=extractor.extract(page(fixture(file)),EventExtractor.Profile.defaults());
            assertThat(result.status()).as(file).isEqualTo("EVENTS_FOUND");
            assertThat(result.events()).as(file).hasSize(1);
            assertThat(result.events().get(0).title()).isEqualTo("Indie Party");
            assertThat(EventNormalizer.date(result.events().get(0).starts().get(0))).isEqualTo(java.time.LocalDate.of(2027,1,9));
        }
    }
    @Test void emptyUnknownJavascriptAndMalformedAreDistinct() {
        assertThat(extractor.extract(page("<p>Keine Veranstaltungen</p>"),EventExtractor.Profile.defaults()).status()).isEqualTo("NO_EVENTS");
        assertThat(extractor.extract(page("<h1>Willkommen</h1>"),EventExtractor.Profile.defaults()).status()).isEqualTo("UNINTERPRETABLE");
        assertThat(extractor.extract(page("<div id='app'></div><script src='/app.js'></script>"),EventExtractor.Profile.defaults()).status()).isEqualTo("JAVASCRIPT_REQUIRED");
        assertThat(extractor.extract(page("<script type='application/ld+json'>{broken</script>"),EventExtractor.Profile.defaults()).status()).isEqualTo("UNINTERPRETABLE");
    }
    @Test void configurableProfileNeedsNoVenueCode() {
        var result=extractor.extract(page("<section class='gig'><b>Indie Party</b><i>2027-01-09</i></section>"),new EventExtractor.Profile(".gig","b","i"));
        assertThat(result.events()).hasSize(1); assertThat(result.events().get(0).starts()).containsExactly("2027-01-09");
    }
    @Test void crawlsHomepageCalendarAndDetailsWithinDomainOnly() throws Exception {
        var fetcher=mock(EventPageFetcher.class);
        when(fetcher.robotsAllowed(any(),any())).thenReturn(true);
        URI root=URI.create("https://new.example/");
        when(fetcher.fetch(root)).thenReturn(new EventPageFetcher.Page(root,"<a href='/programm'>Programm</a><a href='https://evil.example/events'>Events</a>","text/html"));
        URI calendar=URI.create("https://new.example/programm");
        when(fetcher.fetch(calendar)).thenReturn(new EventPageFetcher.Page(calendar,fixture("unknown.html"),"text/html"));
        URI detail=URI.create("https://new.example/events/first");
        when(fetcher.fetch(detail)).thenReturn(new EventPageFetcher.Page(detail,fixture("live-music-hall.html"),"text/html"));
        var found=new VenueSourceDiscoveryService(fetcher,extractor,genres()).discover(root.toString(),EventExtractor.Profile.defaults());
        assertThat(found).hasSize(3); verify(fetcher,never()).fetch(URI.create("https://evil.example/events"));
    }
    @Test void directEventUrlAndFailingChild() throws Exception {
        var fetcher=mock(EventPageFetcher.class); when(fetcher.robotsAllowed(any(),any())).thenReturn(true);
        URI root=URI.create("https://new.example/events/one");
        when(fetcher.fetch(root)).thenReturn(new EventPageFetcher.Page(root,fixture("live-music-hall.html"),"text/html"));
        assertThat(new VenueSourceDiscoveryService(fetcher,extractor,genres()).discover(root.toString(),EventExtractor.Profile.defaults())).hasSize(1);
        when(fetcher.fetch(root)).thenThrow(new EventPageFetcher.FetchFailure("BLOCKED"));
        assertThatThrownBy(() -> new VenueSourceDiscoveryService(fetcher,extractor,genres()).discover(root.toString(),EventExtractor.Profile.defaults())).isInstanceOf(EventPageFetcher.FetchFailure.class);
    }
    private PartySeries party() { var p=mock(PartySeries.class); when(p.getId()).thenReturn("party"); when(p.getName()).thenReturn("Indie Party"); return p; }
    private EventExtractor.Event event(String title,List<String> dates,String state) { return new EventExtractor.Event("id",title,"https://new.example/event",dates,title,state,""); }
    @Test void exactNameAliasConservativeGenreAndUnknownParty() {
        var matcher=new EventMatcher(); var p=party();
        assertThat(matcher.match(event("Indie Party",List.of("2027-01-09T23:00"),""),"New",List.of(p),Map.of(),List.of("Indie"),true).status()).isEqualTo("READY");
        assertThat(matcher.match(event("Alias",List.of("2027-01-09"),""),"New",List.of(p),Map.of("party",List.of("Alias")),List.of(),true).status()).isEqualTo("READY");
        assertThat(matcher.match(event("Neue Indie Party",List.of("2027-01-09"),""),"New",List.of(p),Map.of(),List.of("Indie"),true).status()).isEqualTo("NEEDS_REVIEW");
        assertThat(matcher.match(event("Indie Konzert",List.of("2027-01-09"),""),"New",List.of(p),Map.of(),List.of("Indie"),true).status()).isEqualTo("IGNORED");
        assertThat(matcher.match(event("Indie Party",List.of("2027-01-09"),""),"New",List.of(p),Map.of(),List.of(),false).status()).isEqualTo("NEEDS_REVIEW");
    }
    @Test void missingConflictingAndCancelledDatesRequireInquiry() {
        var matcher=new EventMatcher(); var p=party();
        for(var dates:List.of(List.<String>of(),List.of("2027-01"),List.of("2027-01-08","2027-01-09")))
            assertThat(matcher.match(event("Indie Party",dates,""),"New",List.of(p),Map.of(),List.of(),true).status()).isEqualTo("NEEDS_INQUIRY");
        for(String state:List.of("EventCancelled","EventRescheduled","TENTATIVE","UNCONFIRMED"))
            assertThat(matcher.match(event("Indie Party",List.of("2027-01-09"),state),"New",List.of(p),Map.of(),List.of(),true).status()).isEqualTo("NEEDS_INQUIRY");
    }
    @Test void overnightAndMissingTimesAreNotInvented() throws Exception {
        var event=extractor.extract(page(fixture("mtc.html")),EventExtractor.Profile.defaults()).events().get(0);
        assertThat(event.starts()).containsExactly("2027-01-09T23:00:00+01:00");
        assertThat(EventNormalizer.time("2027-01-09")).isNull();
        assertThat(EventNormalizer.date("2027-01-09T23:30:00Z")).isEqualTo(java.time.LocalDate.of(2027,1,10));
    }
    @Test void ambiguousNamesAndFridaySaturdayTextAreNeverAutomatic() {
        var first=party(); var second=mock(PartySeries.class);
        when(second.getId()).thenReturn("other"); when(second.getName()).thenReturn("Alternative");
        assertThat(new EventMatcher().match(event("Indie Party",List.of("2027-01-09"),""),"New",List.of(first,second),Map.of("other",List.of("Indie Party")),List.of(),true).status()).isEqualTo("NEEDS_REVIEW");
        var extracted=extractor.extract(page("<article><h2>Indie Party</h2><time datetime='2027-01-09'>Freitag oder Samstag</time></article>"),EventExtractor.Profile.defaults()).events().get(0);
        assertThat(new EventMatcher().match(extracted,"New",List.of(first),Map.of(),List.of(),true).status()).isEqualTo("NEEDS_INQUIRY");
        assertThat(EventNormalizer.date("2027-01-09 oder 2027-01-10")).isNull();
    }
    @Test void crawlLimitsAndRobotsBlock() throws Exception {
        var fetcher=mock(EventPageFetcher.class); when(fetcher.robotsAllowed(any(),any())).thenReturn(true);
        when(fetcher.fetch(any())).thenAnswer(call -> {
            URI url=call.getArgument(0); StringBuilder html=new StringBuilder();
            for(int n=0;n<100;n++) html.append("<a href='/events/").append(n).append("'>Events</a>");
            return new EventPageFetcher.Page(url,html.toString(),"text/html");
        });
        var service=new VenueSourceDiscoveryService(fetcher,extractor,genres());
        assertThat(service.discover("https://new.example/",EventExtractor.Profile.defaults())).hasSize(8);
        verify(fetcher,times(8)).fetch(any());
        reset(fetcher); when(fetcher.robotsAllowed(any(),any())).thenReturn(false);
        assertThatThrownBy(() -> service.discover("https://new.example/",EventExtractor.Profile.defaults())).isInstanceOf(EventPageFetcher.FetchFailure.class);
        verify(fetcher,never()).fetch(any());
        assertThatThrownBy(() -> SafeUrls.resolve("localhost")).isInstanceOf(UnknownHostException.class);
    }
    @Test void ssrfAndRobots() throws Exception {
        for(String url:List.of("http://public.example", "https://127.0.0.1", "https://[::1]/", "https://user:pass@public.example", "https://public.example:8443", "https://localhost/", "https://public.example./"))
            assertThatThrownBy(() -> SafeUrls.parse(url)).isInstanceOf(IllegalArgumentException.class);
        for(String ip:List.of("0.0.0.0","10.0.0.1","127.0.0.1","100.64.0.1","169.254.169.254","172.16.0.1","192.168.0.1","192.0.2.1","198.18.0.1","198.51.100.1","203.0.113.1","224.0.0.1","::1","fc00::1","fe80::1","2001:db8::1","2002::1"))
            assertThat(SafeUrls.isPublic(InetAddress.getByName(ip))).as(ip).isFalse();
        assertThat(SafeUrls.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
        assertThat(RobotsRules.allowed("User-agent: *\nDisallow: /private", "/private/events")).isFalse();
        assertThat(RobotsRules.allowed("User-agent: *\nDisallow: /private", "/events")).isTrue();
    }
}
