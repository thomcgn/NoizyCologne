package de.noizycologne.backend.media;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GenreLexiconTests {

    private final GenreLexicon lexicon = new GenreLexicon();

    @Test
    void recognizesAliasesAndMultiWordGenres() {
        List<TesseractOcrService.OcrWord> words = List.of(
                word("Garage"), word("Punk"), word("50s"), word("RnR"), word("Soul")
        );

        assertThat(lexicon.findGenres(words)).containsExactly("Punk", "Garage-Punk", "Rock’n’Roll", "50s Rock’n’Roll", "Soul");
    }

    @Test
    void doesNotMatchGenresInsideArtistNames() {
        assertThat(lexicon.findGenres(List.of(word("rockabillyx")))).doesNotContain("Rockabilly");
    }

    private TesseractOcrService.OcrWord word(String text) {
        return new TesseractOcrService.OcrWord(text, 90, 0, 0, 10, 10, "test", 11, 1, 1, 1);
    }
}
