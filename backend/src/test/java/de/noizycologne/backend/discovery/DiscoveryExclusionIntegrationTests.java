package de.noizycologne.backend.discovery;

import de.noizycologne.backend.venue.*;
import de.noizycologne.backend.party.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.*;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"noizy.discovery.enabled=false","noizy.discovery.monitor.enabled=false","logging.level.root=WARN"})
@ActiveProfiles("local")
class DiscoveryExclusionIntegrationTests {
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired DiscoveryStore store;
    @Autowired DiscoveryKeywordService keywords;
    @Autowired EventCandidateProcessor processor;
    @Autowired EventImportService importer;
    @Autowired DiscoveryResetService resets;
    @Autowired DiscoveryEvaluationService evaluation;
    @Autowired SpecialSourceService sources;
    @Autowired VenueRepository venues;
    @Autowired PartySeriesRepository parties;
    Venue venue;String official,portal,second,rule,partyId;
    @BeforeEach void setup() {
        venue=venues.saveAndFlush(new Venue("Keyword Club "+UUID.randomUUID(),""));
        partyId=parties.saveAndFlush(new PartySeries("Known "+UUID.randomUUID(),"","#E11D48","manual",null,null,null,null,Set.of(),Set.of())).getId();
        official=store.source(venue.getId(),"https://keyword.example/","OFFICIAL",false);
        portal=sources.add("Portal","https://portal-keyword.example/","","","");
        second=sources.add("Andere Quelle","https://second-keyword.example/","","","");
        rule=keywords.save(null,"Release Party","Keine Release-Veranstaltungen",true);
    }
    @AfterEach void cleanup() {
        store.jdbc().update("DELETE FROM discovery_sources WHERE id IN (?,?)",portal,second);
        store.jdbc().update("DELETE FROM calendar_entries WHERE location_id=?",venue.getId());
        venues.deleteById(venue.getId());parties.deleteById(partyId);
        store.jdbc().update("DELETE FROM discovery_keyword_rules WHERE id=?",rule);
        store.jdbc().update("DELETE FROM discovery_operations WHERE kind='KEYWORD_TEST'");
    }
    EventExtractor.Event event(String identity,String day) {
        return new EventExtractor.Event(identity,"Emo Release Party","https://organizer.example/events/release",List.of(requireNonNull(day)),"Emo DJs","","Unbestätigter Club",new EventPlace("Köln","DE","","","",null,null,false));
    }
    String unassigned() {return requireNonNull(store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE source_id=?",String.class,portal));}
    DiscoveryResetService.Request request() {
        var preview=resets.preview(new DiscoveryResetService.Selection(DiscoveryResetService.Mode.ALL,false));
        var id=UUID.randomUUID();
        store.jdbc().update("INSERT INTO discovery_operations(id,kind,request_hash,matcher_version,status) VALUES (?,'KEYWORD_TEST','test','test','RUNNING')",id.toString());
        return new DiscoveryResetService.Request(DiscoveryResetService.Mode.ALL,false,null,preview.token(),id);
    }
    @Test void commaSeparatedBatchPersistsIndividualRulesAndSkipsDuplicates() {
        var result=keywords.saveBatch(List.of("Karneval","B2B","Ausverkauft","b2b"),null,true);
        assertThat(result.created()).isEqualTo(3);
        assertThat(result.alreadyPresent()).isEqualTo(1);
        var stored=keywords.list().stream()
            .filter(row -> List.of("Karneval","B2B","Ausverkauft").contains(row.get("keyword")))
            .toList();
        assertThat(stored).hasSize(3);
        assertThat(stored).allSatisfy(row -> {
            assertThat(row.get("enabled")).isEqualTo(true);
            assertThat((String)row.get("reason")).startsWith("Automatischer Ausschluss: ");
        });
        var repeated=keywords.saveBatch(List.of("Karneval","b2b","Ausverkauft"),"Ein anderer Grund",true);
        assertThat(repeated.created()).isZero();
        assertThat(repeated.alreadyPresent()).isEqualTo(3);
        // The test's fixtures must not leave rules behind for another isolated test.
        store.jdbc().update("DELETE FROM discovery_keyword_rules WHERE keyword IN ('Karneval','B2B','Ausverkauft')");
    }
    @Test void keywordRulesOverrideOpenLegacyReviewForUnknownLocationButNeverConfirmedVenues() {
        keywords.save(rule,"Release Party","Keine Release-Veranstaltungen",false);
        var event=event("legacy-keyword","2027-02-13");
        processor.processSpecial(portal,event,null);
        String candidate=unassigned();
        store.jdbc().update("UPDATE discovery_candidates SET status='NEEDS_REVIEW',decision_origin='MANUAL',dm_text='Vorherige Klärung' WHERE id=?",candidate);
        keywords.save(rule,"Release Party","Keine Release-Veranstaltungen",true);
        var preview=resets.preview(new DiscoveryResetService.Selection(DiscoveryResetService.Mode.ALL,false));
        assertThat(preview.items().stream().anyMatch(item -> item.id().equals(candidate) && item.newStatus().equals("IGNORED"))).isTrue();
        resets.apply(request());
        assertThat(store.candidate(candidate)).containsEntry("status","IGNORED")
            .containsEntry("decision_origin","AUTOMATIC")
            .containsEntry("filter_origin","KEYWORD_FILTER")
            .containsEntry("dm_text","Vorherige Klärung");
        processor.processSpecial(portal,event,null);
        assertThat(store.candidate(candidate)).containsEntry("status","IGNORED");

        keywords.save(rule,"Release Party","Keine Release-Veranstaltungen",false);
        var secondEvent=event("legacy-keyword-scan","2027-02-20");
        processor.processSpecial(portal,secondEvent,null);
        String secondId=requireNonNull(store.jdbc().queryForObject(
            "SELECT id FROM discovery_candidates WHERE source_id=? AND event_date='2027-02-20'",String.class,portal));
        store.jdbc().update("UPDATE discovery_candidates SET status='NEEDS_INQUIRY',decision_origin='MANUAL' WHERE id=?",secondId);
        keywords.save(rule,"Release Party","Keine Release-Veranstaltungen",true);
        processor.processSpecial(portal,secondEvent,null);
        assertThat(store.candidate(secondId)).containsEntry("status","IGNORED")
            .containsEntry("decision_origin","AUTOMATIC");
    }
    @Test void unknownLocationIsPersistentlyFilteredAcrossScansAndTwoSourcesWithoutAuditDuplicates() {
        var event=event("https://organizer.example/events/release","2027-01-09");
        processor.processSpecial(portal,event,null);String id=unassigned();
        assertThat(store.candidate(id)).containsEntry("status","IGNORED").containsEntry("decision_origin","AUTOMATIC").containsEntry("filter_origin","KEYWORD_FILTER").containsEntry("filter_rule_id",rule).containsEntry("filter_keyword","Release Party").containsEntry("filter_reason","Keine Release-Veranstaltungen");
        Integer audits=store.jdbc().queryForObject("SELECT count(*) FROM discovery_decisions WHERE candidate_id=?",Integer.class,id);
        processor.processSpecial(portal,event,null);
        var supplement=new EventExtractor.Event(event.identity(),event.title().toUpperCase(Locale.ROOT),event.url(),event.starts(),"Zusätzliche Emo-DJ-Information der zweiten Quelle",event.state(),event.location(),event.place());
        processor.processSpecial(second,supplement,null);processor.processSpecial(portal,event,null);processor.processSpecial(second,supplement,null);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE source_id IN (?,?)",Integer.class,portal,second)).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_decisions WHERE candidate_id=?",Integer.class,id)).isEqualTo(audits);
        assertThat(store.candidate(id).get("last_seen_at")).isNotNull();assertThat(store.candidates("KEYWORD_FILTER")).hasSize(1);
        importer.reject(id);processor.processSpecial(second,event,null);
        assertThat(resets.preview(new DiscoveryResetService.Selection(DiscoveryResetService.Mode.ALL,false)).protectedRejections()).isEqualTo(1);
        assertThat(resets.preview(new DiscoveryResetService.Selection(DiscoveryResetService.Mode.ALL,true)).protectedRejections()).isZero();
        resets.apply(request());
        assertThat(store.candidate(id)).containsEntry("status","REJECTED").containsEntry("decision_origin","MANUAL");
    }
    @Test void confirmedVenueRemainsReviewableAndEarlierManualDecisionsSurviveRuleChanges() {
        var event=new EventExtractor.Event("known","Emo Release Party","https://keyword.example/one",List.of("2027-01-09"),"Emo DJs","","");
        processor.process(venue,official,event,parties.findAll(),Map.of(),List.of("Emo"),false);
        String id=requireNonNull(store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId()));
        assertThat(store.candidate(id)).containsEntry("status","NEEDS_REVIEW").containsEntry("filter_rule_id",rule).containsEntry("calendar_entry_id",null);
        assertThat(evaluation.forApi(store.candidate(id),evaluation.rules()).get("keywordWarning")).asString().contains("Release Party","Keine Release");
        importer.review(id,null,java.time.LocalDate.of(2027,1,10),"NEEDS_INQUIRY","ANSWERED","Meine Entscheidung");
        keywords.save(rule,"Release Party","Neuer Grund",true);processor.process(venue,official,event,parties.findAll(),Map.of(),List.of("Emo"),false);resets.apply(request());
        assertThat(store.candidate(id)).containsEntry("status","NEEDS_INQUIRY").containsEntry("dm_text","Meine Entscheidung").containsEntry("event_date",java.sql.Date.valueOf("2027-01-10"));
    }
    @Test void rejectionIsForOneEditionAndDoesNotBlockAnotherDateWithTheSameUid() {
        var first=new EventExtractor.Event("reused","Emo Night","https://keyword.example/one",List.of("2027-01-09"),"Emo DJs","","");
        processor.process(venue,official,first,parties.findAll(),Map.of(),List.of("Emo"),false);
        String id=requireNonNull(store.jdbc().queryForObject("SELECT id FROM discovery_candidates WHERE venue_id=?",String.class,venue.getId()));importer.reject(id);
        var next=new EventExtractor.Event(first.identity(),first.title(),first.url(),List.of("2027-01-16"),first.evidence(),"","");
        processor.process(venue,official,next,parties.findAll(),Map.of(),List.of("Emo"),false);processor.process(venue,official,next,parties.findAll(),Map.of(),List.of("Emo"),false);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE venue_id=?",Integer.class,venue.getId())).isEqualTo(2);
        assertThat(store.candidate(id)).containsEntry("status","REJECTED");
        assertThat(store.jdbc().queryForObject("SELECT status FROM discovery_candidates WHERE venue_id=? AND event_date='2027-01-16'",String.class,venue.getId())).isEqualTo("NEEDS_REVIEW");
        var moved=new EventExtractor.Event(first.identity(),first.title(),first.url(),List.of("2027-01-23"),first.evidence(),"EventRescheduled","");
        processor.process(venue,official,moved,parties.findAll(),Map.of(),List.of("Emo"),false);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE venue_id=?",Integer.class,venue.getId())).isEqualTo(2);
        assertThat(store.candidate(id)).containsEntry("status","REJECTED");
    }
    @Test void aNewUnassignedEditionWithReusedUidIsStoredSeparatelyAndStillFiltered() {
        processor.processSpecial(portal,event("reused","2027-01-09"),null);String old=unassigned();importer.reject(old);
        processor.processSpecial(portal,event("reused","2027-01-16"),null);processor.processSpecial(portal,event("reused","2027-01-16"),null);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE source_id=?",Integer.class,portal)).isEqualTo(2);
        assertThat(store.candidate(old)).containsEntry("status","REJECTED").containsEntry("event_date",java.sql.Date.valueOf("2027-01-09"));
        assertThat(store.jdbc().queryForObject("SELECT status FROM discovery_candidates WHERE source_id=? AND event_date='2027-01-16'",String.class,portal)).isEqualTo("IGNORED");
    }
    @Test void disablingRulesRestoresAutomaticCandidatesButInvalidatesOldPreviewsAndRetainsAuditEvidence() {
        processor.processSpecial(portal,event("one","2027-01-09"),null);String id=unassigned();var stale=request();
        keywords.save(rule,"Release Party","Keine Release-Veranstaltungen",false);
        assertThatThrownBy(()->resets.apply(stale)).hasMessageContaining("veraltet");
        var preview=resets.preview(new DiscoveryResetService.Selection(DiscoveryResetService.Mode.ALL,false));assertThat(preview.keywordRestored()).isEqualTo(1);
        resets.apply(request());assertThat(store.candidate(id).get("status")).isEqualTo("NEEDS_REVIEW");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_decisions WHERE candidate_id=? AND filter_rule_id=?",Integer.class,id,rule)).isEqualTo(1);
    }
    @Test void individualReopeningIsProtectedForTheRuleConfigurationAndCanBeReevaluatedAfterChanges() {
        var event=event("one","2027-01-09");processor.processSpecial(portal,event,null);String id=unassigned();keywords.reopen(id);
        String unchanged=keywords.version();keywords.save(rule,"Release Party","Keine Release-Veranstaltungen",true);assertThat(keywords.version()).isEqualTo(unchanged);
        processor.processSpecial(portal,event,null);resets.apply(request());assertThat(store.candidate(id)).containsEntry("status","NEEDS_REVIEW").containsEntry("decision_origin","MANUAL");
        keywords.save(rule,"Release Party","Ausdrücklich geändert",true);resets.apply(request());
        assertThat(store.candidate(id)).containsEntry("status","IGNORED").containsEntry("decision_origin","AUTOMATIC").containsEntry("filter_reason","Ausdrücklich geändert");
    }
    @Test void batchRejectsOneHundredExactlyOnceAndProtectsChangedAndImportedCandidates() throws Exception {
        List<@org.jspecify.annotations.NonNull String> ids=new ArrayList<>();
        for(int i=0;i<102;i++) {
            String id=requireNonNull(UUID.randomUUID().toString());ids.add(id);
            store.jdbc().update("INSERT INTO discovery_candidates(id,venue_id,source_id,identity_key,fingerprint,title,source_url,evidence,status,reason) VALUES (?,?,?,?,?,'Test Party','https://keyword.example/','Emo','NEEDS_REVIEW','Prüfen')",id,venue.getId(),official,EventNormalizer.hash(id),EventNormalizer.hash(id));
        }
        var hundred=ids.subList(0,100);
        Map<String,String> revisions=new HashMap<>();
        for(String selected:hundred) revisions.put(selected,Objects.toString(DiscoveryStore.forApi(store.candidate(selected)).get("updated_at")));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(requireNonNull(context)).build();
        String payload=requireNonNull(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of("candidateIds",hundred,"expectedRevisions",revisions)));
        mvc.perform(requireNonNull(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/discovery/candidates/reject-batch")
            .contentType("application/json").content(payload)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.requested").value(100))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.rejected").value(100));
        assertThat(keywords.rejectBatch(hundred,null,importer).alreadyRejected()).isEqualTo(100);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_decisions WHERE candidate_id IN (SELECT id FROM discovery_candidates WHERE venue_id=?)",Integer.class,venue.getId())).isEqualTo(100);
        String imported=requireNonNull(ids.get(100)),changed=requireNonNull(ids.get(101));
        store.jdbc().update("UPDATE discovery_candidates SET decision_origin='MANUAL',dm_text='Inzwischen geprüft',updated_at=now() WHERE id=?",changed);
        // Imported calendar data must remain untouched, regardless of selection revisions.
        store.jdbc().update("INSERT INTO calendar_entries(id,event_date,location_id,entry_type,notes,party_id) VALUES (?,'2027-01-09',?,'event','Geschützt',?)",imported,venue.getId(),partyId);
        store.jdbc().update("UPDATE discovery_candidates SET calendar_entry_id=?,status='IMPORTED' WHERE id=?",imported,imported);
        var mixed=keywords.rejectBatch(List.of(imported,changed),Map.of(imported,"stale",changed,"stale"),importer);
        assertThat(mixed.rejected()).isZero();assertThat(mixed.conflicts()).hasSize(2);assertThat(store.candidate(changed)).containsEntry("dm_text","Inzwischen geprüft");
        assertThat(store.jdbc().queryForObject("SELECT notes FROM calendar_entries WHERE id=?",String.class,imported)).isEqualTo("Geschützt");
        store.jdbc().update("DELETE FROM calendar_entries WHERE id=?",imported);
        List<@org.jspecify.annotations.NonNull String> tooMany=new ArrayList<>();for(int i=0;i<501;i++)tooMany.add(requireNonNull(UUID.randomUUID().toString()));
        assertThatThrownBy(()->keywords.rejectBatch(tooMany,null,importer)).hasMessageContaining("500");
    }
}
