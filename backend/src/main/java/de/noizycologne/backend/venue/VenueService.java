package de.noizycologne.backend.venue;

import de.noizycologne.backend.calendar.CalendarEntryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VenueService {

    private final VenueRepository venueRepository;
    private final CalendarEntryRepository calendarEntryRepository;

    public VenueService(VenueRepository venueRepository, CalendarEntryRepository calendarEntryRepository) {
        this.venueRepository = venueRepository;
        this.calendarEntryRepository = calendarEntryRepository;
    }

    @Transactional
    public void deleteVenue(String id) {
        Venue venue = venueRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Location nicht gefunden"));
        if (calendarEntryRepository.countByLocationId(id) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Die Location wird noch von Kalendereinträgen verwendet und kann nicht gelöscht werden");
        }
        venueRepository.delete(venue);
    }
}
