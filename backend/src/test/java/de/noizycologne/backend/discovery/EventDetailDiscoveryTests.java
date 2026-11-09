package de.noizycologne.backend.discovery;

import static java.util.Objects.requireNonNull;
import de.noizycologne.backend.genre.*;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NonNull;
import java.net.URI;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventDetailDiscoveryTests {
    private final EventPageFetcher fetcher = mock(EventPageFetcher.class);
    private final DiscoveryGenreFilter genres = mock(DiscoveryGenreFilter.class);
    private final URI root = URI.create("https://club.example/programm");
    private final URI detail = URI.create("https://club.example/nacht/123");
    private VenueSourceDiscoveryService service() throws Exception {
        when(genres.terms()).thenReturn(Set.of("Indie", "Alternative Rock"));
        when(fetcher.robotsAllowed(any(), any())).thenReturn(true);
        return new VenueSourceDiscoveryService(fetcher, new EventExtractor(), genres);
    }
    private void page(URI url, @NonNull String body) throws Exception {
        when(fetcher.fetch(url)).thenReturn(new EventPageFetcher.Page(url, body, "text/html"));
    }
    private @NonNull String overview(String description, String href) {
        return "<article><h2>Neue Party</h2><p>" + description + "</p><a href='" + href + "'>Details</a></article>";
    }
    @Test void enrichesScopedIncompleteListingBeforeFinalFiltering() throws Exception {
        var service = service();
        page(root, overview("Indie und DJs", "/nacht/123"));
        page(detail, "<h1>Neue Party</h1><time datetime='2027-01-09T23:00'>Samstag</time><p>Party im Club</p>");
        var result = service.discover(root.toString(), EventExtractor.Profile.defaults());
        verify(genres).terms(); verify(fetcher).fetch(detail);
        var event = requireNonNull(requireNonNull(result.get(0)).extraction().events().get(0));
        assertThat(event.starts()).containsExactly("2027-01-09T23:00");
        assertThat(event.evidence()).contains("Indie und DJs", "Detailseite", "Party im Club");
        assertThat(event.url()).isEqualTo(detail.toString());
        assertThat(requireNonNull(result.get(1)).extraction().events()).containsExactly(event);
    }
    @Test void concertDetailIsReadButRemainsExcluded() throws Exception {
        var service = service();
        URI unrelated = URI.create("https://club.example/events/klassik");
        page(root, overview("Klassisches Konzert", unrelated.toString()));
        page(unrelated,"<h1>Neue Party</h1><p>Klassisches Konzert</p>");
        var result = service.discover(root.toString(), EventExtractor.Profile.defaults());
        verify(fetcher).fetch(unrelated);
        assertThat(PartyOnlyPolicy.concert(requireNonNull(requireNonNull(result.getFirst()).extraction().events().getFirst()))).isTrue();
        assertThat(result).hasSize(2);
    }
    @Test void missingGenreMatchDoesNotPreventScopedDetailCheck() throws Exception {
        var service = service();
        page(root, overview("Indien Reisevortrag", "/nacht/123"));
        page(detail,"<h1>Neue Party</h1><p>Indien Reisevortrag</p>");
        var result=service.discover(root.toString(), EventExtractor.Profile.defaults());
        verify(fetcher).fetch(detail);
        assertThat(DiscoveryGenreFilter.matches(requireNonNull(requireNonNull(result.getFirst()).extraction().events().getFirst()),Set.of("Indie"))).isFalse();
    }
    @Test void structuredEventWithRelativeUrlIsFollowedAndCancellationPreserved() throws Exception {
        var service = service();
        page(root, "<script type='application/ld+json'>{\"@type\":\"Event\",\"name\":\"Neue Party\",\"description\":\"Alternative Rock Party\",\"url\":\"/nacht/123\",\"startDate\":\"2027-01-09\"}</script>");
        page(detail, "<h1>Neue Party</h1><time datetime='2027-01-10'>Sonntag</time><p>Abgesagt</p>");
        var event = requireNonNull(requireNonNull(service.discover(root.toString(), EventExtractor.Profile.defaults()).get(0)).extraction().events().get(0));
        assertThat(event.starts()).containsExactly("2027-01-09", "2027-01-10");
        assertThat(event.state()).contains("UNCONFIRMED");
    }
    @Test void blockedDetailRemainsUnconfirmedAndNoOtherDomainIsFetched() throws Exception {
        var service = service();
        page(root, overview("Indie Party", "/nacht/123") + overview("Indie Party", "https://other.example/event"));
        when(fetcher.robotsAllowed(eq(detail), any())).thenReturn(false);
        var result = service.discover(root.toString(), EventExtractor.Profile.defaults());
        verify(fetcher,never()).fetch(detail);
        verify(fetcher, never()).fetch(URI.create("https://other.example/event"));
        assertThat(requireNonNull(requireNonNull(result.get(0)).extraction().events().get(0)).state()).contains("UNCONFIRMED");
        assertThat(requireNonNull(result.get(1)).extraction().status()).isEqualTo("ROBOTS_BLOCKED");
    }
    @Test void unrelatedDetailTitleDoesNotReplaceOriginalEvent() throws Exception {
        var service = service(); page(root, overview("Indie Party", "/nacht/123"));
        page(detail, "<h1>Andere Party</h1><time datetime='2027-01-09'>Samstag</time>");
        var event = requireNonNull(requireNonNull(service.discover(root.toString(), EventExtractor.Profile.defaults()).get(0)).extraction().events().get(0));
        assertThat(event.title()).isEqualTo("Neue Party");
        assertThat(event.starts()).isEmpty(); assertThat(event.state()).contains("UNCONFIRMED");
    }
    @Test void activeDatabaseGenresIncludeExplicitAliases() {
        var genreRepo = mock(GenreRepository.class); var aliases = mock(GenreAliasRepository.class);
        var genre = mock(Genre.class); when(genre.getId()).thenReturn("active");
        when(genre.getCanonicalName()).thenReturn("Alternative Rock"); when(genre.getDisplayName()).thenReturn("Alternative");
        when(genreRepo.findAllByActiveTrueOrderByCanonicalNameAsc()).thenReturn(List.of(genre));
        when(aliases.findAllByGenreIdIn(List.of("active"))).thenReturn(List.of(new GenreAlias("active", "Alt Rock")));
        assertThat(new DiscoveryGenreFilter(genreRepo, aliases).terms()).containsExactlyInAnyOrder("Alternative Rock", "Alternative", "Alt Rock");
        var event = new EventExtractor.Event("", "Party", "", List.of(), "Alt Rock mit DJs", "", "");
        assertThat(DiscoveryGenreFilter.matches(event, new DiscoveryGenreFilter(genreRepo, aliases).terms())).isTrue();
    }
    @Test void knownNameOrAliasDoesNotNeedWebsiteGenreProofBeforeDetailFetch() throws Exception {
        var repository=mock(de.noizycologne.backend.party.PartySeriesRepository.class);
        var store=mock(DiscoveryStore.class);
        var party=mock(de.noizycologne.backend.party.PartySeries.class);
        when(party.getName()).thenReturn("Black Parade");
        when(repository.findAll()).thenReturn(List.of(party));
        when(store.aliases()).thenReturn(Map.of("known",List.of("Explicit Alias")));
        when(genres.terms()).thenReturn(Set.of());when(fetcher.robotsAllowed(any(),any())).thenReturn(true);
        page(root,"<article><h2>Explicit Alias</h2><a href='/nacht/123'>Details</a></article>");
        page(detail,"<h1>Explicit Alias</h1><time datetime='2027-01-09'></time>");
        var service=new VenueSourceDiscoveryService(fetcher,new EventExtractor(),genres,repository,store);
        var found=service.discover(root.toString(),EventExtractor.Profile.defaults());
        verify(fetcher).fetch(detail);
        assertThat(requireNonNull(requireNonNull(found.getFirst()).extraction().events().getFirst()).starts()).containsExactly("2027-01-09");
    }

}
