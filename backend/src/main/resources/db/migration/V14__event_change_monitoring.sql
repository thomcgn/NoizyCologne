ALTER TABLE discovery_candidates
 ADD COLUMN first_seen_at TIMESTAMPTZ,
 ADD COLUMN monitor_fingerprint VARCHAR(64),
 ADD COLUMN last_confirmed_at TIMESTAMPTZ,
 ADD COLUMN last_changed_at TIMESTAMPTZ,
 ADD COLUMN monitored_event TEXT;
ALTER TABLE discovery_candidates ALTER COLUMN first_seen_at SET DEFAULT now();
UPDATE discovery_candidates SET monitored_event=raw_event;
CREATE TABLE discovery_observations (
 id BIGSERIAL PRIMARY KEY, candidate_id VARCHAR(100) NOT NULL REFERENCES discovery_candidates(id) ON DELETE CASCADE,
 source_id VARCHAR(100) NOT NULL REFERENCES discovery_sources(id) ON DELETE CASCADE,
 operation_id VARCHAR(100) NOT NULL, fingerprint VARCHAR(64) NOT NULL,
 event_data TEXT NOT NULL, observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(candidate_id,source_id,operation_id,fingerprint)
);
CREATE TABLE event_changes (
 id VARCHAR(100) PRIMARY KEY, candidate_id VARCHAR(100) REFERENCES discovery_candidates(id) ON DELETE CASCADE,
 calendar_entry_id VARCHAR(100) REFERENCES calendar_entries(id) ON DELETE CASCADE,
 venue_id VARCHAR(100) NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
 revision BIGINT NOT NULL DEFAULT 0, kind VARCHAR(40) NOT NULL, state VARCHAR(30) NOT NULL DEFAULT 'OPEN',
 before_data TEXT NOT NULL, after_data TEXT NOT NULL, calendar_snapshot TEXT NOT NULL,
 source_url VARCHAR(2048) NOT NULL, reason TEXT NOT NULL,
 fingerprint VARCHAR(64) NOT NULL, blocks_publication BOOLEAN NOT NULL DEFAULT true,
 inquiry_status VARCHAR(20) NOT NULL DEFAULT 'NONE', dm_text TEXT NOT NULL DEFAULT '', answer TEXT NOT NULL DEFAULT '',
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(venue_id,fingerprint)
);
CREATE INDEX ix_event_changes_calendar ON event_changes(calendar_entry_id) WHERE blocks_publication;
CREATE TABLE event_change_history (
 id BIGSERIAL PRIMARY KEY, change_id VARCHAR(100) NOT NULL REFERENCES event_changes(id) ON DELETE CASCADE,
 action VARCHAR(40) NOT NULL, note TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE discovery_source_checks (
 candidate_id VARCHAR(100) NOT NULL REFERENCES discovery_candidates(id) ON DELETE CASCADE,
 source_id VARCHAR(100) NOT NULL REFERENCES discovery_sources(id) ON DELETE CASCADE,
 consecutive_missing INTEGER NOT NULL DEFAULT 0, last_operation VARCHAR(100),
 PRIMARY KEY(candidate_id,source_id)
);
ALTER TABLE calendar_entries ADD COLUMN event_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE editorial_post_drafts ADD COLUMN approval_needs_review BOOLEAN NOT NULL DEFAULT false;
CREATE TABLE event_cancellations (
 id VARCHAR(100) PRIMARY KEY, calendar_entry_id VARCHAR(100) NOT NULL UNIQUE REFERENCES calendar_entries(id) ON DELETE CASCADE,
 change_id VARCHAR(100) REFERENCES event_changes(id) ON DELETE SET NULL,
 revision BIGINT NOT NULL DEFAULT 0, trigger_type VARCHAR(30) NOT NULL, reason_code VARCHAR(40) NOT NULL,
 source_url VARCHAR(2048) NOT NULL, confirmed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 announcement_caption TEXT NOT NULL, announcement_status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
 media_asset_id VARCHAR(100) REFERENCES media_assets(id) ON DELETE RESTRICT,
 replacement_candidate_id VARCHAR(100) REFERENCES discovery_candidates(id) ON DELETE SET NULL
);
CREATE TABLE cancellation_history (
 id BIGSERIAL PRIMARY KEY, cancellation_id VARCHAR(100) NOT NULL REFERENCES event_cancellations(id) ON DELETE CASCADE,
 action VARCHAR(40) NOT NULL, note TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
