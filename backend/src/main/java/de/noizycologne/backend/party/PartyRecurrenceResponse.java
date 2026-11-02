package de.noizycologne.backend.party;

import org.springframework.lang.Nullable;

public record PartyRecurrenceResponse(
        String kind,
        Integer weekday,
        Integer occurrence,
        String description
) {

    @Nullable
    public static PartyRecurrenceResponse from(PartySeries party) {
        if (party.getRecurrenceKind() == null) {
            return null;
        }
        return new PartyRecurrenceResponse(
                party.getRecurrenceKind(),
                party.getRecurrenceWeekday() == null ? null : party.getRecurrenceWeekday().intValue(),
                party.getRecurrenceOccurrence() == null ? null : party.getRecurrenceOccurrence().intValue(),
                party.getRecurrenceDescription()
        );
    }
}
