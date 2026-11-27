package de.noizycologne.backend.discovery.external;

import de.noizycologne.backend.discovery.EventNormalizer;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalCalendarParserTests {
    private static final @NonNull URI SOURCE = Objects.requireNonNull(URI.create("https://calendar.example.org/mtc.ics"));

    private static String fixture(String name) throws IOException {
        try (var in = ExternalCalendarParserTests.class.getResourceAsStream("/discovery/external/"+name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);
        }
    }

    @Test void icsKeepsUidStartEndLocationAndDescription() throws IOException {
        var parsed=ExternalCalendarParser.parse("ICS",fixture("approved-calendar.ics"),SOURCE);
        assertThat(parsed.extraction().status()).isEqualTo("EVENTS_FOUND");
        assertThat(parsed.extraction().complete()).isTrue();
        assertThat(parsed.extraction().events()).singleElement().satisfies(event -> {
            assertThat(event.identity()).isEqualTo("mtc-2026-11-27@example.org");
            assertThat(event.title()).isEqualTo("Indie Party");
            assertThat(event.starts()).containsExactly("2026-11-28T00:00");
            assertThat(EventNormalizer.date(event.starts().getFirst())).isEqualTo(java.time.LocalDate.of(2026,11,28));
            assertThat(event.location()).isEqualTo("Blue Shell, Luxemburger Straße 32, 50674 Köln");
            assertThat(event.evidence()).contains("DTEND=[20261128T020000Z]");
            assertThat(event.evidence()).contains("Indie, Emo Party");
        });
    }

    @Test void icsSkipsEventsWithoutTitleOrStartAndMarksFeedIncomplete() {
        String body="""
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:without-title@example.org
            DTSTART:20261204T200000
            DESCRIPTION:Kein Titel
            END:VEVENT
            BEGIN:VEVENT
            UID:without-start@example.org
            SUMMARY:Ohne Datum
            DESCRIPTION:Kein Start
            END:VEVENT
            END:VCALENDAR
            """;
        var parsed=ExternalCalendarParser.parse("ICS",body,SOURCE);
        assertThat(parsed.extraction().events()).isEmpty();
        assertThat(parsed.extraction().status()).isEqualTo("UNINTERPRETABLE");
        assertThat(parsed.extraction().complete()).isFalse();
        assertThat(parsed.diagnostics()).contains("MISSING_SUMMARY","MISSING_DTSTART");
    }

    @Test void icsMalformedOrTruncatedFeedCannotProduceEvents() {
        for (String body:new String[]{"kein Kalender","BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:x\nEND:VEVENT\n"}) {
            var parsed=ExternalCalendarParser.parse("ICS",body,SOURCE);
            assertThat(parsed.extraction().events()).isEmpty();
            assertThat(parsed.extraction().status()).isEqualTo("UNINTERPRETABLE");
            assertThat(parsed.extraction().complete()).isFalse();
            assertThat(parsed.diagnostics()).contains("INCOMPLETE_CALENDAR");
        }
    }

    @Test void icsForeignTimezoneIsKeptAsUnconfirmedEvidence() {
        String body="""
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:tz@example.org
            DTSTART;TZID=America/New_York:20261127T200000
            SUMMARY:Indie Party
            END:VEVENT
            END:VCALENDAR
            """;
        var parsed=ExternalCalendarParser.parse("ICS",body,SOURCE);
        assertThat(parsed.extraction().events()).singleElement().satisfies(event -> {
            assertThat(EventNormalizer.date(event.starts().getFirst())).isEqualTo(java.time.LocalDate.of(2026,11,27));
            assertThat(event.state()).contains("SPECULATIVE_TIMEZONE");
        });
    }

    @Test void jsonArrayKeepsAfterMidnightDateAndLocation() throws IOException {
        var parsed=ExternalCalendarParser.parse("JSON",fixture("events.json"),SOURCE);
        assertThat(parsed.extraction().status()).isEqualTo("EVENTS_FOUND");
        assertThat(parsed.extraction().complete()).isTrue();
        assertThat(parsed.extraction().events()).singleElement().satisfies(event -> {
            assertThat(event.title()).isEqualTo("After Midnight");
            assertThat(event.identity()).isEqualTo("mtc-2026-11-28@example.org");
            assertThat(event.starts()).containsExactly("2026-11-27T23:30:00+01:00");
            assertThat(EventNormalizer.date(event.starts().getFirst())).isEqualTo(java.time.LocalDate.of(2026,11,27));
            assertThat(event.location()).isEqualTo("Blue Shell");
            assertThat(event.evidence()).contains("DTEND=[2026-11-28T02:00:00+01:00]");
            assertThat(event.url()).isEqualTo("https://calendar.example.org/events/after-midnight");
        });
    }

    @Test void jsonSkipsInvalidEntriesWithoutInventingFields() {
        String body="""
            [
              {"uid":"no-start","title":"Ohne Datum"},
              7,
              {"uid":"valid","title":"Indie Party","start":"20261204T200000","description":"Indie"}
            ]
            """;
        var parsed=ExternalCalendarParser.parse("JSON",body,SOURCE);
        assertThat(parsed.extraction().events()).singleElement().satisfies(event -> {
            assertThat(event.title()).isEqualTo("Indie Party");
            assertThat(event.identity()).isEqualTo("valid");
        });
        assertThat(parsed.extraction().status()).isEqualTo("PARTIAL_FAILURE");
        assertThat(parsed.extraction().complete()).isFalse();
        assertThat(parsed.diagnostics()).contains("INVALID_JSON_ENTRIES: 2");
    }

    @Test void jsonMalformedRootAndSyntaxAreRejected() {
        var object=ExternalCalendarParser.parse("JSON","{\"events\":[]}",SOURCE);
        assertThat(object.extraction().events()).isEmpty();
        assertThat(object.extraction().status()).isEqualTo("UNINTERPRETABLE");
        assertThat(object.diagnostics()).contains("NOT_AN_ARRAY");
        var broken=ExternalCalendarParser.parse("JSON","[{\"title\":\"x\"",SOURCE);
        assertThat(broken.extraction().events()).isEmpty();
        assertThat(broken.extraction().status()).isEqualTo("UNINTERPRETABLE");
        assertThat(broken.diagnostics()).contains("INVALID_JSON");
    }

    @Test void unknownFormatAndOversizedBodyAreRejected() {
        assertThat(ExternalCalendarParser.parse("HTML","BEGIN:VCALENDAR",SOURCE).extraction().status()).isEqualTo("UNSUPPORTED_FORMAT");
        var oversized=ExternalCalendarParser.parse("ICS","x".repeat(ExternalCalendarParser.MAX_BODY_CHARS+1),SOURCE);
        assertThat(oversized.extraction().status()).isEqualTo("RESPONSE_TOO_LARGE");
        assertThat(oversized.extraction().events()).isEmpty();
    }
}
