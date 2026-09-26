-- Existing duplicate handles lose only the duplicate username field.
-- The account and its display name remain unchanged.
WITH ranked AS (
  SELECT user_id, ROW_NUMBER() OVER (
    PARTITION BY lower(trim(profile->>'username')) ORDER BY updated_at, user_id
  ) AS position
  FROM fallback_profile_overrides
  WHERE NULLIF(trim(profile->>'username'), '') IS NOT NULL
)
UPDATE fallback_profile_overrides AS owner
SET profile = owner.profile - 'username'
FROM ranked WHERE owner.user_id = ranked.user_id AND ranked.position > 1;

CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_profile_username_unique
ON fallback_profile_overrides(lower(trim(profile->>'username')))
WHERE NULLIF(trim(profile->>'username'), '') IS NOT NULL;
