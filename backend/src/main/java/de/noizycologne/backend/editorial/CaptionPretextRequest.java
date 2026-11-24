package de.noizycologne.backend.editorial;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Der optionale expectedRevision ist ein Optimistic-Lock, kein Ersatz für die Serverseitige Revision. */
public record CaptionPretextRequest(
        @NotBlank
        @Size(max = 100)
        String pretext,

        @Positive
        Long expectedRevision
) {
    public CaptionPretextRequest {
        pretext = pretext == null ? "" : pretext.trim();
    }
}
