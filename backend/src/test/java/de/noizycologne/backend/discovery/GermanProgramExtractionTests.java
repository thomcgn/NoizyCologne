package de.noizycologne.backend.discovery;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GermanProgramExtractionTests {
    private final EventExtractor extractor = new EventExtractor();
    private EventExtractor.Extraction extract(String html) {
        return extractor.extract(new EventPageFetcher.Page(URI.create("https://unknown.example/programm"),html,"text/html"),EventExtractor.Profile.defaults());
    }
    @Test void wixCardsAndTextPreviewAreBothExtractedWithoutJavascript() throws Exception {
        String html;
        try (var in=getClass().getResourceAsStream("/discovery/wix-programme.html")) {
            html=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);
        }
        var result=extract(html);
        assertThat(result.status()).isEqualTo("EVENTS_FOUND");
        assertThat(result.events()).hasSize(6);
        var party=result.events().get(0);
        assertThat(party.title()).isEqualTo("TESTNACHT - Indieparty");
        assertThat(party.starts()).containsExactly("2026-11-13T23:00");
        assertThat(party.url()).isEqualTo("https://unknown.example/events/testnacht");
        assertThat(result.events().get(1).state()).contains("UNCONFIRMED");
        var ambiguous=result.events().stream().filter(e -> e.title().equals("VORSCHAU PARTY")).findFirst().orElseThrow();
        assertThat(ambiguous.starts()).isEmpty(); assertThat(ambiguous.state()).contains("UNCONFIRMED");
        var january=result.events().stream().filter(e -> e.title().equals("JANUAR PARTY")).findFirst().orElseThrow();
        assertThat(january.starts()).containsExactly("2027-01-09");
        assertThat(january.state()).contains("UNCONFIRMED");
        var missing=result.events().stream().filter(e -> e.title().equals("OHNE JAHRESKONTEXT")).findFirst().orElseThrow();
        assertThat(missing.starts()).isEmpty();
    }
    @Test void germanMonthsAndNumericFullDatesPreserveExactStartTime() {
        assertThat(GermanProgramText.starts("05. November 2026, 19:00 – 23:00")).containsExactly("2026-11-05T19:00");
        assertThat(GermanProgramText.starts("13.11.2026, 23:00 – 14.11.2026, 04:00")).containsExactly("2026-11-13T23:00");
        assertThat(GermanProgramText.starts("9. März 2027")).containsExactly("2027-03-09");
        assertThat(GermanProgramText.starts("9. Maerz 2027 21:30")).containsExactly("2027-03-09T21:30");
    }
    @Test void missingYearInvalidDateAndExplicitAlternativesAreNeverGuessed() {
        assertThat(GermanProgramText.starts("13. Nov.")).containsExactly("13. Nov.");
        assertThat(EventNormalizer.date(GermanProgramText.starts("31. Februar 2027").get(0))).isNull();
        assertThat(GermanProgramText.starts("13. Nov. 2026 oder 14. Nov. 2026")).containsExactly("2026-11-13","2026-11-14");
    }
    @Test void previewYearComesFromItsSectionAndNoTimeIsInvented() {
        var result=extract("<div><p>2027</p><p>09.01. Party A</p></div><div><p>09.01. Party B</p></div>");
        assertThat(result.events().get(0).starts()).containsExactly("2027-01-09");
        assertThat(EventNormalizer.time(result.events().get(0).starts().get(0))).isNull();
        assertThat(result.events().get(1).starts()).isEmpty();
        assertThat(result.events().get(0).identity()).isNotEqualTo(result.events().get(1).identity());
    }
    @Test void indiepartyIsExplicitGenreAndPartyEvidenceButIndienIsNot() {
        var party=new EventExtractor.Event("id","TESTNACHT - Indieparty","https://unknown.example/event",List.of("2027-01-09"),"", "", "");
        assertThat(DiscoveryGenreFilter.matches(party,Set.of("Indie"))).isTrue();
        assertThat(new EventMatcher().match(party,"Club",List.of(),Map.of(),List.of("Indie"),true).status()).isEqualTo("NEEDS_REVIEW");
        var other=new EventExtractor.Event("id","Indien Vortrag","",List.of(),"", "", "");
        assertThat(DiscoveryGenreFilter.matches(other,Set.of("Indie"))).isFalse();
    }
    @Test void semanticEventsAndTextPreviewCoexist() {
        var result=extract("<script type='application/ld+json'>{\"@type\":\"Event\",\"name\":\"Structured\",\"startDate\":\"2027-01-09\"}</script><div><p>2027</p><p>10.01. Text Party</p></div>");
        assertThat(result.events()).hasSize(2);
        assertThat(result.events().get(1).starts()).containsExactly("2027-01-10");
    }
}
