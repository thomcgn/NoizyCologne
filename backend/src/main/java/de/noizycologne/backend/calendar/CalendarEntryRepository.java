package de.noizycologne.backend.calendar;

import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CalendarEntryRepository extends JpaRepository<CalendarEntry, String> {

    long countByLocationId(String locationId);

    long countByPartyId(String partyId);

    @Override
    @EntityGraph(attributePaths = "mentionDjIds")
    @NonNull List<CalendarEntry> findAll();
}
