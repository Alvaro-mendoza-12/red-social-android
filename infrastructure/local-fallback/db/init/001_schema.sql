CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE IF NOT EXISTS fallback_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email CITEXT NOT NULL UNIQUE,
    password_hash TEXT,
    password_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    display_name TEXT NOT NULL DEFAULT '',
    avatar_url TEXT,
    supabase_user_id UUID UNIQUE,
    sync_source TEXT NOT NULL DEFAULT 'local'
        CHECK (sync_source IN ('local', 'supabase', 'both')),
    sync_status TEXT NOT NULL DEFAULT 'local_only'
        CHECK (sync_status IN (
            'local_only',
            'supabase_only',
            'synced',
            'needs_local_password',
            'needs_supabase_password',
            'sync_failed'
        )),
    sync_error TEXT,
    local_password_set_at TIMESTAMPTZ,
    password_changed_by_user_at TIMESTAMPTZ,
    supabase_synced_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_login_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_fallback_users_email ON fallback_users(email);
CREATE INDEX IF NOT EXISTS idx_fallback_users_supabase_user ON fallback_users(supabase_user_id);
CREATE INDEX IF NOT EXISTS idx_fallback_users_sync_status ON fallback_users(sync_status, updated_at DESC);

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

CREATE INDEX IF NOT EXISTS idx_sync_jobs_created ON sync_jobs(job_type, created_at DESC);

CREATE TABLE IF NOT EXISTS fallback_audit_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_name TEXT NOT NULL,
    user_id UUID REFERENCES fallback_users(id) ON DELETE SET NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_fallback_audit_events_created
    ON fallback_audit_events(event_name, created_at DESC);

CREATE OR REPLACE FUNCTION touch_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_fallback_users_updated_at ON fallback_users;
CREATE TRIGGER trg_fallback_users_updated_at
BEFORE UPDATE ON fallback_users
FOR EACH ROW
EXECUTE FUNCTION touch_updated_at();
