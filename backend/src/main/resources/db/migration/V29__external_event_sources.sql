-- Generische, ausdrücklich freigegebene externe Kalenderquellen (ICS/JSON).
-- Es wird keine MTC-URL erfunden: Das Team legt Zeilen nur mit einer geprüften,
-- öffentlich erreichbaren Feed-Adresse an. Freigabe, Aktivierung, Priorität und
-- Venue-Zuordnung nutzen die bestehenden Spalten; externe Kalender bleiben ohne
-- künstlichen Veranstaltungsort und laufen über den Sonderquellen-Prüfpfad.
ALTER TABLE discovery_sources
    DROP CONSTRAINT ck_source_kind,
    ADD CONSTRAINT ck_source_kind CHECK (source_kind IN
        ('WEBSITE','OFFICIAL_ORGANIZER','OFFICIAL_TICKETS','PLATFORM','CUSTOM','EXTERNAL_CALENDAR')),
    ADD COLUMN external_format VARCHAR(10) NOT NULL DEFAULT '' CHECK (external_format IN ('','ICS','JSON')),
    ADD COLUMN external_last_success_at TIMESTAMPTZ,
    ADD COLUMN external_last_error VARCHAR(2000) NOT NULL DEFAULT '';

ALTER TABLE discovery_sources
    ADD CONSTRAINT ck_external_calendar_source CHECK (
        source_kind <> 'EXTERNAL_CALENDAR'
        OR (venue_id IS NULL AND external_format IN ('ICS','JSON'))
    );
