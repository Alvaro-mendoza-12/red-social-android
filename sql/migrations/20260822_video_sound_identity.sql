ALTER TABLE public.videos
  ADD COLUMN IF NOT EXISTS sound_id TEXT,
  ADD COLUMN IF NOT EXISTS sound_source_video_id TEXT,
  ADD COLUMN IF NOT EXISTS sound_title TEXT,
  ADD COLUMN IF NOT EXISTS sound_artist TEXT,
  ADD COLUMN IF NOT EXISTS sound_url TEXT;

UPDATE public.videos AS video
SET sound_id = COALESCE(NULLIF(video.sound_id, ''), video.id::text),
    sound_source_video_id = COALESCE(NULLIF(video.sound_source_video_id, ''), video.id::text),
    sound_title = COALESCE(
      NULLIF(video.sound_title, ''),
      'Sonido original — ' || COALESCE(
        NULLIF(to_jsonb(video)->>'user_name', ''),
        NULLIF(to_jsonb(video)->>'username', ''),
        'Novvin'
      )
    ),
    sound_artist = COALESCE(
      NULLIF(video.sound_artist, ''),
      NULLIF(to_jsonb(video)->>'user_name', ''),
      NULLIF(to_jsonb(video)->>'username', ''),
      'Novvin'
    ),
    sound_url = COALESCE(
      NULLIF(video.sound_url, ''),
      NULLIF(to_jsonb(video)->>'video_url', ''),
      NULLIF(to_jsonb(video)->>'videourl', '')
    )
WHERE sound_id IS NULL OR sound_id = ''
   OR sound_source_video_id IS NULL OR sound_source_video_id = ''
   OR sound_title IS NULL OR sound_title = ''
   OR sound_artist IS NULL OR sound_artist = ''
   OR sound_url IS NULL OR sound_url = '';

CREATE INDEX IF NOT EXISTS idx_videos_sound
  ON public.videos(sound_id, created_at DESC);
