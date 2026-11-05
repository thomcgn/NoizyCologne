-- Add provenance without inventing a manual/automatic history for ambiguous legacy rows.
ALTER TABLE discovery_candidates
 ADD COLUMN decision_origin VARCHAR(20) NOT NULL DEFAULT 'AUTOMATIC',
 ADD COLUMN matching_class VARCHAR(30) NOT NULL DEFAULT 'UNVERIFIED',
 ADD COLUMN automatic_reason TEXT NOT NULL DEFAULT '',
 ADD COLUMN matcher_version VARCHAR(60) NOT NULL DEFAULT 'legacy',
 ADD COLUMN evaluated_at TIMESTAMPTZ,
 ADD COLUMN previous_status VARCHAR(40),
 ADD COLUMN last_evaluation_id VARCHAR(100),
 ADD COLUMN raw_event TEXT;
UPDATE discovery_candidates SET decision_origin=CASE
 WHEN status='REJECTED' OR reason LIKE 'Manuell%' THEN 'MANUAL'
 WHEN status IN ('IGNORED','IMPORTED','DUPLICATE','READY') THEN 'AUTOMATIC'
 ELSE 'LEGACY' END,
 matching_class=CASE WHEN status='IGNORED' THEN 'IGNORED' ELSE 'UNVERIFIED' END,
 automatic_reason=CASE WHEN status='IGNORED' THEN reason ELSE '' END;
CREATE TABLE discovery_operations (
 id VARCHAR(100) PRIMARY KEY, kind VARCHAR(30) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, request_payload TEXT NOT NULL DEFAULT '{}',
 matcher_version VARCHAR(60) NOT NULL, status VARCHAR(30) NOT NULL,
 started_at TIMESTAMPTZ NOT NULL DEFAULT now(), finished_at TIMESTAMPTZ,
 report TEXT NOT NULL DEFAULT '', summary TEXT NOT NULL DEFAULT '{}'
);
CREATE TABLE discovery_decisions (
 id BIGSERIAL PRIMARY KEY,
 candidate_id VARCHAR(100) NOT NULL REFERENCES discovery_candidates(id) ON DELETE CASCADE,
 operation_id VARCHAR(100), old_status VARCHAR(40), new_status VARCHAR(40) NOT NULL,
 origin VARCHAR(20) NOT NULL, matching_class VARCHAR(30) NOT NULL, old_matching_class VARCHAR(30),
 reason TEXT NOT NULL, matcher_version VARCHAR(60) NOT NULL,
 evaluated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_discovery_decisions_candidate ON discovery_decisions(candidate_id,id DESC);
CREATE INDEX ix_discovery_decisions_operation ON discovery_decisions(operation_id);
CREATE TABLE discovery_candidate_identities (
 venue_id VARCHAR(100) NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
 identity_key VARCHAR(64) NOT NULL,
 candidate_id VARCHAR(100) NOT NULL REFERENCES discovery_candidates(id) ON DELETE CASCADE,
 PRIMARY KEY(venue_id,identity_key)
);
INSERT INTO discovery_candidate_identities(venue_id,identity_key,candidate_id)
 SELECT venue_id,identity_key,id FROM discovery_candidates;
