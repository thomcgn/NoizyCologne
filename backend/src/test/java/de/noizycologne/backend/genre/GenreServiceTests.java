package de.noizycologne.backend.genre;

import de.noizycologne.backend.media.GenreLexicon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenreServiceTests {

    private final GenreRepository genres = mock(GenreRepository.class);
    private final GenreAliasRepository aliases = mock(GenreAliasRepository.class);
    private final GenreLexicon lexicon = mock(GenreLexicon.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final GenreService service = new GenreService(genres, aliases, lexicon, jdbc);

    @BeforeEach
    void stubRepositoryDefaults() {
        when(aliases.findAllByGenreIdIn(anyCollection())).thenReturn(List.of());
        when(genres.save(anyGenre())).thenAnswer(call -> {
            var saved = Objects.requireNonNull(call.getArgument(0, Genre.class));
            saved.assignId();
            return saved;
        });
        when(aliases.save(anyGenreAlias())).thenAnswer(call -> {
            var saved = Objects.requireNonNull(call.getArgument(0, GenreAlias.class));
            saved.assignId();
            return saved;
        });
    }

    /** Mockito matchers are legacy APIs; keep their unannotated values at this boundary. */
    private static Genre anyGenre() {
        return org.mockito.ArgumentMatchers.any();
    }

    private static GenreAlias anyGenreAlias() {
        return org.mockito.ArgumentMatchers.any();
    }

    private static Genre genre(String canonical, String display) {
        var genre = new Genre(canonical, display, true);
        genre.assignId();
        return genre;
    }

    private static GenreAlias alias(String genreId, String value) {
        var alias = new GenreAlias(genreId, value);
        alias.assignId();
        return alias;
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int status) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> assertThat(error.getStatusCode().value()).isEqualTo(status));
    }

    @Test
    void listJoinsAliasesWithoutLosingGenreOrder() {
        var first = genre("House", "House");
        var second = genre("Techno", "Techno");
        when(genres.findAllByOrderByCanonicalNameAsc()).thenReturn(
                List.of(Objects.requireNonNull(first), Objects.requireNonNull(second)));
        when(aliases.findAllByGenreIdIn(anyCollection())).thenReturn(
                List.of(Objects.requireNonNull(alias(first.getId(), "Deep House"))));

        List<GenreResponse> responses = service.list();

        assertThat(responses).extracting(response -> response.canonicalName()).containsExactly("House", "Techno");
        assertThat(responses.getFirst().aliases()).extracting(alias -> alias.value()).containsExactly("Deep House");
        assertThat(responses.getLast().aliases()).isEmpty();
    }

    @Test
    void createTrimsNamesDefaultsActiveAndRefreshesLexicon() {
        when(genres.findAll()).thenReturn(List.of());

        GenreResponse response = service.create(new GenreRequest(" Tech House ", "Tech House", null));

        assertThat(response.canonicalName()).isEqualTo("Tech House");
        assertThat(response.displayName()).isEqualTo("Tech House");
        assertThat(response.active()).isTrue();
        assertThat(response.aliases()).isEmpty();
        verify(lexicon).refresh();
        verify(jdbc).execute("SET LOCAL lock_timeout = '1500ms'");
        verify(jdbc).execute("LOCK TABLE genres, genre_aliases IN SHARE ROW EXCLUSIVE MODE");
    }

    @Test
    void nullInputsAreCleanedToEmptyStringsAtServiceBoundary() {
        when(genres.findAll()).thenReturn(List.of());

        GenreResponse response = service.create(new GenreRequest(null, null, null));

        assertThat(response.canonicalName()).isEmpty();
        assertThat(response.displayName()).isEmpty();
        assertThat(response.active()).isTrue();
    }

    @Test
    void updateRejectsUnknownIdAndNormalizedDuplicateCanonicalName() {
        assertStatus(() -> service.update("missing", new GenreRequest("Rock", "Rock", true)),
                HttpStatus.NOT_FOUND.value());

        var current = genre("Rock", "Rock");
        var other = genre("Rock", "Rock");
        when(genres.findById(current.getId())).thenReturn(Optional.of(current));
        when(genres.findAll()).thenReturn(List.of(current, other));

        assertStatus(() -> service.update(current.getId(), new GenreRequest(" rock ", "Rock", true)),
                HttpStatus.CONFLICT.value());
        verify(genres, never()).save(anyGenre());
    }

    @Test
    void updateAppliesInactiveFlagAndRefreshesLexicon() {
        var current = genre("Rock", "Rock");
        when(genres.findById(current.getId())).thenReturn(Optional.of(current));
        when(genres.findAll()).thenReturn(List.of(current));

        GenreResponse response = service.update(current.getId(), new GenreRequest("Rock", "Rock", false));

        assertThat(response.active()).isFalse();
        verify(lexicon).refresh();
    }

    @Test
    void addAliasTrimsValueAndRejectsDuplicateKnownByAnotherGenre() {
        var current = genre("House", "House");
        when(genres.findById(current.getId())).thenReturn(Optional.of(current));
        when(aliases.findAllByOrderByValueAsc()).thenReturn(List.of());

        GenreAliasResponse created = service.addAlias(current.getId(), new GenreAliasRequest("  Deep House  "));

        assertThat(created.value()).isEqualTo("Deep House");
        verify(aliases).save(argThat(saved -> current.getId().equals(saved.getGenreId())));
        verify(lexicon).refresh();

        when(aliases.findAllByOrderByValueAsc()).thenReturn(
                List.of(Objects.requireNonNull(alias("some-other-genre", "DEEP HOUSE"))));
        assertStatus(() -> service.addAlias(current.getId(), new GenreAliasRequest("Deep House")),
                HttpStatus.CONFLICT.value());
    }

    @Test
    void aliasUpdateAndDeleteRequireAnAliasOfTheGivenGenre() {
        var current = genre("House", "House");
        var foreign = alias("other-genre", "Foreign");
        when(genres.findById(current.getId())).thenReturn(Optional.of(current));
        when(aliases.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertStatus(() -> service.updateAlias(current.getId(), foreign.getId(), new GenreAliasRequest("X")),
                HttpStatus.NOT_FOUND.value());
        assertStatus(() -> service.deleteAlias(current.getId(), foreign.getId()),
                HttpStatus.NOT_FOUND.value());
        verify(aliases, never()).delete(anyGenreAlias());

        var own = alias(current.getId(), "Old");
        when(aliases.findById(own.getId())).thenReturn(Optional.of(own));
        when(aliases.findAllByOrderByValueAsc()).thenReturn(List.of(Objects.requireNonNull(own)));

        GenreAliasResponse updated = service.updateAlias(current.getId(), own.getId(), new GenreAliasRequest(" New "));
        assertThat(updated.value()).isEqualTo("New");
        service.deleteAlias(current.getId(), own.getId());
        verify(aliases).delete(own);
    }

    @Test
    void aliasEndpointsRejectMissingGenres() {
        assertStatus(() -> service.addAlias("missing", new GenreAliasRequest("Alias")),
                HttpStatus.NOT_FOUND.value());
        assertStatus(() -> service.updateAlias("missing", "alias", new GenreAliasRequest("Alias")),
                HttpStatus.NOT_FOUND.value());
        assertStatus(() -> service.deleteAlias("missing", "alias"), HttpStatus.NOT_FOUND.value());
    }
}
