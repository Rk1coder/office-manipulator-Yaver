ALTER TABLE api_calls DROP CONSTRAINT api_calls_state_check;
ALTER TABLE api_calls ADD CONSTRAINT api_calls_state_check
    CHECK (state IN ('RESERVED', 'SETTLED', 'UNCERTAIN', 'RELEASED'));
