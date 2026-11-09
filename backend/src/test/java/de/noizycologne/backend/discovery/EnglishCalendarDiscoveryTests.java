package de.noizycologne.backend.discovery;

import org.junit.jupiter.api.Test;
import org.jspecify.annotations.NonNull;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static java.util.Objects.requireNonNull;

class EnglishCalendarDiscoveryTests {
    private final EventExtractor extractor=new EventExtractor(Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"),ZoneOffset.UTC));
    private List<EventExtractor.@NonNull Event> events(String heading,String cards) {
        return extractor.extract(new EventPageFetcher.Page(URI.create("https://club.example/calendar"),
            "<section><h2>"+heading+"</h2><div>"+cards+"</div></section>","text/html"),EventExtractor.Profile.defaults()).events();
    }
    private String card(String title,String label,String genres) {
        return "<article><h3>"+title+"</h3><p class='date'>"+label+"</p><p>Genres: "+genres+"</p></article>";
    }
    @Test void darkUnicornRetainsOriginalTitleGenresAndContextualNovemberDate() {
        var event=requireNonNull(events("November 2026",card("DARK UNICORN by DJ Martin Heiland","14th Nov | 23:00","Dark Electro, EBM, New Wave, Synth, Wave, Post-Punk")).getFirst());
        assertThat(event.title()).isEqualTo("DARK UNICORN by DJ Martin Heiland");
        assertThat(event.starts()).containsExactly("2026-11-14T23:00");
        assertThat(event.evidence()).contains("Dark Electro, EBM, New Wave, Synth, Wave, Post-Punk");
        assertThat(PartySeriesRecognition.suggestedName(event.title())).isEqualTo("DARK UNICORN");
        assertThat(PartySeriesRecognition.containsName(event.title(),"Dark Unicorn")).isTrue();
        assertThat(PartyOnlyPolicy.concert(event)).isFalse();
        assertThat(EventVerification.assess(event,"").sufficient()).isTrue();
    }
    @Test void ordinalDatesKeepStartTimesAcrossAnchoredYearBoundaries() {
        var events=events("December 2026",card("Year End Party","31st Dec | 23:00","Emo")+card("New Year Party","1st Jan | 01:00","Emo"));
        assertThat(events).extracting(e->e.starts()).containsExactly(List.of("2026-12-31T23:00"),List.of("2027-01-01T01:00"));
        var january=events("January 2027",card("Year End Party","31st Dec | 23:00","Emo")+card("New Year Party","1st Jan | 01:00","Emo"));
        assertThat(january).extracting(e->e.starts()).containsExactly(List.of("2026-12-31T23:00"),List.of("2027-01-01T01:00"));
    }
    @Test void explicitYearsTakePrecedenceAndMissingYearsAreNeverReplacedWithTheCurrentYear() {
        for(String label:List.of("14th Nov 2027 | 23:00","14th Nov, 2027 | 23:00","November 14th, 2027 23:00","14 November 2027 at 23:00"))
            assertThat(requireNonNull(events("November 2026",card("Clubnacht",label,"Emo")).getFirst()).starts()).containsExactly("2027-11-14T23:00");
        var missing=requireNonNull(events("Upcoming Parties",card("Clubnacht","14th Nov | 23:00","Emo")).getFirst());
        assertThat(missing.starts()).noneMatch(start->EventNormalizer.date(start)!=null);
        assertThat(EventVerification.assess(missing,"").sufficient()).isFalse();
        var unparsedYear=requireNonNull(events("November 2026",card("Clubnacht","14th Nov / 2027 | 23:00","Emo")).getFirst());
        assertThat(unparsedYear.starts()).noneMatch(start->EventNormalizer.date(start)!=null);
        var invalid=requireNonNull(events("February 2027",card("Clubnacht","29th Feb | 23:00","Emo")).getFirst());
        assertThat(EventVerification.assess(invalid,"").sufficient()).isFalse();
    }
    @Test void concertEvidenceIsNotRemovedByOrdinalDateParsing() {
        var event=requireNonNull(events("November 2026",card("Live Konzert","14th Nov | 20:00","Dark Electro")).getFirst());
        assertThat(event.starts()).containsExactly("2026-11-14T20:00");
        assertThat(PartyOnlyPolicy.concert(event)).isTrue();
    }
}
