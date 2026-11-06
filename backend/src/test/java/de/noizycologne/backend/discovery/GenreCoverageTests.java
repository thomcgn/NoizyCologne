package de.noizycologne.backend.discovery;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class GenreCoverageTests {
    @Test void fiftyPercentCountsAllActiveGenresOnceRegardlessOfAliases() {
        var genres=List.of(new GenreCoverage.Genre("1","Emo",Set.of("Emo","Emocore")),new GenreCoverage.Genre("2","Indie",Set.of("Indie")),
            new GenreCoverage.Genre("3","Metalcore",Set.of("Metalcore")),new GenreCoverage.Genre("4","Hardcore",Set.of("Hardcore")));
        var event=new EventExtractor.Event("","New Party","https://site.example/metalcore",List.of("2026-11-07"),"Emo, Emocore, Indie, https://site.example/hardcore","","");
        var coverage=GenreCoverage.assess(event,genres);
        assertThat(coverage.matched()).isEqualTo(2);assertThat(coverage.total()).isEqualTo(4);assertThat(coverage.percent()).isEqualTo(50);assertThat(coverage.eligible()).isTrue();
        assertThat(GenreCoverage.assess(new EventExtractor.Event("","Emo Party","",List.of(),"Emocore","",""),genres).eligible()).isFalse();
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
}
