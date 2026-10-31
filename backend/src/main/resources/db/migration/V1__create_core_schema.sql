-- NoizyCologne · NC-101/NC-102
-- Flyway V1: nur die bereits im Frontend modellierten Stammdaten und Kalendereinträge.
-- Keine Medien, Redaktion, Accounts oder Publishing-Tabellen in dieser Migration.

CREATE TABLE venues (
    id          VARCHAR(100) PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    instagram   VARCHAR(30) NOT NULL DEFAULT '',
    CONSTRAINT ck_venues_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_venues_instagram CHECK (instagram ~ '^[A-Za-z0-9._]{0,30}$')
);
CREATE UNIQUE INDEX uq_venues_name_ci ON venues (lower(name));

CREATE TABLE actors (
    id          VARCHAR(100) PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    kind        VARCHAR(20) NOT NULL,
    instagram   VARCHAR(30) NOT NULL DEFAULT '',
    CONSTRAINT ck_actors_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_actors_kind CHECK (kind IN ('person', 'collective')),
    CONSTRAINT ck_actors_instagram CHECK (instagram ~ '^[A-Za-z0-9._]{0,30}$')
);
CREATE UNIQUE INDEX uq_actors_name_ci ON actors (lower(name));

-- Eine Person oder ein Kollektiv kann gleichzeitig DJ und Veranstalter sein.
CREATE TABLE actor_roles (
    actor_id    VARCHAR(100) NOT NULL REFERENCES actors(id) ON DELETE CASCADE,
    role_code   VARCHAR(20) NOT NULL,
    PRIMARY KEY (actor_id, role_code),
    CONSTRAINT ck_actor_roles_code CHECK (role_code IN ('dj', 'organizer'))
);

-- Zuordnungen einer Person zu einem oder mehreren Kollektiven.
-- Ob die referenzierten Akteure wirklich person/collective sind, prüft die API.
CREATE TABLE actor_collective_memberships (
    person_id      VARCHAR(100) NOT NULL REFERENCES actors(id) ON DELETE CASCADE,
    collective_id  VARCHAR(100) NOT NULL REFERENCES actors(id) ON DELETE CASCADE,
    PRIMARY KEY (person_id, collective_id),
    CONSTRAINT ck_actor_membership_not_self CHECK (person_id <> collective_id)
);

CREATE TABLE party_series (
    id                      VARCHAR(100) PRIMARY KEY,
    name                    VARCHAR(200) NOT NULL,
    instagram               VARCHAR(30) NOT NULL DEFAULT '',
    color                   CHAR(7) NOT NULL,
    dj_mention_default      VARCHAR(20) NOT NULL DEFAULT 'manual',
    recurrence_kind         VARCHAR(20),
    recurrence_weekday      SMALLINT,
    recurrence_occurrence   SMALLINT,
    recurrence_description  TEXT,
    CONSTRAINT ck_parties_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_parties_instagram CHECK (instagram ~ '^[A-Za-z0-9._]{0,30}$'),
    CONSTRAINT ck_parties_color CHECK (color ~ '^#[0-9A-Fa-f]{6}$'),
    CONSTRAINT ck_parties_mention_default CHECK (dj_mention_default IN ('manual', 'residents')),
    CONSTRAINT ck_parties_recurrence CHECK (
        CASE
            WHEN recurrence_kind IS NULL THEN
                recurrence_weekday IS NULL
                AND recurrence_occurrence IS NULL
                AND recurrence_description IS NULL
            WHEN recurrence_kind = 'weekly' THEN
                recurrence_weekday IS NOT NULL
                AND recurrence_weekday BETWEEN 0 AND 6
                AND recurrence_occurrence IS NULL
                AND recurrence_description IS NULL
            WHEN recurrence_kind = 'monthly_weekday' THEN
                recurrence_weekday IS NOT NULL
                AND recurrence_weekday BETWEEN 0 AND 6
                AND recurrence_occurrence IS NOT NULL
                AND recurrence_occurrence IN (-1, 1, 2, 3, 4)
                AND recurrence_description IS NULL
            WHEN recurrence_kind = 'other' THEN
                recurrence_weekday IS NULL
                AND recurrence_occurrence IS NULL
                AND recurrence_description IS NOT NULL
                AND length(btrim(recurrence_description)) > 0
            ELSE FALSE
        END
    )
);
CREATE UNIQUE INDEX uq_party_series_name_ci ON party_series (lower(name));

-- Resident-DJ und weiterer DJ schließen sich innerhalb einer Partyreihe aus.
CREATE TABLE party_djs (
    party_id    VARCHAR(100) NOT NULL REFERENCES party_series(id) ON DELETE CASCADE,
    actor_id    VARCHAR(100) NOT NULL REFERENCES actors(id) ON DELETE RESTRICT,
    assignment  VARCHAR(20) NOT NULL,
    PRIMARY KEY (party_id, actor_id),
    CONSTRAINT ck_party_djs_assignment CHECK (assignment IN ('resident', 'other'))
);

CREATE INDEX ix_party_djs_actor ON party_djs (actor_id);

CREATE TABLE party_organizers (
    party_id    VARCHAR(100) NOT NULL REFERENCES party_series(id) ON DELETE CASCADE,
    actor_id    VARCHAR(100) NOT NULL REFERENCES actors(id) ON DELETE RESTRICT,
    PRIMARY KEY (party_id, actor_id)
);

CREATE INDEX ix_party_organizers_actor ON party_organizers (actor_id);
CREATE INDEX ix_actor_collective_memberships_collective ON actor_collective_memberships (collective_id);

-- Ein konkreter Veranstaltungstermin ODER eine Location-Sperre.
-- Datum bewusst DATE ohne Zeitzone, Uhrzeit optional TIME ohne Zeitzone.
CREATE TABLE calendar_entries (
    id            VARCHAR(100) PRIMARY KEY,
    event_date    DATE NOT NULL,
    location_id   VARCHAR(100) NOT NULL REFERENCES venues(id) ON DELETE RESTRICT,
    entry_type    VARCHAR(20) NOT NULL,
    notes         TEXT NOT NULL DEFAULT '',
    party_id      VARCHAR(100) REFERENCES party_series(id) ON DELETE RESTRICT,
    start_time    TIME WITHOUT TIME ZONE,
    reason        VARCHAR(200),
    CONSTRAINT ck_calendar_entries_shape CHECK (
        (entry_type = 'event' AND party_id IS NOT NULL AND reason IS NULL)
        OR
        (entry_type = 'closure' AND party_id IS NULL AND start_time IS NULL
            AND reason IS NOT NULL AND length(btrim(reason)) > 0)
    )
);
CREATE INDEX ix_calendar_entries_event_date ON calendar_entries (event_date);
CREATE INDEX ix_calendar_entries_location_date ON calendar_entries (location_id, event_date);

-- Ausgewählte DJ-Mentions je Veranstaltung; nicht automatisch das Line-up.
CREATE TABLE calendar_entry_mentions (
    entry_id  VARCHAR(100) NOT NULL REFERENCES calendar_entries(id) ON DELETE CASCADE,
    actor_id  VARCHAR(100) NOT NULL REFERENCES actors(id) ON DELETE RESTRICT,
    PRIMARY KEY (entry_id, actor_id)
);

CREATE INDEX ix_calendar_entry_mentions_actor ON calendar_entry_mentions (actor_id);
