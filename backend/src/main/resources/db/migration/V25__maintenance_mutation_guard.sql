-- Infrastructure survives a deliberate application reset, like auth/Flyway state.
CREATE TABLE maintenance_state (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    pending_reset varchar(64) CHECK (pending_reset IS NULL OR pending_reset ~ '^[0-9a-f]{64}$'),
    pending_manifest_sha256 char(64) CHECK (pending_manifest_sha256 IS NULL OR pending_manifest_sha256 ~ '^[0-9a-f]{64}$'),
    deleted_records bigint NOT NULL DEFAULT 0 CHECK (deleted_records >= 0),
    CHECK ((pending_reset IS NULL) = (pending_manifest_sha256 IS NULL))
);
INSERT INTO maintenance_state(singleton) VALUES (true);
-- CACHE 1 keeps the observed revision current across sessions without a hot transactional row.
-- A rolled-back write may invalidate a preview conservatively; no domain rows are committed.
CREATE SEQUENCE maintenance_revision AS bigint MINVALUE 1 START WITH 1 CACHE 1 NO CYCLE;
SELECT nextval('maintenance_revision');

-- Every application mutation participates, including background work and direct JDBC.
-- The reset owns this same lock exclusively on its transaction's physical connection.
CREATE FUNCTION noizy_guard_mutation() RETURNS trigger LANGUAGE plpgsql AS $guard$
DECLARE pending varchar(64);
BEGIN
    IF NOT pg_try_advisory_xact_lock_shared(73624604) THEN
        RAISE EXCEPTION 'Application maintenance is active' USING ERRCODE = '55P03';
    END IF;
    SELECT pending_reset INTO STRICT pending FROM maintenance_state WHERE singleton;
    IF pending IS NOT NULL THEN
        RAISE EXCEPTION 'Application maintenance cleanup is pending' USING ERRCODE = '55P03';
    END IF;
    RETURN NULL;
END;
$guard$;
CREATE FUNCTION noizy_record_mutation() RETURNS trigger LANGUAGE plpgsql AS $revision$
BEGIN
    PERFORM nextval('maintenance_revision');
    RETURN NULL;
END;
$revision$;

CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON actor_collective_memberships
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON actor_collective_memberships
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON actor_roles
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON actor_roles
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON actors
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON actors
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON calendar_entries
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON calendar_entries
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON calendar_entry_mentions
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON calendar_entry_mentions
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON calendar_recurrence_occurrences
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON calendar_recurrence_occurrences
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON cancellation_history
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON cancellation_history
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_candidate_identities
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_candidate_identities
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_candidates
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_candidates
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_decisions
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_decisions
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_geo_events
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_geo_events
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_keyword_rules
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_keyword_rules
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_observations
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_observations
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_operations
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_operations
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_place_aliases
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_place_aliases
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_runs
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_runs
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_source_checks
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_source_checks
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_sources
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_sources
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_venue_geography
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON discovery_venue_geography
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON editorial_post_draft_genres
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON editorial_post_draft_genres
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON editorial_post_draft_mentions
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON editorial_post_draft_mentions
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON editorial_post_drafts
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON editorial_post_drafts
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON event_cancellations
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON event_cancellations
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON event_change_history
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON event_change_history
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON event_changes
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON event_changes
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON genre_aliases
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON genre_aliases
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON genres
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON genres
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON media_assets
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON media_assets
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON music_track_genres
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON music_track_genres
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON music_track_party_series
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON music_track_party_series
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON music_tracks
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON music_tracks
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON party_djs
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON party_djs
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON party_organizers
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON party_organizers
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON party_series
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON party_series
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON party_series_aliases
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON party_series_aliases
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON party_series_genres
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON party_series_genres
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON venues
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON venues
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
