package de.noizycologne.backend.party;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashSet;
import java.util.Set;

public record PartySeriesRequest(
        @NotBlank
        @Size(max = 200)
        String name,

        @Size(max = 30)
        @Pattern(regexp = "[A-Za-z0-9._]*", message = "instagram darf nur Buchstaben, Zahlen, Punkte und Unterstriche enthalten")
        String instagram,

        @NotBlank
        @Pattern(regexp = "#[0-9A-Fa-f]{6}", message = "color muss ein gültiger Hex-Farbwert sein")
        String color,

        @NotBlank
        @Pattern(regexp = "manual|residents", message = "djMentionDefault muss manual oder residents sein")
        String djMentionDefault,

        @Size(max = 100)
        Set<@NotBlank @Size(max = 100) String> residentDjIds,

        @Size(max = 100)
        Set<@NotBlank @Size(max = 100) String> otherDjIds,

        @Size(max = 100)
        Set<@NotBlank @Size(max = 100) String> organizerIds,

        @Valid
        PartyRecurrenceRequest recurrence
) {

    public PartySeriesRequest {
        instagram = instagram == null ? "" : instagram;
        residentDjIds = residentDjIds == null ? Set.of() : new LinkedHashSet<>(residentDjIds);
        otherDjIds = otherDjIds == null ? Set.of() : new LinkedHashSet<>(otherDjIds);
        organizerIds = organizerIds == null ? Set.of() : new LinkedHashSet<>(organizerIds);
    }
}
