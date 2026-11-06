-- No backfill: an unknown legacy value is not midnight or free admission.
ALTER TABLE party_series ADD COLUMN default_start_time TIME,
 ADD COLUMN default_entry_price INTEGER,
 ADD CONSTRAINT party_series_default_price_range CHECK (default_entry_price BETWEEN 0 AND 30),
 ADD CONSTRAINT party_series_default_time_minutes CHECK (default_start_time < TIME '24:00' AND EXTRACT(SECOND FROM default_start_time)=0);
