-- ==========================================================
-- HOTFIX 2026: Signup profile flow
-- Ensures new accounts can persist:
-- - full_name / display_name / country
-- - avatar_url
-- - privacy policy acceptance fields
-- - avatars bucket + storage policies
-- ==========================================================

BEGIN;

-- ----------------------------------------------------------
-- 1) Profiles schema required by the app signup flow
-- ----------------------------------------------------------

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS full_name TEXT;

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS display_name TEXT;

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS avatar_url TEXT;

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS country TEXT;

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS privacy_policy_accepted BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS privacy_policy_accepted_at TIMESTAMPTZ;

ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "signup_profiles_select_authenticated_2026" ON public.profiles;
DROP POLICY IF EXISTS "signup_profiles_insert_own_2026" ON public.profiles;
DROP POLICY IF EXISTS "signup_profiles_update_own_2026" ON public.profiles;

CREATE POLICY "signup_profiles_select_authenticated_2026"
ON public.profiles
FOR SELECT
TO authenticated
USING (true);

CREATE POLICY "signup_profiles_insert_own_2026"
ON public.profiles
FOR INSERT
TO authenticated
WITH CHECK (auth.uid() = id);

CREATE POLICY "signup_profiles_update_own_2026"
ON public.profiles
FOR UPDATE
TO authenticated
USING (auth.uid() = id)
WITH CHECK (auth.uid() = id);

CREATE INDEX IF NOT EXISTS idx_profiles_privacy_policy_accepted
    ON public.profiles (privacy_policy_accepted, privacy_policy_accepted_at DESC);

-- ----------------------------------------------------------
-- 2) Avatars bucket required by profile image upload
-- ----------------------------------------------------------

INSERT INTO storage.buckets (id, name, public)
VALUES ('avatars', 'avatars', true)
ON CONFLICT (id) DO UPDATE
SET public = EXCLUDED.public;

DO $$
BEGIN
    BEGIN
        UPDATE storage.buckets
        SET file_size_limit = 50 * 1024 * 1024
        WHERE id = 'avatars';
    EXCEPTION
        WHEN undefined_column OR undefined_table OR insufficient_privilege THEN
            RAISE NOTICE 'Skipping avatars file_size_limit update';
    END;
END;
$$;

DROP POLICY IF EXISTS "signup_avatars_public_read_2026" ON storage.objects;
DROP POLICY IF EXISTS "signup_avatars_insert_own_folder_2026" ON storage.objects;
DROP POLICY IF EXISTS "signup_avatars_update_own_folder_2026" ON storage.objects;
DROP POLICY IF EXISTS "signup_avatars_delete_own_folder_2026" ON storage.objects;

CREATE POLICY "signup_avatars_public_read_2026"
ON storage.objects
FOR SELECT
TO public
USING (bucket_id = 'avatars');

CREATE POLICY "signup_avatars_insert_own_folder_2026"
ON storage.objects
FOR INSERT
TO authenticated
WITH CHECK (
    bucket_id = 'avatars'
    AND auth.uid() IS NOT NULL
    AND (storage.foldername(name))[1] = auth.uid()::text
);

CREATE POLICY "signup_avatars_update_own_folder_2026"
ON storage.objects
FOR UPDATE
TO authenticated
USING (
    bucket_id = 'avatars'
    AND auth.uid() IS NOT NULL
    AND (storage.foldername(name))[1] = auth.uid()::text
)
WITH CHECK (
    bucket_id = 'avatars'
    AND auth.uid() IS NOT NULL
    AND (storage.foldername(name))[1] = auth.uid()::text
);

CREATE POLICY "signup_avatars_delete_own_folder_2026"
ON storage.objects
FOR DELETE
TO authenticated
USING (
    bucket_id = 'avatars'
    AND auth.uid() IS NOT NULL
    AND (storage.foldername(name))[1] = auth.uid()::text
);

NOTIFY pgrst, 'reload schema';

COMMIT;

-- ----------------------------------------------------------
-- 3) Optional one-time backfill for legacy profiles
-- Uncomment only if your app already REQUIRED acceptance
-- during signup and some older profiles were left with false
-- because the privacy columns did not exist yet.
-- ----------------------------------------------------------
-- UPDATE public.profiles
-- SET privacy_policy_accepted = true,
--     privacy_policy_accepted_at = COALESCE(
--         privacy_policy_accepted_at,
--         updated_at,
--         created_at,
--         NOW()
--     )
-- WHERE COALESCE(privacy_policy_accepted, false) = false;

-- ----------------------------------------------------------
-- 4) Quick verification
-- Run these after executing the hotfix if you want to verify.
-- ----------------------------------------------------------
SELECT column_name
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'profiles'
  AND column_name IN (
      'full_name',
      'display_name',
      'avatar_url',
      'country',
      'privacy_policy_accepted',
      'privacy_policy_accepted_at'
  )
ORDER BY column_name;

SELECT policyname
FROM pg_policies
WHERE schemaname = 'public'
  AND tablename = 'profiles'
  AND policyname IN (
      'signup_profiles_select_authenticated_2026',
      'signup_profiles_insert_own_2026',
      'signup_profiles_update_own_2026'
  )
ORDER BY policyname;

SELECT id, name, public
FROM storage.buckets
WHERE id = 'avatars';
