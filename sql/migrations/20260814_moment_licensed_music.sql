-- Momentos: catálogo musical legal y fragmentos mezclados.
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS music_audio_url TEXT;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS music_id TEXT;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS music_artist TEXT;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS music_source TEXT;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS music_source_url TEXT;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS music_license TEXT;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS music_license_url TEXT;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS music_end_ms BIGINT;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS music_volume REAL NOT NULL DEFAULT 1;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS original_audio_volume REAL NOT NULL DEFAULT 1;

ALTER TABLE public.moments DROP CONSTRAINT IF EXISTS moments_music_duration_range;
ALTER TABLE public.moments ADD CONSTRAINT moments_music_duration_range CHECK (
  music_audio_url IS NULL OR (
    music_start_ms >= 0 AND
    music_duration_ms BETWEEN 5000 AND 60000 AND
    music_end_ms = music_start_ms + music_duration_ms
  )
);

ALTER TABLE public.moments DROP CONSTRAINT IF EXISTS moments_music_volume_range;
ALTER TABLE public.moments ADD CONSTRAINT moments_music_volume_range CHECK (
  music_volume BETWEEN 0 AND 1 AND original_audio_volume BETWEEN 0 AND 1
);

ALTER TABLE public.moments DROP CONSTRAINT IF EXISTS moments_music_approved_source;
ALTER TABLE public.moments ADD CONSTRAINT moments_music_approved_source CHECK (
  music_audio_url IS NULL OR (
    music_source = 'wikimedia_commons' AND
    music_id ~ '^commons:[0-9]+$' AND
    music_license IN ('CC0 1.0', 'Public Domain') AND
    music_audio_url ~ '^https://upload\.wikimedia\.org/' AND
    music_source_url ~ '^https://commons\.wikimedia\.org/'
  )
);

ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS music_audio_url TEXT;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS music_id TEXT;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS music_artist TEXT;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS music_source TEXT;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS music_source_url TEXT;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS music_license TEXT;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS music_license_url TEXT;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS music_end_ms BIGINT;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS music_volume REAL NOT NULL DEFAULT 1;
ALTER TABLE public.moment_highlight_items ADD COLUMN IF NOT EXISTS original_audio_volume REAL NOT NULL DEFAULT 1;
