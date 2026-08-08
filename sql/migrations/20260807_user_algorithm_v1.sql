-- User-owned recommendation controls (feature/live-gifts)
--
-- This migration is additive and idempotent.  It keeps explicit preferences
-- separate by dimension so a negative topic rule never degrades a creator
-- preference.  It intentionally has no fields for private messages, precise
-- location, age, gender, or advertising profiles.
--
-- Rollback strategy: disable USER_ALGORITHM_* flags first.  Retain rows during
-- rollback rather than deleting account-controlled preferences; a later,
-- explicitly reviewed migration may archive them after export support exists.

BEGIN;

CREATE TABLE IF NOT EXISTS public.user_algorithm_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    name TEXT NOT NULL DEFAULT 'Para mí',
    slug TEXT NOT NULL DEFAULT 'for-you',
    mode TEXT NOT NULL DEFAULT 'manual'
        CHECK (mode IN ('manual', 'assisted', 'automatic')),
    is_enabled BOOLEAN NOT NULL DEFAULT false,
    is_default BOOLEAN NOT NULL DEFAULT false,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT user_algorithm_profiles_name_length CHECK (char_length(name) BETWEEN 1 AND 60),
    CONSTRAINT user_algorithm_profiles_slug_length CHECK (char_length(slug) BETWEEN 1 AND 60),
    UNIQUE(user_id, slug)
);

CREATE UNIQUE INDEX IF NOT EXISTS user_algorithm_profiles_default_uidx
    ON public.user_algorithm_profiles(user_id)
    WHERE is_default;
CREATE INDEX IF NOT EXISTS user_algorithm_profiles_owner_idx
    ON public.user_algorithm_profiles(user_id, is_default DESC, updated_at DESC);

CREATE TABLE IF NOT EXISTS public.user_algorithm_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID NOT NULL REFERENCES public.user_algorithm_profiles(id) ON DELETE CASCADE,
    surface TEXT NOT NULL DEFAULT 'global'
        CHECK (surface IN ('global', 'video', 'social', 'live', 'community', 'people', 'challenge', 'event')),
    dimension TEXT NOT NULL
        CHECK (dimension IN ('content', 'topic', 'subtopic', 'creator', 'format', 'audio', 'language', 'community', 'relationship')),
    subject_key TEXT NOT NULL,
    subject_label TEXT NOT NULL,
    score SMALLINT NOT NULL CHECK (score BETWEEN -100 AND 100),
    source TEXT NOT NULL DEFAULT 'explicit' CHECK (source = 'explicit'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(profile_id, surface, dimension, subject_key)
);
CREATE INDEX IF NOT EXISTS user_algorithm_preferences_profile_idx
    ON public.user_algorithm_preferences(profile_id, surface, dimension, score, updated_at DESC);

CREATE TABLE IF NOT EXISTS public.user_algorithm_feed_mix (
    profile_id UUID NOT NULL REFERENCES public.user_algorithm_profiles(id) ON DELETE CASCADE,
    surface TEXT NOT NULL
        CHECK (surface IN ('global', 'video', 'social', 'live', 'community', 'people', 'challenge', 'event')),
    following_weight SMALLINT NOT NULL DEFAULT 40 CHECK (following_weight BETWEEN 0 AND 100),
    interest_weight SMALLINT NOT NULL DEFAULT 30 CHECK (interest_weight BETWEEN 0 AND 100),
    community_weight SMALLINT NOT NULL DEFAULT 10 CHECK (community_weight BETWEEN 0 AND 100),
    new_content_weight SMALLINT NOT NULL DEFAULT 10 CHECK (new_content_weight BETWEEN 0 AND 100),
    trend_weight SMALLINT NOT NULL DEFAULT 5 CHECK (trend_weight BETWEEN 0 AND 100),
    exploration_weight SMALLINT NOT NULL DEFAULT 5 CHECK (exploration_weight BETWEEN 0 AND 100),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(profile_id, surface),
    CHECK (
      following_weight + interest_weight + community_weight + new_content_weight + trend_weight + exploration_weight = 100
    )
);

CREATE TABLE IF NOT EXISTS public.user_algorithm_data_permissions (
    profile_id UUID PRIMARY KEY REFERENCES public.user_algorithm_profiles(id) ON DELETE CASCADE,
    use_likes BOOLEAN NOT NULL DEFAULT false,
    use_follows BOOLEAN NOT NULL DEFAULT false,
    use_searches BOOLEAN NOT NULL DEFAULT false,
    use_watch_time BOOLEAN NOT NULL DEFAULT false,
    use_scroll_velocity BOOLEAN NOT NULL DEFAULT false,
    use_profile_visits BOOLEAN NOT NULL DEFAULT false,
    use_public_interactions BOOLEAN NOT NULL DEFAULT false,
    use_community_activity BOOLEAN NOT NULL DEFAULT false,
    use_live_activity BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.user_algorithm_temporary_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID NOT NULL REFERENCES public.user_algorithm_profiles(id) ON DELETE CASCADE,
    surface TEXT NOT NULL DEFAULT 'global'
        CHECK (surface IN ('global', 'video', 'social', 'live', 'community', 'people', 'challenge', 'event')),
    dimension TEXT NOT NULL
        CHECK (dimension IN ('content', 'topic', 'subtopic', 'creator', 'format', 'audio', 'language', 'community', 'relationship')),
    subject_key TEXT NOT NULL,
    subject_label TEXT NOT NULL,
    score SMALLINT NOT NULL CHECK (score BETWEEN -100 AND 100),
    starts_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (expires_at > starts_at)
);
CREATE INDEX IF NOT EXISTS user_algorithm_temporary_preferences_active_idx
    ON public.user_algorithm_temporary_preferences(profile_id, starts_at, expires_at);

