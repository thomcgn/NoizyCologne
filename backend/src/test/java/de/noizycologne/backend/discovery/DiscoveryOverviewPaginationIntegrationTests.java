package de.noizycologne.backend.discovery;

import static java.util.Objects.requireNonNull;
import de.noizycologne.backend.venue.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import java.sql.Timestamp;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Isolated PostgreSQL checks for the paginated discovery overview; no crawls and no external services. */
@SpringBootTest(properties={"noizy.discovery.enabled=false","logging.level.root=WARN"})
@ActiveProfiles("local")
class DiscoveryOverviewPaginationIntegrationTests {
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired DiscoveryStore store;
    @Autowired VenueRepository venues;
    @MockitoBean VenueSourceDiscoveryService discovery;
    Venue venue;
    String sourceId;
    String run;
    @BeforeEach void setup() {
        run=UUID.randomUUID().toString();
        venue=venues.saveAndFlush(new Venue("Overview Pagination "+run,""));
        sourceId=store.source(venue.getId(),"https://overview-"+run+".example/","OFFICIAL",true);
    }
    @AfterEach void cleanup() {
        store.jdbc().update("DELETE FROM discovery_candidates WHERE venue_id=?",venue.getId());
        store.jdbc().update("DELETE FROM discovery_sources WHERE id=?",sourceId);
        venues.deleteById(venue.getId());
    }
    private String insert(String suffix,String status,String geoStatus,String updatedAt) {
        String id=run+"-"+suffix;
        store.jdbc().update("""
            INSERT INTO discovery_candidates(id,venue_id,source_id,identity_key,fingerprint,title,source_url,evidence,status,reason,geo_status,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
            """,id,venue.getId(),sourceId,"identity-"+suffix,"fingerprint-"+suffix,"Titel "+suffix,
            "https://overview-"+run+".example/"+suffix,"{}",status,"Grund",geoStatus,Timestamp.valueOf(updatedAt));
        return id;
    }
    private static List<String> ids(List<Map<String,Object>> rows) {
        return rows.stream().map(row -> (String)row.get("id")).toList();
    }
    @Test void equalTimestampsAreOrderedByIdAndPagesAreZeroBasedAndClamped() {
        insert("t1","NEEDS_REVIEW","INSIDE","2026-11-01 10:00:00");
        insert("a","NEEDS_REVIEW","INSIDE","2026-11-03 10:00:00");
        insert("t3","NEEDS_REVIEW","INSIDE","2026-11-01 10:00:00");
        insert("b","NEEDS_REVIEW","INSIDE","2026-11-02 10:00:00");
        insert("t2","NEEDS_REVIEW","INSIDE","2026-11-01 10:00:00");
        List<String> expected=List.of(run+"-a",run+"-b",run+"-t3",run+"-t2",run+"-t1");
        assertThat(ids(store.candidates("ALL"))).containsExactlyElementsOf(expected);
        assertThat(ids(store.candidates("ALL",0,2))).containsExactly(run+"-a",run+"-b");
        assertThat(ids(store.candidates("ALL",1,2))).containsExactly(run+"-t3",run+"-t2");
        assertThat(ids(store.candidates("ALL",2,2))).containsExactly(run+"-t1");
        assertThat(store.candidates("ALL",3,2)).isEmpty();
        assertThat(ids(store.candidates("ALL",-4,2))).containsExactly(run+"-a",run+"-b");
        assertThat(store.candidates("ALL",0,0)).isEmpty();
        assertThat(store.candidates("ALL",0,-1)).isEmpty();
        assertThat(ids(store.candidates("ALL",0,5000))).containsExactlyElementsOf(expected);
        assertThat(store.candidates("ALL",Integer.MAX_VALUE,1000)).isEmpty();
    }
    @Test void filtersCombineWithPaginationAndCountsStayIndependent() throws Exception {
        long ignoredBefore=requireNonNull(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE status='IGNORED'",Long.class));
        insert("r1","NEEDS_REVIEW","INSIDE","2026-11-04 10:00:00");
        insert("k1","IGNORED","INSIDE","2026-11-05 10:00:00");
        insert("r2","NEEDS_REVIEW","INSIDE","2026-11-02 10:00:00");
        insert("k2","IGNORED","INSIDE","2026-11-03 10:00:00");
        store.jdbc().update("UPDATE discovery_candidates SET filter_origin='KEYWORD_FILTER' WHERE id IN (?,?)",run+"-k1",run+"-k2");
        assertThat(ids(store.candidates("KEYWORD_FILTER",0,1))).containsExactly(run+"-k1");
        assertThat(ids(store.candidates("KEYWORD_FILTER",1,1))).containsExactly(run+"-k2");
        assertThat(ids(store.candidates("RELEVANT",0,10))).containsExactly(run+"-r1",run+"-r2");
        assertThat(ids(store.candidates("ARCHIVE",0,10))).containsExactly(run+"-k1",run+"-k2");

        var mvc=webAppContextSetup(requireNonNull(context)).build();
        String all=mvc.perform(get("/api/v1/discovery")).andReturn().getResponse().getContentAsString();
        String paged=mvc.perform(get("/api/v1/discovery?filter=RELEVANT&page=1&size=1")).andReturn().getResponse().getContentAsString();
        Integer candidateCount=com.jayway.jsonpath.JsonPath.read(paged,"$.candidates.length()");
        assertThat(candidateCount).isEqualTo(1);
        Object firstId=com.jayway.jsonpath.JsonPath.read(paged,"$.candidates[0].id");
        assertThat(firstId).isEqualTo(run+"-r2");
        Object countsPaged=com.jayway.jsonpath.JsonPath.read(paged,"$.counts");
        Object countsAll=com.jayway.jsonpath.JsonPath.read(all,"$.counts");
        assertThat(countsPaged).isEqualTo(countsAll);
        Object ignoredPaged=com.jayway.jsonpath.JsonPath.read(paged,"$.counts[?(@.status=='IGNORED')].count");
        assertThat(ignoredPaged).isEqualTo(List.of((int)(ignoredBefore+2)));
    }
    @Test void migrationAddsTheThreeOverviewIndexesWithoutDestructiveChanges() {
        assertThat(store.jdbc().queryForList("""
            SELECT indexname FROM pg_indexes WHERE schemaname=current_schema() AND tablename='discovery_candidates'
            """,String.class)).contains("ix_discovery_candidates_updated","ix_discovery_candidates_geo_status","ix_discovery_candidates_calendar_entry");
    }
}
