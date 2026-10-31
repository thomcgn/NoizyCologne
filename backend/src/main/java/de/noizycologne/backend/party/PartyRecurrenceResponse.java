package de.noizycologne.backend.party;

public record PartyRecurrenceResponse(
        String kind,
        Integer weekday,
        Integer occurrence,
        String description
) {

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
