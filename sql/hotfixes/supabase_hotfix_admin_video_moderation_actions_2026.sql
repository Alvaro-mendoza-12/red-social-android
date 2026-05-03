-- ==========================================================
-- HOTFIX 2026: Admin moderation actions for video reports
-- - Real status workflow for video reports
-- - Real warning notifications
-- - Global video deletion by admin
-- - Suspend / enable user account with duration
-- ==========================================================

BEGIN;

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS is_suspended BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS suspended_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS suspension_reason TEXT,
    ADD COLUMN IF NOT EXISTS suspension_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_profiles_suspension_state
    ON public.profiles (is_suspended, suspended_until DESC);

CREATE OR REPLACE FUNCTION public.is_current_user_admin(p_user_id UUID DEFAULT auth.uid())
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.profiles p
        WHERE p.id = COALESCE(p_user_id, auth.uid())
          AND (
            COALESCE(p.is_admin, false) = true
            OR lower(trim(COALESCE(p.email, ''))) IN (
                'gamergamerff017@gmail.com',
                'alvaromendozamarin004@gmail.com'
            )
          )
    );
$$;

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT c.conname
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'public'
          AND t.relname = 'video_reports'
          AND c.contype = 'c'
          AND pg_get_constraintdef(c.oid) ILIKE '%status%'
    LOOP
        EXECUTE format('ALTER TABLE public.video_reports DROP CONSTRAINT %I', r.conname);
    END LOOP;
END;
$$;

ALTER TABLE public.video_reports
    ADD CONSTRAINT video_reports_status_check
    CHECK (
        status IN (
            'pendiente',
            'en_revision',
            'finalizado',
            'revisado',
            'eliminado',
            'advertido',
            'rechazado'
        )
    );

CREATE OR REPLACE FUNCTION public.secure_admin_update_video_report_status(
    p_report_id UUID,
    p_status TEXT,
    p_action_note TEXT DEFAULT NULL
)
RETURNS public.video_reports
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_actor UUID := auth.uid();
    v_status TEXT := lower(trim(COALESCE(p_status, '')));
    v_report public.video_reports%ROWTYPE;
