CREATE TABLE music_tracks (
    id                         VARCHAR(100) PRIMARY KEY,
    artist                     VARCHAR(200) NOT NULL,
    title                      VARCHAR(200) NOT NULL,
    youtube_video_id           VARCHAR(11) NOT NULL,
    spotify_track_url          VARCHAR(500) NOT NULL DEFAULT '',
    preview_start_seconds      INTEGER NOT NULL DEFAULT 0,
    preferred_position_seconds INTEGER NOT NULL DEFAULT 0,
    last_used_at               TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_music_tracks_artist CHECK (length(btrim(artist)) > 0),
    CONSTRAINT ck_music_tracks_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_music_tracks_youtube_id CHECK (youtube_video_id ~ '^[A-Za-z0-9_-]{11}$'),
    CONSTRAINT ck_music_tracks_preview_position CHECK (preview_start_seconds >= 0 AND preferred_position_seconds >= 0)
);

CREATE TABLE music_track_genres (
    track_id VARCHAR(100) NOT NULL REFERENCES music_tracks(id) ON DELETE CASCADE,
    genre    VARCHAR(100) NOT NULL,
    PRIMARY KEY (track_id, genre),
    CONSTRAINT ck_music_track_genre CHECK (length(btrim(genre)) > 0)
);

CREATE TABLE music_track_party_series (
    track_id       VARCHAR(100) NOT NULL REFERENCES music_tracks(id) ON DELETE CASCADE,
    party_series_id VARCHAR(100) NOT NULL REFERENCES party_series(id) ON DELETE CASCADE,
    PRIMARY KEY (track_id, party_series_id)
);

ALTER TABLE editorial_post_drafts
    ADD COLUMN music_track_id VARCHAR(100) REFERENCES music_tracks(id) ON DELETE SET NULL;

CREATE INDEX ix_music_tracks_last_used_at ON music_tracks(last_used_at);
CREATE INDEX ix_music_track_party_series_party ON music_track_party_series(party_series_id);
