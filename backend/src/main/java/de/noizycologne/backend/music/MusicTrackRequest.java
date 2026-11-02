package de.noizycologne.backend.music;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashSet;
import java.util.Set;

public record MusicTrackRequest(
        @NotBlank @Size(max = 200) String artist,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 20) Set<@NotBlank @Size(max = 100) String> genres,
        @Size(max = 100) Set<@NotBlank @Size(max = 100) String> partySeriesIds,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{11}", message = "youtubeVideoId muss eine gültige YouTube-Video-ID sein") String youtubeVideoId,
        @Size(max = 500) @Pattern(regexp = "|https://open\\.spotify\\.com/track/[A-Za-z0-9]+(?:\\?.*)?", message = "spotifyTrackUrl muss auf einen Spotify-Track verweisen") String spotifyTrackUrl,
        @Min(0) Integer previewStartSeconds,
        @Min(0) Integer preferredPositionSeconds
) {
    public MusicTrackRequest {
        artist = artist == null ? "" : artist.trim();
        title = title == null ? "" : title.trim();
        youtubeVideoId = youtubeVideoId == null ? "" : youtubeVideoId.trim();
        spotifyTrackUrl = spotifyTrackUrl == null ? "" : spotifyTrackUrl.trim();
        genres = genres == null ? Set.of() : normalize(genres);
        partySeriesIds = partySeriesIds == null ? Set.of() : new LinkedHashSet<>(partySeriesIds);
        previewStartSeconds = previewStartSeconds == null ? 0 : previewStartSeconds;
        preferredPositionSeconds = preferredPositionSeconds == null ? previewStartSeconds : preferredPositionSeconds;
    }

    private static Set<String> normalize(Set<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        values.forEach(value -> normalized.add(value.trim()));
        return normalized;
    }
}
