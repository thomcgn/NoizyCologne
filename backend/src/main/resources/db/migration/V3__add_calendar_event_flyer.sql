ALTER TABLE calendar_entries
    ADD COLUMN event_flyer_media_id VARCHAR(100)
        REFERENCES media_assets(id) ON DELETE SET NULL;

CREATE INDEX ix_calendar_entries_event_flyer
    ON calendar_entries (event_flyer_media_id);
