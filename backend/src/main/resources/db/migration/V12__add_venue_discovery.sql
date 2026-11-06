ALTER TABLE venues ADD COLUMN website_url VARCHAR(2048) NOT NULL DEFAULT '',
 ADD COLUMN events_url VARCHAR(2048) NOT NULL DEFAULT '',
 ADD COLUMN discovery_enabled BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN last_discovery_at TIMESTAMPTZ,
 ADD COLUMN discovery_status VARCHAR(60) NOT NULL DEFAULT 'NOT_CHECKED';
CREATE TABLE discovery_sources (
 id VARCHAR(100) PRIMARY KEY, venue_id VARCHAR(100) NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
 url VARCHAR(2048) NOT NULL, source_type VARCHAR(30) NOT NULL,
 approved BOOLEAN NOT NULL DEFAULT FALSE, is_seed BOOLEAN NOT NULL DEFAULT FALSE, last_fetched_at TIMESTAMPTZ,
 status VARCHAR(60) NOT NULL DEFAULT 'NOT_CHECKED',
 item_selector VARCHAR(300) NOT NULL DEFAULT '', title_selector VARCHAR(300) NOT NULL DEFAULT '',
 date_selector VARCHAR(300) NOT NULL DEFAULT '',
 UNIQUE(venue_id, url)
);
CREATE TABLE party_series_aliases (
 party_id VARCHAR(100) NOT NULL REFERENCES party_series(id) ON DELETE CASCADE,
 alias VARCHAR(200) NOT NULL, PRIMARY KEY(party_id, alias)
);
CREATE TABLE discovery_candidates (
 id VARCHAR(100) PRIMARY KEY, venue_id VARCHAR(100) NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
 source_id VARCHAR(100) NOT NULL REFERENCES discovery_sources(id) ON DELETE CASCADE,
 identity_key VARCHAR(64) NOT NULL, fingerprint VARCHAR(64) NOT NULL,
 title VARCHAR(500) NOT NULL, source_url VARCHAR(2048) NOT NULL,
 event_date DATE, start_time TIME, evidence TEXT NOT NULL,
 status VARCHAR(40) NOT NULL, reason TEXT NOT NULL,
 party_id VARCHAR(100) REFERENCES party_series(id) ON DELETE SET NULL,
 calendar_entry_id VARCHAR(100) REFERENCES calendar_entries(id) ON DELETE SET NULL,
 dm_text TEXT NOT NULL DEFAULT '', inquiry_status VARCHAR(30) NOT NULL DEFAULT 'NONE',
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(venue_id, identity_key)
);
CREATE INDEX ix_discovery_candidates_status ON discovery_candidates(status);
CREATE TABLE discovery_runs (
 id VARCHAR(100) PRIMARY KEY, started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 finished_at TIMESTAMPTZ, status VARCHAR(30) NOT NULL, report TEXT NOT NULL DEFAULT ''
);
-- Session-level PostgreSQL advisory locks serialize scans and imports across JVMs.
