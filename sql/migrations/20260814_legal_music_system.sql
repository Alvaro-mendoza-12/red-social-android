-- Novvin legal music catalog: provider metadata and Moment attachment audit.
-- Run after 20260814_community_music_catalog.sql and 20260814_moment_licensed_music.sql.

ALTER TABLE public.music_tracks ADD COLUMN IF NOT EXISTS album_name TEXT;
ALTER TABLE public.music_tracks ADD COLUMN IF NOT EXISTS attribution TEXT;
ALTER TABLE public.music_tracks ADD COLUMN IF NOT EXISTS preview_url TEXT;
ALTER TABLE public.music_tracks ADD COLUMN IF NOT EXISTS allow_use_in_moments BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.music_tracks ADD COLUMN IF NOT EXISTS waveform_url TEXT;
ALTER TABLE public.music_tracks ADD COLUMN IF NOT EXISTS source_sha256 TEXT;
UPDATE public.music_tracks SET allow_use_in_moments=TRUE
WHERE allow_reuse=TRUE AND allow_use_in_moments=FALSE;

ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS music_fade_in_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS music_fade_out_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS duck_original_audio BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS music_fade_in_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS music_fade_out_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS duck_original_audio BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.moments DROP CONSTRAINT IF EXISTS moments_music_fade_range;
ALTER TABLE public.moments ADD CONSTRAINT moments_music_fade_range CHECK (
  music_fade_in_ms BETWEEN 0 AND 5000 AND
  music_fade_out_ms BETWEEN 0 AND 5000 AND
  music_fade_in_ms <= music_duration_ms / 2 AND
  music_fade_out_ms <= music_duration_ms / 2
);

-- La selección externa nunca se autoriza solo por estos campos: la API vuelve
-- a consultar al proveedor, verifica la licencia y registra el adjunto en
-- moment_music. Esta restricción evita valores obviamente inválidos también
-- cuando una operación administrativa escribe directamente en PostgreSQL.
ALTER TABLE public.moments DROP CONSTRAINT IF EXISTS moments_music_approved_source;
ALTER TABLE public.moments ADD CONSTRAINT moments_music_approved_source CHECK (
  music_audio_url IS NULL OR (
    music_source = 'wikimedia_commons' AND
    music_id ~ '^commons:[0-9]+$' AND
    music_license IN ('CC0 1.0', 'Public Domain') AND
    music_audio_url ~ '^https://upload\.wikimedia\.org/' AND
    music_source_url ~ '^https://commons\.wikimedia\.org/'
  ) OR (
    music_source = 'novvin_community' AND
    music_id ~ '^community:[0-9a-fA-F-]{36}$' AND
    music_audio_url ~ '^https://'
  ) OR (
    music_source IN ('openverse', 'jamendo') AND
    music_id ~ '^(openverse|jamendo):[0-9A-Za-z-]+$' AND
    music_license IN ('CC0 1.0', 'Public Domain') AND
    music_audio_url ~ '^https://' AND
    music_source_url ~ '^https://'
  )
);

CREATE TABLE IF NOT EXISTS public.music_sources (
  track_key TEXT PRIMARY KEY,
  provider TEXT NOT NULL,
  payload JSONB NOT NULL,
  verified_license TEXT NOT NULL CHECK (verified_license IN ('CC0 1.0','Public Domain')),
  verified_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS public.music_retired_hashes (
  source_sha256 TEXT PRIMARY KEY,
  reason TEXT NOT NULL,
  retired_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  retired_by UUID
);

CREATE TABLE IF NOT EXISTS public.music_external_favorites (
  track_key TEXT NOT NULL,
  user_id UUID NOT NULL,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY(track_key, user_id)
);

CREATE TABLE IF NOT EXISTS public.music_not_interested (
  track_key TEXT NOT NULL,
  user_id UUID NOT NULL,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY(track_key, user_id)
);

CREATE TABLE IF NOT EXISTS public.music_catalog_reports (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  track_key TEXT NOT NULL,
  reporter_id UUID NOT NULL,
  provider TEXT NOT NULL,
  reason TEXT NOT NULL,
  details TEXT,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  status TEXT NOT NULL DEFAULT 'OPEN',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  resolved_at TIMESTAMPTZ,
  UNIQUE(track_key, reporter_id, reason)
);

CREATE TABLE IF NOT EXISTS public.moment_music (
  moment_id UUID PRIMARY KEY REFERENCES public.moments(id) ON DELETE CASCADE,
  track_key TEXT NOT NULL,
  provider TEXT NOT NULL,
  title TEXT NOT NULL,
  artist TEXT NOT NULL,
  source_url TEXT NOT NULL,
  license_name TEXT NOT NULL,
  license_url TEXT NOT NULL,
  start_ms BIGINT NOT NULL CHECK (start_ms >= 0),
  end_ms BIGINT NOT NULL CHECK (end_ms > start_ms AND end_ms-start_ms BETWEEN 5000 AND 60000),
  music_volume REAL NOT NULL DEFAULT 1 CHECK (music_volume BETWEEN 0 AND 1),
  original_audio_volume REAL NOT NULL DEFAULT 1 CHECK (original_audio_volume BETWEEN 0 AND 1),
  fade_in_ms BIGINT NOT NULL DEFAULT 0 CHECK (fade_in_ms BETWEEN 0 AND 5000),
  fade_out_ms BIGINT NOT NULL DEFAULT 0 CHECK (fade_out_ms BETWEEN 0 AND 5000),
  duck_original BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_music_catalog_reports_open ON public.music_catalog_reports(track_key, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_music_not_interested_user ON public.music_not_interested(user_id, created_at DESC);

ALTER TABLE public.music_sources ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.music_retired_hashes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.music_external_favorites ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.music_not_interested ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.music_catalog_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.moment_music ENABLE ROW LEVEL SECURITY;

-- These tables remain backend-service-role only; mobile clients never author
-- provider verification or Moment attachments. Community audio is streaming-only.
