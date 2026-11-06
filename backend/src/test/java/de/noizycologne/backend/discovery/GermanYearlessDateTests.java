package de.noizycologne.backend.discovery;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.*;

class GermanYearlessDateTests {
    private final Clock clock=Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"),ZoneOffset.UTC);
    private List<EventExtractor.@NonNull Event> extract(String html) { return extract(html,clock); }
    private List<EventExtractor.@NonNull Event> extract(String html,Clock fetched) {
        return new EventExtractor(fetched).extract(new EventPageFetcher.Page(URI.create("https://unrelated.example/programme"),requireNonNull(html),"text/html"),EventExtractor.Profile.defaults()).events();
    }
    private String card(String date,String title) { return "<div><strong class='date'>"+date+"</strong><h1>"+title+"</h1><p>Nach dem Konzert, Eintritt frei!</p></div>"; }
    private EventExtractor.@NonNull Event named(List<EventExtractor.@NonNull Event> events,String title) {
        return requireNonNull(events.stream().filter(e -> e.title().equals(title)).findFirst().orElseThrow());
    }
    @Test void sonicFixtureUsesProgrammeAnchorForDecemberAndJanuaryOnAnyDomain() throws Exception {
        String html;
        try(var in=getClass().getResourceAsStream("/discovery/sonic-ballroom-parties.html")) {html=new String(requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);}
        var events=extract(html);assertThat(events).hasSize(22);
        assertThat(events.stream().map(EventExtractor.Event::title)).containsExactlyElementsOf(org.jsoup.Jsoup.parse(html).select("h1").eachText());
        String anchoredOnly=html.replace("HEUTE","Freitag, 06. November").replace("MORGEN","Samstag, 07. November");
        var withoutRelative=extract(anchoredOnly,Clock.fixed(Instant.parse("2040-06-01T00:00:00Z"),ZoneOffset.UTC));
        assertThat(named(withoutRelative,"BallroomBlitz! mit DER GRAUE KLAUS").starts()).containsExactly("2026-12-04");
        assertThat(named(withoutRelative,"Shake Appeal mit KEVIN THE K").starts()).containsExactly("2027-01-02");
        assertThat(named(events,"BallroomBlitz! mit DER GRAUE KLAUS").starts()).containsExactly("2026-12-04");
        assertThat(named(events,"Shake Appeal mit BEATMIKE, FACE B & HOLLY WOOD").starts()).containsExactly("2026-12-05");
        assertThat(events.stream().filter(e -> e.title().equals("BallroomBlitz! mit ROMAN EMPIRE")).flatMap(e -> e.starts().stream())).containsExactly("2026-11-13","2026-12-11");
        assertThat(named(events,"Irish-Pogo").starts()).containsExactly("2026-12-16");
        assertThat(named(events,"Shake Appeal mit KEVIN THE K").starts()).containsExactly("2027-01-02");
        assertThat(named(events,"BallroomBlitz! mit STEVIE").starts()).containsExactly("2027-01-08");
        for(var event:events) assertThat(event.starts()).noneMatch(start -> EventNormalizer.time(start)!=null);
        assertThat(EventTiming.relativeStart(named(events,"BallroomBlitz! mit DER GRAUE KLAUS"))).isEqualTo("nach dem Konzert");
        var inputs=events.stream().map(event -> new DiscoveryBatchMerger.Input("one-list",event,true,true)).toList();
        assertThat(DiscoveryBatchMerger.merge(inputs,"")).hasSize(22);
    }
    @Test void namedAbbreviatedAndNumericDaysResolveFromALaterFullAnchor() {
        var events=extract(card("Freitag, 04. Dezember","A")+card("Sa. 05. Dez.","B")+card("06.12.","C")+card("16.12.2026","Anchor"));
        assertThat(named(events,"A").starts()).containsExactly("2026-12-04");
        assertThat(named(events,"B").starts()).containsExactly("2026-12-05");
        assertThat(named(events,"C").starts()).containsExactly("2026-12-06");
        var sparse=extract(card("30.11.2026","November")+card("Sa. 02. Jan.","New year"));
        assertThat(named(sparse,"New year").starts()).containsExactly("2027-01-02");
    }
    @Test void relativeLabelsUseTheBerlinFetchDayIncludingMidnightDstAndYearChange() {
        var berlinMidnight=Clock.fixed(Instant.parse("2026-12-31T23:30:00Z"),ZoneOffset.UTC);
        var events=extract(card("HEUTE","Today")+card("MORGEN","Tomorrow")+card("So. 03. Jan.","Following"),berlinMidnight);
        assertThat(named(events,"Today").starts()).containsExactly("2027-01-01");
        assertThat(named(events,"Tomorrow").starts()).containsExactly("2027-01-02");
        assertThat(named(events,"Following").starts()).containsExactly("2027-01-03");
        var summer=extract(card("HEUTE","Summer"),Clock.fixed(Instant.parse("2026-07-01T22:30:00Z"),ZoneOffset.UTC));
        assertThat(named(summer,"Summer").starts()).containsExactly("2026-07-02");
    }
    @Test void noCurrentYearGuessNoCrossSectionAnchorsAndNoOutOfOrderRollover() {
        var noAnchor=extract(card("Fr. 04.12.","Unanchored"));
        assertThat(named(noAnchor,"Unanchored").starts()).noneMatch(start -> EventNormalizer.date(start)!=null);
        var separate=extract("<section>"+card("04.12.","Unknown")+"</section><section>"+card("16.12.2026","Other list")+"</section>");
        assertThat(named(separate,"Unknown").starts()).noneMatch(start -> EventNormalizer.date(start)!=null);
        var unordered=extract(card("16.12.2026","Anchor")+card("04.12.","Out of order"));
        assertThat(EventVerification.assess(named(unordered,"Out of order"),"").sufficient()).isFalse();
    }
    @Test void inconsistentAnchorsWeekdaysAndLeapDaysRemainReviewable() {
        var events=extract(card("16.12.2026","A")+card("Fr. 18.12.","Unknown")+card("19.12.2027","B"));
        assertThat(named(events,"Unknown").starts()).noneMatch(start -> EventNormalizer.date(start)!=null);
        var wrongDay=extract(card("16.12.2026","Anchor")+card("Donnerstag, 18. Dezember","Wrong"));
        assertThat(EventVerification.assess(named(wrongDay,"Wrong"),"").sufficient()).isFalse();
        assertThat(named(wrongDay,"Wrong").state()).contains("SOURCE_CONFLICT");
        var wrongAnchor=extract(card("Donnerstag, 16.12.2026","Bad anchor")+card("18.12.","No reliable year"));
        assertThat(EventVerification.assess(named(wrongAnchor,"Bad anchor"),"").sufficient()).isFalse();
        assertThat(named(wrongAnchor,"No reliable year").starts()).noneMatch(start -> EventNormalizer.date(start)!=null);
        var leap=extract(card("28.02.2027","Anchor")+card("29.02.","Invalid leap day"));
        assertThat(EventVerification.assess(named(leap,"Invalid leap day"),"").sufficient()).isFalse();
    }
    @Test void structuredHtmlCardsPreserveExplicitTimesAndValidateWeekdays() {
        var events=extract("<main><article><h2>Anchor</h2><time datetime='2026-12-04T23:30'>Freitag, 04. Dezember</time></article><article><h2>Next</h2><time>Sa. 05. Dez.</time></article></main>");
        assertThat(named(events,"Anchor").starts()).containsExactly("2026-12-04T23:30");
        assertThat(named(events,"Next").starts()).containsExactly("2026-12-05");
        var configured=new EventExtractor(clock).extract(new EventPageFetcher.Page(URI.create("https://configured.example/"),"<article><h2>Custom</h2><span class='date'>01.01.2020</span><time class='start' datetime='2026-12-04T23:30'>Freitag, 04. Dezember</time></article>","text/html"),new EventExtractor.Profile("article","h2","time.start"));
        assertThat(named(configured.events(),"Custom").starts()).containsExactly("2026-12-04T23:30");
        assertThat(EventVerification.assess(named(configured.events(),"Custom"),"").sufficient()).isTrue();
    }
    @Test void legacyMatchingRequiresTheSourceDayAndDoesNotCollapseSeriesEditions() {
        var old=new EventExtractor.Event("","Same series","",List.of("Freitag, 04. Dezember"),"","","");
        assertThat(GermanProgramText.sameUndatedOccurrence(old,LocalDate.of(2026,12,4),null)).isTrue();
        assertThat(GermanProgramText.sameUndatedOccurrence(old,LocalDate.of(2026,12,11),null)).isFalse();
        var relative=new EventExtractor.Event("","Same series","",List.of("MORGEN"),"","","");
        assertThat(GermanProgramText.sameUndatedOccurrence(relative,LocalDate.of(2027,1,1),LocalDate.of(2026,12,31))).isTrue();
        assertThat(GermanProgramText.sameUndatedOccurrence(relative,LocalDate.of(2027,1,1),null)).isFalse();
    }
}
