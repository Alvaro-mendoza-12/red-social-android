-- Persist the profile fields used by the Android/web models when the legacy
-- Supabase/PostgREST backend is selected. The Ubuntu backend stores the same
-- fields in fallback_profile_overrides.profile (JSONB).

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS interests TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS hobbies TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS linked_games TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS game_logo_urls JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS favorite_music_url TEXT,
    ADD COLUMN IF NOT EXISTS favorite_book TEXT,
    ADD COLUMN IF NOT EXISTS favorite_sport TEXT,
    ADD COLUMN IF NOT EXISTS career TEXT,
    ADD COLUMN IF NOT EXISTS availability_schedule TEXT,
    ADD COLUMN IF NOT EXISTS profile_section_order TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS profile_section_visibility JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS avatar_media_type TEXT,
    ADD COLUMN IF NOT EXISTS avatar_video_muted BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS avatar_duration_ms BIGINT,
    ADD COLUMN IF NOT EXISTS profile_theme TEXT NOT NULL DEFAULT 'conectate',
    ADD COLUMN IF NOT EXISTS profile_effect TEXT NOT NULL DEFAULT 'none',
    ADD COLUMN IF NOT EXISTS profile_welcome TEXT,
    ADD COLUMN IF NOT EXISTS favorites_public BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS break_mode TEXT NOT NULL DEFAULT 'off';

COMMENT ON COLUMN public.profiles.profile_welcome IS
    'Optional profile welcome message, maximum length enforced by the API/client.';
COMMENT ON COLUMN public.profiles.game_logo_urls IS
    'Map from linked game name to an allow-listed HTTPS icon URL.';
