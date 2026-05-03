-- ==========================================================
-- Supabase Security Hardening 2026
-- Adds backend rate limiting, security audit logging, safer
-- report RLS policies, and secure RPC endpoints.
-- ==========================================================

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ----------------------------------------------------------
-- 1) Helpers
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
-- 2) Security tables
-- ----------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.security_rate_limit_state (
    user_id UUID NOT NULL,
    action TEXT NOT NULL,
    scope_key TEXT NOT NULL DEFAULT '',
    last_hit_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    total_hits BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, action, scope_key)
);

CREATE TABLE IF NOT EXISTS public.security_rate_limit_windows (
    user_id UUID NOT NULL,
    action TEXT NOT NULL,
    scope_key TEXT NOT NULL DEFAULT '',
    window_start TIMESTAMPTZ NOT NULL,
    hit_count INTEGER NOT NULL DEFAULT 0,
    last_hit_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, action, scope_key, window_start)
);

CREATE INDEX IF NOT EXISTS idx_security_rate_limit_windows_lookup
    ON public.security_rate_limit_windows (user_id, action, scope_key, window_start DESC);

CREATE INDEX IF NOT EXISTS idx_security_rate_limit_state_updated
    ON public.security_rate_limit_state (updated_at DESC);

CREATE TABLE IF NOT EXISTS public.security_audit_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id UUID,
    action TEXT NOT NULL,
    outcome TEXT NOT NULL,
    scope_key TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_security_audit_events_actor_created
    ON public.security_audit_events (actor_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_security_audit_events_action_created
    ON public.security_audit_events (action, created_at DESC);

ALTER TABLE public.security_rate_limit_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.security_rate_limit_windows ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.security_audit_events ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS security_rate_limit_state_admin_read ON public.security_rate_limit_state;
DROP POLICY IF EXISTS security_rate_limit_windows_admin_read ON public.security_rate_limit_windows;
DROP POLICY IF EXISTS security_audit_events_admin_read ON public.security_audit_events;

CREATE POLICY security_rate_limit_state_admin_read
    ON public.security_rate_limit_state
    FOR SELECT
    USING (public.is_current_user_admin());

CREATE POLICY security_rate_limit_windows_admin_read
    ON public.security_rate_limit_windows
    FOR SELECT
    USING (public.is_current_user_admin());

CREATE POLICY security_audit_events_admin_read
    ON public.security_audit_events
    FOR SELECT
    USING (public.is_current_user_admin());

REVOKE ALL ON public.security_rate_limit_state FROM anon, authenticated;
REVOKE ALL ON public.security_rate_limit_windows FROM anon, authenticated;
REVOKE ALL ON public.security_audit_events FROM anon, authenticated;
GRANT SELECT ON public.security_rate_limit_state TO authenticated;
GRANT SELECT ON public.security_rate_limit_windows TO authenticated;
GRANT SELECT ON public.security_audit_events TO authenticated;

-- ----------------------------------------------------------
-- 3) Guard functions
-- ----------------------------------------------------------

CREATE OR REPLACE FUNCTION public.log_security_event(
    p_actor_id UUID,
    p_action TEXT,
    p_outcome TEXT,
    p_scope_key TEXT DEFAULT NULL,
    p_metadata JSONB DEFAULT '{}'::jsonb
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    INSERT INTO public.security_audit_events (
        actor_id,
        action,
        outcome,
        scope_key,
        metadata
    ) VALUES (
        p_actor_id,
        p_action,
        p_outcome,
        p_scope_key,
        COALESCE(p_metadata, '{}'::jsonb)
    );
END;
$$;

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

-- ----------------------------------------------------------
-- 4) Trigger guards for direct table writes
-- ----------------------------------------------------------

