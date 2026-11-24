package de.noizycologne.backend.discovery;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.*;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

class EmbeddedJsonExtractionTests {
    private final EventExtractor extractor = new EventExtractor();
    private final URI page = URI.create("https://unknown.example/about");

    private EventExtractor.Extraction extract(@NonNull String html) {
        return extractor.extract(new EventPageFetcher.Page(page, html, "text/html"), EventExtractor.Profile.defaults());
    }

    @Test void derivedWixFixtureYieldsOnlyTheSyntheticEmbeddedEventAndNeverCompleteness() throws Exception {
        String html;
        try (var in = requireNonNull(getClass().getResourceAsStream("/discovery/mtc-wix-derived-fixture.html"))) {
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var result = extract(html);
        assertThat(result.complete()).isFalse();
        assertThat(result.events()).hasSize(1);
        var event = requireNonNull(result.events().getFirst());
        assertThat(event.title()).isEqualTo("DARK UNICORN by DJ Martin Heiland");
        assertThat(event.starts()).containsExactly("2026-11-14T23:00");
        assertThat(event.evidence()).contains("Dark Electro", "EBM", "New Wave", "Synth", "Wave", "Post-Punk");
        assertThat(event.location()).isEqualTo("MTC Live Music Club");
        assertThat(event.place().city()).isEqualTo("Köln");
        assertThat(event.url()).isEqualTo("https://unknown.example/events/dark-unicorn");
        // The registered reference date is test data, not a live extraction result.
        assertThat(html).contains("SYNTHETISCH – nicht als Live-Befund");
    }

    @Test void nextDataStyleAssignmentIsReadWithoutSiteSelectors() {
        var result = extract("<div id='__next'></div><script type='application/json' "
            + "data-noizy-embedded='assignment:window.__NEXT_DATA__'>"
            + "{\"props\":{\"pageProps\":{\"events\":[{\"name\":\"Neon Night\",\"date\":\"2027-01-09T22:00\","
            + "\"genre\":\"Synth Pop\",\"location\":{\"name\":\"Club\"}}]}}}"
            + "</script>");
        assertThat(result.events()).hasSize(1);
        var event = requireNonNull(result.events().getFirst());
        assertThat(event.title()).isEqualTo("Neon Night");
        assertThat(event.starts()).containsExactly("2027-01-09T22:00");
        assertThat(event.evidence()).contains("Genres: Synth Pop");
        assertThat(result.complete()).isFalse();
    }

    @Test void conflictingEmbeddedDatesAndYearlessDatesRemainUnresolved() {
        var result = extract("<script type='application/json' data-noizy-embedded='assignment:state'>"
            + "{\"calendar\":{\"events\":["
            + "{\"title\":\"Zwei Termine\",\"startDate\":\"2026-11-14T23:00\",\"date\":\"2026-11-16T23:00\"},"
            + "{\"title\":\"Ohne Jahr\",\"startDate\":\"14th Nov | 23:00\"}]}}"
            + "</script>");
        var conflicting = requireNonNull(result.events().stream().filter(event -> event.title().equals("Zwei Termine")).findFirst().orElseThrow());
        assertThat(conflicting.starts()).containsExactly("2026-11-14T23:00", "2026-11-16T23:00");
        // Existing assessment rules, not the extractor, decide whether two
        // retained dates are a conflict; neither date is silently overwritten.
        assertThat(EventVerification.assess(conflicting, "Köln").conflicting()).isTrue();
        var yearless = requireNonNull(result.events().stream().filter(event -> event.title().equals("Ohne Jahr")).findFirst().orElseThrow());
        assertThat(yearless.starts()).containsExactly("14th Nov | 23:00");
        assertThat(EventNormalizer.date(requireNonNull(yearless.starts().getFirst()))).isNull();
        assertThat(result.complete()).isFalse();
    }

    @Test void brokenEmbeddedJsonCannotBecomeAnEventOrCompleteness() {
        var result = extract("<div id='root'></div><script type='application/json' data-noizy-embedded='assignment:state'>{broken</script>");
        assertThat(result.events()).isEmpty();
        assertThat(result.complete()).isFalse();
        assertThat(result.status()).isEqualTo("UNINTERPRETABLE");
    }

    @Test void urlLimitAndCanonicalizationKeepEverySafetyCheck() throws Exception {
        String prefix = "https://club.example/events?p=";
        String allowed = prefix + "a".repeat(SafeUrls.MAX_URL_LENGTH - prefix.length());
        assertThat(SafeUrls.parse(allowed).getHost()).isEqualTo("club.example");
        assertThatThrownBy(() -> SafeUrls.parse(allowed + "b")).isInstanceOf(IllegalArgumentException.class);
        assertThat(SafeUrls.parse("https://club.example/e?utm_source=x&b=2&a=1&b=3&=&gclid=y").getRawQuery()).isEqualTo("b=2&a=1");
        for (String unsafe : List.of("http://club.example/", "https://club.example:8443/", "https://127.0.0.1/",
                "https://user:pass@club.example/", "https://club.example/#fragment", "https://club.example./"))
            assertThatThrownBy(() -> SafeUrls.parse(unsafe)).isInstanceOf(IllegalArgumentException.class);
        assertThat(SafeUrls.isPublic(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(SafeUrls.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
    }
}
