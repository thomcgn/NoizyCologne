package de.noizycologne.backend.music;

import java.time.OffsetDateTime;
import java.util.List;

public record MusicTrackResponse(
        String id,
        String artist,
        String title,
        List<String> genres,
        List<String> partySeriesIds,
        String youtubeVideoId,
        String spotifyTrackUrl,
        int previewStartSeconds,
        int preferredPositionSeconds,
        OffsetDateTime lastUsedAt,
        int score,
        List<String> matchReasons
) {
    public static MusicTrackResponse from(MusicTrack track, int score, List<String> reasons) {
        return new MusicTrackResponse(track.getId(), track.getArtist(), track.getTitle(),
                track.getGenres().stream().sorted().toList(), track.getPartySeriesIds().stream().sorted().toList(),
                track.getYoutubeVideoId(), track.getSpotifyTrackUrl(), track.getPreviewStartSeconds(),
                track.getPreferredPositionSeconds(), track.getLastUsedAt(), score, reasons);
    }
}
