-- Empty by design: rules are created explicitly by the team.
CREATE TABLE discovery_keyword_rules (
 id VARCHAR(100) PRIMARY KEY,
 keyword VARCHAR(200) NOT NULL CHECK (length(trim(keyword))>0),
 reason VARCHAR(1000) NOT NULL CHECK (length(trim(reason))>0),
 enabled BOOLEAN NOT NULL DEFAULT true,
 revision BIGINT NOT NULL DEFAULT 1,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE discovery_candidates
 ADD COLUMN filter_origin VARCHAR(30),
 ADD COLUMN filter_rule_id VARCHAR(100),
 ADD COLUMN filter_keyword VARCHAR(200),
 ADD COLUMN filter_reason VARCHAR(1000),
 ADD COLUMN filter_evidence TEXT,
 ADD COLUMN keyword_release_version VARCHAR(64),
 ADD COLUMN last_seen_at TIMESTAMPTZ;
-- Immutable decision snapshots survive later edits/deactivation of a rule.
ALTER TABLE discovery_decisions
 ADD COLUMN filter_origin VARCHAR(30),
 ADD COLUMN filter_rule_id VARCHAR(100),
 ADD COLUMN filter_keyword VARCHAR(200),
 ADD COLUMN filter_reason VARCHAR(1000),
 ADD COLUMN filter_evidence TEXT;
CREATE INDEX discovery_unassigned_occurrences ON discovery_candidates(event_date,title) WHERE venue_id IS NULL;
