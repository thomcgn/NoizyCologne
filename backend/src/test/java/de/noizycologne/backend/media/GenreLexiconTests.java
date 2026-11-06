package de.noizycologne.backend.media;

import de.noizycologne.backend.genre.Genre;
import de.noizycologne.backend.genre.GenreAliasRepository;
import de.noizycologne.backend.genre.GenreRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenreLexiconTests {

    private final GenreLexicon lexicon = new GenreLexicon();

    @Test
    void recognizesAliasesAndMultiWordGenres() {
        List<TesseractOcrService.OcrWord> words = List.of(
                word("Garage"), word("Punk"), word("50s"), word("RnR"), word("Soul")
        );

        assertThat(lexicon.findGenres(words)).containsExactlyInAnyOrder("Garage-Punk", "50s Rock’n’Roll", "Soul");
    }

    @Test
    void doesNotMatchGenresInsideArtistNames() {
        assertThat(lexicon.findGenres(List.of(word("rockabillyx")))).doesNotContain("Rockabilly");
    }

    @Test
    void returnsEvidenceBasedConfidenceForExactMatches() {
        List<GenreLexicon.GenreMatch> matches = lexicon.findGenreMatches(List.of(
                word("Garage", 92, 0), word("Punk", 68, 20), word("Soul", 41, 40)
        ));

        assertThat(matches).extracting(match -> match.genre())
                .containsExactlyInAnyOrder("Garage-Punk", "Soul");
        assertThat(matches).filteredOn(match -> match.genre().equals("Garage-Punk"))
                .singleElement()
                .satisfies(match -> {
                    assertThat(match.confidence()).isBetween(0.79, 0.81);
                    assertThat(match.uncertain()).isFalse();
                });
        assertThat(matches).filteredOn(match -> match.genre().equals("Soul"))
                .singleElement()
                .satisfies(match -> assertThat(match.confidence()).isEqualTo(0.41));
    }

    @Test
    void marksControlledFuzzyMatchesAsUncertain() {
        List<GenreLexicon.GenreMatch> matches = lexicon.findGenreMatches(List.of(
                word("50s", 88, 0), word("Rnk", 74, 20)
        ));

        assertThat(matches).filteredOn(match -> match.genre().equals("50s Rock’n’Roll"))
                .singleElement()
                .satisfies(match -> {
                    assertThat(match.confidence()).isLessThan(0.9);
                    assertThat(match.uncertain()).isTrue();
                });
    }

    @Test
    void doesNotFuzzyMatchShortAmbiguousGenres() {
        assertThat(lexicon.findGenreMatches(List.of(word("Skb", 90, 0))))
                .extracting(match -> match.genre())
                .doesNotContain("Ska");
    }

    @Test
    void recognizesElectronicoreAndEmoInFromToGenreLists() {
        List<GenreLexicon.GenreMatch> matches = lexicon.findGenreMatches(List.of(
                word("FROM", 95, 0), word("Electronicore", 93, 20), word("TO", 95, 140),
                word("Hardcore", 92, 170), word("FROM", 95, 0), word("Emo", 91, 20),
                word("TO", 95, 140), word("Metalcore", 94, 170)
        ));

        assertThat(matches).extracting(match -> match.genre())
                .contains("Electronicore", "Hardcore", "Emo", "Metalcore");
    }

    @Test
    void doesNotTreatPromotionalLetsRockAsGenreButKeepsRockInAList() {
        assertThat(lexicon.findGenres(List.of(word("let's"), word("rock"))))
                .doesNotContain("Rock");
        assertThat(lexicon.findGenres(List.of(word("Rock"), word("Punk"))))
                .contains("Rock");
    }

    @Test
    void matchesCanonicalNameWhenDynamicGenreHasNoAlias() {
        GenreRepository genres = mock(GenreRepository.class);
        GenreAliasRepository aliases = mock(GenreAliasRepository.class);
        Genre genre = mock(Genre.class);
        when(genre.getId()).thenReturn("breakbeat");
        when(genre.getCanonicalName()).thenReturn("Breakbeat");
        when(genres.findAllByActiveTrueOrderByCanonicalNameAsc()).thenReturn(List.of(genre));
        when(aliases.findAllByGenreIdIn(List.of("breakbeat"))).thenReturn(List.of());

        GenreLexicon dynamicLexicon = new GenreLexicon(genres, aliases);

        assertThat(dynamicLexicon.findGenres(List.of(word("Breakbeat"))))
                .containsExactly("Breakbeat");
    }

    @Test
    void constructorDoesNotDispatchOverridableRefresh() {
        GenreRepository genres = mock(GenreRepository.class);
        GenreAliasRepository aliases = mock(GenreAliasRepository.class);
        when(genres.findAllByActiveTrueOrderByCanonicalNameAsc()).thenReturn(List.of());
        class GuardedLexicon extends GenreLexicon {
            GuardedLexicon() { super(genres, aliases); }
            @Override public synchronized void refresh() {
                throw new AssertionError("Refresh must not run before subclass initialization");
            }
        }
        assertThat(new GuardedLexicon().findGenres(List.of(word("Unknown")))).isEmpty();
    }

    private TesseractOcrService.OcrWord word(String text) {
        return word(text, 90, 0);
    }

    private TesseractOcrService.OcrWord word(String text, int confidence, int left) {
        return new TesseractOcrService.OcrWord(text, confidence, left, 0, 10, 10, "test", 11, 1, 1, 1);
    }
}
