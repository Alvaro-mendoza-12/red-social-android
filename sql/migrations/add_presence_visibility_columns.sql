ALTER TABLE public.profiles
    ADD COLUMN IF NOT EXISTS last_seen_visibility TEXT DEFAULT 'everyone',
    ADD COLUMN IF NOT EXISTS online_status_visibility TEXT DEFAULT 'everyone';

UPDATE public.profiles
SET
    last_seen_visibility = COALESCE(NULLIF(TRIM(last_seen_visibility), ''), 'everyone'),
    online_status_visibility = COALESCE(NULLIF(TRIM(online_status_visibility), ''), 'everyone');
