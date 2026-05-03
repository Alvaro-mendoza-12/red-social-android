-- ==========================================================
-- HOTFIX: Suspension automation for repeat video report offenders
-- Requires: supabase_hotfix_video_reports_admin_2026.sql
-- ==========================================================

BEGIN;

DROP FUNCTION IF EXISTS public.secure_suspend_video_owner_from_report(UUID, UUID, INTEGER, INTEGER);
DROP FUNCTION IF EXISTS public.secure_get_my_suspension_state();

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS is_suspended BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS suspended_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS suspension_reason TEXT,
    ADD COLUMN IF NOT EXISTS suspension_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_profiles_suspension_state
    ON public.profiles (is_suspended, suspended_until DESC);

CREATE OR REPLACE FUNCTION public.secure_suspend_video_owner_from_report(
    p_report_id UUID,
    p_video_owner_id UUID DEFAULT NULL,
    p_days INTEGER DEFAULT 7,
    p_threshold INTEGER DEFAULT 3
)
RETURNS TABLE (
    user_id UUID,
    suspended_until TIMESTAMPTZ,
    suspension_count INTEGER,
    applied BOOLEAN
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_actor UUID := auth.uid();
    v_admin BOOLEAN := false;
    v_target UUID;
    v_until TIMESTAMPTZ;
    v_strike_count INTEGER := 0;
    v_profile public.profiles%ROWTYPE;
    v_report public.video_reports%ROWTYPE;
BEGIN
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    SELECT COALESCE(p.is_admin, false)
    INTO v_admin
    FROM public.profiles p
    WHERE p.id = v_actor;

    IF NOT COALESCE(v_admin, false) THEN
        RAISE EXCEPTION 'ADMIN_REQUIRED';
    END IF;

    IF p_report_id IS NOT NULL THEN
        SELECT vr.*
        INTO v_report
        FROM public.video_reports vr
        WHERE vr.id = p_report_id
        LIMIT 1;
    END IF;

    v_target := COALESCE(v_report.video_owner_id, p_video_owner_id);
    IF v_target IS NULL THEN
        RAISE EXCEPTION 'TARGET_USER_REQUIRED';
    END IF;

    SELECT COALESCE(COUNT(*), 0)::INTEGER
    INTO v_strike_count
    FROM public.video_reports vr
    WHERE vr.video_owner_id = v_target
      AND COALESCE(vr.strike_applied, false) = true;

    IF v_report.id IS NOT NULL AND COALESCE(v_report.strike_applied, false) = false THEN
        v_strike_count := v_strike_count + 1;
    END IF;

    IF v_strike_count < GREATEST(p_threshold, 1) THEN
        SELECT p.*
        INTO v_profile
        FROM public.profiles p
        WHERE p.id = v_target
        LIMIT 1;

        RETURN QUERY
        SELECT
            v_target,
            v_profile.suspended_until,
            COALESCE(v_profile.suspension_count, 0),
            false;
        RETURN;
    END IF;

    v_until := NOW() + make_interval(days => GREATEST(p_days, 1));

    UPDATE public.profiles p
    SET
        is_suspended = true,
        suspended_until = v_until,
        suspension_reason = 'Reincidencia en reportes de video',
        suspension_count = GREATEST(COALESCE(p.suspension_count, 0), 0) + 1,
        updated_at = NOW()
    WHERE p.id = v_target
    RETURNING p.* INTO v_profile;

    IF v_profile.id IS NULL THEN
        RAISE EXCEPTION 'TARGET_PROFILE_NOT_FOUND';
    END IF;

    IF v_report.id IS NOT NULL THEN
        UPDATE public.video_reports
        SET
            strike_applied = true,
            warn_sent = true,
            status = CASE WHEN status = 'eliminado' THEN status ELSE 'advertido' END,
            action_note = 'Cuenta suspendida por reincidencia',
            reviewed_at = NOW(),
            reviewed_by = v_actor
        WHERE id = v_report.id;
    END IF;

    RETURN QUERY
    SELECT
        v_profile.id,
        v_profile.suspended_until,
        COALESCE(v_profile.suspension_count, 0),
        true;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_get_my_suspension_state()
RETURNS TABLE (
    is_suspended BOOLEAN,
    suspended_until TIMESTAMPTZ,
    suspension_reason TEXT,
    suspension_count INTEGER
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    UPDATE public.profiles p
    SET
        is_suspended = false,
        suspended_until = NULL,
        suspension_reason = NULL,
        updated_at = NOW()
    WHERE p.id = v_uid
      AND p.is_suspended = true
      AND p.suspended_until IS NOT NULL
      AND p.suspended_until <= NOW();

    RETURN QUERY
    SELECT
        COALESCE(p.is_suspended, false),
        p.suspended_until,
        p.suspension_reason,
        COALESCE(p.suspension_count, 0)
    FROM public.profiles p
    WHERE p.id = v_uid;
END;
$$;

GRANT EXECUTE ON FUNCTION public.secure_suspend_video_owner_from_report(UUID, UUID, INTEGER, INTEGER) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_get_my_suspension_state() TO authenticated;

NOTIFY pgrst, 'reload schema';

COMMIT;
