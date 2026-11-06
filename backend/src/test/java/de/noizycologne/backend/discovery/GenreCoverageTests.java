package de.noizycologne.backend.discovery;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class GenreCoverageTests {
    @Test void coverageCountsRecognizedEventGenresOnceRegardlessOfAliases() {
        var genres=List.of(new GenreCoverage.Genre("1","Emo",Set.of("Emo","Emocore")),new GenreCoverage.Genre("2","Indie",Set.of("Indie")),
            new GenreCoverage.Genre("3","Metalcore",Set.of("Metalcore")),new GenreCoverage.Genre("4","Hardcore",Set.of("Hardcore")));
        var event=new EventExtractor.Event("","New Party","https://site.example/metalcore",List.of("2026-11-07"),"Emo, Emocore, Indie, https://site.example/hardcore","","");
        var coverage=GenreCoverage.assess(event,genres);
        assertThat(coverage.matched()).isEqualTo(2);assertThat(coverage.total()).isEqualTo(2);assertThat(coverage.percent()).isEqualTo(100);assertThat(coverage.eligible()).isTrue();
        assertThat(GenreCoverage.assess(new EventExtractor.Event("","Emo Party","",List.of(),"Emocore","",""),genres).eligible()).isTrue();
        assertThat(GenreCoverage.assess(event,List.of()).eligible()).isFalse();
    }
    @Test void mixedConcertPageKeepsItsSeparatelyTitledDatedAftershow() {
        var page=new EventPageFetcher.Page(java.net.URI.create("https://source.example/concerts/"),
            "<title>Konzerte</title><h1>Konzerte</h1><article><h2>Live Konzert</h2><time datetime='2026-11-07T20:00'></time></article><article><h2>Indie Aftershowparty</h2><time datetime='2026-11-07'></time><p>Emo; Beginn nach dem Konzert</p></article>","text/html");
        assertThat(new EventExtractor().extract(page,EventExtractor.Profile.defaults()).events()).extracting(EventExtractor.Event::title).containsExactly("Indie Aftershowparty");
    }
    @Test void aftershowKeepsRelativeStartAndConcertRemainsExcluded() {
        var event=new EventExtractor.Event("","Indie Aftershowparty","",List.of("2026-11-07"),"Emo; Beginn nach dem Konzert","","");
        assertThat(PartyOnlyPolicy.concert(event)).isFalse();assertThat(EventTiming.relativeStart(event)).isEqualTo("nach dem Konzert");
        assertThat(PartyOnlyPolicy.concert("Live Konzert mit anschließender Aftershowparty","Emo")).isTrue();
        assertThat(PartyOnlyPolicy.concert("Aftershowparty nach dem Konzert","Emo DJs")).isFalse();
    }
    @Test void explicitUnmatchedSourceGenresAreInTheDenominatorAndInactiveGenresDoNotMatch() {
        var genres=List.of(new GenreCoverage.Genre("emo","Emo",Set.of("Emo","Emocore")),new GenreCoverage.Genre("pop","Pop",Set.of("Pop"),false));
        var event=new EventExtractor.Event("","Clubnacht","",List.of(),"Genres: Emocore, Pop, Unbekannter Stil","","");
        var coverage=GenreCoverage.assess(event,genres);
        assertThat(coverage.total()).isEqualTo(3);assertThat(coverage.matched()).isEqualTo(1);assertThat(coverage.percent()).isEqualTo(33);
        assertThat(coverage.sourceNames()).containsExactlyInAnyOrder("Emo","Pop","Unbekannter Stil");assertThat(coverage.eligible()).isFalse();
    }
    @Test void knownSeriesStandardsAreSeparateAndNeverInventSourceGenres() {
        var genres=List.of(new GenreCoverage.Genre("emo","Emo",Set.of("Emo","Emocore")));
        var event=new EventExtractor.Event("","Middance Crisis","",List.of(),"Clubnacht","","");
        var coverage=GenreCoverage.assess(event,genres,List.of("Emocore"));
        assertThat(coverage.sourceNames()).isEmpty();assertThat(coverage.standardNames()).containsExactly("Emo");
        assertThat(coverage.basis()).isEqualTo("STANDARD");assertThat(coverage.percent()).isEqualTo(100);
        assertThat(GenreCoverage.assess(event,genres).basis()).isEqualTo("MISSING");
        assertThat(GenreCoverage.assess(event,genres).total()).isZero();
    }
    @Test void sourceGenresTakePrecedenceWhileStandardsStayVisibleAndCompoundsCountOnce() {
        var genres=List.of(new GenreCoverage.Genre("rock","Rock",Set.of("Rock")),new GenreCoverage.Genre("hard","Hard Rock",Set.of("Hard Rock")),new GenreCoverage.Genre("emo","Emo",Set.of("Emo")));
        var event=new EventExtractor.Event("","Clubnacht","",List.of(),"Hard Rock","","");
        var coverage=GenreCoverage.assess(event,genres,List.of("Emo"));
        assertThat(coverage.total()).isEqualTo(1);assertThat(coverage.sourceNames()).containsExactly("Hard Rock");
        assertThat(coverage.standardNames()).containsExactly("Emo");assertThat(coverage.basis()).isEqualTo("SOURCE");
    }
    @Test void structuredGenreFieldsRetainUnknownGenresWithoutCountingUrlsOrAliasesTwice() {
        var genres=List.of(new GenreCoverage.Genre("emo","Emo",Set.of("Emo","Emocore")));
        var event=new EventExtractor.Event("","Clubnacht","https://site.example/emo",List.of(),"{\"genre\":[\"Emo\",\"Emocore\",\"Techno\"],\"url\":\"https://site.example/rock\"}","","");
        var coverage=GenreCoverage.assess(event,genres);
        assertThat(coverage.sourceNames()).containsExactly("Emo","Techno");assertThat(coverage.total()).isEqualTo(2);
        assertThat(coverage.percent()).isEqualTo(50);assertThat(coverage.eligible()).isTrue();
    }
    @Test void longExplicitListsApplyTheLengthLimitPerGenreInsteadOfDroppingTheWholeDenominator() {
        var event=new EventExtractor.Event("","Clubnacht","",List.of(),"Genres: Emo, "+"Unbekannt A, ".repeat(12)+"Unbekannt B","","");
        var result=GenreCoverage.assess(event,List.of(new GenreCoverage.Genre("emo","Emo",Set.of("Emo"))));
        assertThat(result.total()).isEqualTo(3);assertThat(result.percent()).isEqualTo(33);
    }

}
