ALTER TABLE venues
    DROP CONSTRAINT ck_venues_postal_code,
    DROP CONSTRAINT ck_venues_city,
    DROP COLUMN street,
    DROP COLUMN postal_code,
    DROP COLUMN city;
