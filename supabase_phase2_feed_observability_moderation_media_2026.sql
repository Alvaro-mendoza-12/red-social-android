-- ==========================================================
-- Supabase Scale Phase 2 (2026)
-- Feed personalization signals, telemetry + alerting,
-- moderation audit, and media governance registration.
-- ==========================================================

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ----------------------------------------------------------
-- 0) Admin helper (idempotent)
-- ----------------------------------------------------------

CREATE OR REPLACE FUNCTION public.is_current_user_admin(p_user_id UUID DEFAULT auth.uid())
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.profiles
        WHERE id = COALESCE(p_user_id, auth.uid())
          AND is_admin = true
    );
$$;

-- ----------------------------------------------------------
-- 0.5) Hotfix guard_rate_limit ambiguity (window_start)
-- ----------------------------------------------------------

CREATE OR REPLACE FUNCTION public.guard_rate_limit(
    p_user_id UUID,
    p_action TEXT,
    p_scope_key TEXT DEFAULT NULL,
    p_max_events INTEGER DEFAULT 20,
    p_window_seconds INTEGER DEFAULT 60,
    p_min_interval_ms INTEGER DEFAULT 0
)
RETURNS TABLE (
    allowed BOOLEAN,
    retry_after_ms BIGINT,
    current_hits INTEGER,
    window_start TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
#variable_conflict use_column
DECLARE
    v_now TIMESTAMPTZ := NOW();
    v_scope TEXT := COALESCE(p_scope_key, '');
    v_window_start_ts TIMESTAMPTZ;
    v_last_hit TIMESTAMPTZ;
    v_hits INTEGER := 0;
    v_retry_ms BIGINT := 0;
BEGIN
    IF p_user_id IS NULL OR p_action IS NULL OR btrim(p_action) = '' THEN
        RETURN QUERY SELECT FALSE, 0::BIGINT, 0::INTEGER, date_trunc('minute', v_now);
        RETURN;
    END IF;

    IF to_regclass('public.security_rate_limit_state') IS NULL
       OR to_regclass('public.security_rate_limit_windows') IS NULL THEN
        RETURN QUERY SELECT TRUE, 0::BIGINT, 1::INTEGER, date_trunc('minute', v_now);
        RETURN;
    END IF;

    PERFORM pg_advisory_xact_lock(hashtextextended(p_action || ':' || p_user_id::text || ':' || v_scope, 0));

    v_window_start_ts := to_timestamp(
        floor(extract(epoch FROM v_now) / GREATEST(p_window_seconds, 1)) * GREATEST(p_window_seconds, 1)
    );

    SELECT last_hit_at
      INTO v_last_hit
      FROM public.security_rate_limit_state
     WHERE user_id = p_user_id
       AND action = p_action
       AND scope_key = v_scope
     FOR UPDATE;

    IF p_min_interval_ms > 0 AND v_last_hit IS NOT NULL THEN
        IF (extract(epoch FROM (v_now - v_last_hit)) * 1000) < p_min_interval_ms THEN
            v_retry_ms := p_min_interval_ms - (extract(epoch FROM (v_now - v_last_hit)) * 1000)::BIGINT;
            RETURN QUERY SELECT FALSE, GREATEST(v_retry_ms, 1), 0::INTEGER, v_window_start_ts;
            RETURN;
        END IF;
    END IF;

    INSERT INTO public.security_rate_limit_windows (
        user_id,
        action,
        scope_key,
        window_start,
        hit_count,
        last_hit_at,
        created_at,
        updated_at
    ) VALUES (
        p_user_id,
        p_action,
        v_scope,
        v_window_start_ts,
        1,
        v_now,
        v_now,
        v_now
    )
    ON CONFLICT ON CONSTRAINT security_rate_limit_windows_pkey
    DO UPDATE SET
        hit_count = public.security_rate_limit_windows.hit_count + 1,
        last_hit_at = EXCLUDED.last_hit_at,
        updated_at = EXCLUDED.updated_at
    RETURNING hit_count INTO v_hits;

    INSERT INTO public.security_rate_limit_state (
        user_id,
        action,
        scope_key,
        last_hit_at,
        total_hits,
        created_at,
        updated_at
    ) VALUES (
        p_user_id,
        p_action,
        v_scope,
        v_now,
        1,
        v_now,
        v_now
    )
    ON CONFLICT (user_id, action, scope_key)
    DO UPDATE SET
        last_hit_at = EXCLUDED.last_hit_at,
        total_hits = public.security_rate_limit_state.total_hits + 1,
        updated_at = EXCLUDED.updated_at;

    IF v_hits > GREATEST(p_max_events, 1) THEN
        v_retry_ms := (
            extract(epoch FROM ((v_window_start_ts + make_interval(secs => GREATEST(p_window_seconds, 1))) - v_now)) * 1000
        )::BIGINT;
        RETURN QUERY SELECT FALSE, GREATEST(v_retry_ms, 1), v_hits, v_window_start_ts;
        RETURN;
    END IF;

    RETURN QUERY SELECT TRUE, 0::BIGINT, v_hits, v_window_start_ts;
END;
$$;

GRANT EXECUTE ON FUNCTION public.guard_rate_limit(UUID, TEXT, TEXT, INTEGER, INTEGER, INTEGER) TO authenticated;

-- ----------------------------------------------------------
-- 1) Observability telemetry + threshold alerts
-- ----------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.app_telemetry_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_name TEXT NOT NULL,
    attributes JSONB NOT NULL DEFAULT '{}'::jsonb,
    session_user_id UUID,
    app_version TEXT,
    platform TEXT NOT NULL DEFAULT 'android',
    client_ts TIMESTAMPTZ,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (length(event_name) BETWEEN 1 AND 120)
);

