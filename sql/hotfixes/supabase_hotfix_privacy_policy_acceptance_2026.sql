-- ==========================================================
-- HOTFIX 2026: Privacy policy acceptance tracking
-- - Required for account creation consent
-- - Required before first post/video publication
-- ==========================================================

BEGIN;

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS privacy_policy_accepted BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS privacy_policy_accepted_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_profiles_privacy_policy_accepted
    ON public.profiles (privacy_policy_accepted, privacy_policy_accepted_at DESC);

NOTIFY pgrst, 'reload schema';

COMMIT;
