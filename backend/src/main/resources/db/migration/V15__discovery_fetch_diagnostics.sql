ALTER TABLE discovery_sources ADD COLUMN fetch_method VARCHAR(16) NOT NULL DEFAULT 'HTTP';
ALTER TABLE discovery_sources ADD COLUMN scan_complete BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE discovery_sources ADD COLUMN fetch_diagnostics VARCHAR(2000) NOT NULL DEFAULT '';
ALTER TABLE discovery_sources ADD COLUMN last_success_at TIMESTAMPTZ;
