package de.noizycologne.backend.party;

import java.time.LocalDate;

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
        String description,
        @Size(max = 100) @org.jspecify.annotations.Nullable String locationId,
        @org.jspecify.annotations.Nullable LocalDate startDate
) {
    public PartyRecurrenceRequest(String kind,Integer weekday,Integer occurrence,String description) {
        this(kind,weekday,occurrence,description,null,null);
    }
}
