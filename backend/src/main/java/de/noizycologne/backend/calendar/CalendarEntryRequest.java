package de.noizycologne.backend.calendar;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashSet;
import java.util.Set;

public record CalendarEntryRequest(
        @NotBlank
        String date,

        @NotBlank
        @Size(max = 100)
        String locationId,

        @NotBlank
        String type,

        @Size(max = 10000)
        String notes,

        @Size(max = 100)
        String entryPrice,

        @Size(max = 100)
        String partyId,

        String startTime,

        @Size(max = 200)
        String reason,

        @Size(max = 100)
        Set<@NotBlank @Size(max = 100) String> mentionDjIds
) {

    public CalendarEntryRequest(String date, String locationId, String type,
                                String notes, String partyId, String startTime,
                                String reason, Set<String> mentionDjIds) {
        this(date, locationId, type, notes, "", partyId, startTime, reason, mentionDjIds);
    }

    public CalendarEntryRequest {
        notes = notes == null ? "" : notes;
        entryPrice = entryPrice == null ? "" : entryPrice.trim();
        mentionDjIds = mentionDjIds == null ? Set.of() : new LinkedHashSet<>(mentionDjIds);
    }
}