-- Classification is written by reviewed server-side workers only.  Client
-- supplied scores and labels are never accepted as a content classification.
CREATE TABLE IF NOT EXISTS public.algorithm_content_classifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content_kind TEXT NOT NULL CHECK (content_kind IN ('video', 'post', 'live', 'community', 'challenge', 'event')),
    content_id TEXT NOT NULL,
    creator_id TEXT,
    language_code TEXT,
    format_key TEXT,
    audio_key TEXT,
    community_id TEXT,
    safety_state TEXT NOT NULL DEFAULT 'unknown'
        CHECK (safety_state IN ('unknown', 'allowed', 'limited', 'blocked')),
    classifier_version TEXT NOT NULL DEFAULT 'server-v1',
    classified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(content_kind, content_id)
);

CREATE TABLE IF NOT EXISTS public.algorithm_content_topics (
    classification_id UUID NOT NULL REFERENCES public.algorithm_content_classifications(id) ON DELETE CASCADE,
    topic_key TEXT NOT NULL,
    subtopic_key TEXT NOT NULL DEFAULT '',
    confidence NUMERIC(4,3) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    source TEXT NOT NULL DEFAULT 'server' CHECK (source IN ('server', 'reviewed')),
    PRIMARY KEY(classification_id, topic_key, subtopic_key)
);
CREATE INDEX IF NOT EXISTS algorithm_content_topics_lookup_idx
    ON public.algorithm_content_topics(topic_key, subtopic_key, confidence DESC);

ALTER TABLE public.user_algorithm_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_algorithm_preferences ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_algorithm_feed_mix ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_algorithm_data_permissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_algorithm_temporary_preferences ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.algorithm_content_classifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.algorithm_content_topics ENABLE ROW LEVEL SECURITY;

-- The Android client always goes through the authenticated Novvin backend.  Do
-- not grant authenticated/PostgREST roles direct mutation access: otherwise a
-- modified client could bypass profile revisions and server-side validation.
REVOKE ALL ON TABLE public.user_algorithm_profiles,
                    public.user_algorithm_preferences,
                    public.user_algorithm_feed_mix,
                    public.user_algorithm_data_permissions,
                    public.user_algorithm_temporary_preferences,
                    public.algorithm_content_classifications,
                    public.algorithm_content_topics
FROM PUBLIC, anon, authenticated;

GRANT ALL ON TABLE public.user_algorithm_profiles,
                   public.user_algorithm_preferences,
                   public.user_algorithm_feed_mix,
                   public.user_algorithm_data_permissions,
                   public.user_algorithm_temporary_preferences,
                   public.algorithm_content_classifications,
                   public.algorithm_content_topics
TO service_role;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'user_algorithm_profiles'
      AND policyname = 'user_algorithm_profiles_own'
  ) THEN
    CREATE POLICY user_algorithm_profiles_own ON public.user_algorithm_profiles
      FOR ALL TO authenticated
      USING (user_id = auth.uid())
      WITH CHECK (user_id = auth.uid());
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'user_algorithm_preferences'
      AND policyname = 'user_algorithm_preferences_own'
  ) THEN
    CREATE POLICY user_algorithm_preferences_own ON public.user_algorithm_preferences
      FOR ALL TO authenticated
      USING (EXISTS (
        SELECT 1 FROM public.user_algorithm_profiles p
        WHERE p.id = profile_id AND p.user_id = auth.uid()
      ))
      WITH CHECK (EXISTS (
        SELECT 1 FROM public.user_algorithm_profiles p
        WHERE p.id = profile_id AND p.user_id = auth.uid()
      ));
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'user_algorithm_feed_mix'
      AND policyname = 'user_algorithm_feed_mix_own'
  ) THEN
    CREATE POLICY user_algorithm_feed_mix_own ON public.user_algorithm_feed_mix
      FOR ALL TO authenticated
      USING (EXISTS (
        SELECT 1 FROM public.user_algorithm_profiles p
        WHERE p.id = profile_id AND p.user_id = auth.uid()
      ))
      WITH CHECK (EXISTS (
        SELECT 1 FROM public.user_algorithm_profiles p
        WHERE p.id = profile_id AND p.user_id = auth.uid()
      ));
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'user_algorithm_data_permissions'
      AND policyname = 'user_algorithm_data_permissions_own'
  ) THEN
    CREATE POLICY user_algorithm_data_permissions_own ON public.user_algorithm_data_permissions
      FOR ALL TO authenticated
      USING (EXISTS (
        SELECT 1 FROM public.user_algorithm_profiles p
        WHERE p.id = profile_id AND p.user_id = auth.uid()
      ))
      WITH CHECK (EXISTS (
        SELECT 1 FROM public.user_algorithm_profiles p
        WHERE p.id = profile_id AND p.user_id = auth.uid()
      ));
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = 'user_algorithm_temporary_preferences'
      AND policyname = 'user_algorithm_temporary_preferences_own'
  ) THEN
    CREATE POLICY user_algorithm_temporary_preferences_own ON public.user_algorithm_temporary_preferences
      FOR ALL TO authenticated
      USING (EXISTS (
        SELECT 1 FROM public.user_algorithm_profiles p
        WHERE p.id = profile_id AND p.user_id = auth.uid()
      ))
      WITH CHECK (EXISTS (
        SELECT 1 FROM public.user_algorithm_profiles p
        WHERE p.id = profile_id AND p.user_id = auth.uid()
      ));
  END IF;
END $$;

COMMIT;
