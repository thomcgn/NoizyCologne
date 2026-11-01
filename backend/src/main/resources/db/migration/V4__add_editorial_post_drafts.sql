CREATE TABLE editorial_post_drafts (
    id                VARCHAR(100) PRIMARY KEY,
    calendar_entry_id VARCHAR(100) NOT NULL UNIQUE REFERENCES calendar_entries(id) ON DELETE CASCADE,
    caption           TEXT NOT NULL DEFAULT '',
    hashtags          TEXT NOT NULL DEFAULT '',
    status            VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    CONSTRAINT ck_editorial_post_drafts_status
        CHECK (status IN ('DRAFT', 'READY_FOR_REVIEW', 'APPROVED'))
);

CREATE TABLE editorial_post_draft_mentions (
    draft_id  VARCHAR(100) NOT NULL REFERENCES editorial_post_drafts(id) ON DELETE CASCADE,
    actor_id  VARCHAR(100) NOT NULL REFERENCES actors(id) ON DELETE RESTRICT,
    PRIMARY KEY (draft_id, actor_id)
);

CREATE INDEX ix_editorial_post_draft_mentions_actor
    ON editorial_post_draft_mentions(actor_id);
