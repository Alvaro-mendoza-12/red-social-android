-- Minimal and robust redefinition for guard_rate_limit.

DROP FUNCTION IF EXISTS public.guard_rate_limit(UUID, TEXT, TEXT, INTEGER, INTEGER, INTEGER);

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
AS $function$
DECLARE
    v_now TIMESTAMPTZ := NOW();
    v_scope TEXT := COALESCE(p_scope_key, '');
    v_window_start_ts TIMESTAMPTZ;
    v_last_hit TIMESTAMPTZ;
    v_hits INTEGER := 0;
    v_retry_ms BIGINT := 0;
BEGIN
    IF COALESCE(auth.role(), 'authenticated') NOT IN ('authenticated', 'service_role') THEN
        RAISE EXCEPTION 'FORBIDDEN';
    END IF;

    IF auth.role() IS DISTINCT FROM 'service_role' AND auth.uid() IS DISTINCT FROM p_user_id THEN
        RAISE EXCEPTION 'FORBIDDEN';
    END IF;

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

    SELECT s.last_hit_at
      INTO v_last_hit
      FROM public.security_rate_limit_state s
     WHERE s.user_id = p_user_id
       AND s.action = p_action
       AND s.scope_key = v_scope
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
$function$;

GRANT EXECUTE ON FUNCTION public.guard_rate_limit(UUID, TEXT, TEXT, INTEGER, INTEGER, INTEGER) TO authenticated;
NOTIFY pgrst, 'reload schema';
