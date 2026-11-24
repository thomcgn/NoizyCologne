package de.noizycologne.backend.venue;

import de.noizycologne.backend.calendar.CalendarEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VenueServiceTests {

    private final VenueRepository venues = mock(VenueRepository.class);
    private final CalendarEntryRepository entries = mock(CalendarEntryRepository.class);
    private final VenueService service = new VenueService(venues, entries);

    private static Venue venue() {
        var venue = new Venue("NC Test Venue", "nc_venue");
        venue.assignId();
        return venue;
    }

    @Test
    void deleteRejectsUnknownAndEmptyIdsBeforeDeleting() {
        assertThatThrownBy(() -> service.deleteVenue("missing"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value()));
        assertThatThrownBy(() -> service.deleteVenue(""))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode().value()).isEqualTo(HttpStatus.NOT_FOUND.value()));
        verify(venues, never()).delete(any(Venue.class));
    }

    @Test
    void deleteRejectsVenueStillReferencedByCalendarEntries() {
        var venue = venue();
        when(venues.findById(venue.getId())).thenReturn(Optional.of(venue));
        when(entries.countByLocationId(venue.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.deleteVenue(venue.getId()))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value());
                    assertThat(error).hasMessageContaining("Kalendereinträgen");
                });
        verify(venues, never()).delete(any(Venue.class));
    }

    @Test
    void deleteRemovesUnreferencedVenue() {
        var venue = venue();
        when(venues.findById(venue.getId())).thenReturn(Optional.of(venue));
        when(entries.countByLocationId(venue.getId())).thenReturn(0L);

        service.deleteVenue(venue.getId());

        verify(venues).delete(venue);
    }
}
