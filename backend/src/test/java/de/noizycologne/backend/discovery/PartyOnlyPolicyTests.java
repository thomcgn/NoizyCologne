package de.noizycologne.backend.discovery;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import de.noizycologne.backend.party.PartySeries;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PartyOnlyPolicyTests {
    private EventExtractor.Event event(String title,String text) {
        return new EventExtractor.Event("id",title,"https://club.example/event",List.of("2027-01-09"),text,"","");
    }
    @Test void concertWinsOverGenreDjAndExactPartyName() {
        var party=mock(PartySeries.class);when(party.getName()).thenReturn("Indie Party");
        var e=event("Indie Party","Indie Konzert mit DJs und anschließender Party");
        assertThat(DiscoveryGenreFilter.matches(e,Set.of("Indie"))).isFalse();
        assertThat(new EventMatcher().match(e,"Club",List.of(party),Map.of(),List.of("Indie"),true).status()).isEqualTo("IGNORED");
    }
    @Test void pureConcertPageIsExplicitlyExcludedAndMixedOverviewIsFilteredPerEvent() {
        var extractor=new EventExtractor();
        var concert=extractor.extract(new EventPageFetcher.Page(URI.create("https://club.example/veranstaltungen"),"<title>KONZERTE | Club</title><article><h2>Indie Party</h2><time datetime='2027-01-09'></time></article>","text/html"),EventExtractor.Profile.defaults());
        assertThat(concert.status()).isEqualTo("EXCLUDED_CONCERTS");assertThat(concert.events()).isEmpty();
        var mixed=extractor.extract(new EventPageFetcher.Page(URI.create("https://club.example/events"),"<title>Konzerte und Partys</title><article><h2>Indie Konzert</h2><time datetime='2027-01-09'></time></article><article><h2>Indie Party</h2><time datetime='2027-01-10'></time></article>","text/html"),EventExtractor.Profile.defaults());
        assertThat(mixed.events()).extracting(EventExtractor.Event::title).containsExactly("Indie Party");
    }
    @Test void concertFeedsAreAlsoExcluded() {
        var result=new EventExtractor().extract(new EventPageFetcher.Page(URI.create("https://club.example/feed.ics"),"BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:concert\nSUMMARY:Rockkonzert\nDTSTART:20270109\nEND:VEVENT\nEND:VCALENDAR","text/calendar"),EventExtractor.Profile.defaults());
        assertThat(result.status()).isEqualTo("EXCLUDED_CONCERTS");assertThat(result.events()).isEmpty();
    }
    @Test void partyMusicEventIsNotAutomaticallyAConcert() {
        assertThat(PartyOnlyPolicy.concert(event("Indieparty","DJs und Dancefloor; MusicEvent"))).isFalse();
    }
}
