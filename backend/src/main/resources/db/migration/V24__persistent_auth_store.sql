-- Infrastructure survives application-data reset. Store only SHA-256 identifiers, never cookies or IPs.
CREATE TABLE auth_sessions (
    nonce_hash CHAR(64) PRIMARY KEY CHECK (nonce_hash ~ '^[0-9a-f]{64}$'),
    generation CHAR(64) NOT NULL CHECK (generation ~ '^[0-9a-f]{64}$'),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX auth_sessions_expiry ON auth_sessions (expires_at);
CREATE TABLE auth_login_buckets (
    bucket_hash CHAR(64) PRIMARY KEY CHECK (bucket_hash ~ '^[0-9a-f]{64}$'),
    attempts INTEGER NOT NULL CHECK (attempts BETWEEN 1 AND 26),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX auth_login_buckets_expiry ON auth_login_buckets (expires_at);