CREATE OR REPLACE FUNCTION public.tg_guard_messages_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_guard RECORD;
BEGIN
    IF v_uid IS NULL THEN
        RETURN NEW;
    END IF;

    IF NEW.sender_id IS NULL THEN
        NEW.sender_id := v_uid;
    ELSIF NEW.sender_id <> v_uid THEN
        RAISE EXCEPTION 'SENDER_MISMATCH';
    END IF;

    NEW.content := LEFT(trim(COALESCE(NEW.content, '')), 4000);
    NEW.content_type := COALESCE(NULLIF(NEW.content_type, ''), 'text');

    IF NEW.content_type = 'text' AND NEW.content = '' AND COALESCE(NEW.media_url, '') = '' THEN
        RAISE EXCEPTION 'EMPTY_MESSAGE';
    END IF;

    SELECT * INTO v_guard
      FROM public.guard_rate_limit(v_uid, 'direct_message_send', NEW.chat_id::text, 24, 60, 350);

    IF NOT v_guard.allowed THEN
        PERFORM public.log_security_event(
            v_uid,
            'direct_message_send',
            'blocked',
            NEW.chat_id::text,
            jsonb_build_object('retry_after_ms', v_guard.retry_after_ms)
        );
        RAISE EXCEPTION 'RATE_LIMIT_DIRECT_MESSAGE:%', v_guard.retry_after_ms;
    END IF;

    PERFORM public.log_security_event(
        v_uid,
        'direct_message_send',
        'allowed',
        NEW.chat_id::text,
        jsonb_build_object('message_id', NEW.id)
    );

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.tg_guard_group_messages_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_guard RECORD;
BEGIN
    IF v_uid IS NULL THEN
        RETURN NEW;
    END IF;

    IF NEW.sender_id IS NULL THEN
        NEW.sender_id := v_uid;
    ELSIF NEW.sender_id <> v_uid THEN
        RAISE EXCEPTION 'SENDER_MISMATCH';
    END IF;

    NEW.content := LEFT(trim(COALESCE(NEW.content, '')), 4000);
    NEW.content_type := COALESCE(NULLIF(NEW.content_type, ''), 'text');

    IF NEW.content_type = 'text' AND NEW.content = '' AND COALESCE(NEW.media_url, '') = '' THEN
        RAISE EXCEPTION 'EMPTY_GROUP_MESSAGE';
    END IF;

    SELECT * INTO v_guard
      FROM public.guard_rate_limit(v_uid, 'group_message_send', NEW.group_id::text, 30, 60, 300);

    IF NOT v_guard.allowed THEN
        PERFORM public.log_security_event(
            v_uid,
            'group_message_send',
            'blocked',
            NEW.group_id::text,
            jsonb_build_object('retry_after_ms', v_guard.retry_after_ms)
        );
        RAISE EXCEPTION 'RATE_LIMIT_GROUP_MESSAGE:%', v_guard.retry_after_ms;
    END IF;

    PERFORM public.log_security_event(
        v_uid,
        'group_message_send',
        'allowed',
        NEW.group_id::text,
        jsonb_build_object('message_id', NEW.id)
    );

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.tg_guard_community_messages_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_guard RECORD;
BEGIN
    IF v_uid IS NULL THEN
        RETURN NEW;
    END IF;

    IF NEW.sender_id IS NULL THEN
        NEW.sender_id := v_uid;
    ELSIF NEW.sender_id <> v_uid THEN
        RAISE EXCEPTION 'SENDER_MISMATCH';
    END IF;

    NEW.content := LEFT(trim(COALESCE(NEW.content, '')), 4000);
    NEW.content_type := COALESCE(NULLIF(NEW.content_type, ''), 'text');

    IF NEW.content_type = 'text' AND NEW.content = '' AND COALESCE(NEW.media_url, '') = '' THEN
        RAISE EXCEPTION 'EMPTY_COMMUNITY_MESSAGE';
    END IF;

    SELECT * INTO v_guard
      FROM public.guard_rate_limit(v_uid, 'community_message_send', NEW.community_id::text, 36, 60, 250);

    IF NOT v_guard.allowed THEN
        PERFORM public.log_security_event(
            v_uid,
            'community_message_send',
            'blocked',
            NEW.community_id::text,
            jsonb_build_object('retry_after_ms', v_guard.retry_after_ms)
        );
        RAISE EXCEPTION 'RATE_LIMIT_COMMUNITY_MESSAGE:%', v_guard.retry_after_ms;
    END IF;

    PERFORM public.log_security_event(
        v_uid,
        'community_message_send',
        'allowed',
        NEW.community_id::text,
        jsonb_build_object('message_id', NEW.id)
    );

    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.tg_guard_reports_insert()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_guard RECORD;
BEGIN
    IF v_uid IS NULL THEN
        RETURN NEW;
    END IF;

    IF NEW.reporter_id IS NULL THEN
        NEW.reporter_id := v_uid;
    ELSIF NEW.reporter_id <> v_uid THEN
        RAISE EXCEPTION 'REPORTER_MISMATCH';
    END IF;

    NEW.reason := LEFT(trim(COALESCE(NEW.reason, '')), 120);
    NEW.description := LEFT(trim(COALESCE(NEW.description, '')), 2000);

    IF NEW.reason = '' THEN
        RAISE EXCEPTION 'REPORT_REASON_REQUIRED';
    END IF;

    IF NEW.reported_user_id = v_uid THEN
        RAISE EXCEPTION 'REPORT_SELF_NOT_ALLOWED';
    END IF;

    SELECT * INTO v_guard
      FROM public.guard_rate_limit(v_uid, 'report_submission', COALESCE(NEW.reported_user_id::text, ''), 5, 600, 10000);

    IF NOT v_guard.allowed THEN
        PERFORM public.log_security_event(
            v_uid,
            'report_submission',
            'blocked',
            COALESCE(NEW.reported_user_id::text, ''),
            jsonb_build_object('retry_after_ms', v_guard.retry_after_ms)
        );
        RAISE EXCEPTION 'RATE_LIMIT_REPORT:%', v_guard.retry_after_ms;
    END IF;

    PERFORM public.log_security_event(
        v_uid,
        'report_submission',
        'allowed',
        COALESCE(NEW.reported_user_id::text, ''),
        jsonb_build_object('reported_user_id', NEW.reported_user_id)
    );

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_guard_messages_insert ON public.messages;
CREATE TRIGGER trg_guard_messages_insert
BEFORE INSERT ON public.messages
FOR EACH ROW
EXECUTE FUNCTION public.tg_guard_messages_insert();

