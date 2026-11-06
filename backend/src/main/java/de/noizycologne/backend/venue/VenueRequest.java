package de.noizycologne.backend.venue;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VenueRequest(
        @NotBlank
        @Size(max = 200)
        String name,

        @Size(max = 30)
        @Pattern(regexp = "[A-Za-z0-9._]*", message = "instagram darf nur Buchstaben, Zahlen, Punkte und Unterstriche enthalten")
        String instagram,
        @Size(max = 2048) String websiteUrl,
        @Size(max = 2048) String eventsUrl,
        boolean discoveryEnabled
) {

    public VenueRequest(String name, String instagram) { this(name, instagram, "", "", false); }
    public VenueRequest {
        websiteUrl = websiteUrl == null ? "" : websiteUrl.trim();
        eventsUrl = eventsUrl == null ? "" : eventsUrl.trim();
        instagram = instagram == null ? "" : instagram;
    }
}
