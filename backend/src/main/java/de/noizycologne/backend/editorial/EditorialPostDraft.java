package de.noizycologne.backend.editorial;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "editorial_post_drafts")
public class EditorialPostDraft {

    @Id
    @Column(nullable = false, length = 100)
    private String id;

    @Column(name = "calendar_entry_id", nullable = false, unique = true, length = 100)
    private String calendarEntryId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String caption;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String hashtags;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "music_track_id", length = 100)
    private String musicTrackId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "editorial_post_draft_mentions",
            joinColumns = @JoinColumn(name = "draft_id")
    )
    @Column(name = "actor_id", nullable = false, length = 100)
    private Set<String> mentionActorIds = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "editorial_post_draft_genres",
            joinColumns = @JoinColumn(name = "draft_id")
    )
    @Column(name = "genre", nullable = false, length = 50)
    private Set<String> genres = new LinkedHashSet<>();

    protected EditorialPostDraft() {
    }

    public EditorialPostDraft(String calendarEntryId, String caption, String hashtags,
                              String status, Collection<String> mentionActorIds,
                              Collection<String> genres) {
        this.calendarEntryId = calendarEntryId;
        this.caption = caption;
        this.hashtags = hashtags;
        this.status = status;
        this.mentionActorIds = new LinkedHashSet<>(mentionActorIds);
        this.genres = new LinkedHashSet<>(genres);
    }

    @PrePersist
    void assignId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }

    public String getId() { return id; }
    public String getCalendarEntryId() { return calendarEntryId; }
    public String getCaption() { return caption; }
    public String getHashtags() { return hashtags; }
    public String getStatus() { return status; }
    public Set<String> getMentionActorIds() { return mentionActorIds; }
    public Set<String> getGenres() { return genres; }
    @Nullable
    public String getMusicTrackId() { return musicTrackId; }

    public void update(String caption, String hashtags, String status,
                       Collection<String> mentionActorIds, Collection<String> genres,
                       String musicTrackId) {
        this.caption = caption;
        this.hashtags = hashtags;
        this.status = status;
        this.mentionActorIds.clear();
        this.mentionActorIds.addAll(mentionActorIds);
        this.genres.clear();
        this.genres.addAll(genres);
        this.musicTrackId = musicTrackId;
    }
}
