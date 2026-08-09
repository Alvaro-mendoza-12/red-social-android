BEGIN;

CREATE TABLE IF NOT EXISTS fallback_game_catalog (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  image_url TEXT,
  platforms JSONB NOT NULL DEFAULT '[]'::jsonb,
  ranks JSONB NOT NULL DEFAULT '[]'::jsonb,
  roles JSONB NOT NULL DEFAULT '[]'::jsonb,
  cross_play BOOLEAN NOT NULL DEFAULT FALSE,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_fallback_game_catalog_active
  ON fallback_game_catalog(active, sort_order, lower(name));

CREATE TABLE IF NOT EXISTS fallback_gaming_profiles (
  user_id UUID PRIMARY KEY REFERENCES fallback_users(id) ON DELETE CASCADE,
  gamer_bio TEXT NOT NULL DEFAULT '',
  region_code TEXT NOT NULL DEFAULT '',
  languages TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  play_style TEXT NOT NULL DEFAULT 'casual'
    CHECK (play_style IN ('casual', 'competitive', 'mixed')),
  voice_preference BOOLEAN NOT NULL DEFAULT FALSE,
  discovery_level SMALLINT NOT NULL DEFAULT 50
    CHECK (discovery_level BETWEEN 0 AND 100),
  discoverable BOOLEAN NOT NULL DEFAULT FALSE,
  adult_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
  game_id_visibility TEXT NOT NULL DEFAULT 'connections'
    CHECK (game_id_visibility IN ('private', 'connections', 'public')),
  photos_visibility TEXT NOT NULL DEFAULT 'connections'
    CHECK (photos_visibility IN ('private', 'connections', 'public')),
  availability_visibility TEXT NOT NULL DEFAULT 'connections'
    CHECK (availability_visibility IN ('private', 'connections', 'public')),
  photo_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
  timezone TEXT NOT NULL DEFAULT 'UTC',
  version BIGINT NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_fallback_gaming_profiles_discovery
  ON fallback_gaming_profiles(discoverable, adult_confirmed, lower(region_code), updated_at DESC);

CREATE TABLE IF NOT EXISTS fallback_user_games (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
  game_id TEXT NOT NULL REFERENCES fallback_game_catalog(id) ON DELETE RESTRICT,
  game_user_id TEXT NOT NULL DEFAULT '',
  platform TEXT NOT NULL,
  rank_key TEXT NOT NULL DEFAULT '',
  level_key TEXT NOT NULL DEFAULT '',
  role_key TEXT NOT NULL DEFAULT '',
  visibility TEXT NOT NULL DEFAULT 'connections'
    CHECK (visibility IN ('private', 'connections', 'public')),
  is_primary BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (user_id, game_id, platform)
);

CREATE INDEX IF NOT EXISTS idx_fallback_user_games_search
  ON fallback_user_games(game_id, lower(platform), lower(rank_key), user_id);

CREATE TABLE IF NOT EXISTS fallback_gaming_availability (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
  day_of_week SMALLINT NOT NULL CHECK (day_of_week BETWEEN 0 AND 6),
  start_minute SMALLINT NOT NULL CHECK (start_minute BETWEEN 0 AND 1439),
  end_minute SMALLINT NOT NULL CHECK (end_minute BETWEEN 0 AND 1439),
  timezone TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (user_id, day_of_week, start_minute, end_minute, timezone)
);

CREATE INDEX IF NOT EXISTS idx_fallback_gaming_availability_user_day
  ON fallback_gaming_availability(user_id, day_of_week, start_minute);

CREATE TABLE IF NOT EXISTS fallback_gaming_preferences (
  user_id UUID PRIMARY KEY REFERENCES fallback_users(id) ON DELETE CASCADE,
  game_ids TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  platforms TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  rank_tolerance SMALLINT NOT NULL DEFAULT 2 CHECK (rank_tolerance BETWEEN 0 AND 10),
  roles TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  styles TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  languages TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  regions TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
  voice_required BOOLEAN NOT NULL DEFAULT FALSE,
  discovery_level SMALLINT NOT NULL DEFAULT 50 CHECK (discovery_level BETWEEN 0 AND 100),
  version BIGINT NOT NULL DEFAULT 1,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS fallback_gaming_connection_requests (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  requester_user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
  recipient_user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
  status TEXT NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED', 'BLOCKED')),
  message TEXT NOT NULL DEFAULT '',
  idempotency_key TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  responded_at TIMESTAMPTZ,
  CHECK (requester_user_id <> recipient_user_id),
  UNIQUE (requester_user_id, idempotency_key)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_fallback_gaming_connection_active_pair
  ON fallback_gaming_connection_requests(
    LEAST(requester_user_id, recipient_user_id),
    GREATEST(requester_user_id, recipient_user_id)
  ) WHERE status IN ('PENDING', 'ACCEPTED');

CREATE INDEX IF NOT EXISTS idx_fallback_gaming_connection_inbox
  ON fallback_gaming_connection_requests(recipient_user_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS fallback_matchmaking_sessions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
  status TEXT NOT NULL DEFAULT 'SEARCHING'
    CHECK (status IN ('SEARCHING', 'MATCH_FOUND', 'ACCEPTED', 'SKIPPED', 'TIMEOUT', 'CANCELLED', 'ERROR')),
  criteria JSONB NOT NULL DEFAULT '{}'::jsonb,
  current_match_id UUID,
  idempotency_key TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (user_id, idempotency_key)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_fallback_matchmaking_active_user
  ON fallback_matchmaking_sessions(user_id)
  WHERE status IN ('SEARCHING', 'MATCH_FOUND');

CREATE INDEX IF NOT EXISTS idx_fallback_matchmaking_status_expiry
  ON fallback_matchmaking_sessions(status, expires_at);

CREATE TABLE IF NOT EXISTS fallback_gaming_matches (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id UUID NOT NULL REFERENCES fallback_matchmaking_sessions(id) ON DELETE CASCADE,
  user_a_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
  user_b_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
  score SMALLINT NOT NULL CHECK (score BETWEEN 0 AND 100),
  reasons JSONB NOT NULL DEFAULT '[]'::jsonb,
  status TEXT NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING', 'ACCEPTED', 'SKIPPED', 'TIMEOUT', 'CANCELLED', 'BLOCKED')),
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CHECK (user_a_id <> user_b_id),
  UNIQUE (session_id, user_b_id)
);

ALTER TABLE fallback_matchmaking_sessions
  DROP CONSTRAINT IF EXISTS fallback_matchmaking_sessions_current_match_id_fkey;
ALTER TABLE fallback_matchmaking_sessions
  ADD CONSTRAINT fallback_matchmaking_sessions_current_match_id_fkey
  FOREIGN KEY (current_match_id) REFERENCES fallback_gaming_matches(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_fallback_gaming_matches_user_a
  ON fallback_gaming_matches(user_a_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_fallback_gaming_matches_user_b
  ON fallback_gaming_matches(user_b_id, status, created_at DESC);

CREATE TABLE IF NOT EXISTS fallback_gaming_match_decisions (
  match_id UUID NOT NULL REFERENCES fallback_gaming_matches(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
  decision TEXT NOT NULL CHECK (decision IN ('ACCEPT', 'SKIP')),
  reason TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (match_id, user_id)
);

INSERT INTO fallback_game_catalog(id, name, description, platforms, ranks, roles, cross_play, sort_order)
VALUES
  ('valorant', 'Valorant', 'Shooter tactico competitivo', '["PC"]', '["iron","bronze","silver","gold","platinum","diamond","ascendant","immortal","radiant"]', '["duelist","initiator","controller","sentinel"]', FALSE, 10),
  ('league-of-legends', 'League of Legends', 'MOBA competitivo por equipos', '["PC"]', '["iron","bronze","silver","gold","platinum","emerald","diamond","master","grandmaster","challenger"]', '["top","jungle","mid","adc","support"]', FALSE, 20),
  ('fortnite', 'Fortnite', 'Battle royale y modos creativos', '["PC","PlayStation","Xbox","Switch","Mobile"]', '[]', '["builder","fragger","support","igl"]', TRUE, 30),
  ('minecraft', 'Minecraft', 'Construccion, supervivencia y servidores', '["PC","PlayStation","Xbox","Switch","Mobile"]', '[]', '["builder","explorer","redstone","pvp"]', TRUE, 40),
  ('warzone', 'Call of Duty: Warzone', 'Battle royale de Call of Duty', '["PC","PlayStation","Xbox"]', '["bronze","silver","gold","platinum","diamond","crimson","iridescent"]', '["entry","support","sniper","igl"]', TRUE, 50),
  ('mobile-legends', 'Mobile Legends', 'MOBA para dispositivos moviles', '["Mobile"]', '["warrior","elite","master","grandmaster","epic","legend","mythic"]', '["tank","fighter","assassin","mage","marksman","support"]', FALSE, 60),
  ('free-fire', 'Free Fire', 'Battle royale para dispositivos moviles', '["Mobile"]', '["bronze","silver","gold","platinum","diamond","heroic","master"]', '["rusher","support","sniper","igl"]', FALSE, 70),
  ('rocket-league', 'Rocket League', 'Futbol con vehiculos', '["PC","PlayStation","Xbox","Switch"]', '["bronze","silver","gold","platinum","diamond","champion","grand-champion","supersonic-legend"]', '["striker","defender","all-rounder"]', TRUE, 80)
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  description = EXCLUDED.description,
  platforms = EXCLUDED.platforms,
  ranks = EXCLUDED.ranks,
  roles = EXCLUDED.roles,
  cross_play = EXCLUDED.cross_play,
  sort_order = EXCLUDED.sort_order,
  updated_at = NOW();

COMMIT;
