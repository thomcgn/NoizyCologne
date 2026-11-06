package de.noizycologne.backend.editorial;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
        @NonNull Set<@NotBlank @Size(max = 100) String> mentionActorIds,

        @Size(max = 20)
        Set<@NotBlank @Size(max = 50) String> genres,

        @Size(max = 100)
        String musicTrackId
) {
    public EditorialPostDraftRequest(String caption, String hashtags, String status,
                                     Set<String> mentionActorIds, Set<String> genres) {
        this(caption, hashtags, status, mentionActorIds, genres, null);
    }

    public EditorialPostDraftRequest(String caption, String hashtags, String status,
                                     @Nullable Set<String> mentionActorIds, Set<String> genres,
                                     String musicTrackId) {
        this.caption = caption == null ? "" : caption;
        this.hashtags = hashtags == null ? "" : hashtags;
        this.status = status;
        this.mentionActorIds = mentionActorIds == null ? java.util.Objects.requireNonNull(Set.<String>of()) : new LinkedHashSet<>(mentionActorIds);
        this.genres = genres == null ? Set.of() : new LinkedHashSet<>(genres);
        this.musicTrackId = musicTrackId == null || musicTrackId.isBlank() ? null : musicTrackId.trim();
    }
}
