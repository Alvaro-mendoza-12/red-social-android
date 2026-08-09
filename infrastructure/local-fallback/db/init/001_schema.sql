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

-- These three tables are shared prerequisites for versioned migrations. They
-- live in the initial schema so a brand-new PostgreSQL volume can apply all
-- migrations before the API container starts; runtime ensure-functions remain
-- idempotent for existing installations.
CREATE TABLE IF NOT EXISTS fallback_compat_rows (
    table_name TEXT NOT NULL,
    row_id TEXT NOT NULL,
    row JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (table_name, row_id)
);

CREATE INDEX IF NOT EXISTS idx_fallback_compat_rows_updated
    ON fallback_compat_rows(table_name, updated_at DESC);

CREATE TABLE IF NOT EXISTS fallback_live_streams (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    host_user_id TEXT NOT NULL,
    host_name TEXT NOT NULL DEFAULT 'Usuario',
    host_avatar_url TEXT,
    title TEXT NOT NULL DEFAULT '',
    source TEXT NOT NULL DEFAULT 'camera' CHECK (source IN ('camera', 'screen', 'audio')),
    hashtags JSONB NOT NULL DEFAULT '[]'::jsonb,
    viewer_count INT NOT NULL DEFAULT 0,
    peak_viewer_count INT NOT NULL DEFAULT 0,
    total_comments INT NOT NULL DEFAULT 0,
    total_reactions INT NOT NULL DEFAULT 0,
    chat_paused BOOLEAN NOT NULL DEFAULT FALSE,
    allow_guest_requests BOOLEAN NOT NULL DEFAULT FALSE,
    blocked_words JSONB NOT NULL DEFAULT '[]'::jsonb,
    replay_url TEXT,
    replay_thumbnail_url TEXT,
    replay_duration_ms BIGINT NOT NULL DEFAULT 0,
    replay_published BOOLEAN NOT NULL DEFAULT FALSE,
    replay_updated_at TIMESTAMPTZ,
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_fallback_live_streams_active_created
    ON fallback_live_streams(is_active, created_at DESC);

CREATE TABLE IF NOT EXISTS fallback_notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id TEXT NOT NULL,
    actor_id TEXT,
    actor_name TEXT,
    actor_avatar_url TEXT,
    type TEXT NOT NULL DEFAULT 'activity',
    target_id TEXT,
    target_type TEXT,
    content TEXT,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    extra_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

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
