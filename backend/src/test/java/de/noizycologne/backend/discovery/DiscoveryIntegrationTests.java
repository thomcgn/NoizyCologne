package de.noizycologne.backend.discovery;

import static java.util.Objects.requireNonNull;
import de.noizycologne.backend.venue.*;
import de.noizycologne.backend.party.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"noizy.discovery.enabled=false","logging.level.root=WARN"})
@ActiveProfiles("local")
class DiscoveryIntegrationTests {
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired DiscoveryStore store;
    @Autowired DiscoveryScheduler scheduler;
    @Autowired VenueRepository venues;
    @Autowired PartySeriesRepository parties;
    @Autowired EventImportService importer;
    @Autowired DiscoveryEvaluationService evaluation;
    @Autowired de.noizycologne.backend.calendar.CalendarEntryService calendar;
    final Set<String> extraParties=new HashSet<>();
    @MockitoBean VenueSourceDiscoveryService discovery;
    Venue venue; PartySeries party;
    String root="https://new-club.example/";
    @BeforeEach void setup() {
        venue=new Venue("Unbekannter Club " + UUID.randomUUID(), ""); venue.configureDiscovery(root,"",true); venue=venues.saveAndFlush(venue);
        party=parties.saveAndFlush(new PartySeries("Indie Party " + UUID.randomUUID(),"","#E11D48","manual",null,null,null,null,Set.of(),Set.of()));
    }
    @AfterEach void cleanup() {
        store.jdbc().update("DELETE FROM discovery_candidates WHERE venue_id=? OR party_id=?",venue.getId(),party.getId());
        store.jdbc().update("DELETE FROM calendar_entries WHERE party_id=?",party.getId());
        store.jdbc().update("DELETE FROM calendar_entries WHERE location_id=?",venue.getId());
        venues.deleteById(venue.getId()); parties.deleteById(party.getId());
        extraParties.forEach(parties::deleteById); extraParties.clear();
        store.jdbc().update("DELETE FROM discovery_runs");
    }
    EventExtractor.@org.jspecify.annotations.NonNull Event event(String identity,String title,String date,String state) {
        return new EventExtractor.Event(identity,title,root+"events/one",date==null ? List.of() : List.of(date),"Indie Party",state,"");
    }
    void found(EventExtractor.@org.jspecify.annotations.NonNull Event event) throws Exception {
        when(discovery.discover(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(List.of(event),"EVENTS_FOUND"))));
    }

    void foundEvents(List<EventExtractor.@org.jspecify.annotations.NonNull Event> events) throws Exception {
        when(discovery.discover(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(events,"EVENTS_FOUND"))));
    }
    @Test void programmeYearResolutionUpdatesLegacyRowsAndPreservesManualRejectedAndImportedDecisions() throws Exception {
        String html;
        try(var in=getClass().getResourceAsStream("/discovery/sonic-ballroom-parties.html")) { html=new String(requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); }
        var clock=Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"),ZoneOffset.UTC);
        var current=new EventExtractor(clock).extract(new EventPageFetcher.Page(URI.create(root),html,"text/html"),EventExtractor.Profile.defaults()).events();
        var labels=org.jsoup.Jsoup.parse(html).select("strong.date").eachText();
        List<EventExtractor.@org.jspecify.annotations.NonNull Event> legacy=new ArrayList<>();
        for(int i=0;i<current.size();i++) {
            var event=requireNonNull(current.get(i));
            legacy.add(new EventExtractor.Event(event.identity(),event.title(),event.url(),event.title().equals("Irish-Pogo") ? event.starts() : List.of(requireNonNull(labels.get(i))),event.evidence(),event.state(),event.location(),event.place()));
        }
        foundEvents(legacy);scheduler.scan();
        var rows=store.jdbc().queryForList("SELECT * FROM discovery_candidates WHERE venue_id=?",venue.getId());
        assertThat(rows).hasSize(22);var ids=rows.stream().map(row -> row.get("id")).toList();
        store.jdbc().update("UPDATE discovery_observations SET observed_at='2026-11-06T12:00:00Z' WHERE candidate_id IN (SELECT id FROM discovery_candidates WHERE venue_id=?)",venue.getId());
        var rejected=requireNonNull(rows.stream().filter(row -> "BallroomBlitz! mit DER GRAUE KLAUS".equals(row.get("title"))).findFirst().orElseThrow());
        var manual=requireNonNull(rows.stream().filter(row -> "BallroomBlitz! mit ROMAN EMPIRE".equals(row.get("title")) && evaluation.event(row).starts().contains("Freitag, 11. Dezember")).findFirst().orElseThrow());
        var imported=requireNonNull(rows.stream().filter(row -> "Shake Appeal mit BEATMIKE, FACE B & HOLLY WOOD".equals(row.get("title"))).findFirst().orElseThrow());
        store.jdbc().update("UPDATE discovery_candidates SET status='REJECTED',decision_origin='AUTOMATIC',reason='Nicht übernehmen',dm_text='Abgelehnt',inquiry_status='ANSWERED' WHERE id=?",rejected.get("id"));
        store.jdbc().update("UPDATE discovery_candidates SET decision_origin='MANUAL',party_id=?,event_date='2027-01-09',reason='Manuell entschieden',dm_text='Teamantwort',inquiry_status='ANSWERED' WHERE id=?",party.getId(),manual.get("id"));
        var entry=calendar.createEntry(new de.noizycologne.backend.calendar.CalendarEntryRequest("2026-12-05",venue.getId(),"event","Manuelle Kalendernotiz","9 €",party.getId(),"23:30",null,Set.of()));
        store.jdbc().update("UPDATE discovery_candidates SET status='IMPORTED',decision_origin='MANUAL',party_id=?,event_date='2026-12-05',calendar_entry_id=?,monitored_event=raw_event WHERE id=?",party.getId(),entry.id(),imported.get("id"));
        var calendarBefore=store.jdbc().queryForMap("SELECT * FROM calendar_entries WHERE id=?",entry.id());
        foundEvents(current);scheduler.scan();scheduler.scan();
        var after=store.jdbc().queryForList("SELECT * FROM discovery_candidates WHERE venue_id=?",venue.getId());
        assertThat(after).hasSize(22);assertThat(after.stream().map(row -> row.get("id"))).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(store.candidate((String)rejected.get("id"))).containsEntry("status","REJECTED").containsEntry("event_date",null).containsEntry("dm_text","Abgelehnt");
        assertThat(store.candidate((String)manual.get("id"))).containsEntry("decision_origin","MANUAL").containsEntry("party_id",party.getId()).containsEntry("dm_text","Teamantwort").containsEntry("inquiry_status","ANSWERED");
        assertThat(store.jdbc().queryForObject("SELECT event_date FROM discovery_candidates WHERE id=?",LocalDate.class,manual.get("id"))).isEqualTo(LocalDate.of(2027,1,9));
        assertThat(evaluation.event(store.candidate((String)manual.get("id"))).starts()).containsExactly("2026-12-11");
        assertThat(store.jdbc().queryForObject("SELECT event_date FROM discovery_candidates WHERE venue_id=? AND title='BallroomBlitz! mit ROMAN EMPIRE' AND id<>?",LocalDate.class,venue.getId(),manual.get("id"))).isEqualTo(LocalDate.of(2026,11,13));
        assertThat(store.jdbc().queryForMap("SELECT * FROM calendar_entries WHERE id=?",entry.id())).isEqualTo(calendarBefore);
        assertThat(store.jdbc().queryForObject("SELECT monitored_event::jsonb->'starts'->>0 FROM discovery_candidates WHERE id=?",String.class,imported.get("id"))).isEqualTo("2026-12-05");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM event_changes WHERE venue_id=?",Integer.class,venue.getId())).isZero();
    }
    @Test void reviewedBallroomBlitzWithoutDateReceivesVerifiedDateWithoutLosingDecision() throws Exception {
        String html;
        try(var in=getClass().getResourceAsStream("/discovery/sonic-ballroom-parties.html")) {
            html=new String(requireNonNull(in).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
        var events=new EventExtractor(Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"),ZoneOffset.UTC))
            .extract(new EventPageFetcher.Page(URI.create(root),html,"text/html"),EventExtractor.Profile.defaults()).events();
        var resolved=requireNonNull(events.stream().filter(event -> event.title().equals("BallroomBlitz! mit DER GRAUE KLAUS")).findFirst().orElseThrow());
        foundEvents(List.of(new EventExtractor.Event(resolved.identity(),resolved.title(),resolved.url(),List.of("Freitag, 04. Dezember"),
            resolved.evidence(),resolved.state(),resolved.location(),resolved.place())));
        scheduler.scan();
        String id=requireNonNull(store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId()));
        importer.review(id,party.getId(),null,"NEEDS_INQUIRY","ANSWERED","Belegte Teamentscheidung");
        String reason=(String)store.candidate(id).get("reason");
        foundEvents(List.of(requireNonNull(resolved)));scheduler.scan();scheduler.scan();
        var row=store.candidate(id);
        assertThat(evaluation.event(row).starts()).containsExactly("2026-12-04");
        assertThat(row).containsEntry("event_date",java.sql.Date.valueOf("2026-12-04"))
            .containsEntry("decision_origin","MANUAL").containsEntry("status","NEEDS_INQUIRY")
            .containsEntry("party_id",party.getId()).containsEntry("inquiry_status","ANSWERED")
            .containsEntry("dm_text","Belegte Teamentscheidung").containsEntry("reason",reason).containsEntry("calendar_entry_id",null);
        // Also repair the already refreshed raw-event/fingerprint state left by older scans.
        store.jdbc().update("UPDATE discovery_candidates SET event_date=NULL WHERE id=?",id);
        scheduler.scan();
        assertThat(store.candidate(id)).containsEntry("event_date",java.sql.Date.valueOf("2026-12-04"));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(requireNonNull(context)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/discovery/candidates/"+id))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.event_date").value("2026-12-04"));
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE venue_id=?",Integer.class,venue.getId())).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
    }
    @Test void conflictingWeekdayStaysReviewableAndNeverImportsKnownParty() throws Exception {
        String html="<main><div><strong class='date'>16.12.2026</strong><h1>Anchor Party</h1></div><div><strong class='date'>Donnerstag, 18. Dezember</strong><h1>"+party.getName()+"</h1><p>Indie Party, nach dem Konzert</p></div></main>";
        var events=new EventExtractor(Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"),ZoneOffset.UTC)).extract(new EventPageFetcher.Page(URI.create(root),html,"text/html"),EventExtractor.Profile.defaults()).events();
        foundEvents(events);scheduler.scan();
        var candidate=store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE venue_id=? AND title=?",venue.getId(),party.getName());
        assertThat(candidate).containsEntry("event_date",null).containsEntry("start_time",null).containsEntry("calendar_entry_id",null);
        assertThat(candidate.get("status")).isNotEqualTo("IMPORTED");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
    }
    @Test void ambiguousUndatedPredecessorsAreNotChosenOrDuplicatedAutomatically() throws Exception {
        var first=new EventExtractor.Event("",party.getName(),root,List.of("Freitag, 04. Dezember"),"Indie Party","","");
        var second=new EventExtractor.Event("",party.getName(),root,List.of("04.12."),"Indie Party","","");
        foundEvents(List.of(first,second));scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE venue_id=?",Integer.class,venue.getId())).isEqualTo(2);
        foundEvents(List.of(new EventExtractor.Event("",party.getName(),root,List.of("2026-12-04"),"Indie Party","","")));scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE venue_id=?",Integer.class,venue.getId())).isEqualTo(2);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
    }
    @Test void rejectedLegacyRowCannotBeAutomaticallyReimportedWhenItsYearBecomesKnown() throws Exception {
        foundEvents(List.of(new EventExtractor.Event("",party.getName(),root,List.of("Freitag, 04. Dezember"),"Indie Party","","")));scheduler.scan();
        String id=requireNonNull(store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId()));
        store.jdbc().update("UPDATE discovery_candidates SET status='REJECTED',decision_origin='AUTOMATIC',reason='Abgelehnt' WHERE id=?",id);
        foundEvents(List.of(new EventExtractor.Event("",party.getName(),root,List.of("2026-12-04"),"Indie Party","","")));scheduler.scan();scheduler.scan();
        assertThat(store.candidate(id)).containsEntry("status","REJECTED").containsEntry("calendar_entry_id",null).containsEntry("event_date",null);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE venue_id=?",Integer.class,venue.getId())).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
    }
    @Test void homepageOnlyImportsIdempotentlyAndChangesRequireReview() throws Exception {
        found(event("stable",party.getName(),"2027-01-09T23:00",""));
        scheduler.scan(); scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isEqualTo(1);
        var c=store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE venue_id=?",venue.getId());
        assertThat(c.get("status")).isEqualTo("IMPORTED"); assertThat(c.get("party_id")).isEqualTo(party.getId());
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM editorial_post_drafts WHERE calendar_entry_id=?",Integer.class,c.get("calendar_entry_id"))).isZero();
        found(event("stable",party.getName(),"2027-01-16T23:00","EventRescheduled")); scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT status FROM discovery_candidates WHERE id=?",String.class,c.get("id"))).isEqualTo("NEEDS_REVIEW");
        assertThat(store.jdbc().queryForObject("SELECT event_date FROM calendar_entries WHERE id=?",LocalDate.class,c.get("calendar_entry_id"))).isEqualTo(LocalDate.of(2027,1,9));
    }
    @Test void importedEventsUseSeriesDefaultsButPreferSourceValuesAndKeepRelativeTimesAndEdits() throws Exception {
        party.setDefaults(LocalTime.of(22,45),7);parties.saveAndFlush(requireNonNull(party));
        found(event("default",party.getName(),"2027-01-09",""));scheduler.scan();
        var inherited=store.jdbc().queryForMap("SELECT * FROM calendar_entries WHERE location_id=?",venue.getId());
        assertThat(requireNonNull(inherited.get("start_time")).toString()).isEqualTo("22:45:00");assertThat(inherited.get("entry_price")).isEqualTo("7 €");
        String id=(String)inherited.get("id");
        calendar.updateEntry(id,new de.noizycologne.backend.calendar.CalendarEntryRequest("2027-01-09",venue.getId(),"event","Manuell","9 €",party.getId(),"00:30",null,Set.of()));
        party.setDefaults(LocalTime.of(21,0),12);parties.saveAndFlush(requireNonNull(party));scheduler.scan();
        assertThat(store.jdbc().queryForMap("SELECT * FROM calendar_entries WHERE id=?",id)).containsEntry("entry_price","9 €").containsEntry("notes","Manuell");
        assertThat(store.jdbc().queryForObject("SELECT start_time FROM calendar_entries WHERE id=?",String.class,id)).isEqualTo("00:30:00");
        var specific=event("specific",party.getName(),"2027-01-16T23:30","");
        found(new EventExtractor.Event(specific.identity(),specific.title(),specific.url(),specific.starts(),"Eintritt: 15 €",specific.state(),specific.location()));scheduler.scan();
        assertThat(store.jdbc().queryForMap("SELECT * FROM calendar_entries WHERE location_id=? AND event_date='2027-01-16'",venue.getId())).containsEntry("entry_price","15 €");
        assertThat(store.jdbc().queryForObject("SELECT start_time FROM calendar_entries WHERE location_id=? AND event_date='2027-01-16'",String.class,venue.getId())).isEqualTo("23:30:00");
        var relative=event("relative",party.getName(),"2027-01-23","");
        found(new EventExtractor.Event(relative.identity(),relative.title(),relative.url(),relative.starts(),"Indie Party; Beginn: nach dem Konzert",relative.state(),relative.location()));scheduler.scan();
        assertThat(store.jdbc().queryForMap("SELECT * FROM calendar_entries WHERE location_id=? AND event_date='2027-01-23'",venue.getId())).containsEntry("start_time",null);
    }
    @Test void automaticImportsReachCalendarApiAndDeletedEntriesStopCountingWithoutLosingDecisions() throws Exception {
        Long before=store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates c JOIN calendar_entries e ON e.id=c.calendar_entry_id WHERE c.status='IMPORTED' AND c.reason='Automatisch übernommen'",Long.class);
        found(event("calendar-one",party.getName(),"2026-11-07T23:00","")); scheduler.scan();
        var first=store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE venue_id=?",venue.getId());
        String firstEntry=(String)first.get("calendar_entry_id");
        assertThat(first.get("status")).isEqualTo("IMPORTED");assertThat(firstEntry).isNotBlank();
        found(event("calendar-two",party.getName(),"2027-02-13T22:00","")); scheduler.scan();
        String secondEntry=store.jdbc().queryForObject("SELECT calendar_entry_id FROM discovery_candidates WHERE venue_id=? AND event_date='2027-02-13'",String.class,venue.getId());
        store.jdbc().update("UPDATE discovery_candidates SET decision_origin='MANUAL',dm_text='Geprüfte Teamantwort',inquiry_status='ANSWERED' WHERE calendar_entry_id=?",secondEntry);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(requireNonNull(context)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/calendar-entries"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[*].id").value(requireNonNull(org.hamcrest.Matchers.hasItems(firstEntry,secondEntry))));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/discovery"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.automaticallyImported").value(requireNonNull(before)+2));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/calendar-entries/"+firstEntry))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        var missing=store.candidate((String)first.get("id"));
        assertThat(missing.get("status")).isEqualTo("NEEDS_REVIEW");assertThat(missing.get("previous_status")).isEqualTo("IMPORTED");
        assertThat(missing.get("calendar_entry_id")).isNull();assertThat(missing.get("reason").toString()).contains("Kalendereintrag fehlt","Automatisch übernommen");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/discovery"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.automaticallyImported").value(before+1));
        found(event("calendar-one",party.getName(),"2026-11-07T23:00",""));scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE id=?",Integer.class,firstEntry)).isZero();
        var preserved=store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE calendar_entry_id=?",secondEntry);
        assertThat(preserved.get("status")).isEqualTo("IMPORTED");assertThat(preserved.get("decision_origin")).isEqualTo("MANUAL");
        assertThat(preserved.get("dm_text")).isEqualTo("Geprüfte Teamantwort");assertThat(preserved.get("inquiry_status")).isEqualTo("ANSWERED");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates c LEFT JOIN calendar_entries e ON e.id=c.calendar_entry_id WHERE c.status='IMPORTED' AND e.id IS NULL",Integer.class)).isZero();
        assertThatThrownBy(()->store.jdbc().update("UPDATE discovery_candidates SET status='IMPORTED' WHERE id=?",first.get("id")))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void manualReviewDmAndImportWithoutInventedTime() throws Exception {
        found(event("new","Neue Indie Party",null,"UNCONFIRMED")); scheduler.scan();
        var c=store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE venue_id=?",venue.getId()); String id=(String)c.get("id");
        assertThat(importer.dm(id)).contains(venue.getName(),"Neue Indie Party","Könnt ihr uns kurz bestätigen");
        importer.review(id,party.getId(),LocalDate.of(2027,1,9),"NEEDS_REVIEW","ANSWERED","Manuell bestätigte Antwort");
        importer.importCandidate(id,party.getId(),LocalDate.of(2027,1,9),true);
        importer.importCandidate(id,party.getId(),LocalDate.of(2027,1,9),true);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isEqualTo(1);
        assertThat(store.jdbc().queryForMap("SELECT start_time FROM calendar_entries WHERE location_id=?",venue.getId()).get("start_time")).isNull();
    }
    @Test void existingCalendarConflictAndRejectedCandidates() throws Exception {
        found(event("one",party.getName(),"2027-01-09","")); scheduler.scan();
        found(event("two",party.getName(),"2027-01-09","")); scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isEqualTo(1);
        assertThat(store.jdbc().queryForList("SELECT status FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId())).containsExactly("IMPORTED");
        found(event("unknown","Neue Indie Party","2027-01-10","")); scheduler.scan();
        String id=store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE title='Neue Indie Party' AND venue_id=?",String.class,venue.getId());
        importer.review(id,null,LocalDate.of(2027,1,10),"REJECTED","NONE","");
        scheduler.scan(); assertThat(store.candidate(id).get("status")).isEqualTo("REJECTED");
    }
    @Test void externalSourceRequiresExplicitApprovalAndRecheck() throws Exception {
        venue.configureDiscovery("",root,true); venues.saveAndFlush(venue);
        found(event("external",party.getName(),"2027-01-09","")); scheduler.scan();
        var c=store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE venue_id=?",venue.getId());
        assertThat(c.get("status")).isEqualTo("NEEDS_REVIEW");
        store.jdbc().update("UPDATE discovery_sources SET approved=true WHERE venue_id=?",venue.getId());
        scheduler.scan(); assertThat(store.candidate((String)c.get("id")).get("status")).isEqualTo("IMPORTED");
    }
    @Test void failedVenueDoesNotBlockOthersAndRunsArePersisted() throws Exception {
        when(discovery.discover(eq(root),any())).thenThrow(new EventPageFetcher.FetchFailure("BLOCKED"));
        var second=new Venue("Zweiter Club " + UUID.randomUUID(),""); second.configureDiscovery("https://other.example/","",true); second=venues.saveAndFlush(second);
        try {
            when(discovery.discover(eq("https://other.example/"),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create("https://other.example/"),new EventExtractor.Extraction(List.of(),"NO_EVENTS"))));
            scheduler.scan();
            assertThat(venues.findById(second.getId()).orElseThrow().getDiscoveryStatus()).isEqualTo("NO_EVENTS");
            assertThat(store.jdbc().queryForObject("SELECT status FROM discovery_runs ORDER BY started_at DESC LIMIT 1",String.class)).isEqualTo("PARTIAL");
        } finally { venues.deleteById(second.getId()); }
    }
    @Test void concurrentManualImportsCreateOnlyOneCalendarEntry() throws Exception {
        found(event("concurrent","Neue Indie Party","2027-01-09","")); scheduler.scan();
        String id=store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId());
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one=executor.submit(() -> importer.importCandidate(id,party.getId(),LocalDate.of(2027,1,9),true));
            var two=executor.submit(() -> importer.importCandidate(id,party.getId(),LocalDate.of(2027,1,9),true));
            one.get(10,java.util.concurrent.TimeUnit.SECONDS); two.get(10,java.util.concurrent.TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isEqualTo(1);
    }
    @Test void contradictoryBatchIsCheckedBeforeAnyAutomaticImport() throws Exception {
        when(discovery.discover(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(
                List.of(event("same-event",party.getName(),"2027-01-08",""),event("same-event",party.getName(),"2027-01-09","")),"EVENTS_FOUND"))));
        scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
        assertThat(store.jdbc().queryForObject("SELECT status FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId())).isEqualTo("NEEDS_INQUIRY");
        store.jdbc().update("DELETE FROM discovery_candidates WHERE venue_id=?",venue.getId());
        when(discovery.discover(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(
                List.of(event("same-event",party.getName(),"2027-01-09","EventCancelled"),event("same-event",party.getName(),"2027-01-09","EventScheduled")),"EVENTS_FOUND"))));
        scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
        assertThat(store.jdbc().queryForObject("SELECT status FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId())).isEqualTo("NEEDS_INQUIRY");
    }
    @Test void contradictoryTimesInOneBatchNeverCreateACalendarEntry() throws Exception {
        when(discovery.discover(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(
            List.of(event("same-event",party.getName(),"2027-01-09T22:00",""),
                event("same-event",party.getName(),"2027-01-09T23:00","")),"EVENTS_FOUND"))));
        scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
        var rows=store.jdbc().queryForList("SELECT * FROM discovery_candidates WHERE venue_id=?",venue.getId());
        assertThat(rows).hasSize(1);
        assertThat(requireNonNull(rows.getFirst())).containsEntry("status","NEEDS_INQUIRY").containsEntry("start_time",null)
            .containsEntry("calendar_entry_id",null);
    }
    @Test void apiSerializesDatesAndValidatesSources() throws Exception {
        found(event("api",party.getName(),"2027-01-09","")); scheduler.scan();
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(requireNonNull(context)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/discovery"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.candidates[0].event_date").value("2027-01-09"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/venues"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(requireNonNull(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/discovery/sources")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{\"venueId\":\""+venue.getId()+"\",\"url\":\"https://127.0.0.1/\",\"approved\":true}")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
    }
    @Test void navigationStatusDoesNotHideExplicitEmptyCalendar() throws Exception {
        when(discovery.discover(eq(root),any())).thenReturn(List.of(
            new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(List.of(),"SOURCE_LINKS_FOUND")),
            new VenueSourceDiscoveryService.Found(URI.create(root+"programm"),new EventExtractor.Extraction(List.of(),"NO_EVENTS"))));
        scheduler.scan();
        assertThat(venues.findById(venue.getId()).orElseThrow().getDiscoveryStatus()).isEqualTo("NO_EVENTS");
    }
    @Test void concertCannotBeImportedAndFreshScansPreserveManualReview() throws Exception {
        found(event("legacy","Neue Indie Party","2027-01-09","")); scheduler.scan();
        String id=store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId());
        store.jdbc().update("UPDATE discovery_candidates SET evidence='Indie Konzert mit DJs' WHERE id=?",id);
        importer.review(id,party.getId(),LocalDate.of(2027,1,9),"NEEDS_REVIEW","NONE","");
        var failure=org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> importer.importCandidate(id,party.getId(),LocalDate.of(2027,1,9),true));
        assertThat(failure.getMessage()).contains("Konzerte");
        found(new EventExtractor.Event("legacy","Neue Indie Party",root+"events/one",List.of("2027-01-09"),"Indie Konzert mit DJs","",""));
        scheduler.scan(); assertThat(store.candidate(id).get("status")).isEqualTo("NEEDS_REVIEW");
        found(new EventExtractor.Event("new-concert",party.getName(),root+"events/two",List.of("2027-01-10"),"Indie Konzert mit DJs","",""));
        scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE venue_id=?",Integer.class,venue.getId())).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
    }
    @Test void postgresLockPreventsConcurrentScan() throws Exception {
        try(var conn=java.util.Objects.requireNonNull(store.jdbc().getDataSource(), "Test database source missing").getConnection(); var stmt=conn.createStatement()) {
            stmt.execute("SELECT pg_advisory_lock(73624601)");
            try { scheduler.scan(); verifyNoInteractions(discovery); }
            finally { stmt.execute("SELECT pg_advisory_unlock(73624601)"); }
        }
    }
    private String candidateId() {
        return requireNonNull(store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE venue_id=? ORDER BY updated_at DESC LIMIT 1",String.class,venue.getId()));
    }
    private void middance() {
        venue.update("Blue Shell","");venue=venues.saveAndFlush(venue);
        party.update("Middance Crisis","","#E11D48","manual",null,null,null,null,Set.of(),Set.of(),Set.of("Indie"));
        party=parties.saveAndFlush(requireNonNull(party));
    }
    @Test void middanceCrisisAtBlueShellIsRecognizedWithoutSourceGenres() throws Exception {
        middance();found(event("middance-jan", "Middance Crisis – Anti-Herbstblues-Special", "2027-01-09", ""));scheduler.scan();
        var candidate=store.candidate(candidateId());
        assertThat(candidate).containsEntry("status","IMPORTED").containsEntry("party_id",party.getId());
        assertThat(calendar.getEntries()).anyMatch(entry -> entry.id().equals(candidate.get("calendar_entry_id")));
    }
    @Test void confirmationPersistsReusableAliasAndNextEditionImportsOnceWithoutApprovingOtherParties() throws Exception {
        middance();found(event("edition-one","MDC Clubnacht", "2027-01-09", ""));scheduler.scan();
        String id=candidateId();
        String calendarId=importer.confirm(id,party.getId(),LocalDate.of(2027,1,9),"ANSWERED","Termin bestätigt","MDC Clubnacht");
        assertThat(store.candidate(id)).containsEntry("status","IMPORTED").containsEntry("party_id",party.getId());
        assertThat(store.candidates("NEEDS_REVIEW")).noneMatch(row -> id.equals(row.get("id")));
        assertThat(store.candidates("NEEDS_INQUIRY")).noneMatch(row -> id.equals(row.get("id")));
        assertThat(calendar.getEntries()).anyMatch(entry -> entry.id().equals(calendarId));
        assertThat(importer.confirm(id,party.getId(),LocalDate.of(2027,1,9),"ANSWERED","","MDC Clubnacht")).isEqualTo(calendarId);
        scheduler.scan();
        found(event("edition-two","MDC Clubnacht – Anti-Herbstblues-Special","2027-01-16",""));scheduler.scan();scheduler.scan();
        assertThat(store.candidate(candidateId())).containsEntry("status","IMPORTED");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isEqualTo(2);
        assertThat(store.jdbc().queryForList("SELECT alias FROM party_series_aliases WHERE party_id=?",String.class,party.getId())).containsExactly("MDC Clubnacht");
        found(event("unrelated","Andere Indie Party","2027-01-23",""));scheduler.scan();
        assertThat(store.candidate(candidateId()).get("status")).isNotEqualTo("IMPORTED");
    }
    @Test void alreadyOpenAutomaticProposalUsesNewAliasEvenWhenItsFingerprintIsUnchanged() throws Exception {
        middance();found(event("edition-two","MDC Clubnacht","2027-01-16",""));scheduler.scan();String later=candidateId();
        found(event("edition-one","MDC Clubnacht","2027-01-09",""));scheduler.scan();String first=candidateId();
        importer.confirm(first,party.getId(),LocalDate.of(2027,1,9),"ANSWERED","","MDC Clubnacht");
        found(event("edition-two","MDC Clubnacht","2027-01-16",""));scheduler.scan();
        assertThat(store.candidate(later)).containsEntry("status","IMPORTED");
    }
    @Test void closureConflictRollsBackConfirmationAndNeverReportsSuccessfulImport() throws Exception {
        calendar.createEntry(new de.noizycologne.backend.calendar.CalendarEntryRequest("2027-01-09",venue.getId(),"closure","",null,null,"Geschlossen",Set.of()));
        found(event("blocked","MDC Clubnacht","2027-01-09",""));scheduler.scan();String id=candidateId();var before=store.candidate(id);
        var failure=org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> importer.confirm(id,party.getId(),LocalDate.of(2027,1,9),"ANSWERED","neu","MDC Clubnacht"));
        assertThat(failure.getStatusCode().value()).isEqualTo(409);assertThat(failure.getReason()).contains("Location-Sperre");
        assertThat(store.candidate(id)).containsEntry("status",before.get("status")).containsEntry("party_id",before.get("party_id"))
            .containsEntry("decision_origin",before.get("decision_origin")).containsEntry("calendar_entry_id",null).containsEntry("dm_text",before.get("dm_text"));
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM party_series_aliases WHERE party_id=?",Integer.class,party.getId())).isZero();
    }
    @Test void sameDayConcertAndSeparatelyIdentifiedAftershowRemainDistinctWithoutInventedStart() throws Exception {
        party.update("Live Konzert","","#E11D48","manual",null,null,null,null,Set.of(),Set.of(),Set.of());party=parties.saveAndFlush(requireNonNull(party));
        calendar.createEntry(new de.noizycologne.backend.calendar.CalendarEntryRequest("2027-01-09",venue.getId(),"event","",party.getId(),"20:00",null,Set.of()));
        var aftershow=parties.saveAndFlush(new PartySeries("Indie Aftershowparty","","#E11D48","manual",null,null,null,null,Set.of(),Set.of()));extraParties.add(aftershow.getId());
        found(new EventExtractor.Event("aftershow-one",aftershow.getName(),root+"aftershow",List.of("2027-01-09"),"Emo DJs; Beginn nach dem Konzert","",venue.getName()));
        scheduler.scan();scheduler.scan();
        assertThat(store.candidate(candidateId())).containsEntry("status","IMPORTED");
        assertThat(calendar.getEntries().stream().filter(e -> e.locationId().equals(venue.getId())).toList()).hasSize(2);
        var imported=store.jdbc().queryForMap("SELECT start_time,notes FROM calendar_entries WHERE location_id=? AND party_id=?",venue.getId(),aftershow.getId());
        assertThat(imported).containsEntry("start_time",null);assertThat(requireNonNull(imported.get("notes")).toString()).contains("nach dem Konzert");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM event_changes WHERE venue_id=?",Integer.class,venue.getId())).isZero();
    }
    @Test void knownSpecialDoesNotCreateAliasAndInvalidSpecialAliasRollsBack() throws Exception {
        middance();found(event("one","MDC Clubnacht Anti-Herbstblues-Special","2027-01-09",""));scheduler.scan();String id=candidateId();
        assertThatThrownBy(() -> importer.confirm(id,party.getId(),LocalDate.of(2027,1,9),"NONE","","MDC Clubnacht Anti-Herbstblues-Special"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(store.candidate(id)).containsEntry("calendar_entry_id",null);
        importer.confirm(id,party.getId(),LocalDate.of(2027,1,9),"NONE","","MDC Clubnacht");
        found(event("two","Middance Crisis Anti-Herbstblues-Special","2027-01-16",""));scheduler.scan();
        assertThat(store.jdbc().queryForList("SELECT alias FROM party_series_aliases WHERE party_id=?",String.class,party.getId())).containsExactly("MDC Clubnacht");
    }
    @Test void confirmationCannotBypassRejectedCancelledOrMovedEvents() throws Exception {
        for (String state:List.of("EventCancelled","EventPostponed","EventRescheduled")) {
            found(event(state,"Neue Indie Party", "2027-01-09",state));scheduler.scan();String id=candidateId();
            assertThatThrownBy(() -> importer.confirm(id,party.getId(),LocalDate.of(2027,1,9),"ANSWERED","",""))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(store.candidate(id)).containsEntry("calendar_entry_id",null);
        }
        found(event("rejected","Abgelehnte Indie Party","2027-01-10",""));scheduler.scan();String id=candidateId();
        importer.review(id,null,LocalDate.of(2027,1,10),"REJECTED","NONE","");
        assertThatThrownBy(() -> importer.confirm(id,party.getId(),LocalDate.of(2027,1,10),"ANSWERED","",""))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        scheduler.scan();assertThat(store.candidate(id)).containsEntry("status","REJECTED");
    }

    @Test void independentStableIdentitiesAndDifferentTimesInOneSeriesStaySeparateAcrossScans() throws Exception {
        var early=event("early",party.getName(),"2027-01-09T20:00","");
        var late=event("late",party.getName(),"2027-01-09T23:00","");
        when(discovery.discover(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(List.of(early,late),"EVENTS_FOUND"))));
        scheduler.scan();scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isEqualTo(2);
        assertThat(store.jdbc().queryForList("SELECT status FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId())).containsExactlyInAnyOrder("IMPORTED","IMPORTED");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM event_changes WHERE venue_id=?",Integer.class,venue.getId())).isZero();
    }
    @Test void manualConfirmationNeverApprovesAnExternalSourceForFutureAutomaticImports() throws Exception {
        venue.configureDiscovery("",root,true);venues.saveAndFlush(requireNonNull(venue));
        found(event("one","MDC Clubnacht","2027-01-09",""));scheduler.scan();
        importer.confirm(candidateId(),party.getId(),LocalDate.of(2027,1,9),"ANSWERED","","MDC Clubnacht");
        found(event("two","MDC Clubnacht","2027-01-16",""));scheduler.scan();
        assertThat(store.candidate(candidateId())).containsEntry("status","NEEDS_REVIEW");
        assertThat(store.jdbc().queryForObject("SELECT approved FROM discovery_sources WHERE venue_id=?",Boolean.class,venue.getId())).isFalse();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isEqualTo(1);
    }
    @Test void confirmationEndpointReportsActualClosureCauseAndRollsBackChoice() throws Exception {
        calendar.createEntry(new de.noizycologne.backend.calendar.CalendarEntryRequest("2027-01-09",venue.getId(),"closure","",null,null,"Geschlossen",Set.of()));
        found(event("blocked-api","MDC Clubnacht","2027-01-09",""));scheduler.scan();String id=candidateId();
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(requireNonNull(context)).build();
        mvc.perform(requireNonNull(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/discovery/candidates/"+id+"/confirm")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{\"partyId\":\""+party.getId()+"\",\"date\":\"2027-01-09\",\"inquiryStatus\":\"ANSWERED\",\"dmText\":\"bestätigt\"}")))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("Ganztägige Location-Sperre am bestätigten Datum"));
        assertThat(store.candidate(id)).containsEntry("calendar_entry_id",null).containsEntry("party_id",null);
    }

}
