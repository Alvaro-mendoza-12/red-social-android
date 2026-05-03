-- ==========================================================
-- HOTFIX: Video reports + admin moderation workflow
-- Adds table, policies, update trigger and secure RPC.
-- ==========================================================

BEGIN;

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
          AND COALESCE(p.is_admin, false) = true
    );
$$;

CREATE TABLE IF NOT EXISTS public.video_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id UUID NOT NULL REFERENCES public.videos(id) ON DELETE CASCADE,
    video_owner_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    video_owner_name TEXT,
    video_title TEXT,
    video_thumbnail_url TEXT,
    reporter_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    reporter_name TEXT,
    reason TEXT NOT NULL,
    details TEXT,
    status TEXT NOT NULL DEFAULT 'pendiente'
        CHECK (status IN ('pendiente', 'revisado', 'eliminado', 'advertido', 'rechazado')),
    warn_sent BOOLEAN NOT NULL DEFAULT false,
    strike_applied BOOLEAN NOT NULL DEFAULT false,
    action_note TEXT,
    reviewed_at TIMESTAMPTZ,
    reviewed_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(video_id, reporter_id)
);

CREATE INDEX IF NOT EXISTS idx_video_reports_status_created
    ON public.video_reports (status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_video_reports_reporter_created
    ON public.video_reports (reporter_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_video_reports_video_created
    ON public.video_reports (video_id, created_at DESC);

CREATE OR REPLACE FUNCTION public.set_video_reports_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_video_reports_updated_at ON public.video_reports;
CREATE TRIGGER trg_video_reports_updated_at
BEFORE UPDATE ON public.video_reports
FOR EACH ROW
EXECUTE FUNCTION public.set_video_reports_updated_at();

ALTER TABLE public.video_reports ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS video_reports_insert_self ON public.video_reports;
CREATE POLICY video_reports_insert_self
ON public.video_reports
FOR INSERT
TO authenticated
WITH CHECK (reporter_id = auth.uid());

DROP POLICY IF EXISTS video_reports_select_self_or_admin ON public.video_reports;
CREATE POLICY video_reports_select_self_or_admin
ON public.video_reports
FOR SELECT
TO authenticated
USING (
    reporter_id = auth.uid()
    OR public.is_current_user_admin()
);

DROP POLICY IF EXISTS video_reports_update_admin ON public.video_reports;
CREATE POLICY video_reports_update_admin
ON public.video_reports
FOR UPDATE
TO authenticated
USING (public.is_current_user_admin())
WITH CHECK (public.is_current_user_admin());

DROP POLICY IF EXISTS video_reports_delete_admin ON public.video_reports;
CREATE POLICY video_reports_delete_admin
ON public.video_reports
FOR DELETE
TO authenticated
USING (public.is_current_user_admin());

CREATE OR REPLACE FUNCTION public.secure_submit_video_report(
    p_video_id UUID,
    p_reason TEXT,
    p_details TEXT DEFAULT NULL
)
RETURNS public.video_reports
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_reason TEXT := NULLIF(trim(COALESCE(p_reason, '')), '');
    v_details TEXT := NULLIF(trim(COALESCE(p_details, '')), '');
    v_reporter_name TEXT;
    v_video public.videos%ROWTYPE;
    v_video_owner_name TEXT;
    v_report public.video_reports;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF p_video_id IS NULL THEN
        RAISE EXCEPTION 'VIDEO_REQUIRED';
    END IF;

    IF v_reason IS NULL THEN
        RAISE EXCEPTION 'REASON_REQUIRED';
    END IF;

    SELECT *
      INTO v_video
      FROM public.videos
     WHERE id = p_video_id
     LIMIT 1;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'VIDEO_NOT_FOUND';
    END IF;

    IF v_video.user_id = v_uid THEN
        RAISE EXCEPTION 'SELF_REPORT_FORBIDDEN';
    END IF;

    SELECT COALESCE(NULLIF(trim(p.display_name), ''), NULLIF(trim(p.full_name), ''), NULLIF(trim(p.username), ''), 'Usuario')
      INTO v_reporter_name
      FROM public.profiles p
     WHERE p.id = v_uid;

    SELECT COALESCE(NULLIF(trim(p.display_name), ''), NULLIF(trim(p.full_name), ''), NULLIF(trim(p.username), ''), 'Usuario')
      INTO v_video_owner_name
      FROM public.profiles p
     WHERE p.id = v_video.user_id;

    INSERT INTO public.video_reports (
        video_id,
        video_owner_id,
        video_owner_name,
        video_title,
        video_thumbnail_url,
        reporter_id,
        reporter_name,
        reason,
        details,
        status,
        reviewed_at,
        reviewed_by,
        action_note,
        warn_sent,
        strike_applied
    )
    VALUES (
        p_video_id,
        v_video.user_id,
        v_video_owner_name,
        v_video.title,
        v_video.thumbnail_url,
        v_uid,
        v_reporter_name,
        v_reason,
        v_details,
        'pendiente',
        NULL,
        NULL,
        NULL,
        false,
        false
    )
    ON CONFLICT (video_id, reporter_id)
    DO UPDATE SET
        reason = EXCLUDED.reason,
        details = EXCLUDED.details,
        status = 'pendiente',
        reviewed_at = NULL,
        reviewed_by = NULL,
        action_note = NULL,
        warn_sent = false,
        strike_applied = false,
        updated_at = NOW()
    RETURNING *
    INTO v_report;

    RETURN v_report;
END;
$$;

GRANT SELECT, INSERT ON public.video_reports TO authenticated;
GRANT UPDATE(status, reviewed_at, reviewed_by, action_note, warn_sent, strike_applied) ON public.video_reports TO authenticated;
GRANT DELETE ON public.video_reports TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_submit_video_report(UUID, TEXT, TEXT) TO authenticated;

NOTIFY pgrst, 'reload schema';

COMMIT;
