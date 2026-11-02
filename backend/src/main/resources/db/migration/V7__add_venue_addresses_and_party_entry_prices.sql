ALTER TABLE venues
    ADD COLUMN street VARCHAR(200) NOT NULL DEFAULT '',
    ADD COLUMN postal_code VARCHAR(20) NOT NULL DEFAULT '',
    ADD COLUMN city VARCHAR(120) NOT NULL DEFAULT '';

ALTER TABLE party_series
    ADD COLUMN entry_price VARCHAR(100) NOT NULL DEFAULT '';

ALTER TABLE venues
    ADD CONSTRAINT ck_venues_postal_code CHECK (length(postal_code) <= 20),
    ADD CONSTRAINT ck_venues_city CHECK (length(city) <= 120);

ALTER TABLE party_series
    ADD CONSTRAINT ck_party_series_entry_price CHECK (length(entry_price) <= 100);
