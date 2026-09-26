ALTER TABLE fallback_videos
  ADD COLUMN IF NOT EXISTS sound_id TEXT,
  ADD COLUMN IF NOT EXISTS sound_source_video_id TEXT,
  ADD COLUMN IF NOT EXISTS sound_title TEXT,
  ADD COLUMN IF NOT EXISTS sound_artist TEXT,
  ADD COLUMN IF NOT EXISTS sound_url TEXT;

UPDATE fallback_videos
SET sound_id = COALESCE(NULLIF(sound_id, ''), id::text),
    sound_source_video_id = COALESCE(NULLIF(sound_source_video_id, ''), id::text),
    sound_title = COALESCE(NULLIF(sound_title, ''), 'Sonido original — ' || COALESCE(NULLIF(user_name, ''), 'Novvin')),
    sound_artist = COALESCE(NULLIF(sound_artist, ''), NULLIF(user_name, ''), 'Novvin'),
    sound_url = COALESCE(NULLIF(sound_url, ''), video_url)
WHERE sound_id IS NULL OR sound_id = ''
   OR sound_source_video_id IS NULL OR sound_source_video_id = ''
   OR sound_title IS NULL OR sound_title = ''
   OR sound_artist IS NULL OR sound_artist = ''
   OR sound_url IS NULL OR sound_url = '';

CREATE INDEX IF NOT EXISTS idx_fallback_videos_sound
  ON fallback_videos(lower(sound_id), created_at DESC);
