ALTER TABLE jobs
    ADD COLUMN parent_job_id UUID REFERENCES jobs(id) ON DELETE SET NULL;

CREATE INDEX idx_jobs_parent_job_id ON jobs (parent_job_id);
