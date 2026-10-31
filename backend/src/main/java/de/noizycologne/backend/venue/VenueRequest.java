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
        String instagram
) {

    public VenueRequest {
        instagram = instagram == null ? "" : instagram;
    }
}
