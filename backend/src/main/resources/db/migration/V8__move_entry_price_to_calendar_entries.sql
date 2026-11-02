ALTER TABLE party_series
    DROP CONSTRAINT ck_party_series_entry_price,
    DROP COLUMN entry_price;

ALTER TABLE calendar_entries
    ADD COLUMN entry_price VARCHAR(100) NOT NULL DEFAULT '';

ALTER TABLE calendar_entries
    ADD CONSTRAINT ck_calendar_entries_entry_price CHECK (length(entry_price) <= 100);
