package de.noizycologne.backend.music;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "music_tracks")
public class MusicTrack {

    @Id
    @Column(nullable = false, length = 100)
    private String id;

    @Column(nullable = false, length = 200)
    private String artist;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "youtube_video_id", nullable = false, length = 11)
    private String youtubeVideoId;

    @Column(name = "spotify_track_url", nullable = false, length = 500)
    private String spotifyTrackUrl;

    @Column(name = "preview_start_seconds", nullable = false)
    private int previewStartSeconds;

    @Column(name = "preferred_position_seconds", nullable = false)
    private int preferredPositionSeconds;

    @Column(name = "last_used_at")
    private OffsetDateTime lastUsedAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "music_track_genres", joinColumns = @JoinColumn(name = "track_id"))
    @Column(name = "genre", nullable = false, length = 100)
    private Set<String> genres = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "music_track_party_series", joinColumns = @JoinColumn(name = "track_id"))
    @Column(name = "party_series_id", nullable = false, length = 100)
    private Set<String> partySeriesIds = new LinkedHashSet<>();

    protected MusicTrack() {
    }

    public MusicTrack(String artist, String title, Collection<String> genres,
                      Collection<String> partySeriesIds, String youtubeVideoId,
                      String spotifyTrackUrl, int previewStartSeconds,
                      int preferredPositionSeconds) {
        this.artist = artist;
        this.title = title;
        this.genres = new LinkedHashSet<>(genres);
        this.partySeriesIds = new LinkedHashSet<>(partySeriesIds);
        this.youtubeVideoId = youtubeVideoId;
        this.spotifyTrackUrl = spotifyTrackUrl;
        this.previewStartSeconds = previewStartSeconds;
        this.preferredPositionSeconds = preferredPositionSeconds;
    }

    @PrePersist
    void assignId() {
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
    }

    public String getId() { return id; }
    public String getArtist() { return artist; }
    public String getTitle() { return title; }
    public String getYoutubeVideoId() { return youtubeVideoId; }
    public String getSpotifyTrackUrl() { return spotifyTrackUrl; }
    public int getPreviewStartSeconds() { return previewStartSeconds; }
    public int getPreferredPositionSeconds() { return preferredPositionSeconds; }
    public OffsetDateTime getLastUsedAt() { return lastUsedAt; }
    public Set<String> getGenres() { return genres; }
    public Set<String> getPartySeriesIds() { return partySeriesIds; }

    public void update(String artist, String title, Collection<String> genres,
                       Collection<String> partySeriesIds, String youtubeVideoId,
                       String spotifyTrackUrl, int previewStartSeconds,
                       int preferredPositionSeconds) {
        this.artist = artist;
        this.title = title;
        this.genres.clear();
        this.genres.addAll(genres);
        this.partySeriesIds.clear();
        this.partySeriesIds.addAll(partySeriesIds);
        this.youtubeVideoId = youtubeVideoId;
        this.spotifyTrackUrl = spotifyTrackUrl;
        this.previewStartSeconds = previewStartSeconds;
        this.preferredPositionSeconds = preferredPositionSeconds;
    }

    public void markUsed(OffsetDateTime usedAt) {
        this.lastUsedAt = usedAt;
    }
}
