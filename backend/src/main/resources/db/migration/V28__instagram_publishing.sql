-- Instagram-Publishing: fail-closed Jobtabelle, Gate-Protokoll, Versuche und Remote-Befunde.
-- Die Tabellen liegen bewusst im eigenen Schema: Der Factory-Reset prüft die
-- öffentlichen Anwendungstabellen als exakte Menge (FactoryResetService.validateSchema).
-- Publishing-Jobs sind keine redaktionellen Stammdaten und werden nicht vom
-- Factory-Reset gelöscht; sie referenzieren Drafts nur als Zeichenkette, damit ein
-- Reset nicht durch Fremdschlüssel blockiert wird. Kein Token wird gespeichert.
CREATE SCHEMA IF NOT EXISTS publishing;

CREATE TABLE publishing.instagram_publish_job (
    id                  VARCHAR(100) PRIMARY KEY,
    draft_id            VARCHAR(100) NOT NULL,
    calendar_entry_id   VARCHAR(100) NOT NULL,
    account_id          VARCHAR(100) NOT NULL,
    venue_id            VARCHAR(100),
    status              VARCHAR(30) NOT NULL DEFAULT 'SCHEDULED',
    planned_at          TIMESTAMPTZ NOT NULL,
    idempotency_key     VARCHAR(200) NOT NULL UNIQUE,
    caption             TEXT NOT NULL DEFAULT '',
    position_in_plan    INTEGER NOT NULL DEFAULT 0 CHECK (position_in_plan >= 0),
    plan_revision       BIGINT NOT NULL DEFAULT 0 CHECK (plan_revision >= 0),
    lease_owner         VARCHAR(100),
    lease_until         TIMESTAMPTZ,
    attempt_count       INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    container_id        VARCHAR(200),
    last_error          TEXT,
    media_id            VARCHAR(200),
    permalink           VARCHAR(2048),
    needs_reconciliation BOOLEAN NOT NULL DEFAULT false,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_instagram_publish_job_status CHECK (status IN (
        'DRAFT', 'SCHEDULED', 'BLOCKED', 'CLAIMED', 'CONTAINER_CREATED',
        'READY_FOR_PUBLISH', 'PUBLISHING', 'PUBLISHED', 'UNKNOWN_REMOTE_RESULT',
        'FAILED', 'CANCELLED_LOCALLY', 'REMOVED_EXTERNALLY')),
    CONSTRAINT ck_instagram_publish_job_lease CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT uq_instagram_publish_job_draft UNIQUE (draft_id, account_id)
);
CREATE INDEX ix_instagram_publish_job_due ON publishing.instagram_publish_job (status, planned_at);
CREATE INDEX ix_instagram_publish_job_lease ON publishing.instagram_publish_job (lease_until) WHERE lease_owner IS NOT NULL;
CREATE INDEX ix_instagram_publish_job_reconcile ON publishing.instagram_publish_job (status, updated_at) WHERE needs_reconciliation;

CREATE TABLE publishing.instagram_publish_gate_result (
    job_id        VARCHAR(100) NOT NULL REFERENCES publishing.instagram_publish_job(id) ON DELETE CASCADE,
    gate_key      VARCHAR(60) NOT NULL,
    passed        BOOLEAN NOT NULL,
    message       TEXT NOT NULL,
    evidence      TEXT,
    evaluated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, gate_key)
);

CREATE TABLE publishing.instagram_publish_attempt (
    id           BIGSERIAL PRIMARY KEY,
    job_id       VARCHAR(100) NOT NULL REFERENCES publishing.instagram_publish_job(id) ON DELETE CASCADE,
    attempt_no   INTEGER NOT NULL CHECK (attempt_no > 0),
    status       VARCHAR(30) NOT NULL,
    error        TEXT,
    media_id     VARCHAR(200),
    permalink    VARCHAR(2048),
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ,
    UNIQUE (job_id, attempt_no)
);

CREATE TABLE publishing.instagram_remote_publication (
    media_id     VARCHAR(200) PRIMARY KEY,
    job_id       VARCHAR(100) REFERENCES publishing.instagram_publish_job(id) ON DELETE SET NULL,
    account_id   VARCHAR(100) NOT NULL,
    permalink    VARCHAR(2048) NOT NULL,
    caption      TEXT,
    observed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    observed_by  VARCHAR(20) NOT NULL CHECK (observed_by IN ('PUBLISH','FETCH','RECENT_MEDIA'))
);
CREATE INDEX ix_instagram_remote_publication_account ON publishing.instagram_remote_publication (account_id, observed_at);

-- Wie in V25/V27 nehmen auch mutierende Publishing-Tabellen am Wartungsschutz teil.
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON publishing.instagram_publish_job
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON publishing.instagram_publish_job
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON publishing.instagram_publish_gate_result
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON publishing.instagram_publish_gate_result
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON publishing.instagram_publish_attempt
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON publishing.instagram_publish_attempt
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
CREATE TRIGGER noizy_mutation_guard BEFORE INSERT OR UPDATE OR DELETE OR TRUNCATE ON publishing.instagram_remote_publication
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_guard_mutation();
CREATE TRIGGER noizy_mutation_revision AFTER INSERT OR UPDATE OR DELETE OR TRUNCATE ON publishing.instagram_remote_publication
    FOR EACH STATEMENT EXECUTE FUNCTION noizy_record_mutation();
