package de.noizycologne.backend.actor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record ActorRequest(
        @NotBlank
        @Size(max = 200)
        String name,

        @NotBlank
        @Pattern(regexp = "person|collective", message = "kind muss person oder collective sein")
        String kind,

        @Size(max = 2)
        Set<@NotBlank @Pattern(regexp = "dj|organizer", message = "Unbekannte Akteurrolle") String> roles,

        @Size(max = 30)
        @Pattern(regexp = "[A-Za-z0-9._]*", message = "instagram darf nur Buchstaben, Zahlen, Punkte und Unterstriche enthalten")
        String instagram,

        Set<@NotBlank @Size(max = 100) String> collectiveIds
) {

    public ActorRequest {
        roles = roles == null ? Set.of() : new java.util.LinkedHashSet<>(roles);
        instagram = instagram == null ? "" : instagram;
        collectiveIds = collectiveIds == null ? Set.of() : new java.util.LinkedHashSet<>(collectiveIds);
    }
}
