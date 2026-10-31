package de.noizycologne.backend.party;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record PartyRecurrenceRequest(
        @Size(max = 20)
        String kind,

        @Min(0)
        @Max(6)
        Integer weekday,

        Integer occurrence,

        @Size(max = 2000)
        String description
) {
}
