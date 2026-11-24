-- Hot-query indexes for the paginated discovery overview. Additive and repeatable only.
-- The overview sorts by (updated_at DESC, id DESC) and filters by geo_status and
-- calendar_entry_id; these indexes keep that read path stable and index friendly.
CREATE INDEX IF NOT EXISTS ix_discovery_candidates_updated ON discovery_candidates(updated_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_discovery_candidates_geo_status ON discovery_candidates(geo_status);
CREATE INDEX IF NOT EXISTS ix_discovery_candidates_calendar_entry ON discovery_candidates(calendar_entry_id);
