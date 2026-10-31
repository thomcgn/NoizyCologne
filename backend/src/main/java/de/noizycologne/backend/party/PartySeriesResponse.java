package de.noizycologne.backend.party;

import java.util.List;

public record PartySeriesResponse(
        String id,
        String name,
        String instagram,
        String color,
        List<String> residentDjIds,
        List<String> otherDjIds,
        List<String> organizerIds,
        String djMentionDefault,
        PartyRecurrenceResponse recurrence
) {

    public static PartySeriesResponse from(PartySeries party) {
        return new PartySeriesResponse(
                party.getId(),
                party.getName(),
                party.getInstagram(),
                party.getColor(),
                party.getDjAssignments().stream()
                        .filter(assignment -> "resident".equals(assignment.getAssignment()))
                        .map(PartyDjAssignment::getActorId)
                        .sorted()
                        .toList(),
                party.getDjAssignments().stream()
                        .filter(assignment -> "other".equals(assignment.getAssignment()))
                        .map(PartyDjAssignment::getActorId)
                        .sorted()
                        .toList(),
                party.getOrganizerIds().stream().sorted().toList(),
                party.getDjMentionDefault(),
                PartyRecurrenceResponse.from(party)
        );
    }
}
