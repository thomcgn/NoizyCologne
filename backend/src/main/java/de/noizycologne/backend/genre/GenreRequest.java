package de.noizycologne.backend.genre;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GenreRequest(
        @NotBlank @Size(max = 100) String canonicalName,
        @NotBlank @Size(max = 100) String displayName,
        Boolean active
) {
    public boolean activeOrDefault() { return active == null || active; }
}
