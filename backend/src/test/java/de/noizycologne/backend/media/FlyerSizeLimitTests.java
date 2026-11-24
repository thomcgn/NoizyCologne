package de.noizycologne.backend.media;

import de.noizycologne.backend.calendar.CalendarEntry;
import de.noizycologne.backend.calendar.CalendarEntryRepository;
import de.noizycologne.backend.discovery.DiscoveryStore;
import de.noizycologne.backend.party.PartySeries;
import de.noizycologne.backend.party.PartySeriesRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlyerSizeLimitTests {

    @Test
    void sharedLimitIsTenMegabytesAndDrivesTheUserFacingMessage() {
        assertThat(FlyerSizeLimit.MAX_BYTES).isEqualTo(10L * 1024 * 1024);
        assertThat(FlyerSizeLimit.tooLargeMessage()).contains("10 MB");
    }

    @Test
    void partyFlyerServiceRejectsFilesAboveTheSharedLimitWithTheSharedMessage() {
        var parties = mock(PartySeriesRepository.class);
        when(parties.findById("party")).thenAnswer(invocation -> Optional.of(mock(PartySeries.class)));
        var service = new PartyFlyerService(parties, mock(MediaAssetRepository.class), "target/test-flyer-limit");

        var thrown = requireNonNull(assertThrows(ResponseStatusException.class,
                () -> service.upload("party", oversizedFile())));
        assertThat(requireNonNull(thrown.getMessage())).contains(FlyerSizeLimit.tooLargeMessage());
    }

    @Test
    void calendarEntryFlyerServiceRejectsFilesAboveTheSharedLimitWithTheSharedMessage() {
        var entries = mock(CalendarEntryRepository.class);
        var entry = mock(CalendarEntry.class);
        when(entry.getEntryType()).thenReturn("event");
        when(entries.findById("entry")).thenAnswer(invocation -> Optional.of(entry));
        var store = mock(DiscoveryStore.class);
        when(store.jdbc()).thenReturn(mock(JdbcTemplate.class));
        var service = new CalendarEntryFlyerService(
                entries, mock(MediaAssetRepository.class), "target/test-flyer-limit", store);

        var thrown = requireNonNull(assertThrows(ResponseStatusException.class,
                () -> service.upload("entry", oversizedFile())));
        assertThat(requireNonNull(thrown.getMessage())).contains(FlyerSizeLimit.tooLargeMessage());
    }

    private MultipartFile oversizedFile() {
        var file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(FlyerSizeLimit.MAX_BYTES + 1);
        return file;
    }
}
