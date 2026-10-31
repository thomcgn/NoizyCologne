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
        String partyId,

        String startTime,

        @Size(max = 200)
        String reason,

        @Size(max = 100)
        Set<@NotBlank @Size(max = 100) String> mentionDjIds
) {

    public CalendarEntryRequest {
        notes = notes == null ? "" : notes;
        mentionDjIds = mentionDjIds == null ? Set.of() : new LinkedHashSet<>(mentionDjIds);
    }
}
