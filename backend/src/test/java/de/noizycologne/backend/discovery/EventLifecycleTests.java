package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

class EventLifecycleTests {
    private EventExtractor.Event event(String state, String evidence) {
        return new EventExtractor.Event("stable-id", "NEON PARADISE", "https://club.example/event",
                List.of("2026-11-07T23:00"), evidence, state, "Club");
    }

    private EventExtractor.@NonNull Event html(@NonNull String text) {
        var page = new EventPageFetcher.Page(URI.create("https://club.example/event"),
                "<article><h2>NEON PARADISE</h2><time datetime='2026-11-07T23:00'></time><p>" + text + "</p></article>",
                "text/html");
        return requireNonNull(new EventExtractor().extract(page, EventExtractor.Profile.defaults()).events().getFirst());
    }

    @Test
    void technicalConfirmationLabelsAndTentativeDatesAreNotLifecycleChanges() {
        for (String state : List.of("UNCONFIRMED", "UNCONFIRMED Detailseite nicht eindeutig auswertbar",
                "UNCONFIRMED Textvorschau oder unvollständiges Datum", "TENTATIVE", "SPECULATIVE_TIMEZONE")) {
            assertThat(EventLifecycle.signal(event(state, "Indie Party"))).isEqualTo("ACTIVE");
        }
    }

    @Test
    void structuredSignalsKeepCancellationPostponementAndReschedulingSeparate() {
        assertThat(EventLifecycle.signal(event("CANCELLED", ""))).isEqualTo("CANCELLED");
        assertThat(EventLifecycle.signal(event("https://schema.org/EventCancelled", ""))).isEqualTo("CANCELLED");
        assertThat(EventLifecycle.signal(event("EVENT_CANCELLED UNCONFIRMED", ""))).isEqualTo("CANCELLED");
        assertThat(EventLifecycle.signal(event("POSTPONED", ""))).isEqualTo("POSTPONED");
        assertThat(EventLifecycle.signal(event("https://schema.org/EventPostponed", ""))).isEqualTo("POSTPONED");
        assertThat(EventLifecycle.signal(event("RESCHEDULED", ""))).isEqualTo("RESCHEDULED");
        assertThat(EventLifecycle.signal(event("https://schema.org/EventRescheduled", ""))).isEqualTo("RESCHEDULED");
    }

    @Test
    void unrelatedUncertaintyAndNegatedCancellationNeverBecomeCancellationHints() {
        assertThat(EventLifecycle.signal(html("Vielleicht gibt es einen zweiten Dancefloor."))).isEqualTo("ACTIVE");
        assertThat(EventLifecycle.signal(html("Die Party ist nicht abgesagt."))).isEqualTo("ACTIVE");
        assertThat(EventLifecycle.signal(html("The event is not cancelled."))).isEqualTo("ACTIVE");
        assertThat(EventLifecycle.signal(html("Die Party ist nicht verschoben."))).isEqualTo("ACTIVE");
    }

    @Test
    void cancellationRumoursRemainSeparateFromExplicitEventCancellation() {
        assertThat(EventLifecycle.signal(html("Die Party ist vielleicht abgesagt."))).isEqualTo("CANCELLATION_UNCONFIRMED");
        assertThat(EventLifecycle.signal(html("Party abgesagt?"))).isEqualTo("CANCELLATION_UNCONFIRMED");
        assertThat(EventLifecycle.signal(html("Die Veranstaltung wurde abgesagt."))).isEqualTo("CANCELLED");
    }

    @Test
    void djOutageNeverCancelsAnOtherwiseActiveParty() {
        assertThat(EventLifecycle.signal(html("DJ Example fällt aus. Party findet statt."))).isEqualTo("LINEUP_CHANGE");
        assertThat(EventLifecycle.signal(html("DJ Example fällt aus, aber die Party ist nicht abgesagt.")))
                .isEqualTo("LINEUP_CHANGE");
        assertThat(EventLifecycle.signal(html("DJ Example fällt aus. Die gesamte Veranstaltung wird abgesagt.")))
                .isEqualTo("CANCELLED");
    }

    @Test
    void explicitPostponementIsPreservedByHtmlExtraction() {
        var postponed = html("Die Party wurde verschoben.");
        assertThat(postponed.state()).contains("POSTPONED");
        assertThat(EventLifecycle.signal(postponed)).isEqualTo("POSTPONED");
    }

    @Test
    void cancellationInStructuredTitleIsNotLostWhenTheDescriptionIsEmpty() {
        String body = """
                <script type='application/ld+json'>
                {"@type":"Event","name":"NEON PARADISE abgesagt","startDate":"2026-11-07T23:00"}
                </script>
                """;
        var result = new EventExtractor().extract(new EventPageFetcher.Page(URI.create("https://club.example/event"),
                body, "text/html"), EventExtractor.Profile.defaults());
        assertThat(EventLifecycle.signal(requireNonNull(result.events().getFirst()))).isEqualTo("CANCELLED");
    }

    @Test
    void datedTextPreviewCanRemainActiveButItsExplicitCancellationIsPreserved() {
        String body = "<div><p>VORSCHAU</p><p>2026</p><p>07.11. NEON PARADISE</p><p>08.11. NEON PARADISE abgesagt</p></div>";
        var result = new EventExtractor().extract(new EventPageFetcher.Page(URI.create("https://club.example/programm"),
                body, "text/html"), EventExtractor.Profile.defaults());
        var active = requireNonNull(result.events().getFirst());
        assertThat(active.state()).contains("UNCONFIRMED");
        assertThat(active.starts()).containsExactly("2026-11-07");
        assertThat(EventLifecycle.signal(active)).isEqualTo("ACTIVE");
        assertThat(EventLifecycle.signal(requireNonNull(result.events().get(1)))).isEqualTo("CANCELLED");
    }

    @Test
    void unsupportedIcsTimezoneHasAnExplicitVerificationMarker() {
        String body = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:tz-test\nSUMMARY:NEON PARADISE\n"
                + "DTSTART;TZID=Unknown/Zone:20261107T230000\nEND:VEVENT\nEND:VCALENDAR";
        var result = new EventExtractor().extract(new EventPageFetcher.Page(URI.create("https://club.example/calendar.ics"),
                body, "text/calendar"), EventExtractor.Profile.defaults());
        var event = requireNonNull(result.events().getFirst());
        assertThat(event.state()).contains("SPECULATIVE_TIMEZONE");
        assertThat(EventLifecycle.signal(event)).isEqualTo("ACTIVE");
        assertThat(EventVerification.assess(event, "Club").status()).isEqualTo("SPECULATIVE");
    }
}
