package de.noizycologne.backend.calendar;

import de.noizycologne.backend.discovery.*;
import de.noizycologne.backend.party.*;
import de.noizycologne.backend.venue.*;
import de.noizycologne.backend.editorial.*;
import de.noizycologne.backend.cancellation.PublishEligibilityService;
import org.junit.jupiter.api.*;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.time.*;
import java.util.*;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"noizy.discovery.enabled=false","noizy.discovery.monitor.enabled=false","logging.level.root=WARN"})
@ActiveProfiles("local")
class RecurrencePlanningIntegrationTests {
    @Autowired PartySeriesService series;
    @Autowired PartySeriesRepository parties;
    @Autowired VenueRepository venues;
    @Autowired CalendarEntryService calendar;
    @Autowired RecurrencePlanningService planning;
    @Autowired DiscoveryStore store;
    @Autowired DiscoveryScheduler scheduler;
    @Autowired DiscoveryPortfolioService portfolio;
    @Autowired PublishEligibilityService eligibility;
    @Autowired EditorialPostDraftService drafts;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean VenueSourceDiscoveryService discovery;
    Venue venue;
    final Set<String> created=new HashSet<>();
    final LocalDate today=LocalDate.now(ZoneId.of("Europe/Berlin"));
    final String root="https://recurrence.example/";
    @BeforeEach void setup() throws Exception {
        venue=new Venue("Bestätigter Partyclub","");venue.configureDiscovery(root,"",true);venue=venues.saveAndFlush(venue);
        when(discovery.discover(eq(root),any())).thenReturn(List.of());
    }
    @AfterEach void cleanup() {
        store.jdbc().update("DELETE FROM calendar_entries WHERE location_id=?",venue.getId());
        for(String id:created) parties.deleteById(requireNonNull(id));
        venues.deleteById(venue.getId());
    }
    private PartyRecurrenceRequest rule(String kind,int weekday,Integer occurrence) {
        return new PartyRecurrenceRequest(kind,weekday,occurrence,null,venue.getId(),today);
    }
    private PartySeriesRequest request(String name,PartyRecurrenceRequest recurrence) {
        return new PartySeriesRequest(name,"","#E11D48","manual",Set.of(),Set.of(),Set.of(),recurrence,Set.of(),"23:00",8);
    }
    private PartySeriesResponse create(PartyRecurrenceRequest recurrence) {
        var party=series.createParty(request("BallroomBlitz!",recurrence));created.add(party.id());return party;
    }
    private List<Map<String,Object>> entries(String party) {
        return store.jdbc().queryForList("SELECT * FROM calendar_entries WHERE party_id=? ORDER BY event_date",party);
    }
    private String id(Map<String,Object> row) { return requireNonNull((String)row.get("id")); }
    private String date(Map<String,Object> row) { return requireNonNull(row.get("event_date")).toString(); }
    private void scan(String title,String day,List<@NonNull String> starts,String state) throws Exception {
        var event=new EventExtractor.Event("edition-"+day,title,root+"events/"+day,starts,"Eintritt: 12 €",state,venue.getName());
        when(discovery.discover(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(List.of(event),"EVENTS_FOUND"))));
        scheduler.scan();
    }
    @Test void monthlyPlansAreUnconfirmedIdempotentAndCannotReceivePublicationApproval() {
        var party=create(rule("monthly_weekday",6,2));
        var before=entries(party.id());
        assertThat(before).hasSize(RecurrencePlanningService.dates("monthly_weekday",6,2,today,today).size()).allSatisfy(row -> {
            assertThat(row).containsEntry("event_status","PLANNED").containsEntry("entry_price","8 €");
            assertThat(requireNonNull(row.get("start_time")).toString()).isEqualTo("23:00:00");
        });
        series.updateParty(party.id(),request(party.name(),rule("monthly_weekday",6,2)));planning.extendHorizon();planning.extendHorizon();
        assertThat(entries(party.id())).extracting(row -> row.get("id")).containsExactlyElementsOf(before.stream().map(row -> row.get("id")).toList());
        String entry=id(requireNonNull(before.getFirst()));
        assertThat(eligibility.blockers(entry)).anyMatch(reason -> reason.startsWith("PLANNED:"));
        assertThatThrownBy(() -> drafts.create(entry,new EditorialPostDraftRequest("Caption","","APPROVED",Set.of(),Set.of())))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM editorial_post_drafts WHERE calendar_entry_id=?",Integer.class,entry)).isZero();
    }
    @Test void weeklyRuleChangesPreserveEditsConfirmedCancelledAndDeletedOccurrences() {
        int weekday=today.getDayOfWeek().getValue()%7;
        var party=create(rule("weekly",weekday,null));
        var before=entries(party.id());assertThat(before.size()).isGreaterThan(5);
        var deleted=requireNonNull(before.get(0));var edited=requireNonNull(before.get(1));var confirmed=requireNonNull(before.get(2));var cancelled=requireNonNull(before.get(3));
        calendar.deleteEntry(id(deleted));
        var drafted=requireNonNull(before.get(5));
        drafts.create(id(drafted),new EditorialPostDraftRequest("Manuell vorbereiteter Entwurf","","DRAFT",Set.of(),Set.of()));
        calendar.updateEntry(id(edited),new CalendarEntryRequest(date(edited),venue.getId(),"event","Manuell korrigiert","5 €",party.id(),"21:00",null,Set.of()));
        store.jdbc().update("UPDATE calendar_entries SET event_status='ACTIVE' WHERE id=?",id(confirmed));
        store.jdbc().update("UPDATE calendar_entries SET event_status='CANCELLED' WHERE id=?",id(cancelled));
        series.updateParty(party.id(),request(party.name(),rule("weekly",(weekday+1)%7,null)));
        for(var protectedRow:List.of(edited,confirmed,cancelled,drafted)) assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE id=?",Integer.class,id(protectedRow))).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE id=?",Integer.class,id(requireNonNull(before.get(4))))).isZero();
        series.updateParty(party.id(),request(party.name(),rule("weekly",weekday,null)));planning.extendHorizon();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE party_id=? AND event_date=?::date",Integer.class,party.id(),date(deleted))).isZero();
        assertThat(store.jdbc().queryForMap("SELECT notes,start_time,event_status FROM calendar_entries WHERE id=?",id(edited)))
            .containsEntry("notes","Manuell korrigiert").containsEntry("event_status","PLANNED");
        assertThat(store.jdbc().queryForObject("SELECT event_status FROM calendar_entries WHERE id=?",String.class,id(cancelled))).isEqualTo("CANCELLED");
    }
    @Test void discoveryConfirmsExistingPlansWithEventValuesInsteadOfCreatingDuplicates() throws Exception {
        var party=create(rule("monthly_weekday",6,2));var initial=entries(party.id());
        var first=requireNonNull(initial.getFirst());String day=date(first),title="BallroomBlitz! mit ROMAN EMPIRE";
        scan(title,day,List.of(day+"T21:30"),"");scan(title,day,List.of(day+"T21:30"),"");
        var actual=store.jdbc().queryForMap("SELECT * FROM calendar_entries WHERE id=?",id(first));
        assertThat(actual).containsEntry("event_status","ACTIVE").containsEntry("entry_price","12 €");
        assertThat(requireNonNull(actual.get("start_time")).toString()).isEqualTo("21:30:00");
        assertThat(requireNonNull(actual.get("notes")).toString()).contains(title);
        assertThat(entries(party.id())).hasSize(initial.size());
        var candidate=store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE venue_id=? AND title=?",venue.getId(),title);
        assertThat(candidate).containsEntry("calendar_entry_id",id(first)).containsEntry("status","IMPORTED");
        var second=requireNonNull(initial.get(1));String next=date(second);
        scan("BallroomBlitz! mit DER GRAUE KLAUS",next,List.of(next+"T22:00"),"");
        assertThat(store.jdbc().queryForObject("SELECT event_status FROM calendar_entries WHERE id=?",String.class,id(second))).isEqualTo("ACTIVE");
        assertThat(entries(party.id())).hasSize(initial.size());
    }
    @Test void relativeStartsAndExplicitFreeAdmissionOverridePlannedDefaultsWithoutInventingTime() throws Exception {
        var party=create(rule("weekly",6,null));var first=requireNonNull(entries(party.id()).getFirst());String day=date(first);
        var event=new EventExtractor.Event("relative-"+day,"BallroomBlitz! mit ROMAN EMPIRE",root+"relative",List.of(requireNonNull(day)),"Beginn nach dem Konzert; Eintritt: frei","",venue.getName());
        when(discovery.discover(eq(root),any())).thenReturn(List.of(new VenueSourceDiscoveryService.Found(URI.create(root),new EventExtractor.Extraction(List.of(event),"EVENTS_FOUND"))));
        scheduler.scan();
        var confirmed=store.jdbc().queryForMap("SELECT * FROM calendar_entries WHERE id=?",id(first));
        assertThat(confirmed).containsEntry("event_status","ACTIVE").containsEntry("start_time",null).containsEntry("entry_price","Eintritt frei");
        assertThat(requireNonNull(confirmed.get("notes")).toString()).contains("nach dem Konzert");
    }
    @Test void removedAndEditedPlansAndConflictingSourcesNeverGetAutomaticallyReplaced() throws Exception {
        var party=create(rule("weekly",6,null));var rows=entries(party.id());
        var deleted=requireNonNull(rows.get(0));calendar.deleteEntry(id(deleted));
        scan("BallroomBlitz! mit ROMAN EMPIRE",date(deleted),List.of(date(deleted)+"T22:00"),"");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE party_id=? AND event_date=?::date",Integer.class,party.id(),date(deleted))).isZero();
        var edited=requireNonNull(rows.get(1));
        calendar.updateEntry(id(edited),new CalendarEntryRequest(date(edited),venue.getId(),"event","Redaktionell bearbeitet","",party.id(),"20:00",null,Set.of()));
        scan("BallroomBlitz! mit DER GRAUE KLAUS",date(edited),List.of(date(edited)+"T23:30"),"");
        assertThat(store.jdbc().queryForMap("SELECT event_status,notes FROM calendar_entries WHERE id=?",id(edited)))
            .containsEntry("event_status","PLANNED").containsEntry("notes","Redaktionell bearbeitet");
        var conflicting=requireNonNull(rows.get(2));String day=date(conflicting);
        scan("BallroomBlitz! mit CONFLICT DJ",day,List.of(day+"T22:00",day+"T23:00"),"SOURCE_CONFLICT CONFLICTING_DATA");
        assertThat(store.jdbc().queryForObject("SELECT event_status FROM calendar_entries WHERE id=?",String.class,id(conflicting))).isEqualTo("PLANNED");
    }
    @Test void manuallyMovedPlansRemainProtectedAtTheOriginalDateAndLocation() throws Exception {
        var party=create(rule("weekly",6,null));var first=requireNonNull(entries(party.id()).getFirst());String day=date(first);
        String moved=java.sql.Date.valueOf(day).toLocalDate().plusDays(1).toString();
        calendar.updateEntry(id(first),new CalendarEntryRequest(moved,venue.getId(),"event","Manuell verschoben","",party.id(),"21:00",null,Set.of()));
        scan("BallroomBlitz! mit ROMAN EMPIRE",day,List.of(day+"T22:00"),"");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE party_id=? AND event_date=?::date",Integer.class,party.id(),day)).isZero();
        assertThat(store.jdbc().queryForMap("SELECT event_date,notes FROM calendar_entries WHERE id=?",id(first)))
            .containsEntry("event_date",java.sql.Date.valueOf(moved)).containsEntry("notes","Manuell verschoben");
    }
    @Test void rollingHorizonUsesInjectedBerlinClockAndRetainsExistingPlans() {
        var party=create(rule("weekly",0,null));
        var initial=entries(party.id());
        LocalDate later=today.plusMonths(1);
        var clock=Clock.fixed(later.minusDays(1).atTime(23,30).toInstant(ZoneOffset.UTC),ZoneOffset.UTC);
        var fixed=new RecurrencePlanningService(store,calendar,parties,clock);
        new TransactionTemplate(requireNonNull(transactions)).executeWithoutResult(status -> {fixed.extendHorizon();fixed.extendHorizon();});
        assertThat(entries(party.id())).extracting(row -> row.get("id")).containsAll(initial.stream().map(row -> row.get("id")).toList());
        int expected=RecurrencePlanningService.dates("weekly",0,null,today,later).size();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE party_id=? AND event_date>=? AND event_date<?",Integer.class,party.id(),later,later.plusMonths(3))).isEqualTo(expected);
    }
    @Test void missingPlanningContextIsRejectedAndOtherRemainsAnUnscheduledHint() {
        assertThatThrownBy(() -> create(new PartyRecurrenceRequest("weekly",6,null,null))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var party=create(new PartyRecurrenceRequest("other",null,null,"Unregelmäßig"));
        assertThat(entries(party.id())).isEmpty();planning.extendHorizon();assertThat(entries(party.id())).isEmpty();
    }
    @Test void discoveryPortfolioApprovalCreatesStemNamedPlansOnlyAfterExplicitRecurrenceConfirmation() throws Exception {
        String day=today.plusDays(7).toString(),title="BallroomBlitz! mit DER GRAUE KLAUS";
        scan(title,day,List.of(day+"T22:00"),"");
        String candidate=requireNonNull(store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId()));
        var rule=rule("monthly_weekday",5,1);
        String party=portfolio.approve(candidate,"BallroomBlitz!",null,"23:00",Set.of(),rule);created.add(party);
        assertThat(requireNonNull(parties.findById(party).orElseThrow()).getName()).isEqualTo("BallroomBlitz!");
        assertThat(entries(party)).allSatisfy(row -> assertThat(row).containsEntry("event_status","PLANNED"));
        assertThat(store.candidate(candidate)).containsEntry("party_id",party).containsEntry("title",title).containsEntry("decision_origin","MANUAL");
    }
}
