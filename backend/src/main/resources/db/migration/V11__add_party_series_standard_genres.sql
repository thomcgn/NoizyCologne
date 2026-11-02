CREATE TABLE party_series_genres (
    party_id VARCHAR(100) NOT NULL REFERENCES party_series(id) ON DELETE CASCADE,
    genre    VARCHAR(100) NOT NULL,
    PRIMARY KEY (party_id, genre),
    CONSTRAINT ck_party_series_genre CHECK (length(btrim(genre)) > 0)
);
