-- Existing recurrence descriptions remain unplanned until location and start are explicitly supplied.
ALTER TABLE party_series ADD COLUMN recurrence_location_id VARCHAR(100) REFERENCES venues(id) ON DELETE RESTRICT;
ALTER TABLE party_series ADD COLUMN recurrence_start_date DATE;
ALTER TABLE party_series ADD CONSTRAINT ck_recurrence_planning_pair CHECK ((recurrence_location_id IS NULL) = (recurrence_start_date IS NULL));
ALTER TABLE calendar_entries ADD CONSTRAINT ck_calendar_event_status CHECK (event_status IN ('ACTIVE','PLANNED','CANCELLED'));

-- A NULL entry is a durable tombstone, never an invitation to recreate a deleted date.
CREATE TABLE calendar_recurrence_occurrences (
 party_id VARCHAR(100) NOT NULL REFERENCES party_series(id) ON DELETE CASCADE,
 event_date DATE NOT NULL,
 calendar_entry_id VARCHAR(100) UNIQUE REFERENCES calendar_entries(id) ON DELETE SET NULL,
 protected BOOLEAN NOT NULL DEFAULT false,
 PRIMARY KEY (party_id,event_date)
);
CREATE FUNCTION protect_recurrence_occurrence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME = 'calendar_entry_mentions' THEN
  IF TG_OP <> 'INSERT' THEN UPDATE calendar_recurrence_occurrences SET protected=true WHERE calendar_entry_id=OLD.entry_id; END IF;
  IF TG_OP <> 'DELETE' THEN UPDATE calendar_recurrence_occurrences SET protected=true WHERE calendar_entry_id=NEW.entry_id; END IF;
 ELSIF TG_TABLE_NAME = 'editorial_post_drafts' THEN
  UPDATE calendar_recurrence_occurrences SET protected=true WHERE calendar_entry_id=NEW.calendar_entry_id;
 ELSIF TG_OP = 'DELETE' THEN
  IF OLD.party_id IS NOT NULL THEN
   INSERT INTO calendar_recurrence_occurrences(party_id,event_date,calendar_entry_id,protected)
    VALUES(OLD.party_id,OLD.event_date,NULL,true)
    ON CONFLICT(party_id,event_date) DO UPDATE SET protected=true;
  END IF;
 ELSE
  UPDATE calendar_recurrence_occurrences SET protected=true WHERE calendar_entry_id=OLD.id;
 END IF;
 RETURN NULL;
END $$;
CREATE TRIGGER protect_recurrence_entry AFTER UPDATE OR DELETE ON calendar_entries
 FOR EACH ROW EXECUTE FUNCTION protect_recurrence_occurrence();
CREATE TRIGGER protect_recurrence_mentions AFTER INSERT OR UPDATE OR DELETE ON calendar_entry_mentions
 FOR EACH ROW EXECUTE FUNCTION protect_recurrence_occurrence();

CREATE TRIGGER protect_recurrence_draft AFTER INSERT OR UPDATE ON editorial_post_drafts
 FOR EACH ROW EXECUTE FUNCTION protect_recurrence_occurrence();
