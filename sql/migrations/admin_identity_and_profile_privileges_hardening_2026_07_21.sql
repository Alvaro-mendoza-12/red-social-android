-- Administrative identity hardening for the legacy Supabase backend.
-- Review and apply through the normal migration process; this file is not
-- executed automatically by the local fallback stack.

BEGIN;

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS is_admin BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS is_verified BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS profile_frame TEXT NOT NULL DEFAULT '';

ALTER TABLE public.profiles
    DROP CONSTRAINT IF EXISTS profiles_profile_frame_allowed;
ALTER TABLE public.profiles
    ADD CONSTRAINT profiles_profile_frame_allowed
    CHECK (profile_frame IN ('', 'gold', 'neon', 'ruby', 'ocean'));

-- Owners may update ordinary profile fields, but never grant themselves
-- administrative, verification, or frame privileges through the broad owner
-- UPDATE policy. SECURITY DEFINER admin RPCs run as their function owner and
-- are therefore not blocked by this trigger.
CREATE OR REPLACE FUNCTION public.protect_profile_admin_fields()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = public, pg_temp
AS $$
BEGIN
    IF current_user IN ('anon', 'authenticated') THEN
        IF TG_OP = 'INSERT' THEN
            IF NEW.is_admin OR NEW.is_verified OR COALESCE(NEW.profile_frame, '') <> '' THEN
                RAISE EXCEPTION 'PROFILE_PRIVILEGE_FIELDS_FORBIDDEN'
                    USING ERRCODE = '42501';
            END IF;
        ELSIF NEW.is_admin IS DISTINCT FROM OLD.is_admin
           OR NEW.is_verified IS DISTINCT FROM OLD.is_verified
           OR NEW.profile_frame IS DISTINCT FROM OLD.profile_frame THEN
            RAISE EXCEPTION 'PROFILE_PRIVILEGE_FIELDS_FORBIDDEN'
                USING ERRCODE = '42501';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS protect_profile_admin_fields_2026 ON public.profiles;
CREATE TRIGGER protect_profile_admin_fields_2026
BEFORE INSERT OR UPDATE ON public.profiles
FOR EACH ROW EXECUTE FUNCTION public.protect_profile_admin_fields();

-- The optional argument is retained for API compatibility, but authorization
-- always uses the authenticated caller. A caller can no longer pass another
-- administrator's UUID to impersonate that account.
CREATE OR REPLACE FUNCTION public.is_current_user_admin(p_user_id UUID DEFAULT auth.uid())
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    SELECT auth.uid() IS NOT NULL
       AND EXISTS (
           SELECT 1 FROM public.profiles
           WHERE id = auth.uid() AND is_admin = true
       );
$$;

CREATE OR REPLACE FUNCTION public.grant_badge_to_user(
    p_user_id UUID,
    p_badge_id UUID,
    p_granted_by UUID
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_actor UUID := auth.uid();
BEGIN
    IF v_actor IS NULL OR NOT public.is_current_user_admin() THEN
        RAISE EXCEPTION 'ADMIN_REQUIRED' USING ERRCODE = '42501';
    END IF;
    INSERT INTO public.user_badges(user_id, badge_id, granted_by)
    VALUES (p_user_id, p_badge_id, v_actor)
    ON CONFLICT (user_id, badge_id) DO UPDATE
        SET granted_by = EXCLUDED.granted_by, granted_at = NOW();
    RETURN true;
END;
$$;

CREATE OR REPLACE FUNCTION public.revoke_badge_from_user(
    p_user_id UUID,
    p_badge_id UUID,
    p_revoked_by UUID
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_current_user_admin() THEN
        RAISE EXCEPTION 'ADMIN_REQUIRED' USING ERRCODE = '42501';
    END IF;
    DELETE FROM public.user_badges
    WHERE user_id = p_user_id AND badge_id = p_badge_id;
    RETURN true;
END;
$$;

CREATE OR REPLACE FUNCTION public.create_custom_badge(
    p_name TEXT,
    p_description TEXT,
    p_icon TEXT,
    p_color TEXT,
    p_created_by UUID
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_badge_id UUID;
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_current_user_admin() THEN
        RAISE EXCEPTION 'ADMIN_REQUIRED' USING ERRCODE = '42501';
    END IF;
    INSERT INTO public.badges(name, description, icon, color, type, created_by, is_admin_badge)
    VALUES (LEFT(TRIM(p_name), 120), LEFT(TRIM(p_description), 500), LEFT(TRIM(p_icon), 32), LEFT(TRIM(p_color), 20), 'manual', auth.uid(), false)
    RETURNING id INTO v_badge_id;
    RETURN v_badge_id;
END;
$$;

CREATE OR REPLACE FUNCTION public.set_user_verified(
    p_user_id UUID,
    p_verified BOOLEAN,
    p_set_by UUID
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF auth.uid() IS NULL OR NOT public.is_current_user_admin() THEN
        RAISE EXCEPTION 'ADMIN_REQUIRED' USING ERRCODE = '42501';
    END IF;
    UPDATE public.profiles
    SET is_verified = p_verified, updated_at = NOW()
    WHERE id = p_user_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'USER_NOT_FOUND'; END IF;
    RETURN true;
END;
$$;

ALTER TABLE public.badges ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_badges ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Only admins can manage badges" ON public.badges;
CREATE POLICY "Only authenticated admins can manage badges"
ON public.badges FOR ALL TO authenticated
USING (public.is_current_user_admin())
WITH CHECK (public.is_current_user_admin());

DROP POLICY IF EXISTS "Only admins can manage user badges" ON public.user_badges;
CREATE POLICY "Only authenticated admins can manage user badges"
ON public.user_badges FOR ALL TO authenticated
USING (public.is_current_user_admin())
WITH CHECK (public.is_current_user_admin());

REVOKE ALL ON FUNCTION public.is_current_user_admin(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.grant_badge_to_user(UUID, UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.revoke_badge_from_user(UUID, UUID, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.create_custom_badge(TEXT, TEXT, TEXT, TEXT, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.set_user_verified(UUID, BOOLEAN, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.is_current_user_admin(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.grant_badge_to_user(UUID, UUID, UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.revoke_badge_from_user(UUID, UUID, UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.create_custom_badge(TEXT, TEXT, TEXT, TEXT, UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.set_user_verified(UUID, BOOLEAN, UUID) TO authenticated;

COMMIT;
