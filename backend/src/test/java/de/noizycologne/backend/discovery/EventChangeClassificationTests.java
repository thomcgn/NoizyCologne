package de.noizycologne.backend.discovery;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class EventChangeClassificationTests {
    private EventExtractor.Event event(String title,String start,String state,String location,String description) {
        return new EventExtractor.Event("stable-uid",title,"https://club.example/one",List.of(start),description,state,location);
    }
    @Test void sameStableIdentityCanMoveAndTitleDateAreNotTheIdentity() {
        var old=event("Party","2026-11-07T23:00","","Club","DJs");
        var moved=event("Neue Edition","2026-11-08T23:00","","Club","DJs");
        assertThat(EventChangeMonitor.identity(old)).isEqualTo(EventChangeMonitor.identity(moved));
        assertThat(EventChangeMonitor.classify(old,moved,false)).isEqualTo("RESCHEDULE_DETECTED");
    }
    @Test void timeLocationTitleAndDescriptionChangesAreTracked() {
        var old=event("Party","2026-11-07T23:00","","Club","DJs");
        for(var changed:List.of(event("Party","2026-11-07T22:00","","Club","DJs"),event("Party","2026-11-07T23:00","","Anderer Club","DJs"),event("Party","2026-11-07T23:00","","Club","Neue Details")))
            assertThat(EventChangeMonitor.classify(old,changed,false)).isEqualTo("EVENT_CHANGED");
        assertThat(EventChangeMonitor.classify(old,event("Neue Party","2026-11-07T23:00","","Club","DJs"),false)).isEqualTo("POSSIBLE_REPLACEMENT");
        assertThat(EventChangeMonitor.classify(old,old,false)).isNull();
    }
    @Test void extractorSeparatesDjOutageConfirmedEventCancellationAndRumour() {
        var extractor=new EventExtractor();
        for(String text:List.of("DJ Example fällt aus. Party findet statt.","Party abgesagt.","Party vielleicht abgesagt.","DJ Example ist krank. Die Veranstaltung wird abgesagt.")) {
            var page=new EventPageFetcher.Page(URI.create("https://club.example/event"),"<article><h2>Party</h2><time datetime='2026-11-07T23:00'></time><p>"+text+"</p></article>","text/html");
            var event=extractor.extract(page,EventExtractor.Profile.defaults()).events().getFirst();
            String expected=text.contains("Party findet statt") ? "LINEUP_CHANGE" : text.contains("vielleicht") ? "CANCELLATION_HINT" : "EVENT_CANCELLED";
            assertThat(event.state()).contains(expected);
        }
    }
    @Test void onlyWholeWellFormedCalendarsCanProvideAbsenceEvidence() {
        var extractor=new EventExtractor();
        for(String body:List.of("BEGIN:VCALENDAR\nEND:VCALENDAR", "BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:Party\nEND:VEVENT\nEND:VCALENDAR", "BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:Party\nDTSTART:20261107\nEND:VEVENT")) {
            var result=extractor.extract(new EventPageFetcher.Page(URI.create("https://club.example/calendar.ics"),body,"text/calendar"),EventExtractor.Profile.defaults());
            assertThat(result.complete()).isEqualTo(body.equals("BEGIN:VCALENDAR\nEND:VCALENDAR"));
        }
    }
    @Test void canonicalDetailUrlIsStableAcrossTrackingLinksAndExternalCanonicalIsIgnored() {
        var extractor=new EventExtractor();
        String body="<link rel='canonical' href='https://club.example/events/stable'><h1>Party</h1><time datetime='2026-11-07T23:00'></time>";
        var result=extractor.extract(new EventPageFetcher.Page(URI.create("https://club.example/events/stable?utm_source=test"),body,"text/html"),EventExtractor.Profile.defaults());
        assertThat(result.events().getFirst().identity()).isEqualTo("https://club.example/events/stable");
        var untrusted=extractor.extract(new EventPageFetcher.Page(URI.create("https://club.example/one"),body.replace("https://club.example/events/stable","https://external.example/wrong"),"text/html"),EventExtractor.Profile.defaults());
        assertThat(untrusted.events().getFirst().identity()).isEqualTo("https://club.example/one");
    }

}
