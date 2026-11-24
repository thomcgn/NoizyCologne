package de.noizycologne.backend.calendar;

import de.noizycologne.backend.actor.ActorRepository;
import de.noizycologne.backend.discovery.DiscoveryStore;
import de.noizycologne.backend.media.MediaAsset;
import de.noizycologne.backend.media.MediaAssetRepository;
import de.noizycologne.backend.party.PartySeriesRepository;
import de.noizycologne.backend.venue.VenueRepository;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CalendarEntryServiceFetchTests {

    private final CalendarEntryRepository entries = mock(CalendarEntryRepository.class);
    private final MediaAssetRepository mediaAssets = mock(MediaAssetRepository.class);

    private CalendarEntryService service() {
        return new CalendarEntryService(entries, mock(VenueRepository.class),
                mock(PartySeriesRepository.class), mock(ActorRepository.class), mediaAssets,
                mock(DiscoveryStore.class));
    }

    private static CalendarEntry entry(String date, String mediaId, String mentionId) {
        CalendarEntry entry = new CalendarEntry(LocalDate.parse(date), "venue", "event", "notes",
                "party", null, null, Set.of(mentionId));
        if (mediaId != null) {
            entry.setEventFlyerMediaId(mediaId);
        }
        return entry;
    }

    private static MediaAsset asset(String id) {
        return new MediaAsset(id, "storage/" + id, id + ".jpg", "image/jpeg", 100, "checksum");
    }

    @Test
    void listUsesOneBulkFlyerLookupForAnyNumberOfRows() {
        when(entries.findAll()).thenReturn(List.of(
                entry("2027-01-01", "media-a", "dj-a"),
                entry("2027-01-02", "media-b", "dj-b"),
                entry("2027-01-03", "media-c", "dj-c")));
        @NonNull List<String> mediaIds = java.util.Objects.requireNonNull(
                List.of("media-a", "media-b", "media-c"));
        when(mediaAssets.findAllById(mediaIds)).thenReturn(List.of(
                asset("media-a"), asset("media-b"), asset("media-c")));

        var responses = service().getEntries();

        assertThat(responses).hasSize(3);
        assertThat(responses).extracting(response -> response.mentionDjIds())
                .containsExactly(List.of("dj-a"), List.of("dj-b"), List.of("dj-c"));
        assertThat(responses).extracting(response -> response.eventFlyer().id())
                .containsExactly("media-a", "media-b", "media-c");
        verify(entries, times(1)).findAll();
        verify(mediaAssets, never()).findById(anyString());
        verify(mediaAssets, times(1)).findAllById(mediaIds);
        verifyNoMoreInteractions(mediaAssets);
    }

    @Test
    void emptyListSkipsFlyerLookup() {
        when(entries.findAll()).thenReturn(List.of());

        assertThat(service().getEntries()).isEmpty();

        verify(entries, times(1)).findAll();
        verifyNoMoreInteractions(mediaAssets);
    }
}
