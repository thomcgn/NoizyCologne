-- Historical operations and resets have no invented progress counts.
ALTER TABLE discovery_operations
 ADD COLUMN total_units INTEGER,
 ADD COLUMN completed_units INTEGER,
 ADD COLUMN failed_units INTEGER,
 ADD COLUMN current_label TEXT,
 ADD COLUMN phase VARCHAR(30),
 ADD COLUMN progress_updated_at TIMESTAMPTZ,
 ADD CONSTRAINT discovery_progress_counts CHECK (
   (total_units IS NULL AND completed_units IS NULL AND failed_units IS NULL)
   OR (total_units IS NOT NULL AND completed_units IS NOT NULL AND failed_units IS NOT NULL
       AND total_units >= 0 AND completed_units BETWEEN 0 AND total_units
       AND failed_units BETWEEN 0 AND completed_units)
 );
