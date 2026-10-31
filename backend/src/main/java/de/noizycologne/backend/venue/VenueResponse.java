package de.noizycologne.backend.venue;

public record VenueResponse(String id, String name, String instagram) {

    public static VenueResponse from(Venue venue) {
        return new VenueResponse(venue.getId(), venue.getName(), venue.getInstagram());
    }
}
