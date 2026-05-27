ALTER TABLE fallback_users
    ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE fallback_users
    ADD COLUMN IF NOT EXISTS password_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS sync_source TEXT NOT NULL DEFAULT 'local',
    ADD COLUMN IF NOT EXISTS sync_status TEXT NOT NULL DEFAULT 'local_only',
    ADD COLUMN IF NOT EXISTS sync_error TEXT,
    ADD COLUMN IF NOT EXISTS local_password_set_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS supabase_synced_at TIMESTAMPTZ;

DO $$
BEGIN
    ALTER TABLE fallback_users
        ADD CONSTRAINT fallback_users_sync_source_check
        CHECK (sync_source IN ('local', 'supabase', 'both'));
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$
BEGIN
    ALTER TABLE fallback_users
        ADD CONSTRAINT fallback_users_sync_status_check
        CHECK (sync_status IN (
            'local_only',
            'supabase_only',
            'synced',
            'needs_local_password',
            'needs_supabase_password',
            'sync_failed'
        ));
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

UPDATE fallback_users
SET
    password_enabled = TRUE,
    local_password_set_at = COALESCE(local_password_set_at, created_at)
WHERE password_hash IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_fallback_users_sync_status
    ON fallback_users(sync_status, updated_at DESC);

CREATE TABLE IF NOT EXISTS sync_jobs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_type TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'running', 'completed', 'failed')),
    totals JSONB NOT NULL DEFAULT '{}'::jsonb,
    error TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sync_jobs_created
    ON sync_jobs(job_type, created_at DESC);
