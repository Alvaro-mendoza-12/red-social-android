BEGIN;

ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS social_links JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE public.profiles
    DROP CONSTRAINT IF EXISTS profiles_social_links_object_check;

ALTER TABLE public.profiles
    ADD CONSTRAINT profiles_social_links_object_check
    CHECK (jsonb_typeof(social_links) = 'object');

COMMENT ON COLUMN public.profiles.social_links IS
    'Validated public profile links for Instagram, TikTok, Facebook and WhatsApp.';

COMMIT;
