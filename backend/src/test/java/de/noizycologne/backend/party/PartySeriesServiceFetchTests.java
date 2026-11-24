package de.noizycologne.backend.party;

import de.noizycologne.backend.actor.ActorRepository;
import de.noizycologne.backend.calendar.CalendarEntryRepository;
import de.noizycologne.backend.calendar.RecurrencePlanningService;
import de.noizycologne.backend.genre.GenreRepository;
import de.noizycologne.backend.media.MediaAsset;
import de.noizycologne.backend.media.MediaAssetRepository;
import de.noizycologne.backend.venue.VenueRepository;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

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

class PartySeriesServiceFetchTests {

    private final PartySeriesRepository parties = mock(PartySeriesRepository.class);
    private final MediaAssetRepository mediaAssets = mock(MediaAssetRepository.class);

    private PartySeriesService service() {
        return new PartySeriesService(parties, mock(ActorRepository.class), mediaAssets,
                mock(GenreRepository.class), mock(CalendarEntryRepository.class),
                mock(RecurrencePlanningService.class), mock(VenueRepository.class));
    }

    private static @NonNull PartySeries party(String name, String mediaId) {
        PartySeries party = new PartySeries(name, "handle", "#123456", "manual", null, null, null,
                null, List.of(new PartyDjAssignment("dj", "resident")), Set.of("organizer"),
                Set.of("Techno"));
        if (mediaId != null) {
            party.setDefaultFlyerMediaId(mediaId);
        }
        return party;
    }

    private static MediaAsset asset(String id) {
        return new MediaAsset(id, "storage/" + id, id + ".jpg", "image/jpeg", 100, "checksum");
    }

    @Test
    void listUsesOneBulkFlyerLookupForAnyNumberOfRows() {
        when(parties.findAll()).thenReturn(List.of(
                party("Party A", "media-a"),
                party("Party B", "media-b"),
                party("Party C", "media-c")));
        @NonNull List<String> mediaIds = java.util.Objects.requireNonNull(
                List.of("media-a", "media-b", "media-c"));
        when(mediaAssets.findAllById(mediaIds)).thenReturn(List.of(
                asset("media-a"), asset("media-b"), asset("media-c")));

        var responses = service().getParties();

        assertThat(responses).hasSize(3);
        assertThat(responses).extracting(response -> response.defaultFlyer().id())
                .containsExactly("media-a", "media-b", "media-c");
        assertThat(responses).allSatisfy(response -> assertThat(response.residentDjIds()).containsExactly("dj"));
        verify(parties, times(1)).findAll();
        verify(mediaAssets, never()).findById(anyString());
        verify(mediaAssets, times(1)).findAllById(mediaIds);
        verifyNoMoreInteractions(mediaAssets);
    }

    @Test
    void emptyListSkipsFlyerLookup() {
        when(parties.findAll()).thenReturn(List.of());

        assertThat(service().getParties()).isEmpty();

        verify(parties, times(1)).findAll();
        verifyNoMoreInteractions(mediaAssets);
    }
}
