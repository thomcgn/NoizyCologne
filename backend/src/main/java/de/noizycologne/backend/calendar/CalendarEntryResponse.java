package de.noizycologne.backend.calendar;

import de.noizycologne.backend.media.PartyFlyerResponse;

import java.time.format.DateTimeFormatter;
import java.util.List;

public record CalendarEntryResponse(
        String id,
        String date,
        String locationId,
        String type,
        String notes,
        String partyId,
        String startTime,
        String reason,
        List<String> mentionDjIds,
        PartyFlyerResponse eventFlyer
) {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    public static CalendarEntryResponse from(CalendarEntry entry, PartyFlyerResponse eventFlyer) {
        return new CalendarEntryResponse(
                entry.getId(),
                entry.getEventDate().toString(),
                entry.getLocationId(),
                entry.getEntryType(),
                entry.getNotes(),
                entry.getPartyId(),
                entry.getStartTime() == null ? null : entry.getStartTime().format(TIME_FORMAT),
                entry.getReason(),
                entry.getMentionDjIds().stream().sorted().toList(),
                eventFlyer
        );
    }
}