CREATE INDEX IF NOT EXISTS idx_app_telemetry_events_name_received
    ON public.app_telemetry_events (event_name, received_at DESC);

CREATE INDEX IF NOT EXISTS idx_app_telemetry_events_user_received
    ON public.app_telemetry_events (session_user_id, received_at DESC);

CREATE TABLE IF NOT EXISTS public.observability_metric_thresholds (
    metric_name TEXT PRIMARY KEY,
    warning_count INTEGER NOT NULL CHECK (warning_count > 0),
    critical_count INTEGER NOT NULL CHECK (critical_count >= warning_count),
    window_seconds INTEGER NOT NULL DEFAULT 300 CHECK (window_seconds BETWEEN 30 AND 86400),
    cooldown_seconds INTEGER NOT NULL DEFAULT 300 CHECK (cooldown_seconds BETWEEN 30 AND 86400),
    enabled BOOLEAN NOT NULL DEFAULT true,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.observability_alert_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    metric_name TEXT NOT NULL,
    severity TEXT NOT NULL CHECK (severity IN ('warning', 'critical')),
    event_count INTEGER NOT NULL,
    window_seconds INTEGER NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_observability_alert_events_metric_created
    ON public.observability_alert_events (metric_name, created_at DESC);

INSERT INTO public.observability_metric_thresholds (
    metric_name, warning_count, critical_count, window_seconds, cooldown_seconds
) VALUES
    ('dm_send_failure', 8, 16, 300, 300),
    ('group_message_send_failure', 8, 16, 300, 300),
    ('community_message_send_failure', 8, 16, 300, 300),
    ('media_upload_failure', 12, 24, 300, 300),
    ('sync_cycle_failure', 6, 12, 600, 600),
    ('moderation_block_failure', 25, 50, 300, 300)
ON CONFLICT (metric_name) DO NOTHING;

ALTER TABLE public.app_telemetry_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.observability_alert_events ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS app_telemetry_events_insert_self ON public.app_telemetry_events;
DROP POLICY IF EXISTS app_telemetry_events_select_admin ON public.app_telemetry_events;
DROP POLICY IF EXISTS observability_alert_events_select_admin ON public.observability_alert_events;

CREATE POLICY app_telemetry_events_insert_self
    ON public.app_telemetry_events
    FOR INSERT
    WITH CHECK (
        auth.uid() IS NOT NULL
        AND (
            session_user_id IS NULL
            OR session_user_id = auth.uid()
            OR public.is_current_user_admin()
        )
    );

CREATE POLICY app_telemetry_events_select_admin
    ON public.app_telemetry_events
    FOR SELECT
    USING (public.is_current_user_admin());

CREATE POLICY observability_alert_events_select_admin
    ON public.observability_alert_events
    FOR SELECT
    USING (public.is_current_user_admin());

REVOKE ALL ON public.app_telemetry_events FROM anon;
REVOKE ALL ON public.observability_alert_events FROM anon;
REVOKE ALL ON public.observability_metric_thresholds FROM anon;
GRANT INSERT ON public.app_telemetry_events TO authenticated;
GRANT SELECT ON public.app_telemetry_events TO authenticated;
GRANT SELECT ON public.observability_alert_events TO authenticated;
GRANT SELECT ON public.observability_metric_thresholds TO authenticated;

CREATE OR REPLACE FUNCTION public.tg_observability_threshold_check()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_rule RECORD;
    v_count INTEGER := 0;
    v_now TIMESTAMPTZ := NOW();
    v_severity TEXT;
BEGIN
    SELECT *
      INTO v_rule
      FROM public.observability_metric_thresholds
     WHERE metric_name = NEW.event_name
       AND enabled = true
     LIMIT 1;

    IF NOT FOUND THEN
        RETURN NEW;
    END IF;

    SELECT COUNT(*)
      INTO v_count
      FROM public.app_telemetry_events
     WHERE event_name = NEW.event_name
       AND received_at >= (v_now - make_interval(secs => v_rule.window_seconds));

    IF v_count < v_rule.warning_count THEN
        RETURN NEW;
    END IF;

    v_severity := CASE
        WHEN v_count >= v_rule.critical_count THEN 'critical'
        ELSE 'warning'
    END;

    IF EXISTS (
        SELECT 1
          FROM public.observability_alert_events
         WHERE metric_name = NEW.event_name
           AND severity = v_severity
           AND created_at >= (v_now - make_interval(secs => v_rule.cooldown_seconds))
    ) THEN
        RETURN NEW;
    END IF;

    INSERT INTO public.observability_alert_events (
        metric_name,
        severity,
        event_count,
        window_seconds,
        metadata
    ) VALUES (
        NEW.event_name,
        v_severity,
        v_count,
        v_rule.window_seconds,
        COALESCE(NEW.attributes, '{}'::jsonb)
    );

    IF to_regprocedure('public.log_security_event(uuid,text,text,text,jsonb)') IS NOT NULL THEN
        PERFORM public.log_security_event(
            auth.uid(),
            'observability_threshold',
            v_severity,
            NEW.event_name,
            jsonb_build_object(
                'event_count', v_count,
                'window_seconds', v_rule.window_seconds
            )
        );
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_observability_threshold_check ON public.app_telemetry_events;
CREATE TRIGGER trg_observability_threshold_check
AFTER INSERT ON public.app_telemetry_events
FOR EACH ROW
EXECUTE FUNCTION public.tg_observability_threshold_check();

-- ----------------------------------------------------------
-- 2) Feed interactions and affinity RPC
-- ----------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.feed_interactions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id UUID NOT NULL,
    post_id UUID NOT NULL,
    author_id UUID NOT NULL,
    interaction_type TEXT NOT NULL CHECK (
        interaction_type IN ('impression', 'open', 'like', 'unlike', 'share', 'unshare', 'comment')
    ),
    weight NUMERIC(8,3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_feed_interactions_user_created
    ON public.feed_interactions (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_feed_interactions_author_created
    ON public.feed_interactions (author_id, created_at DESC);

ALTER TABLE public.feed_interactions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS feed_interactions_insert_self ON public.feed_interactions;
DROP POLICY IF EXISTS feed_interactions_select_self_or_admin ON public.feed_interactions;

CREATE POLICY feed_interactions_insert_self
    ON public.feed_interactions
    FOR INSERT
    WITH CHECK (user_id = auth.uid());

CREATE POLICY feed_interactions_select_self_or_admin
    ON public.feed_interactions
    FOR SELECT
    USING (user_id = auth.uid() OR public.is_current_user_admin());

REVOKE ALL ON public.feed_interactions FROM anon;
GRANT INSERT ON public.feed_interactions TO authenticated;
GRANT SELECT ON public.feed_interactions TO authenticated;

CREATE OR REPLACE FUNCTION public.record_feed_interaction(
    p_post_id UUID,
    p_author_id UUID,
    p_interaction_type TEXT
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_type TEXT := lower(trim(COALESCE(p_interaction_type, '')));
    v_weight NUMERIC(8,3);
    v_guard RECORD;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF p_post_id IS NULL OR p_author_id IS NULL OR v_type = '' THEN
        RAISE EXCEPTION 'INVALID_FEED_INTERACTION';
    END IF;

    v_weight := CASE v_type
        WHEN 'impression' THEN 0.10
        WHEN 'open' THEN 0.45
        WHEN 'like' THEN 1.40
        WHEN 'unlike' THEN -1.00
        WHEN 'share' THEN 1.80
        WHEN 'unshare' THEN -1.20
        WHEN 'comment' THEN 1.60
        ELSE NULL
    END;

    IF v_weight IS NULL THEN
        RAISE EXCEPTION 'UNSUPPORTED_FEED_INTERACTION';
    END IF;

    IF to_regprocedure('public.guard_rate_limit(uuid,text,text,integer,integer,integer)') IS NOT NULL THEN
        SELECT * INTO v_guard
          FROM public.guard_rate_limit(v_uid, 'feed_interaction', p_post_id::text, 300, 60, 50);

        IF v_guard.allowed IS NOT TRUE THEN
            RAISE EXCEPTION 'RATE_LIMIT_FEED_INTERACTION:%', COALESCE(v_guard.retry_after_ms, 0);
        END IF;
    END IF;

    INSERT INTO public.feed_interactions (
        user_id,
        post_id,
        author_id,
        interaction_type,
        weight
    ) VALUES (
        v_uid,
        p_post_id,
        p_author_id,
        v_type,
        v_weight
    );
END;
$$;

CREATE OR REPLACE FUNCTION public.get_feed_author_affinity(
    p_user_id UUID DEFAULT auth.uid(),
    p_hours INTEGER DEFAULT 24 * 14
)
RETURNS TABLE (
    author_id UUID,
    affinity_score DOUBLE PRECISION,
    interaction_count BIGINT
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT
        fi.author_id,
        SUM(
            fi.weight::double precision *
            EXP(
                -(
                    EXTRACT(EPOCH FROM (NOW() - fi.created_at))
                    / 3600.0
                ) / 72.0
            )
        ) AS affinity_score,
        COUNT(*)::bigint AS interaction_count
    FROM public.feed_interactions fi
    WHERE fi.user_id = COALESCE(p_user_id, auth.uid())
      AND fi.created_at >= NOW() - make_interval(hours => GREATEST(p_hours, 1))
    GROUP BY fi.author_id
    ORDER BY affinity_score DESC;
$$;

GRANT EXECUTE ON FUNCTION public.record_feed_interaction(UUID, UUID, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_feed_author_affinity(UUID, INTEGER) TO authenticated;

-- ----------------------------------------------------------
-- 3) Moderation events audit (backend sink)
-- ----------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.moderation_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id UUID,
    context TEXT NOT NULL,
    content_ref TEXT,
    verdict TEXT NOT NULL CHECK (verdict IN ('allow', 'sanitize', 'block')),
    flags JSONB NOT NULL DEFAULT '[]'::jsonb,
    sanitized_preview TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_moderation_events_actor_created
    ON public.moderation_events (actor_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_moderation_events_context_created
    ON public.moderation_events (context, created_at DESC);

ALTER TABLE public.moderation_events ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS moderation_events_select_admin ON public.moderation_events;

CREATE POLICY moderation_events_select_admin
    ON public.moderation_events
    FOR SELECT
    USING (public.is_current_user_admin());

REVOKE ALL ON public.moderation_events FROM anon, authenticated;
GRANT SELECT ON public.moderation_events TO authenticated;

CREATE OR REPLACE FUNCTION public.log_moderation_event(
    p_context TEXT,
    p_content_ref TEXT DEFAULT NULL,
    p_verdict TEXT DEFAULT 'allow',
    p_flags JSONB DEFAULT '[]'::jsonb,
    p_sanitized_preview TEXT DEFAULT NULL,
    p_metadata JSONB DEFAULT '{}'::jsonb
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_verdict TEXT := lower(trim(COALESCE(p_verdict, 'allow')));
BEGIN
    IF v_verdict NOT IN ('allow', 'sanitize', 'block') THEN
        v_verdict := 'allow';
    END IF;

    INSERT INTO public.moderation_events (
        actor_id,
        context,
        content_ref,
        verdict,
        flags,
        sanitized_preview,
        metadata
    ) VALUES (
        v_uid,
        LEFT(COALESCE(p_context, 'unknown'), 80),
        LEFT(COALESCE(p_content_ref, ''), 200),
        v_verdict,
        COALESCE(p_flags, '[]'::jsonb),
        LEFT(COALESCE(p_sanitized_preview, ''), 800),
        COALESCE(p_metadata, '{}'::jsonb)
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.log_moderation_event(TEXT, TEXT, TEXT, JSONB, TEXT, JSONB) TO authenticated;

-- ----------------------------------------------------------
-- 4) Media registry and pre-upload registration RPC
-- ----------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.media_registry (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id UUID NOT NULL,
    bucket_name TEXT NOT NULL,
    object_path TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 TEXT,
    status TEXT NOT NULL DEFAULT 'registered' CHECK (status IN ('registered', 'uploaded', 'rejected', 'deleted')),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (bucket_name, object_path)
);

CREATE INDEX IF NOT EXISTS idx_media_registry_owner_created
    ON public.media_registry (owner_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_media_registry_sha256_created
    ON public.media_registry (sha256, created_at DESC);

ALTER TABLE public.media_registry ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS media_registry_insert_self ON public.media_registry;
DROP POLICY IF EXISTS media_registry_select_self_or_admin ON public.media_registry;
DROP POLICY IF EXISTS media_registry_update_self_or_admin ON public.media_registry;

CREATE POLICY media_registry_insert_self
    ON public.media_registry
    FOR INSERT
    WITH CHECK (owner_id = auth.uid() OR public.is_current_user_admin());

CREATE POLICY media_registry_select_self_or_admin
    ON public.media_registry
    FOR SELECT
    USING (owner_id = auth.uid() OR public.is_current_user_admin());

CREATE POLICY media_registry_update_self_or_admin
    ON public.media_registry
    FOR UPDATE
    USING (owner_id = auth.uid() OR public.is_current_user_admin())
    WITH CHECK (owner_id = auth.uid() OR public.is_current_user_admin());

REVOKE ALL ON public.media_registry FROM anon;
GRANT INSERT, SELECT, UPDATE ON public.media_registry TO authenticated;

CREATE OR REPLACE FUNCTION public.tg_media_registry_touch_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_media_registry_touch_updated_at ON public.media_registry;
CREATE TRIGGER trg_media_registry_touch_updated_at
BEFORE UPDATE ON public.media_registry
FOR EACH ROW
EXECUTE FUNCTION public.tg_media_registry_touch_updated_at();

CREATE OR REPLACE FUNCTION public.register_media_object(
    p_bucket_name TEXT,
    p_object_path TEXT,
    p_content_type TEXT,
    p_size_bytes BIGINT,
    p_sha256 TEXT DEFAULT NULL
)
RETURNS public.media_registry
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_bucket TEXT := lower(trim(COALESCE(p_bucket_name, '')));
    v_path TEXT := trim(COALESCE(p_object_path, ''));
    v_content_type TEXT := lower(trim(COALESCE(p_content_type, '')));
    v_sha256 TEXT := lower(trim(COALESCE(p_sha256, '')));
    v_guard RECORD;
    v_max_bytes BIGINT;
    v_registered public.media_registry;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF v_bucket = '' OR v_path = '' OR v_content_type = '' THEN
        RAISE EXCEPTION 'INVALID_MEDIA_METADATA';
    END IF;

    IF v_path LIKE '%..%' OR v_path LIKE '/%' OR v_path LIKE '%\\%' THEN
        RAISE EXCEPTION 'UNSAFE_MEDIA_PATH';
    END IF;

    v_max_bytes := CASE v_bucket
        WHEN 'avatars' THEN 50 * 1024 * 1024
        WHEN 'post-media' THEN 25 * 1024 * 1024
        WHEN 'chat-media' THEN 50 * 1024 * 1024
        WHEN 'group-media' THEN 60 * 1024 * 1024
        WHEN 'community-media' THEN 60 * 1024 * 1024
        WHEN 'videos' THEN 120 * 1024 * 1024
        ELSE 80 * 1024 * 1024
    END;

    IF p_size_bytes IS NULL OR p_size_bytes <= 0 OR p_size_bytes > v_max_bytes THEN
        RAISE EXCEPTION 'MEDIA_SIZE_NOT_ALLOWED';
    END IF;

    IF to_regprocedure('public.guard_rate_limit(uuid,text,text,integer,integer,integer)') IS NOT NULL THEN
        SELECT * INTO v_guard
          FROM public.guard_rate_limit(v_uid, 'media_register', v_bucket, 90, 60, 50);

        IF v_guard.allowed IS NOT TRUE THEN
            IF to_regprocedure('public.log_security_event(uuid,text,text,text,jsonb)') IS NOT NULL THEN
                PERFORM public.log_security_event(
                    v_uid,
                    'media_register',
                    'blocked',
                    v_bucket,
                    jsonb_build_object('retry_after_ms', COALESCE(v_guard.retry_after_ms, 0))
                );
            END IF;
            RAISE EXCEPTION 'RATE_LIMIT_MEDIA_REGISTER:%', COALESCE(v_guard.retry_after_ms, 0);
        END IF;
    END IF;

    IF v_sha256 <> '' THEN
        IF (
            SELECT COUNT(*)
            FROM public.media_registry
            WHERE owner_id = v_uid
              AND sha256 = v_sha256
              AND created_at >= NOW() - INTERVAL '10 minutes'
        ) >= 20 THEN
            RAISE EXCEPTION 'MEDIA_DUPLICATE_SPAM';
        END IF;
    ELSE
        v_sha256 := NULL;
    END IF;

    INSERT INTO public.media_registry (
        owner_id,
        bucket_name,
        object_path,
        content_type,
        size_bytes,
        sha256,
        status
    ) VALUES (
        v_uid,
        v_bucket,
        v_path,
        v_content_type,
        p_size_bytes,
        v_sha256,
        'registered'
    )
    ON CONFLICT (bucket_name, object_path)
    DO UPDATE SET
        owner_id = EXCLUDED.owner_id,
        content_type = EXCLUDED.content_type,
        size_bytes = EXCLUDED.size_bytes,
        sha256 = EXCLUDED.sha256,
        status = 'registered',
        updated_at = NOW()
    RETURNING * INTO v_registered;

    RETURN v_registered;
END;
$$;

GRANT EXECUTE ON FUNCTION public.register_media_object(TEXT, TEXT, TEXT, BIGINT, TEXT) TO authenticated;

DO $$
BEGIN
    IF to_regclass('public.app_telemetry_events_id_seq') IS NOT NULL THEN
        EXECUTE 'GRANT USAGE, SELECT ON SEQUENCE public.app_telemetry_events_id_seq TO authenticated';
    END IF;
    IF to_regclass('public.feed_interactions_id_seq') IS NOT NULL THEN
        EXECUTE 'GRANT USAGE, SELECT ON SEQUENCE public.feed_interactions_id_seq TO authenticated';
    END IF;
    IF to_regclass('public.media_registry_id_seq') IS NOT NULL THEN
        EXECUTE 'GRANT USAGE, SELECT ON SEQUENCE public.media_registry_id_seq TO authenticated';
    END IF;
END;
$$;

COMMIT;