BEGIN
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF NOT public.is_current_user_admin(v_actor) THEN
        RAISE EXCEPTION 'ADMIN_REQUIRED';
    END IF;

    v_status := replace(replace(replace(v_status, ' ', '_'), 'ó', 'o'), 'í', 'i');

    IF v_status NOT IN ('pendiente', 'en_revision', 'finalizado', 'revisado', 'eliminado', 'advertido', 'rechazado') THEN
        RAISE EXCEPTION 'VIDEO_REPORT_STATUS_INVALID';
    END IF;

    UPDATE public.video_reports vr
    SET
        status = v_status,
        reviewed_at = NOW(),
        reviewed_by = v_actor,
        action_note = COALESCE(NULLIF(trim(COALESCE(p_action_note, '')), ''), vr.action_note),
        updated_at = NOW()
    WHERE vr.id = p_report_id
    RETURNING vr.* INTO v_report;

    IF v_report.id IS NULL THEN
        RAISE EXCEPTION 'VIDEO_REPORT_NOT_FOUND';
    END IF;

    RETURN v_report;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_admin_warn_video_owner_from_report(
    p_report_id UUID,
    p_message TEXT DEFAULT 'Tu video fue reportado por incumplir las normas. Revisa y corrige para evitar sanciones.'
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_actor UUID := auth.uid();
    v_report public.video_reports%ROWTYPE;
    v_message TEXT := NULLIF(trim(COALESCE(p_message, '')), '');
BEGIN
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF NOT public.is_current_user_admin(v_actor) THEN
        RAISE EXCEPTION 'ADMIN_REQUIRED';
    END IF;

    SELECT *
    INTO v_report
    FROM public.video_reports
    WHERE id = p_report_id
    LIMIT 1;

    IF v_report.id IS NULL THEN
        RAISE EXCEPTION 'VIDEO_REPORT_NOT_FOUND';
    END IF;

    IF v_message IS NULL THEN
        v_message := 'Tu video fue reportado por incumplir las normas. Revisa y corrige para evitar sanciones.';
    END IF;

    BEGIN
        INSERT INTO public.notifications (recipient_id, actor_id, type, target_id, content)
        VALUES (v_report.video_owner_id, v_actor, 'admin_warning_video', v_report.video_id, v_message);
    EXCEPTION
        WHEN check_violation THEN
            INSERT INTO public.notifications (recipient_id, actor_id, type, target_id, content)
            VALUES (v_report.video_owner_id, v_actor, 'message', v_report.video_id, v_message);
    END;

    UPDATE public.video_reports
    SET
        warn_sent = true,
        status = CASE WHEN status = 'pendiente' THEN 'en_revision' ELSE status END,
        action_note = 'Advertencia enviada al creador',
        reviewed_at = NOW(),
        reviewed_by = v_actor,
        updated_at = NOW()
    WHERE id = v_report.id;

    RETURN true;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_admin_delete_reported_video(
    p_report_id UUID,
    p_action_note TEXT DEFAULT 'Video eliminado por moderación'
)
RETURNS TABLE (
    video_id UUID,
    owner_id UUID,
    report_status TEXT,
    deleted BOOLEAN
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_actor UUID := auth.uid();
    v_report public.video_reports%ROWTYPE;
    v_deleted BOOLEAN := false;
BEGIN
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF NOT public.is_current_user_admin(v_actor) THEN
        RAISE EXCEPTION 'ADMIN_REQUIRED';
    END IF;

    SELECT *
    INTO v_report
    FROM public.video_reports
    WHERE id = p_report_id
    LIMIT 1;

    IF v_report.id IS NULL THEN
        RAISE EXCEPTION 'VIDEO_REPORT_NOT_FOUND';
    END IF;

    DELETE FROM public.videos
    WHERE id = v_report.video_id;

    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    v_deleted := COALESCE(v_deleted, false);

    UPDATE public.video_reports
    SET
        status = 'finalizado',
        strike_applied = true,
        warn_sent = true,
        action_note = COALESCE(NULLIF(trim(COALESCE(p_action_note, '')), ''), 'Video eliminado por moderación'),
        reviewed_at = NOW(),
        reviewed_by = v_actor,
        updated_at = NOW()
    WHERE id = v_report.id;

    BEGIN
        INSERT INTO public.notifications (recipient_id, actor_id, type, target_id, content)
        VALUES (
            v_report.video_owner_id,
            v_actor,
            'admin_warning_video',
            v_report.video_id,
            'Tu video fue eliminado por moderación por incumplimiento de normas.'
        );
    EXCEPTION
        WHEN check_violation THEN
            INSERT INTO public.notifications (recipient_id, actor_id, type, target_id, content)
            VALUES (
                v_report.video_owner_id,
                v_actor,
                'message',
                v_report.video_id,
                'Tu video fue eliminado por moderación por incumplimiento de normas.'
            );
    END;

    RETURN QUERY
    SELECT v_report.video_id, v_report.video_owner_id, 'finalizado'::TEXT, v_deleted;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_admin_set_user_suspension(
    p_user_id UUID,
    p_suspend BOOLEAN DEFAULT true,
    p_days INTEGER DEFAULT 7,
    p_reason TEXT DEFAULT NULL
)
RETURNS TABLE (
    user_id UUID,
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
    v_actor UUID := auth.uid();
    v_until TIMESTAMPTZ;
    v_reason TEXT := NULLIF(trim(COALESCE(p_reason, '')), '');
BEGIN
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF NOT public.is_current_user_admin(v_actor) THEN
        RAISE EXCEPTION 'ADMIN_REQUIRED';
    END IF;

    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'TARGET_USER_REQUIRED';
    END IF;

    IF COALESCE(p_suspend, true) THEN
        v_until := NOW() + make_interval(days => GREATEST(COALESCE(p_days, 7), 1));
        UPDATE public.profiles p
        SET
            is_suspended = true,
            suspended_until = v_until,
            suspension_reason = COALESCE(v_reason, 'Moderación administrativa'),
            suspension_count = GREATEST(COALESCE(p.suspension_count, 0), 0) + 1,
            updated_at = NOW()
        WHERE p.id = p_user_id;
    ELSE
        UPDATE public.profiles p
        SET
            is_suspended = false,
            suspended_until = NULL,
            suspension_reason = NULL,
            updated_at = NOW()
        WHERE p.id = p_user_id;
    END IF;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'TARGET_PROFILE_NOT_FOUND';
    END IF;

    RETURN QUERY
    SELECT
        p.id,
        COALESCE(p.is_suspended, false),
        p.suspended_until,
        p.suspension_reason,
        COALESCE(p.suspension_count, 0)
    FROM public.profiles p
    WHERE p.id = p_user_id
    LIMIT 1;
END;
$$;

GRANT EXECUTE ON FUNCTION public.secure_admin_update_video_report_status(UUID, TEXT, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_admin_warn_video_owner_from_report(UUID, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_admin_delete_reported_video(UUID, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_admin_set_user_suspension(UUID, BOOLEAN, INTEGER, TEXT) TO authenticated;

NOTIFY pgrst, 'reload schema';

COMMIT;
