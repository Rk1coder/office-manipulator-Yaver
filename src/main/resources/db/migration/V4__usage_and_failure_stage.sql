ALTER TABLE jobs ADD COLUMN failed_at TEXT;
CREATE TABLE api_calls (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs(id),
    provider TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('RESERVED', 'SETTLED', 'UNCERTAIN')),
    amount NUMERIC(16,6) NOT NULL CHECK (amount >= 0),
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Preserve all previously recorded spend before switching to the per-call ledger.
INSERT INTO api_calls (id, job_id, provider, state, amount, input_tokens, output_tokens)
SELECT gen_random_uuid(), id, 'legacy', 'SETTLED', cost_usd,
       COALESCE(token_input, 0), COALESCE(token_output, 0)
FROM jobs WHERE cost_usd > 0;
CREATE INDEX idx_api_calls_job ON api_calls(job_id);
