package de.noizycologne.backend.discovery.external;

import de.noizycologne.backend.discovery.*;
import de.noizycologne.backend.party.PartySeries;
import de.noizycologne.backend.party.PartySeriesRepository;
import de.noizycologne.backend.venue.Venue;
import de.noizycologne.backend.venue.VenueRepository;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

/** Isolated-DB fixture test: no live network, no real MTC URL. */
@SpringBootTest(properties={"noizy.discovery.enabled=false","noizy.discovery.monitor.enabled=false",
    "noizy.discovery.external-calendar.enabled=true","logging.level.root=WARN"})
@ActiveProfiles("local")
class ExternalCalendarIntegrationTests {
    private static final @NonNull URI APPROVED_URI=Objects.requireNonNull(URI.create("https://calendar.example.org/approved-calendar.ics"));
    private static final @NonNull URI UNAPPROVED_URI=Objects.requireNonNull(URI.create("https://calendar.example.org/unapproved-calendar.ics"));

    @Autowired DiscoveryStore store;
    @Autowired DiscoveryScheduler scheduler;
    @Autowired DiscoveryGeographyService geography;
    @Autowired VenueRepository venues;
    @Autowired PartySeriesRepository parties;
    @MockitoBean EventPageFetcher fetcher;
    @MockitoBean VenueSourceDiscoveryService discovery;

    String approved,id;
    Venue venue;
    PartySeries party;

    @BeforeEach void setup() throws IOException {
        venue=venues.saveAndFlush(new Venue("Blue Shell "+UUID.randomUUID(),""));
        party=parties.saveAndFlush(new PartySeries("Indie Party "+UUID.randomUUID(),"","#E11D48","manual",null,null,null,null,java.util.Set.of(),java.util.Set.of()));
        approved=addSource("Freigegebener Kalender",APPROVED_URI.toString(),true,true);
        id=addSource("Nicht freigegebener Kalender",UNAPPROVED_URI.toString(),false,true);
        String body;
        try (var in=getClass().getResourceAsStream("/discovery/external/approved-calendar.ics")) {
            body=Objects.requireNonNull(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8));
        }
        // Use the same party-series name the editor has already approved, exactly
        // as the existing special-source integration tests do.
        body=Objects.requireNonNull(body.replace("SUMMARY:Indie Party","SUMMARY:"+party.getName()));
        when(fetcher.robotsAllowed(any(URI.class),anyMap())).thenReturn(true);
        when(fetcher.fetch(APPROVED_URI)).thenReturn(new EventPageFetcher.Page(APPROVED_URI,body,"text/calendar"));
        when(fetcher.fetch(UNAPPROVED_URI)).thenReturn(new EventPageFetcher.Page(UNAPPROVED_URI,body,"text/calendar"));
        when(discovery.discoverSpecial(any(),any())).thenReturn(List.of());
        // Confirm the venue from exactly the feed evidence the scan will see;
        // a bare city name is deliberately not accepted as a Cologne address.
        var parsed=ExternalCalendarParser.parse("ICS",body,APPROVED_URI);
        assertThat(parsed.extraction().events()).hasSize(1);
        geography.remember(venue.getId(),parsed.extraction().events().getFirst(),APPROVED_URI.toString());
    }

    @AfterEach void cleanup() {
        store.jdbc().update("DELETE FROM calendar_entries WHERE location_id=?",venue.getId());
        store.jdbc().update("DELETE FROM discovery_sources WHERE id IN (?,?)",approved,id);
        venues.deleteById(venue.getId());
        parties.deleteById(party.getId());
    }

    private String addSource(String name,String url,boolean isApproved,boolean enabled) {
        String sourceId=UUID.randomUUID().toString();
        store.jdbc().update("""
            INSERT INTO discovery_sources(id,venue_id,name,url,source_type,source_kind,is_seed,approved,enabled,priority,external_format)
            VALUES (?,NULL,?,?,'EXTERNAL','EXTERNAL_CALENDAR',true,?,?,100,'ICS')
            """,sourceId,name,url,isApproved,enabled);
        return sourceId;
    }

    @Test void approvedCalendarImportsEventExactlyOnceAndUnapprovedSourceDoesNothing() throws IOException {
        scheduler.scan();scheduler.scan();
        var candidates=store.jdbc().queryForList("SELECT * FROM discovery_candidates WHERE source_id=?",approved);
        assertThat(candidates).hasSize(1);
        var row=candidates.getFirst();
        assertThat(row).containsEntry("title",party.getName())
            .containsEntry("venue_id",venue.getId())
            .containsEntry("status","IMPORTED")
            .containsEntry("decision_origin","AUTOMATIC");
        assertThat(row.get("event_date")).isEqualTo(java.sql.Date.valueOf(LocalDate.of(2026,11,28)));
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM calendar_entries WHERE location_id=?",Integer.class,venue.getId())).isEqualTo(1);
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM editorial_post_drafts WHERE calendar_entry_id=?",Integer.class,row.get("calendar_entry_id"))).isZero();
        assertThat(store.jdbc().queryForObject("SELECT external_last_success_at FROM discovery_sources WHERE id=?",Object.class,approved)).isNotNull();
        assertThat(store.jdbc().queryForObject("SELECT external_last_error FROM discovery_sources WHERE id=?",String.class,approved)).isEmpty();
        assertThat(store.jdbc().queryForObject("SELECT count(*) FROM discovery_candidates WHERE source_id=?",Integer.class,id)).isZero();
        verify(fetcher,times(2)).fetch(APPROVED_URI);
        verify(fetcher,never()).fetch(UNAPPROVED_URI);
    }
}
