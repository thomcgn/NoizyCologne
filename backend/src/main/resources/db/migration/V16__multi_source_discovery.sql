ALTER TABLE discovery_sources
 ADD COLUMN source_kind VARCHAR(30) NOT NULL DEFAULT 'CUSTOM',
 ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT true,
 ADD COLUMN priority INTEGER NOT NULL DEFAULT 100 CHECK (priority BETWEEN 0 AND 999),
 ADD COLUMN quality VARCHAR(30) NOT NULL DEFAULT 'NOT_CHECKED',
 ADD COLUMN event_count INTEGER,
 ADD COLUMN fallback_used BOOLEAN NOT NULL DEFAULT false,
 ADD CONSTRAINT ck_source_kind CHECK (source_kind IN ('WEBSITE','OFFICIAL_ORGANIZER','OFFICIAL_TICKETS','PLATFORM','CUSTOM')),
 ADD CONSTRAINT ck_source_quality CHECK (quality IN ('NOT_CHECKED','SUCCESS','PARTIAL','BLOCKED','FAILED','NO_EVENTS_CONFIRMED'));
UPDATE discovery_sources SET source_kind='WEBSITE' WHERE source_type='OFFICIAL';
ALTER TABLE venues
 ADD COLUMN last_discovery_attempt_at TIMESTAMPTZ,
 ADD COLUMN discovery_quality VARCHAR(30) NOT NULL DEFAULT 'NOT_CHECKED',
 ADD COLUMN discovery_event_count INTEGER;
CREATE INDEX ix_discovery_observations_sources ON discovery_observations(candidate_id,source_id,observed_at DESC);
