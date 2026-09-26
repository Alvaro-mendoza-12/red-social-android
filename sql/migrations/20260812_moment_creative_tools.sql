-- Novvin Momentos: texto superpuesto y música para imágenes.
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS overlay_text TEXT;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS overlay_color TEXT NOT NULL DEFAULT '#FFFFFFFF';
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS overlay_font TEXT NOT NULL DEFAULT 'sans';
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS overlay_scale REAL NOT NULL DEFAULT 1;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS overlay_x REAL NOT NULL DEFAULT 0.5;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS overlay_y REAL NOT NULL DEFAULT 0.38;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS music_url TEXT;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS music_title TEXT;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS music_start_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS music_duration_ms BIGINT NOT NULL DEFAULT 6000;

ALTER TABLE public.moments DROP CONSTRAINT IF EXISTS moments_overlay_scale_range;
ALTER TABLE public.moments ADD CONSTRAINT moments_overlay_scale_range
  CHECK (overlay_scale BETWEEN 0.7 AND 2.1);

ALTER TABLE public.moments DROP CONSTRAINT IF EXISTS moments_overlay_position_range;
ALTER TABLE public.moments ADD CONSTRAINT moments_overlay_position_range
  CHECK (overlay_x BETWEEN 0 AND 1 AND overlay_y BETWEEN 0 AND 1);

ALTER TABLE public.moments DROP CONSTRAINT IF EXISTS moments_music_duration_range;
ALTER TABLE public.moments ADD CONSTRAINT moments_music_duration_range
  CHECK (music_start_ms >= 0 AND music_duration_ms BETWEEN 1000 AND 25000);
