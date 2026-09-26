-- Preserve whether the account avatar is an image, GIF or looping video.
-- PlayMatch inherits this value until the user configures a gamer-only avatar.
ALTER TABLE fallback_users
    ADD COLUMN IF NOT EXISTS avatar_media_type TEXT;

UPDATE fallback_users
   SET avatar_media_type = NULL
 WHERE avatar_media_type IS NOT NULL
   AND avatar_media_type NOT IN ('image', 'gif', 'video');

ALTER TABLE fallback_users
    DROP CONSTRAINT IF EXISTS fallback_users_avatar_media_type_check;

ALTER TABLE fallback_users
    ADD CONSTRAINT fallback_users_avatar_media_type_check
    CHECK (
        avatar_media_type IS NULL
        OR avatar_media_type IN ('image', 'gif', 'video')
    );

