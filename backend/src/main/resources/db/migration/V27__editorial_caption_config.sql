-- Teamweite, versionierte Konfiguration für den Pre-Text der Instagram-Caption.
-- Die Zeile gehört zum Anwendungszustand: Der Factory-Reset löscht sie und legt
-- bewusst wieder den Ausgangswert an (siehe FactoryResetService).
CREATE TABLE editorial_caption_config (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    pretext   text NOT NULL,
    revision  bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    CONSTRAINT ck_editorial_caption_config_pretext CHECK (char_length(pretext) BETWEEN 1 AND 100)
);
INSERT INTO editorial_caption_config (singleton, pretext, revision) VALUES (true, 'noizycologne', 1);

-- V25-Konvention: jede mutierende Anwendungstabelle nimmt am Wartungs-/Revisionsschutz teil.
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON editorial_caption_config
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON editorial_caption_config
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
