package de.noizycologne.backend.discovery;

import de.noizycologne.backend.party.*;
import de.noizycologne.backend.venue.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.*;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"noizy.discovery.enabled=false","noizy.discovery.monitor.enabled=false","logging.level.root=WARN"})
@ActiveProfiles("local")
class EventPrefilterIntegrationTests {
    @Autowired DiscoveryStore store;
    @Autowired EventCandidateProcessor processor;
    @Autowired DiscoveryEvaluationService evaluation;
    @Autowired DiscoveryResetService resets;
    @Autowired SpecialSourceService sources;
    @Autowired VenueRepository venues;
    @Autowired PartySeriesRepository parties;
    Venue venue; PartySeries misery; String official,portal,other;
    @BeforeEach void setup() {
        venue=venues.saveAndFlush(new Venue("Subway",""));
        misery=parties.saveAndFlush(new PartySeries("Misery","","#E11D48","manual",null,null,null,null,Set.of(),Set.of(),Set.of("Emo")));
        official=store.source(venue.getId(),"https://prefilter.example/","OFFICIAL",true);
        portal=sources.add("Qrushapp fixture","https://qrush-fixture.example/","","","");
        other=sources.add("Other fixture","https://other-fixture.example/","","","");
        store.jdbc().update("INSERT INTO discovery_venue_geography(venue_id,city,street,country,proof_url) VALUES (?,'Köln','Aachener Str. 82','DE','https://prefilter.example/')",venue.getId());
        store.jdbc().update("INSERT INTO discovery_place_aliases(venue_id,normalized_name,place_key) VALUES (?,?,?)",venue.getId(),CologneGeography.name("Subway"),"address:"+CologneGeography.name("Aachener Str. 82")+"|koeln");
    }
    @AfterEach void cleanup() {
        store.jdbc().update("DELETE FROM discovery_sources WHERE id IN (?,?)",portal,other);
        store.jdbc().update("DELETE FROM calendar_entries WHERE location_id=?",venue.getId());
        venues.deleteById(venue.getId());parties.deleteById(misery.getId());
        store.jdbc().update("DELETE FROM discovery_operations WHERE kind='PREFILTER_TEST'");
    }
    EventExtractor.Event event(String identity,String title,String day,String genres) {
        return new EventExtractor.Event(identity,title,"https://qrush-fixture.example/events/"+identity,List.of(requireNonNull(day)),genres,"","Subway",new EventPlace("Köln","DE","Aachener Str. 82","","",null,null,false));
    }
    Map<String,Object> candidate(String title) {
        return store.jdbc().queryForMap("SELECT * FROM discovery_candidates WHERE title=?",title);
    }
    @Test void sameVenueRecognizesKnownSeriesWithoutGenresButExcludesUnrelatedHipHop() {
        processor.processSpecial(portal,event("misery","Misery mit DJ A","2027-02-06",""),null);
        assertThat(candidate("Misery mit DJ A")).containsEntry("party_id",misery.getId()).containsEntry("matching_class","MATCH");
        processor.processSpecial(portal,event("hiphop","Hip-Hop Clubnacht","2027-02-06","Genre: Hip-Hop"),null);
        assertThat(candidate("Hip-Hop Clubnacht")).containsEntry("status","IGNORED").containsEntry("matcher_version",EventMatcher.VERSION).containsEntry("filter_origin","EVENT_FILTER");
        assertThat(candidate("Hip-Hop Clubnacht").get("filter_evidence")).asString().contains("Genre: Hip-Hop","https://qrush-fixture.example/events/hiphop");
        assertThat(candidate("Hip-Hop Clubnacht").get("reason")).asString().contains("ohne Portfolio-Bezug");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isZero();
        assertThat(parties.count()).isEqualTo(1);
    }
    @Test void unknownWithoutGenresStaysSeparateAndNeverImportsAtKnownVenue() {
        var event=event("unknown","Unbekannte Clubnacht","2027-02-06","");
        processor.processSpecial(portal,event,null);
        var row=candidate(event.title());
        assertThat(row).containsEntry("matching_class","UNCLASSIFIABLE").containsEntry("status","NEEDS_INQUIRY");
        assertThat(store.candidates("UNCLEAR")).extracting(item -> item.get("id")).contains(row.get("id"));
        assertThat(store.candidates("RELEVANT")).extracting(item -> item.get("id")).doesNotContain(row.get("id"));
        assertThat(row.get("calendar_entry_id")).isNull();
    }
    @Test void explicitUnknownGenresRespectFiftyPercentAndKeepPartialCoverageUnclear() {
        processor.processSpecial(portal,event("half","Halbe Clubnacht","2027-02-06","Genre: Emo, Techno"),null);
        assertThat(candidate("Halbe Clubnacht")).containsEntry("matching_class","POSSIBLE_MATCH");
        processor.processSpecial(portal,event("third","Drittel Clubnacht","2027-02-06","Genre: Emo, Techno, Trance"),null);
        assertThat(candidate("Drittel Clubnacht")).containsEntry("matching_class","UNCLASSIFIABLE");
    }
    @Test void repeatedCrossSourceCrawlArchivesOneOccurrenceAndKeepsDifferentDaysSeparate() {
        var first=event("reused-page","Hip-Hop Clubnacht","2027-02-06","Genre: Hip-Hop");
        for(int i=0;i<3;i++) {
            processor.processSpecial(portal,first,null);
            processor.processSpecial(other,new EventExtractor.Event("other-id",first.title(),"https://other-fixture.example/events/one",first.starts(),first.evidence(),"",first.location(),first.place()),null);
        }
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE title=?",Integer.class,first.title())).isEqualTo(1);
        assertThat(store.candidates("RELEVANT")).isEmpty();
        var next=event("reused-page",first.title(),"2027-02-13",first.evidence());
        processor.processSpecial(portal,next,null);processor.processSpecial(portal,next,null);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE title=? AND status='IGNORED'",Integer.class,first.title())).isEqualTo(2);
    }
    @Test void concertAndIndependentAftershowAreArchivedAndRecognizedSeparately() {
        processor.processSpecial(portal,event("concert","Indie Konzert","2027-02-06","Konzert mit anschließender Party"),null);
        store.jdbc().update("INSERT INTO party_series_aliases(party_id,alias) VALUES (?,?)",misery.getId(),"Misery Aftershow Party");
        processor.processSpecial(portal,event("aftershow","Misery Aftershow Party","2027-02-06","Beginn nach dem Konzert"),null);
        assertThat(candidate("Indie Konzert")).containsEntry("status","IGNORED");
        assertThat(candidate("Misery Aftershow Party")).containsEntry("party_id",misery.getId());
    }
    @Test void explicitReevaluationReusesStoredEvidenceAndAliasWithoutCreatingSeriesOrCalendarEntries() {
        var event=event("reevaluate","Neue Clubnacht","2027-02-06","Genre: Hip-Hop");
        processor.processSpecial(portal,event,null);
        String id=(String)candidate(event.title()).get("id");
        assertThat(store.candidate(id)).containsEntry("status","IGNORED");
        store.jdbc().update("INSERT INTO party_series_aliases(party_id,alias) VALUES (?,?)",misery.getId(),event.title());
        var preview=resets.preview(new DiscoveryResetService.Selection(DiscoveryResetService.Mode.ALL,false));
        assertThat(preview.items()).anySatisfy(item -> {
            assertThat(item.id()).isEqualTo(id);assertThat(item.classification()).isEqualTo("MATCH");
        });
        var operation=UUID.randomUUID();
        store.jdbc().update("INSERT INTO discovery_operations(id,kind,request_hash,matcher_version,status) VALUES (?,'PREFILTER_TEST','test',?,'RUNNING')",operation.toString(),EventMatcher.VERSION);
        resets.apply(new DiscoveryResetService.Request(DiscoveryResetService.Mode.ALL,false,null,preview.token(),operation));
        assertThat(store.candidate(id)).containsEntry("status","NEEDS_REVIEW").containsEntry("party_id",misery.getId());
        assertThat(store.jdbc().queryForObject("SELECT filter_evidence FROM discovery_decisions WHERE candidate_id=? AND new_status='IGNORED' ORDER BY id LIMIT 1",String.class,id)).contains("Genre: Hip-Hop");
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE title=?",Integer.class,event.title())).isEqualTo(1);
        assertThat(store.candidate(id).get("calendar_entry_id")).isNull();
    }
    @Test void outsideCologneIsArchivedAndReevaluationPreservesManualDecisionsAndSeries() {
        var original=event("outside","Misery auswärts","2027-02-06","");
        var outside=new EventExtractor.Event(original.identity(),original.title(),original.url(),original.starts(),"","","Club",new EventPlace("Bonn","DE","Straße 1","","",null,null,false));
        processor.processSpecial(portal,outside,null);
        assertThat(candidate(outside.title())).containsEntry("status","IGNORED");
        assertThat(candidate(outside.title()).get("reason")).asString().contains("außerhalb");
        var unknown=event("manual","Manuelle Clubnacht","2027-02-06","");
        processor.processSpecial(portal,unknown,null);
        var row=candidate(unknown.title());
        store.jdbc().update("UPDATE discovery_candidates SET status='REJECTED',decision_origin='MANUAL',reason='Redaktion' WHERE id=?",row.get("id"));
        var preview=resets.preview(new DiscoveryResetService.Selection(DiscoveryResetService.Mode.ALL,false));
        assertThat(preview.items()).noneMatch(item -> item.id().equals(row.get("id")));
        processor.processSpecial(portal,unknown,null);
        assertThat(candidate(unknown.title())).containsEntry("status","REJECTED").containsEntry("reason","Redaktion");
        assertThat(parties.findById(misery.getId())).isPresent();
    }
}
