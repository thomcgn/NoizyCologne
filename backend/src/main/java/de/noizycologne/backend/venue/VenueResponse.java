package de.noizycologne.backend.venue;
public record VenueResponse(String id, String name, String instagram, String websiteUrl,
        String eventsUrl, boolean discoveryEnabled, java.time.Instant lastDiscoveryAt, String discoveryStatus) {
    public static VenueResponse from(Venue venue) {
        return new VenueResponse(venue.getId(), venue.getName(), venue.getInstagram(), venue.getWebsiteUrl(),
            venue.getEventsUrl(), venue.isDiscoveryEnabled(), venue.getLastDiscoveryAt(), venue.getDiscoveryStatus());
    }
}
