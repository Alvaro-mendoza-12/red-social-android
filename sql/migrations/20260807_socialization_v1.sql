-- Socializar: consent-only foundation (feature/live-gifts)
--
-- This migration stores only explicit feature toggles owned by the account.
-- It is deliberately separate from recommendation profiles, chat contents,
-- and presence records.  The authenticated backend is the sole mutation path;
-- clients never receive another user's consent settings.
--
-- Rollback: disable SOCIALIZATION_* flags first.  Keep preference rows so a
-- user can retain/revoke their explicit choices when the feature returns.

BEGIN;

CREATE TABLE IF NOT EXISTS public.user_socialization_preferences (
    user_id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    discovery_opt_in BOOLEAN NOT NULL DEFAULT false,
    connection_requests_opt_in BOOLEAN NOT NULL DEFAULT false,
    community_discovery_opt_in BOOLEAN NOT NULL DEFAULT false,
    icebreakers_opt_in BOOLEAN NOT NULL DEFAULT false,
    social_rooms_opt_in BOOLEAN NOT NULL DEFAULT false,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS user_socialization_preferences_updated_idx
    ON public.user_socialization_preferences(updated_at DESC);

ALTER TABLE public.user_socialization_preferences ENABLE ROW LEVEL SECURITY;

-- The Android client uses the authenticated Novvin backend, which validates
-- account ownership and revisions in a transaction.  Do not grant direct
-- PostgREST access, otherwise a modified client could forge another user's
-- socialization settings or bypass the optimistic concurrency check.
REVOKE ALL ON TABLE public.user_socialization_preferences
FROM PUBLIC, anon, authenticated;

GRANT ALL ON TABLE public.user_socialization_preferences TO service_role;

COMMIT;
