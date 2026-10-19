ALTER TABLE jobs
    ADD COLUMN plan_json         TEXT,
    ADD COLUMN image_paths_json  TEXT,
    ADD COLUMN plan_confirmed_at TIMESTAMPTZ;