DROP TRIGGER IF EXISTS trg_guard_group_messages_insert ON public.group_messages;
CREATE TRIGGER trg_guard_group_messages_insert
BEFORE INSERT ON public.group_messages
FOR EACH ROW
EXECUTE FUNCTION public.tg_guard_group_messages_insert();

DROP TRIGGER IF EXISTS trg_guard_community_messages_insert ON public.community_messages;
CREATE TRIGGER trg_guard_community_messages_insert
BEFORE INSERT ON public.community_messages
FOR EACH ROW
EXECUTE FUNCTION public.tg_guard_community_messages_insert();

DROP TRIGGER IF EXISTS trg_guard_reports_insert ON public.reports;
CREATE TRIGGER trg_guard_reports_insert
BEFORE INSERT ON public.reports
FOR EACH ROW
EXECUTE FUNCTION public.tg_guard_reports_insert();

-- ----------------------------------------------------------
-- 5) Secure RPC endpoints (preferred app path)
-- ----------------------------------------------------------

CREATE OR REPLACE FUNCTION public.secure_send_message(
    p_chat_id UUID,
    p_message_id UUID DEFAULT NULL,
    p_content TEXT DEFAULT '',
    p_content_type TEXT DEFAULT 'text',
    p_media_url TEXT DEFAULT NULL,
    p_media_size BIGINT DEFAULT 0,
    p_reply_to_id UUID DEFAULT NULL
)
RETURNS public.messages
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_inserted public.messages;
    v_preview TEXT;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    INSERT INTO public.messages (
        id,
        chat_id,
        sender_id,
        content,
        content_type,
        media_url,
        media_size,
        reply_to_id,
        status,
        is_edited,
        deleted_for_all
    ) VALUES (
        COALESCE(p_message_id, gen_random_uuid()),
        p_chat_id,
        v_uid,
        COALESCE(p_content, ''),
        COALESCE(NULLIF(p_content_type, ''), 'text'),
        p_media_url,
        COALESCE(p_media_size, 0),
        p_reply_to_id,
        'sent',
        false,
        false
    )
    RETURNING * INTO v_inserted;

    v_preview := CASE
        WHEN v_inserted.content_type = 'text' THEN v_inserted.content
        WHEN v_inserted.content_type = 'image' THEN '📷 Foto'
        WHEN v_inserted.content_type = 'video' THEN '🎥 Video'
        WHEN v_inserted.content_type = 'audio' THEN '🎤 Audio'
        ELSE 'Archivo'
    END;

    UPDATE public.chats
       SET last_message = v_preview,
           last_message_at = v_inserted.created_at,
           last_message_id = v_inserted.id,
           last_message_sender_id = v_uid,
           updated_at = NOW()
     WHERE id = p_chat_id;

    RETURN v_inserted;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_send_group_message(
    p_group_id UUID,
    p_message_id UUID DEFAULT NULL,
    p_content TEXT DEFAULT '',
    p_content_type TEXT DEFAULT 'text',
    p_media_url TEXT DEFAULT NULL,
    p_reply_to_id UUID DEFAULT NULL
)
RETURNS public.group_messages
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_inserted public.group_messages;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    INSERT INTO public.group_messages (
        id,
        group_id,
        sender_id,
        content,
        content_type,
        media_url,
        reply_to_id,
        status
    ) VALUES (
        COALESCE(p_message_id, gen_random_uuid()),
        p_group_id,
        v_uid,
        COALESCE(p_content, ''),
        COALESCE(NULLIF(p_content_type, ''), 'text'),
        p_media_url,
        p_reply_to_id,
        'sent'
    )
    RETURNING * INTO v_inserted;

    RETURN v_inserted;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_send_community_message(
    p_community_id UUID,
    p_message_id UUID DEFAULT NULL,
    p_content TEXT DEFAULT '',
    p_content_type TEXT DEFAULT 'text',
    p_media_url TEXT DEFAULT NULL,
    p_reply_to_id UUID DEFAULT NULL
)
RETURNS public.community_messages
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_inserted public.community_messages;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    INSERT INTO public.community_messages (
        id,
        community_id,
        sender_id,
        content,
        content_type,
        media_url,
        reply_to_id
    ) VALUES (
        COALESCE(p_message_id, gen_random_uuid()),
        p_community_id,
        v_uid,
        COALESCE(p_content, ''),
        COALESCE(NULLIF(p_content_type, ''), 'text'),
        p_media_url,
        p_reply_to_id
    )
    RETURNING * INTO v_inserted;

    RETURN v_inserted;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_submit_report(
    p_reported_user_id UUID,
    p_reported_user_name TEXT,
    p_reason TEXT,
    p_description TEXT DEFAULT NULL,
    p_evidence_url TEXT DEFAULT NULL,
    p_reporter_name TEXT DEFAULT NULL
)
RETURNS public.reports
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_inserted public.reports;
    v_has_reporter_name BOOLEAN;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    SELECT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = 'public'
           AND table_name = 'reports'
           AND column_name = 'reporter_name'
    ) INTO v_has_reporter_name;

    IF v_has_reporter_name THEN
        EXECUTE $sql$
            INSERT INTO public.reports (
                reporter_id,
                reporter_name,
                reported_user_id,
                reported_user_name,
                reason,
                description,
                evidence_url,
                status
            ) VALUES ($1, $2, $3, $4, $5, $6, $7, 'pendiente')
            RETURNING *
        $sql$
        INTO v_inserted
        USING
            v_uid,
            LEFT(trim(COALESCE(p_reporter_name, 'Usuario')), 120),
            p_reported_user_id,
            LEFT(trim(COALESCE(p_reported_user_name, 'Usuario')), 120),
            LEFT(trim(COALESCE(p_reason, '')), 120),
            LEFT(trim(COALESCE(p_description, '')), 2000),
            p_evidence_url;
    ELSE
        INSERT INTO public.reports (
            reporter_id,
            reported_user_id,
            reported_user_name,
            reason,
            description,
            evidence_url,
            status
        ) VALUES (
            v_uid,
            p_reported_user_id,
            LEFT(trim(COALESCE(p_reported_user_name, 'Usuario')), 120),
            LEFT(trim(COALESCE(p_reason, '')), 120),
            LEFT(trim(COALESCE(p_description, '')), 2000),
            p_evidence_url,
            'pendiente'
        )
        RETURNING * INTO v_inserted;
    END IF;

    RETURN v_inserted;
