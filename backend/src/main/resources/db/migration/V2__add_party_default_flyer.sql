CREATE TABLE media_assets (
    id                VARCHAR(100) PRIMARY KEY,
    storage_path      VARCHAR(500) NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    media_type        VARCHAR(50) NOT NULL,
    file_size         BIGINT NOT NULL,
    checksum_sha256   VARCHAR(64) NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_media_assets_type CHECK (media_type IN ('image/jpeg', 'image/png')),
    CONSTRAINT ck_media_assets_size CHECK (file_size > 0 AND file_size <= 10485760)
);

ALTER TABLE party_series
    ADD COLUMN default_flyer_media_id VARCHAR(100)
        REFERENCES media_assets(id) ON DELETE SET NULL;

CREATE INDEX ix_party_series_default_flyer ON party_series (default_flyer_media_id);
