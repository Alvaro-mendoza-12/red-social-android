ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS client_publish_id UUID;
ALTER TABLE fallback_videos ADD COLUMN IF NOT EXISTS client_requested_sound_id TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_videos_publish_attempt
  ON fallback_videos(user_id, client_publish_id);
