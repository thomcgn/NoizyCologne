package de.noizycologne.backend.party;

import de.noizycologne.backend.media.PartyFlyerResponse;
import java.util.List;

public record PartySeriesResponse(
        String id,
        String name,
        String instagram,
        String color,
        List<String> residentDjIds,
        List<String> otherDjIds,
        List<String> organizerIds,
        List<String> standardGenres,
        String djMentionDefault,
        PartyRecurrenceResponse recurrence,
        PartyFlyerResponse defaultFlyer
) {

    public static PartySeriesResponse from(PartySeries party) {
        return new PartySeriesResponse(
                party.getId(),
                party.getName(),
                party.getInstagram(),
                party.getColor(),
                party.getDjAssignments().stream()
                        .filter(assignment -> "resident".equals(assignment.getAssignment()))
                        .map(assignment -> assignment.getActorId())
                        .sorted()
                        .toList(),
                party.getDjAssignments().stream()
                        .filter(assignment -> "other".equals(assignment.getAssignment()))
                        .map(assignment -> assignment.getActorId())
                        .sorted()
                        .toList(),
                party.getOrganizerIds().stream().sorted().toList(),
                party.getStandardGenres().stream().sorted().toList(),
                party.getDjMentionDefault(),
                PartyRecurrenceResponse.from(party),
                null
        );
    }

    public static PartySeriesResponse from(PartySeries party, PartyFlyerResponse defaultFlyer) {
        PartySeriesResponse response = from(party);
        return new PartySeriesResponse(response.id(), response.name(), response.instagram(),
                response.color(), response.residentDjIds(), response.otherDjIds(),
                response.organizerIds(), response.standardGenres(), response.djMentionDefault(), response.recurrence(),
                defaultFlyer);
    }
}
