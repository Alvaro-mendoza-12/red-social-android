-- ==========================================================
-- HOTFIX 2026: chat_folders unique name per user (case-insensitive)
-- ==========================================================

BEGIN;

-- Normalize whitespace in existing folder names.
UPDATE public.chat_folders
   SET name = TRIM(regexp_replace(COALESCE(name, ''), '\s+', ' ', 'g'))
 WHERE name IS DISTINCT FROM TRIM(regexp_replace(COALESCE(name, ''), '\s+', ' ', 'g'));

-- Remove duplicates keeping the oldest row per (user_id, lower(name)).
WITH ranked AS (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY user_id, LOWER(name)
            ORDER BY created_at ASC, id ASC
        ) AS rn
    FROM public.chat_folders
)
DELETE FROM public.chat_folders f
USING ranked r
WHERE f.id = r.id
  AND r.rn > 1;

-- Enforce one folder name per user regardless of casing.
CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_folders_user_name_ci_unique
    ON public.chat_folders (user_id, LOWER(name));

NOTIFY pgrst, 'reload schema';

COMMIT;
