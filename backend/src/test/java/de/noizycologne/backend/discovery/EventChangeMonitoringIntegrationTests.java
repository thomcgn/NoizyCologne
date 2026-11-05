package de.noizycologne.backend.discovery;

import de.noizycologne.backend.cancellation.*;
import de.noizycologne.backend.calendar.CalendarEntryService;
import de.noizycologne.backend.calendar.CalendarEntryRequest;
import de.noizycologne.backend.editorial.*;
import de.noizycologne.backend.party.*;
import de.noizycologne.backend.venue.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"noizy.discovery.enabled=false","logging.level.root=WARN"})
@ActiveProfiles("local")
class EventChangeMonitoringIntegrationTests {
    @Autowired DiscoveryStore store;
    @Autowired CalendarEntryService calendar;
    @Autowired EventImportService importer;
    @Autowired DiscoveryScheduler scheduler;
    @Autowired EventChangeMonitor monitor;
    @Autowired EventChangeService changes;
    @Autowired CancellationService cancellations;
    @Autowired PublishEligibilityService eligibility;
    @Autowired EditorialPostDraftRepository drafts;
    @Autowired EditorialPostDraftService draftService;
    @Autowired de.noizycologne.backend.media.PartyFlyerService flyers;
    @Autowired VenueRepository venues;
    @Autowired PartySeriesRepository parties;
    @MockitoBean VenueSourceDiscoveryService discovery;
    Venue venue;
    PartySeries party;
    String root="https://monitor.example/";
    LocalDate day=LocalDate.now(ZoneId.of("Europe/Berlin")).plusDays(2);
    @BeforeEach void setup() {
        venue=new Venue("Monitor "+UUID.randomUUID(),"monitor_club");venue.configureDiscovery(root,"",true);venue=venues.saveAndFlush(venue);
        party=parties.saveAndFlush(new PartySeries("Teenage Dirtbag "+UUID.randomUUID(),"","#E11D48","manual",null,null,null,null,Set.of(),Set.of()));
    }
    @AfterEach void cleanup() {
        store.jdbc().update("DELETE FROM calendar_entries WHERE location_id=?",venue.getId());
        venues.deleteById(venue.getId());parties.deleteById(party.getId());
    }
    EventExtractor.@org.jspecify.annotations.NonNull Event event(String title,LocalDate date,String state) {
        return new EventExtractor.Event("uid-"+venue.getId(),title,root+"events/stable",List.of(date+"T23:00:00"),"Offizielle Partyankündigung",state,venue.getName());
    }
    void scan(EventExtractor.@org.jspecify.annotations.NonNull Event event) throws Exception {
        when(discovery.discover(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(List.of(event),"EVENTS_FOUND"))));scheduler.scan();
    }
    String imported() { return store.jdbc().queryForObject("SELECT id FROM calendar_entries WHERE location_id=?",String.class,venue.getId()); }
    Map<String,Object> change(String kind) { return store.jdbc().queryForMap("SELECT * FROM event_changes WHERE venue_id=? AND kind=? ORDER BY created_at LIMIT 1",venue.getId(),kind); }
    EventChangeService.Decision decision(Map<String,Object> change,String action,String trigger) {
        return new EventChangeService.Decision(((Number)change.get("revision")).longValue(),action,"Redaktionell bestätigt", "", "",party.getId(),venue.getId(),day.plusDays(1).toString(),"22:00",trigger,"PERSONALBEDINGTER_AUSFALL",null);
    }
    @Test void stableIdentityMoveBlocksOriginalAndKeepsEditorialInputs() throws Exception {
        scan(event(party.getName(),day,""));String entry=imported();
        drafts.saveAndFlush(new EditorialPostDraft(entry,"Original Caption","#original","APPROVED",Set.of(),Set.of("Emo")));
        scan(event(party.getName(),day.plusDays(1),""));
        var change=change("RESCHEDULE_DETECTED");
        assertThat(store.jdbc().queryForObject("SELECT event_date FROM calendar_entries WHERE id=?",LocalDate.class,entry)).isEqualTo(day);
        assertThat(eligibility.blockers(entry)).isNotEmpty();
        var draft=drafts.findByCalendarEntryId(entry).orElseThrow();
        assertThat(draft.getCaption()).isEqualTo("Original Caption");assertThat(draft.getStatus()).isEqualTo("APPROVED");assertThat(draft.getGenres()).containsExactly("Emo");assertThat(draft.isApprovalNeedsReview()).isTrue();
        assertThatThrownBy(() -> eligibility.requireOriginalApproval(entry)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        changes.decide((String)change.get("id"),decision(change,"UPDATE_CALENDAR",null));
        assertThat(store.jdbc().queryForObject("SELECT event_date FROM calendar_entries WHERE id=?",LocalDate.class,entry)).isEqualTo(day.plusDays(1));
        assertThat(eligibility.blockers(entry)).isEmpty();assertThat(drafts.findByCalendarEntryId(entry).orElseThrow().isApprovalNeedsReview()).isTrue();
        assertThatThrownBy(() -> changes.decide((String)change.get("id"),decision(change,"RELEASE",null))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void confirmedOfficialCancellationIsIdempotentAndCreatesSeparateGraphicDraft() throws Exception {
        scan(event(party.getName(),day,""));String entry=imported();
        var background=new java.awt.image.BufferedImage(200,200,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics=background.createGraphics();graphics.setColor(java.awt.Color.BLUE);graphics.fillRect(0,0,200,200);graphics.dispose();
        var bytes=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(background,"png",bytes);
        var originalFlyer=flyers.upload(party.getId(),new org.springframework.mock.web.MockMultipartFile("file","original.png","image/png",bytes.toByteArray()));
        drafts.saveAndFlush(new EditorialPostDraft(entry,"Original","#tag","APPROVED",Set.of(),Set.of()));
        scan(event(party.getName(),day,"EVENT_CANCELLED"));var change=change("CANCELLATION_DETECTED");
        // A detected cancellation is withheld for editorial confirmation, never a public announcement.
        assertThat(cancellations.list()).noneMatch(row -> entry.equals(row.get("calendar_entry_id")));
        changes.decide((String)change.get("id"),decision(change,"CANCEL","OFFICIAL_SOURCE"));
        String cancellation=cancellations.confirm(entry,(String)change.get("id"),"OFFICIAL_SOURCE","PERSONALBEDINGTER_AUSFALL",null);
        String media=cancellations.prepareGraphic(cancellation);assertThat(cancellations.prepareGraphic(cancellation)).isEqualTo(media);
        assertThat(cancellations.graphic(cancellation).contentLength()).isPositive();
        assertThat(media).isNotEqualTo(originalFlyer.id());
        try(var input=flyers.download(party.getId()).resource().getInputStream()) { assertThat(input.readAllBytes()).isEqualTo(bytes.toByteArray()); }
        String draftId=drafts.findByCalendarEntryId(entry).orElseThrow().getId();
        assertThatThrownBy(() -> draftService.update(draftId,new EditorialPostDraftRequest("Original","#tag","APPROVED",Set.of(),Set.of()))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(draftService.getDrafts().stream().filter(draft -> draft.calendarEntryId().equals(entry)).findFirst().orElseThrow().publicationBlockers()).isNotEmpty();
        assertThat(cancellations.get(cancellation).get("announcement_status")).isEqualTo("DRAFT");
        assertThat((String)cancellations.get(cancellation).get("announcement_caption")).doesNotContain("krank","Diagnose");
        assertThat(drafts.findByCalendarEntryId(entry).orElseThrow().getCaption()).isEqualTo("Original");
        assertThat(drafts.findByCalendarEntryId(entry).orElseThrow().getStatus()).isEqualTo("APPROVED");
        scan(event(party.getName(),day,"EVENT_CANCELLED"));
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM event_cancellations WHERE calendar_entry_id=?",Long.class,entry)).isEqualTo(1L);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM event_changes WHERE venue_id=? AND kind='CANCELLATION_DETECTED'",Long.class,venue.getId())).isEqualTo(1L);
        assertThat(eligibility.blockers(entry)).isNotEmpty();
    }
    @Test void replacementDoesNotOverwritePartyAndTwoEventsAreNotAReplacement() throws Exception {
        var old=event(party.getName(),day,"");scan(old);String entry=imported();
        var replacement=new EventExtractor.Event("new-id","Neon Paradise",root+"events/neon",List.of(day+"T23:00:00"),"Emo Party","",venue.getName());
        when(discovery.discover(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(List.of(old,replacement),"EVENTS_FOUND"))));scheduler.scan();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM event_changes WHERE venue_id=? AND kind='POSSIBLE_REPLACEMENT'",Long.class,venue.getId())).isZero();
        when(discovery.discover(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(List.of(replacement),"EVENTS_FOUND"))));scheduler.scan();
        assertThat(change("POSSIBLE_REPLACEMENT").get("calendar_entry_id")).isEqualTo(entry);
        assertThat(store.jdbc().queryForObject("SELECT party_id FROM calendar_entries WHERE id=?",String.class,entry)).isEqualTo(party.getId());
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Long.class,venue.getId())).isEqualTo(1L);
        assertThat(changes.dm((String)change("POSSIBLE_REPLACEMENT").get("id"))).contains("Neon Paradise",venue.getName(),party.getName());
    }
    @Test void incompleteCrawlCannotMarkMissingAndTwoCompleteChecksOnlyWarn() throws Exception {
        scan(event(party.getName(),day,""));String entry=imported();
        String source=store.jdbc().queryForObject("SELECT source_id FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId());
        monitor.missing(venue,source,List.of(),"failed",false);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM event_changes WHERE venue_id=?",Long.class,venue.getId())).isZero();
        monitor.missing(venue,source,List.of(),"first",true);monitor.missing(venue,source,List.of(),"first",true);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM event_changes WHERE venue_id=?",Long.class,venue.getId())).isZero();
        monitor.missing(venue,source,List.of(),"second",true);assertThat(change("SOURCE_MISSING")).isNotEmpty();
        assertThat(store.jdbc().queryForObject("SELECT event_status FROM calendar_entries WHERE id=?",String.class,entry)).isEqualTo("ACTIVE");
        assertThat(cancellations.list()).noneMatch(row -> entry.equals(row.get("calendar_entry_id")));
    }
    @Test void djOnlyAndRumoursNeverCancelEventWhileDmCanConfirmIt() throws Exception {
        scan(event(party.getName(),day,""));String entry=imported();
        scan(event(party.getName(),day,"LINEUP_CHANGE UNCONFIRMED"));var lineup=change("LINEUP_CHANGE");
        changes.decide((String)lineup.get("id"),decision(lineup,"DJ_ONLY",null));
        assertThat(store.jdbc().queryForObject("SELECT event_status FROM calendar_entries WHERE id=?",String.class,entry)).isEqualTo("ACTIVE");
        assertThat(eligibility.blockers(entry)).isNotEmpty();
        scan(event(party.getName(),day,"CANCELLATION_HINT UNCONFIRMED"));var rumour=change("CANCELLATION_UNCONFIRMED");
        assertThatThrownBy(() -> cancellations.confirm(entry,(String)rumour.get("id"),"OFFICIAL_SOURCE","UNSPECIFIED",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cancellations.confirm(entry,(String)rumour.get("id"),"DM_CONFIRMED","UNSPECIFIED",null)).isInstanceOf(IllegalArgumentException.class);
        var answer=new EventChangeService.Decision(((Number)rumour.get("revision")).longValue(),"ANSWER","Location bestätigt", "Frage", "Veranstaltung fällt vollständig aus",null,null,null,null,null,null,null);
        changes.decide((String)rumour.get("id"),answer);
        cancellations.confirm(entry,(String)rumour.get("id"),"DM_CONFIRMED","ORGANISATORISCH",null);
        assertThat(store.jdbc().queryForObject("SELECT event_status FROM calendar_entries WHERE id=?",String.class,entry)).isEqualTo("CANCELLED");
    }
    @Test void externalHealthDetailsAreNotPersisted() throws Exception {
        scan(new EventExtractor.Event("health-id",party.getName(),root+"events/health",List.of(day+"T23:00:00"),"DJ hat eine private Diagnose und ist krank", "LINEUP_CHANGE",venue.getName()));
        assertThat(store.jdbc().queryForObject("SELECT evidence FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId())).doesNotContain("private Diagnose");
        assertThat(store.jdbc().queryForObject("SELECT event_data FROM discovery_observations WHERE candidate_id=(SELECT id FROM discovery_candidates WHERE venue_id=?) LIMIT 1",String.class,venue.getId())).doesNotContain("private Diagnose");
    }
    @Test void manuallyCreatedCalendarEntryIsLinkedWithoutDuplicationAndThenMonitored() throws Exception {
        String entry=calendar.createEntry(new CalendarEntryRequest(day.toString(),venue.getId(),"event","Manuell",party.getId(),"23:00",null,Set.of())).id();
        scan(event(party.getName(),day,""));
        assertThat(store.jdbc().queryForObject("SELECT calendar_entry_id FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId())).isEqualTo(entry);
        scan(event(party.getName(),day,"EVENT_CANCELLED UNCONFIRMED"));
        assertThat(change("CANCELLATION_DETECTED").get("calendar_entry_id")).isEqualTo(entry);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Long.class,venue.getId())).isEqualTo(1L);
    }
    @Test void reusedIdentityCreatesIndependentReplacementAndConfirmedCancellationAllowsReviewedImport() throws Exception {
        scan(event(party.getName(),day,""));String original=imported();
        PartySeries replacement=parties.saveAndFlush(new PartySeries("Neon Paradise "+UUID.randomUUID(),"","#E11D48","manual",null,null,null,null,Set.of(),Set.of()));
        try {
            scan(event(replacement.getName(),day,""));scan(event(replacement.getName(),day,""));
            var change=change("POSSIBLE_REPLACEMENT");
            String candidate=store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE venue_id=? AND title=?",String.class,venue.getId(),replacement.getName());
            assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE venue_id=?",Long.class,venue.getId())).isEqualTo(2L);
            assertThat(store.jdbc().queryForObject("SELECT title FROM discovery_candidates WHERE calendar_entry_id=?",String.class,original)).isEqualTo(party.getName());
            String cancellation=cancellations.confirm(original,(String)change.get("id"),"MANUAL","REPLACEMENT",candidate);
            assertThat(cancellations.get(cancellation).get("replacement_candidate_id")).isEqualTo(candidate);
            importer.review(candidate,replacement.getId(),day,"NEEDS_REVIEW","NONE","");
            importer.importCandidate(candidate,replacement.getId(),day,true);
            assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Long.class,venue.getId())).isEqualTo(2L);
            assertThat(store.jdbc().queryForObject("SELECT party_id FROM calendar_entries WHERE id=?",String.class,original)).isEqualTo(party.getId());
            assertThat(store.jdbc().queryForObject("SELECT event_status FROM calendar_entries WHERE id=?",String.class,original)).isEqualTo("CANCELLED");
        } finally {
            store.jdbc().update("DELETE FROM calendar_entries WHERE party_id=?",replacement.getId());
            store.jdbc().update("DELETE FROM discovery_candidates WHERE party_id=?",replacement.getId());
            parties.deleteById(replacement.getId());
        }
    }
    @Test void contradictoryOfficialSourcesStayIdempotentAndDoNotBecomeConfirmedCancellation() throws Exception {
        scan(event(party.getName(),day,""));String entry=imported();
        String source=store.jdbc().queryForObject("SELECT source_id FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId());
        var observations=List.of(new EventChangeMonitor.Observation(source,event(party.getName(),day,""),true),new EventChangeMonitor.Observation(source,event(party.getName(),day.plusDays(1),""),true));
        monitor.batch(venue,observations,"conflict-one");monitor.batch(venue,observations,"conflict-two");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM event_changes WHERE venue_id=? AND kind='CONFLICTING_SOURCES'",Long.class,venue.getId())).isEqualTo(1L);
        assertThat(store.jdbc().queryForObject("SELECT event_status FROM calendar_entries WHERE id=?",String.class,entry)).isEqualTo("ACTIVE");
        assertThat(eligibility.blockers(entry)).isNotEmpty();
    }
    @Test void monitorHorizonExcludesOlderEventsAndCancelledAnnouncementHasIndependentApproval() throws Exception {
        scan(event(party.getName(),day,""));String entry=imported();
        String cancellation=cancellations.confirm(entry,null,"MANUAL","ORGANISATORISCH",null);
        assertThatThrownBy(() -> cancellations.updateAnnouncement(cancellation,"Neutrale Absage","APPROVED",0)).isInstanceOf(IllegalArgumentException.class);
        cancellations.prepareGraphic(cancellation);
        cancellations.updateAnnouncement(cancellation,"Leider abgesagt, weitere Informationen folgen.","APPROVED",0);
        assertThat(cancellations.get(cancellation).get("announcement_status")).isEqualTo("APPROVED");
        assertThatThrownBy(() -> cancellations.updateAnnouncement(cancellation,"Anderer Text","DRAFT",0)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        store.jdbc().update("UPDATE calendar_entries SET event_date=? WHERE id=?",day.plusDays(30),entry);
        String source=store.jdbc().queryForObject("SELECT source_id FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId());
        monitor.batch(venue,List.of(new EventChangeMonitor.Observation(source,event(party.getName(),day.plusDays(31),""),true)),"outside-window",true);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM event_changes WHERE venue_id=?",Long.class,venue.getId())).isZero();
    }

}
