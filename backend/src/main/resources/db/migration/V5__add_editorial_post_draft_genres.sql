CREATE TABLE editorial_post_draft_genres (
    draft_id  VARCHAR(100) NOT NULL REFERENCES editorial_post_drafts(id) ON DELETE CASCADE,
    genre     VARCHAR(50) NOT NULL,
    PRIMARY KEY (draft_id, genre),
    CONSTRAINT ck_editorial_post_draft_genre CHECK (length(btrim(genre)) > 0)
);
