-- User-owned recommendation configuration for the local/Ubuntu backend.
-- Additive, idempotent and fail-closed: rollout still requires USER_ALGORITHM_*
-- flags.  These tables intentionally contain no private messages, precise
-- location, age or gender fields.

CREATE TABLE IF NOT EXISTS fallback_user_algorithm_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id TEXT NOT NULL,
    name TEXT NOT NULL DEFAULT 'Para mí',
    slug TEXT NOT NULL DEFAULT 'for-you',
    mode TEXT NOT NULL DEFAULT 'manual'
        CHECK (mode IN ('manual', 'assisted', 'automatic')),
    is_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, slug)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_user_algorithm_default_profile
    ON fallback_user_algorithm_profiles(user_id) WHERE is_default;
CREATE INDEX IF NOT EXISTS idx_fallback_user_algorithm_profiles_owner
    ON fallback_user_algorithm_profiles(lower(user_id), is_default DESC, updated_at DESC);

CREATE TABLE IF NOT EXISTS fallback_user_algorithm_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID NOT NULL REFERENCES fallback_user_algorithm_profiles(id) ON DELETE CASCADE,
    surface TEXT NOT NULL DEFAULT 'global'
        CHECK (surface IN ('global', 'video', 'social', 'live', 'community', 'people', 'challenge', 'event')),
    dimension TEXT NOT NULL
        CHECK (dimension IN ('content', 'topic', 'subtopic', 'creator', 'format', 'audio', 'language', 'community', 'relationship')),
    subject_key TEXT NOT NULL,
    subject_label TEXT NOT NULL,
    score SMALLINT NOT NULL CHECK (score BETWEEN -100 AND 100),
    source TEXT NOT NULL DEFAULT 'explicit' CHECK (source = 'explicit'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(profile_id, surface, dimension, subject_key)
);
CREATE INDEX IF NOT EXISTS idx_fallback_algorithm_preferences_profile
    ON fallback_user_algorithm_preferences(profile_id, surface, dimension, score, updated_at DESC);

CREATE TABLE IF NOT EXISTS fallback_user_algorithm_feed_mixes (
    profile_id UUID NOT NULL REFERENCES fallback_user_algorithm_profiles(id) ON DELETE CASCADE,
    surface TEXT NOT NULL
        CHECK (surface IN ('global', 'video', 'social', 'live', 'community', 'people', 'challenge', 'event')),
    following_weight SMALLINT NOT NULL DEFAULT 40 CHECK (following_weight BETWEEN 0 AND 100),
    interest_weight SMALLINT NOT NULL DEFAULT 30 CHECK (interest_weight BETWEEN 0 AND 100),
    community_weight SMALLINT NOT NULL DEFAULT 10 CHECK (community_weight BETWEEN 0 AND 100),
    new_content_weight SMALLINT NOT NULL DEFAULT 10 CHECK (new_content_weight BETWEEN 0 AND 100),
    trend_weight SMALLINT NOT NULL DEFAULT 5 CHECK (trend_weight BETWEEN 0 AND 100),
    exploration_weight SMALLINT NOT NULL DEFAULT 5 CHECK (exploration_weight BETWEEN 0 AND 100),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(profile_id, surface),
    CHECK (following_weight + interest_weight + community_weight + new_content_weight + trend_weight + exploration_weight = 100)
);

CREATE TABLE IF NOT EXISTS fallback_user_algorithm_data_permissions (
    profile_id UUID PRIMARY KEY REFERENCES fallback_user_algorithm_profiles(id) ON DELETE CASCADE,
    use_likes BOOLEAN NOT NULL DEFAULT FALSE,
    use_follows BOOLEAN NOT NULL DEFAULT FALSE,
    use_searches BOOLEAN NOT NULL DEFAULT FALSE,
    use_watch_time BOOLEAN NOT NULL DEFAULT FALSE,
    use_scroll_velocity BOOLEAN NOT NULL DEFAULT FALSE,
    use_profile_visits BOOLEAN NOT NULL DEFAULT FALSE,
    use_public_interactions BOOLEAN NOT NULL DEFAULT FALSE,
    use_community_activity BOOLEAN NOT NULL DEFAULT FALSE,
    use_live_activity BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS fallback_user_algorithm_temporary_preferences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id UUID NOT NULL REFERENCES fallback_user_algorithm_profiles(id) ON DELETE CASCADE,
    surface TEXT NOT NULL DEFAULT 'global'
        CHECK (surface IN ('global', 'video', 'social', 'live', 'community', 'people', 'challenge', 'event')),
    dimension TEXT NOT NULL
        CHECK (dimension IN ('content', 'topic', 'subtopic', 'creator', 'format', 'audio', 'language', 'community', 'relationship')),
    subject_key TEXT NOT NULL,
    subject_label TEXT NOT NULL,
    score SMALLINT NOT NULL CHECK (score BETWEEN -100 AND 100),
    starts_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (expires_at > starts_at)
);
CREATE INDEX IF NOT EXISTS idx_fallback_algorithm_temporary_preferences_active
    ON fallback_user_algorithm_temporary_preferences(profile_id, starts_at, expires_at);

-- Classification is only written by trusted server processes; no HTTP route
-- accepts client supplied content classifications.
CREATE TABLE IF NOT EXISTS fallback_algorithm_content_classifications (
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
    classified_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(content_kind, content_id)
);
CREATE TABLE IF NOT EXISTS fallback_algorithm_content_topics (
    classification_id UUID NOT NULL REFERENCES fallback_algorithm_content_classifications(id) ON DELETE CASCADE,
    topic_key TEXT NOT NULL,
    subtopic_key TEXT NOT NULL DEFAULT '',
    confidence NUMERIC(4,3) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    source TEXT NOT NULL DEFAULT 'server' CHECK (source IN ('server', 'reviewed')),
    PRIMARY KEY(classification_id, topic_key, subtopic_key)
);
CREATE INDEX IF NOT EXISTS idx_fallback_algorithm_content_topics_lookup
    ON fallback_algorithm_content_topics(topic_key, subtopic_key, confidence DESC);
