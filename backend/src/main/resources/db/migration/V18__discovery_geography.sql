-- Geography is independent of editorial decisions and never removes calendar/cancellation data.
ALTER TABLE discovery_candidates ADD COLUMN geo_status VARCHAR(30) NOT NULL DEFAULT 'NOT_CHECKED', ADD COLUMN geo_reason TEXT NOT NULL DEFAULT '';
CREATE TABLE discovery_venue_geography (
 venue_id VARCHAR(100) PRIMARY KEY REFERENCES venues(id) ON DELETE CASCADE,
 city VARCHAR(200) NOT NULL, street VARCHAR(300) NOT NULL, country VARCHAR(100) NOT NULL,
 proof_url VARCHAR(2048) NOT NULL, confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE discovery_place_aliases (
 venue_id VARCHAR(100) NOT NULL REFERENCES discovery_venue_geography(venue_id) ON DELETE CASCADE,
 normalized_name VARCHAR(500) NOT NULL, place_key TEXT NOT NULL,
 PRIMARY KEY(venue_id,normalized_name,place_key)
);
CREATE TABLE discovery_geo_events (
 id VARCHAR(100) PRIMARY KEY, source_id VARCHAR(100) NOT NULL REFERENCES discovery_sources(id) ON DELETE CASCADE,
 candidate_id VARCHAR(100) REFERENCES discovery_candidates(id) ON DELETE SET NULL,
 identity_key VARCHAR(64) NOT NULL, title VARCHAR(500) NOT NULL, source_url VARCHAR(2048) NOT NULL,
 raw_event TEXT NOT NULL, status VARCHAR(30) NOT NULL, reason TEXT NOT NULL,
 venue_id VARCHAR(100) REFERENCES venues(id) ON DELETE SET NULL,
 confirmed_place TEXT, confirmed_fingerprint VARCHAR(64), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(source_id,identity_key)
);
