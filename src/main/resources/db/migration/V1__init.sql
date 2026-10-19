CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE jobs (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    status       TEXT        NOT NULL DEFAULT 'PENDING',
    format       TEXT        NOT NULL,
    request      TEXT        NOT NULL,
    source_text  TEXT,
    file_path    TEXT,
    file_name    TEXT,
    file_size    BIGINT,
    error        TEXT,
    token_input  INT,
    token_output INT,
    cost_usd     NUMERIC(10, 6),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ NOT NULL DEFAULT now() + INTERVAL '24 hours'
);

CREATE INDEX idx_jobs_status      ON jobs (status);
CREATE INDEX idx_jobs_expires_at  ON jobs (expires_at) WHERE file_path IS NOT NULL;

CREATE TABLE budget_log (
    id         BIGSERIAL   PRIMARY KEY,
    job_id     UUID        NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    cost_usd   NUMERIC(10, 6) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
