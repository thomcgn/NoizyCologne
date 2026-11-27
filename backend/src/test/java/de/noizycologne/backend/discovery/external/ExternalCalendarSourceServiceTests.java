package de.noizycologne.backend.discovery.external;

import de.noizycologne.backend.discovery.DiscoveryProgress;
import de.noizycologne.backend.discovery.DiscoveryStore;
import de.noizycologne.backend.discovery.EventCandidateProcessor;
import de.noizycologne.backend.discovery.EventChangeMonitor;
import de.noizycologne.backend.discovery.EventExtractor;
import de.noizycologne.backend.discovery.EventPageFetcher;
import de.noizycologne.backend.venue.VenueRepository;
import de.noizycologne.backend.discovery.external.ExternalCalendarSourceService.ExternalSource;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalCalendarSourceServiceTests {
    private static final @NonNull String ID="calendar-source";
    private static final @NonNull URI SAFE=Objects.requireNonNull(URI.create("https://calendar.example.org/mtc.ics"));
    private static final @NonNull String SAFE_URL=Objects.requireNonNull(SAFE.toString());
    private static final @NonNull ExternalSource SOURCE=new ExternalSource(ID,"MTC Kalender",SAFE_URL);

    private final DiscoveryStore store=mock(DiscoveryStore.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final EventPageFetcher fetcher=mock(EventPageFetcher.class);
    private final EventCandidateProcessor processor=mock(EventCandidateProcessor.class);
    private final EventChangeMonitor monitor=mock(EventChangeMonitor.class);
    private final VenueRepository venues=mock(VenueRepository.class);
    private final DiscoveryProgress progress=mock(DiscoveryProgress.class);

    private ExternalCalendarSourceService service(boolean enabled) {
        when(store.jdbc()).thenReturn(jdbc);
        return new ExternalCalendarSourceService(store,fetcher,processor,monitor,venues,progress,enabled);
    }

    private static Map<String,@Nullable Object> row(boolean enabled,boolean approved,@NonNull String url) {
        Map<String,@Nullable Object> row=new HashMap<>();
        row.put("enabled",enabled);row.put("approved",approved);row.put("deleted_at",null);
        row.put("source_kind","EXTERNAL_CALENDAR");row.put("external_format","ICS");row.put("url",url);
        return row;
    }

    @Test void configGatePreventsListingAndFetching() {
        var service=service(false);
        assertThat(service.list()).isEmpty();
        var result=service.scan("operation",List.of(SOURCE),false);
        assertThat(result.checked()).isZero();
        assertThat(result.skipped()).isZero();
        verifyNoInteractions(fetcher,processor);
    }

    @Test void unapprovedOrDisabledRowsAreNeverFetched() throws IOException {
        var service=service(true);
        when(jdbc.queryForList(Objects.requireNonNull(anyString()),any(Object.class))).thenReturn(List.of(row(true,false,SAFE_URL)));
        assertThat(service.scan("operation",List.of(SOURCE),false).skipped()).isEqualTo(1);
        when(jdbc.queryForList(Objects.requireNonNull(anyString()),any(Object.class))).thenReturn(List.of(row(false,true,SAFE_URL)));
        assertThat(service.scan("operation",List.of(SOURCE),false).skipped()).isEqualTo(1);
        verify(fetcher,never()).fetch(any());
        verify(fetcher,never()).robotsAllowed(any(),any());
        verifyNoInteractions(processor);
    }

    @Test void invalidUrlIsRejectedBeforeAnyRequest() throws IOException {
        var service=service(true);
        when(jdbc.queryForList(Objects.requireNonNull(anyString()),any(Object.class)))
            .thenReturn(List.of(row(true,true,"http://127.0.0.1/calendar.ics")));
        var result=service.scan("operation",List.of(SOURCE),false);
        assertThat(result.checked()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        verify(fetcher,never()).fetch(any());
        verify(fetcher,never()).robotsAllowed(any(),any());
    }

    @Test void approvedCalendarIsFetchedOnceAndProcessedThroughTheSpecialSourcePath() throws Exception {
        var service=service(true);
        when(jdbc.queryForList(Objects.requireNonNull(anyString()),any(Object.class))).thenReturn(List.of(row(true,true,SAFE_URL)));
        when(fetcher.robotsAllowed(any(URI.class),anyMap())).thenReturn(true);
        when(fetcher.fetch(SAFE)).thenReturn(new EventPageFetcher.Page(SAFE,"""
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:unit@example.org
            DTSTART:20261204T200000
            SUMMARY:Indie Party
            END:VEVENT
            END:VCALENDAR
            ""","text/calendar"));
        var result=service.scan("operation",List.of(SOURCE),false);
        assertThat(result.checked()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(result.failed()).isZero();
        verify(fetcher,times(1)).fetch(SAFE);
        verify(processor,times(1)).processSpecial(eq(ID),any(EventExtractor.Event.class),eq("operation"));
        verify(jdbc).update("UPDATE discovery_sources SET external_last_success_at=now(),external_last_error='' WHERE id=?",ID);
    }

    @Test void robotsBlockedFeedNeverFetchesTheDocument() throws IOException {
        var service=service(true);
        when(jdbc.queryForList(Objects.requireNonNull(anyString()),any(Object.class))).thenReturn(List.of(row(true,true,SAFE_URL)));
        when(fetcher.robotsAllowed(any(URI.class),anyMap())).thenReturn(false);
        var result=service.scan("operation",List.of(SOURCE),false);
        assertThat(result.failed()).isEqualTo(1);
        verify(fetcher,never()).fetch(any());
        verifyNoInteractions(processor);
    }
}
