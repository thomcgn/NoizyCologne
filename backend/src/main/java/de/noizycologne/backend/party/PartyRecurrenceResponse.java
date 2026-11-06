package de.noizycologne.backend.party;

import org.jspecify.annotations.Nullable;

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
        var weekday = party.getRecurrenceWeekday();
        var occurrence = party.getRecurrenceOccurrence();
        return new PartyRecurrenceResponse(
                party.getRecurrenceKind(),
                weekday == null ? null : weekday.intValue(),
                occurrence == null ? null : occurrence.intValue(),
                party.getRecurrenceDescription()
        );
    }
}
