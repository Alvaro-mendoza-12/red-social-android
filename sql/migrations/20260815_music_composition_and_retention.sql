-- Media3 Composition publication metadata, offline retention and auditable recommendations.
-- Run after 20260814_legal_music_system.sql.

ALTER TABLE public.moments
  ADD COLUMN IF NOT EXISTS music_embedded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.moment_highlight_items
  ADD COLUMN IF NOT EXISTS music_embedded BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.moment_music
  ADD COLUMN IF NOT EXISTS embedded_in_media BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.moment_music
  ADD COLUMN IF NOT EXISTS track_owner_id TEXT;
ALTER TABLE public.moment_music
  ADD COLUMN IF NOT EXISTS attribution TEXT;
ALTER TABLE public.moment_music
  ADD COLUMN IF NOT EXISTS used_duration_ms BIGINT NOT NULL DEFAULT 0;

ALTER TABLE public.moments DROP CONSTRAINT IF EXISTS moments_embedded_music_requires_video;
ALTER TABLE public.moments ADD CONSTRAINT moments_embedded_music_requires_video CHECK (
  music_embedded = FALSE OR (media_type = 'video' AND music_audio_url IS NOT NULL)
);

CREATE TABLE IF NOT EXISTS public.music_play_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  track_key TEXT NOT NULL,
  user_id UUID,
  event_type TEXT NOT NULL CHECK (event_type IN ('preview_started','preview_completed','moment_used','offline_played')),
  listened_ms BIGINT NOT NULL DEFAULT 0 CHECK (listened_ms >= 0),
  context TEXT NOT NULL DEFAULT 'catalog',
  region_code TEXT CHECK (region_code IS NULL OR region_code ~ '^[A-Z]{2}$'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.music_recommendations (
  user_id UUID NOT NULL,
  track_key TEXT NOT NULL,
  score REAL NOT NULL,
  reason JSONB NOT NULL DEFAULT '{}'::jsonb,
  generated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '6 hours'),
  PRIMARY KEY (user_id, track_key)
);

CREATE INDEX IF NOT EXISTS idx_music_play_events_track_time
  ON public.music_play_events(track_key, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_music_play_events_user_time
  ON public.music_play_events(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_music_recommendations_user_score
  ON public.music_recommendations(user_id, score DESC, generated_at DESC);

ALTER TABLE public.music_play_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.music_recommendations ENABLE ROW LEVEL SECURITY;

-- The backend service role is the only writer/reader. Android receives a
-- filtered recommendation response and never authors scores or license facts.
