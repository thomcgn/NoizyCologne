-- Retain the proposal and its previous editorial information when its calendar entry disappears.
CREATE FUNCTION discovery_missing_calendar_entry() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='IMPORTED' AND NEW.calendar_entry_id IS NULL THEN
  NEW.previous_status := 'IMPORTED';
  NEW.status := 'NEEDS_REVIEW';
  -- Protect an intentionally removed calendar entry from automatic re-import.
  NEW.decision_origin := 'MANUAL';
  NEW.reason := 'Kalendereintrag fehlt oder wurde gelöscht; erneute Übernahme ausdrücklich prüfen. Bisherige Entscheidung: ' || NEW.reason;
  NEW.updated_at := now();
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER discovery_calendar_entry_removed
 BEFORE UPDATE OF calendar_entry_id ON discovery_candidates
 FOR EACH ROW EXECUTE FUNCTION discovery_missing_calendar_entry();

-- Repair only broken historical import states. Valid imports and other decisions remain unchanged.
UPDATE discovery_candidates SET calendar_entry_id=NULL
 WHERE status='IMPORTED' AND calendar_entry_id IS NULL;

ALTER TABLE discovery_candidates ADD CONSTRAINT discovery_import_requires_calendar
 CHECK (status<>'IMPORTED' OR calendar_entry_id IS NOT NULL);