END;
$$;

GRANT EXECUTE ON FUNCTION public.is_current_user_admin(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.log_security_event(UUID, TEXT, TEXT, TEXT, JSONB) TO authenticated;
GRANT EXECUTE ON FUNCTION public.guard_rate_limit(UUID, TEXT, TEXT, INTEGER, INTEGER, INTEGER) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_send_message(UUID, UUID, TEXT, TEXT, TEXT, BIGINT, UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_send_group_message(UUID, UUID, TEXT, TEXT, TEXT, UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_send_community_message(UUID, UUID, TEXT, TEXT, TEXT, UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_submit_report(UUID, TEXT, TEXT, TEXT, TEXT, TEXT) TO authenticated;

-- ----------------------------------------------------------
-- 6) Reports RLS hardening
-- ----------------------------------------------------------

ALTER TABLE public.reports ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Anyone can read reports" ON public.reports;
DROP POLICY IF EXISTS "Anyone can update reports" ON public.reports;
DROP POLICY IF EXISTS "Anyone can delete reports" ON public.reports;
DROP POLICY IF EXISTS "Users can insert own reports" ON public.reports;
DROP POLICY IF EXISTS reports_insert_self ON public.reports;
DROP POLICY IF EXISTS reports_select_self_or_admin ON public.reports;
DROP POLICY IF EXISTS reports_update_admin ON public.reports;
DROP POLICY IF EXISTS reports_delete_admin ON public.reports;

CREATE POLICY reports_insert_self
    ON public.reports
    FOR INSERT
    WITH CHECK (auth.uid() = reporter_id);

CREATE POLICY reports_select_self_or_admin
    ON public.reports
    FOR SELECT
    USING (
        reporter_id = auth.uid()
        OR public.is_current_user_admin()
    );

CREATE POLICY reports_update_admin
    ON public.reports
    FOR UPDATE
    USING (public.is_current_user_admin())
    WITH CHECK (public.is_current_user_admin());

CREATE POLICY reports_delete_admin
    ON public.reports
    FOR DELETE
    USING (public.is_current_user_admin());

COMMIT;
