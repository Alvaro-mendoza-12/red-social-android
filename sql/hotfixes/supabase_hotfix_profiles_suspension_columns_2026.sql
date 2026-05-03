-- ==========================================================
-- HOTFIX 2026: Suspension columns + admin suspension RPC
-- Fixes runtime error: column p.suspension_count does not exist
-- ==========================================================

BEGIN;

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS is_suspended BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS suspended_until TIMESTAMPTZ;

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS suspension_reason TEXT;

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS suspension_count INTEGER NOT NULL DEFAULT 0;

UPDATE public.profiles
SET suspension_count = 0
WHERE suspension_count IS NULL;

CREATE INDEX IF NOT EXISTS idx_profiles_suspension_state
    ON public.profiles (is_suspended, suspended_until DESC);

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

    IF NOT EXISTS (
        SELECT 1
        FROM public.profiles p
        WHERE p.id = v_actor
          AND (
              COALESCE(p.is_admin, false) = true
              OR lower(trim(COALESCE(p.email, ''))) IN (
                  'gamergamerff017@gmail.com',
                  'alvaromendozamarin004@gmail.com'
              )
          )
    ) THEN
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

GRANT EXECUTE ON FUNCTION public.secure_admin_set_user_suspension(UUID, BOOLEAN, INTEGER, TEXT) TO authenticated;

NOTIFY pgrst, 'reload schema';

COMMIT;
