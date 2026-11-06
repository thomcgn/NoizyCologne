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
        PartyRecurrenceRequest recurrence,

        @Size(max = 50)
        Set<@NotBlank @Size(max = 100) String> standardGenres,
        @Pattern(regexp = "(?:[01][0-9]|2[0-3]):[0-5][0-9]", message = "defaultStartTime muss im Format HH:mm vorliegen")
        @org.jspecify.annotations.Nullable String defaultStartTime,
        @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(30)
        @tools.jackson.databind.annotation.JsonDeserialize(using = WholeEuroDeserializer.class)
        @org.jspecify.annotations.Nullable Integer defaultEntryPrice
) {

    public PartySeriesRequest(String name, String instagram, String color, String djMentionDefault,
                              Set<String> residentDjIds, Set<String> otherDjIds, Set<String> organizerIds,
                              PartyRecurrenceRequest recurrence, Set<String> standardGenres) {
        this(name, instagram, color, djMentionDefault, residentDjIds, otherDjIds, organizerIds, recurrence, standardGenres, null, null);
    }

    public PartySeriesRequest(String name, String instagram, String color, String djMentionDefault,
                              Set<String> residentDjIds, Set<String> otherDjIds, Set<String> organizerIds,
                              PartyRecurrenceRequest recurrence) {
        this(name, instagram, color, djMentionDefault, residentDjIds, otherDjIds, organizerIds, recurrence, Set.of());
    }

    public PartySeriesRequest {
        instagram = instagram == null ? "" : instagram;
        residentDjIds = residentDjIds == null ? Set.of() : new LinkedHashSet<>(residentDjIds);
        otherDjIds = otherDjIds == null ? Set.of() : new LinkedHashSet<>(otherDjIds);
        organizerIds = organizerIds == null ? Set.of() : new LinkedHashSet<>(organizerIds);
        standardGenres = standardGenres == null ? Set.of() : normalize(standardGenres);
    }

    private static Set<String> normalize(Set<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        values.forEach(value -> normalized.add(value.trim()));
        return normalized;
    }
}
