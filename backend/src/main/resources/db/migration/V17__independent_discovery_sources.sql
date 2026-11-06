-- Independent event portals are sources, never artificial venues.
ALTER TABLE discovery_sources ALTER COLUMN venue_id DROP NOT NULL;
ALTER TABLE discovery_sources
 ADD COLUMN name VARCHAR(200) NOT NULL DEFAULT '',
 ADD COLUMN deleted_at TIMESTAMPTZ;
CREATE UNIQUE INDEX uq_special_source_url ON discovery_sources(url) WHERE venue_id IS NULL;
ALTER TABLE discovery_candidates ALTER COLUMN venue_id DROP NOT NULL;
CREATE UNIQUE INDEX uq_unassigned_source_candidate ON discovery_candidates(source_id,identity_key) WHERE venue_id IS NULL;
