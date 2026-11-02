package de.noizycologne.backend.calendar;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarEntryRepository extends JpaRepository<CalendarEntry, String> {

    long countByLocationId(String locationId);

    long countByPartyId(String partyId);
}
