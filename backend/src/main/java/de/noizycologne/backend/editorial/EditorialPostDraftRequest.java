package de.noizycologne.backend.editorial;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashSet;
import java.util.Set;

public record EditorialPostDraftRequest(
        @Size(max = 10_000)
        String caption,

        @Size(max = 2_000)
        String hashtags,

        @NotBlank
        @Pattern(regexp = "DRAFT|READY_FOR_REVIEW|APPROVED",
                message = "status muss DRAFT, READY_FOR_REVIEW oder APPROVED sein")
        String status,

        @Size(max = 100)
        Set<@NotBlank @Size(max = 100) String> mentionActorIds,

        @Size(max = 20)
        Set<@NotBlank @Size(max = 50) String> genres,

        @Size(max = 100)
        String musicTrackId
) {
    public EditorialPostDraftRequest(String caption, String hashtags, String status,
                                     Set<String> mentionActorIds, Set<String> genres) {
        this(caption, hashtags, status, mentionActorIds, genres, null);
    }

    public EditorialPostDraftRequest {
        caption = caption == null ? "" : caption;
        hashtags = hashtags == null ? "" : hashtags;
        mentionActorIds = mentionActorIds == null ? Set.of() : new LinkedHashSet<>(mentionActorIds);
        genres = genres == null ? Set.of() : new LinkedHashSet<>(genres);
        musicTrackId = musicTrackId == null || musicTrackId.isBlank() ? null : musicTrackId.trim();
    }
}
