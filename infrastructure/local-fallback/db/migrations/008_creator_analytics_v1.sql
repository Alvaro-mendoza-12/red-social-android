-- Estadisticas privadas para creadores de videos y directos (v1).
--
-- Migracion aditiva e idempotente. No habilita la funcion: el backend debe
-- conservar CREATOR_ANALYTICS_ENABLED=false hasta completar el wiring y las
-- pruebas de privacidad. Los espectadores/donantes se guardan unicamente como
-- HMAC; nunca se persiste su identificador original en esta tabla.

CREATE TABLE IF NOT EXISTS fallback_creator_analytics_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    idempotency_hash CHAR(64) NOT NULL UNIQUE,
    content_type TEXT NOT NULL CHECK (content_type IN ('video', 'live')),
    content_id UUID NOT NULL,
    event_type TEXT NOT NULL CHECK (event_type IN (
        'video_view',
        'video_watch',
        'video_like',
        'video_comment',
        'video_share',
        'video_save',
        'video_follow',
        'live_join',
        'live_leave',
        'live_presence',
        'live_follow',
        'live_comment',
        'live_like',
        'live_gift'
    )),
    actor_hash CHAR(64),
    session_hash CHAR(64),
    country_code CHAR(2) CHECK (country_code IS NULL OR country_code ~ '^[A-Z]{2}$'),
    watch_ms BIGINT CHECK (watch_ms IS NULL OR watch_ms BETWEEN 0 AND 86400000),
    duration_ms BIGINT CHECK (duration_ms IS NULL OR duration_ms BETWEEN 1 AND 86400000),
    concurrent_count INT CHECK (concurrent_count IS NULL OR concurrent_count BETWEEN 0 AND 10000000),
    value_coins BIGINT CHECK (value_coins IS NULL OR value_coins BETWEEN 0 AND 1000000000000),
    occurred_at TIMESTAMPTZ NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (
        (content_type = 'video' AND event_type LIKE 'video_%') OR
        (content_type = 'live' AND event_type LIKE 'live_%')
    ),
    CHECK (event_type <> 'video_watch' OR (watch_ms IS NOT NULL AND duration_ms IS NOT NULL)),
    CHECK (event_type <> 'live_presence' OR concurrent_count IS NOT NULL),
    CHECK (event_type <> 'live_gift' OR value_coins IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_creator_analytics_content_time
    ON fallback_creator_analytics_events(content_type, content_id, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_analytics_content_event_time
    ON fallback_creator_analytics_events(content_type, content_id, event_type, occurred_at DESC);

CREATE INDEX IF NOT EXISTS idx_creator_analytics_unique_actor
    ON fallback_creator_analytics_events(content_type, content_id, actor_hash)
    WHERE actor_hash IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_creator_analytics_live_donors
    ON fallback_creator_analytics_events(content_id, actor_hash, value_coins DESC)
    WHERE content_type = 'live' AND event_type = 'live_gift' AND actor_hash IS NOT NULL;
