CREATE TABLE office_sessions (
    id UUID PRIMARY KEY,
    root_job_id UUID NOT NULL REFERENCES jobs(id),
    source_job_id UUID NOT NULL REFERENCES jobs(id),
    saved_job_id UUID NOT NULL REFERENCES jobs(id),
    document_key TEXT NOT NULL UNIQUE,
    revision INTEGER NOT NULL DEFAULT 0,
    saved_at TIMESTAMPTZ,
    content_hash TEXT,
    error TEXT,
    closed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_office_sessions_root ON office_sessions(root_job_id,created_at DESC);
ALTER TABLE office_sessions ADD COLUMN acknowledged_request UUID;
ALTER TABLE office_sessions ADD COLUMN acknowledged_sequence BIGINT NOT NULL DEFAULT 0;
CREATE TABLE office_save_requests (
    sequence BIGSERIAL PRIMARY KEY,
    id UUID NOT NULL UNIQUE,
    session_id UUID NOT NULL REFERENCES office_sessions(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
