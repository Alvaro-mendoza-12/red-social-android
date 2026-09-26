-- Catálogo musical aportado por la comunidad.
-- La API autoritativa es la única capa de escritura; RLS queda cerrada para clientes.
CREATE TABLE IF NOT EXISTS public.music_tracks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id UUID NOT NULL,
  title TEXT NOT NULL,
  artist_name TEXT NOT NULL,
  collaborators JSONB NOT NULL DEFAULT '[]'::jsonb,
  cover_url TEXT,
  audio_url TEXT NOT NULL,
  storage_object_name TEXT NOT NULL,
  content_type TEXT NOT NULL,
  size_bytes BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 83886080),
  duration_ms BIGINT NOT NULL CHECK (duration_ms BETWEEN 5000 AND 900000),
  genre TEXT,
  subgenre TEXT,
  mood TEXT,
  tags JSONB NOT NULL DEFAULT '[]'::jsonb,
  language TEXT,
  instrumental BOOLEAN NOT NULL DEFAULT FALSE,
  explicit_content BOOLEAN NOT NULL DEFAULT FALSE,
  release_year INT,
  description TEXT,
  lyrics TEXT,
  rights_type TEXT NOT NULL CHECK (rights_type IN ('ORIGINAL_OWNER','AUTHORIZED_LICENSEE','PUBLIC_DOMAIN','CC0')),
  license_name TEXT,
  license_url TEXT,
  original_source_url TEXT,
  territories JSONB NOT NULL DEFAULT '[]'::jsonb,
  allow_reuse BOOLEAN NOT NULL DEFAULT FALSE,
  allow_remix BOOLEAN NOT NULL DEFAULT FALSE,
  requested_visibility TEXT NOT NULL DEFAULT 'PRIVATE' CHECK (requested_visibility IN ('PRIVATE','UNLISTED','PUBLIC_LISTEN_ONLY','PUBLIC_REUSABLE','PUBLIC_REMIXABLE')),
  visibility TEXT NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','UNLISTED','PUBLIC_LISTEN_ONLY','PUBLIC_REUSABLE','PUBLIC_REMIXABLE')),
  moderation_state TEXT NOT NULL DEFAULT 'PROCESSING' CHECK (moderation_state IN ('UPLOADING','PROCESSING','PENDING_REVIEW','APPROVED','REJECTED','CHANGES_REQUIRED','DISABLED','TAKEDOWN_PENDING','REMOVED')),
  moderation_reason TEXT,
  technical_validation TEXT NOT NULL DEFAULT 'SIGNATURE_VALIDATED',
  terms_version TEXT NOT NULL,
  published_at TIMESTAMPTZ,
  reviewed_at TIMESTAMPTZ,
  reviewed_by UUID,
  disabled_at TIMESTAMPTZ,
  removed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.music_rights_acceptances (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  track_id UUID NOT NULL REFERENCES public.music_tracks(id) ON DELETE CASCADE,
  user_id UUID NOT NULL,
  terms_version TEXT NOT NULL,
  rights_type TEXT NOT NULL,
  owns_or_controls_rights BOOLEAN NOT NULL,
  accepts_publication BOOLEAN NOT NULL,
  accepts_moderation BOOLEAN NOT NULL,
  accepts_takedown BOOLEAN NOT NULL,
  evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
  request_ip_hash TEXT,
  user_agent TEXT,
  accepted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.music_moderation_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  track_id UUID NOT NULL REFERENCES public.music_tracks(id) ON DELETE CASCADE,
  actor_id UUID NOT NULL,
  from_state TEXT,
  to_state TEXT NOT NULL,
  reason TEXT,
  evidence JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.music_favorites (
  track_id UUID NOT NULL REFERENCES public.music_tracks(id) ON DELETE CASCADE,
  user_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY(track_id, user_id)
);

CREATE TABLE IF NOT EXISTS public.music_artist_follows (
  artist_id UUID NOT NULL,
  follower_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY(artist_id, follower_id)
);

CREATE TABLE IF NOT EXISTS public.music_listens (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  track_id UUID NOT NULL REFERENCES public.music_tracks(id) ON DELETE CASCADE,
  user_id UUID,
  session_id UUID NOT NULL,
  context TEXT NOT NULL DEFAULT 'catalog',
  started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  completed_at TIMESTAMPTZ,
  listened_ms BIGINT NOT NULL DEFAULT 0,
  counted BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE(track_id, session_id)
);

CREATE TABLE IF NOT EXISTS public.music_reports (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  track_id UUID NOT NULL REFERENCES public.music_tracks(id) ON DELETE CASCADE,
  reporter_id UUID NOT NULL,
  reason TEXT NOT NULL,
  details TEXT,
  status TEXT NOT NULL DEFAULT 'OPEN',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  resolved_at TIMESTAMPTZ,
  UNIQUE(track_id, reporter_id, reason)
);

CREATE INDEX IF NOT EXISTS idx_music_tracks_public_search ON public.music_tracks(moderation_state, visibility, published_at DESC);
CREATE INDEX IF NOT EXISTS idx_music_tracks_owner ON public.music_tracks(owner_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_music_listens_counted ON public.music_listens(track_id, counted, completed_at DESC);
CREATE INDEX IF NOT EXISTS idx_music_reports_open ON public.music_reports(track_id, status, created_at DESC);

ALTER TABLE public.music_tracks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.music_rights_acceptances ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.music_moderation_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.music_favorites ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.music_artist_follows ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.music_listens ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.music_reports ENABLE ROW LEVEL SECURITY;

INSERT INTO storage.buckets(id, name, public, file_size_limit, allowed_mime_types)
VALUES(
  'community-music',
  'community-music',
  TRUE,
  83886080,
  ARRAY['audio/mpeg','audio/mp4','audio/aac','audio/ogg','audio/opus','audio/flac','audio/wav','audio/x-wav','audio/webm']
)
ON CONFLICT(id) DO UPDATE SET
  public=EXCLUDED.public,
  file_size_limit=EXCLUDED.file_size_limit,
  allowed_mime_types=EXCLUDED.allowed_mime_types;

-- Momentos puede usar Commons o una pista comunitaria ya aprobada por la API.
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
    music_id ~ '^community:[0-9a-fA-F-]{36}$'
  )
);
