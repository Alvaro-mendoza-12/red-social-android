BEGIN;

CREATE TABLE IF NOT EXISTS fallback_live_point_settings (
  live_id UUID PRIMARY KEY REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
  creator_id TEXT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  interval_seconds INT NOT NULL DEFAULT 300 CHECK (interval_seconds BETWEEN 60 AND 3600),
  points_per_interval INT NOT NULL DEFAULT 10 CHECK (points_per_interval BETWEEN 1 AND 1000),
  daily_cap INT NOT NULL DEFAULT 2000 CHECK (daily_cap BETWEEN 1 AND 100000),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS fallback_channel_points_balances (
  live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
  creator_id TEXT NOT NULL,
  viewer_id TEXT NOT NULL,
  balance INT NOT NULL DEFAULT 0 CHECK (balance >= 0),
  earned_total BIGINT NOT NULL DEFAULT 0 CHECK (earned_total >= 0),
  redeemed_total BIGINT NOT NULL DEFAULT 0 CHECK (redeemed_total >= 0),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (live_id, viewer_id)
);

CREATE TABLE IF NOT EXISTS fallback_channel_points_earnings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
  viewer_id TEXT NOT NULL,
  event_type TEXT NOT NULL DEFAULT 'watch_interval',
  interval_id TEXT NOT NULL,
  points INT NOT NULL CHECK (points > 0),
  idempotency_key TEXT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (live_id, viewer_id, event_type, interval_id),
  UNIQUE (idempotency_key)
);

CREATE TABLE IF NOT EXISTS fallback_channel_point_rewards (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
  creator_id TEXT NOT NULL,
  title TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  cost INT NOT NULL CHECK (cost > 0),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  stock INT CHECK (stock IS NULL OR stock >= 0),
  cooldown_seconds INT NOT NULL DEFAULT 0 CHECK (cooldown_seconds BETWEEN 0 AND 86400),
  version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_fallback_channel_point_rewards_live
  ON fallback_channel_point_rewards(live_id, active, cost, created_at);

CREATE TABLE IF NOT EXISTS fallback_channel_point_sessions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
  viewer_id TEXT NOT NULL,
  session_nonce TEXT NOT NULL UNIQUE,
  token_hash TEXT NOT NULL UNIQUE,
  device_hash TEXT,
  joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_rewarded_interval INT NOT NULL DEFAULT 0 CHECK (last_rewarded_interval >= 0),
  state TEXT NOT NULL DEFAULT 'active' CHECK (state IN ('active', 'ended', 'expired')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_points_active_session
  ON fallback_channel_point_sessions(live_id, lower(viewer_id)) WHERE state = 'active';

CREATE TABLE IF NOT EXISTS fallback_channel_point_redemptions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
  reward_id UUID NOT NULL REFERENCES fallback_channel_point_rewards(id) ON DELETE CASCADE,
  viewer_id TEXT NOT NULL,
  cost INT NOT NULL CHECK (cost > 0),
  status TEXT NOT NULL DEFAULT 'requested'
    CHECK (status IN ('requested', 'approved', 'rejected', 'completed', 'canceled')),
  request_nonce TEXT NOT NULL,
  refunded_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (live_id, request_nonce)
);

CREATE INDEX IF NOT EXISTS idx_fallback_points_redemptions_live
  ON fallback_channel_point_redemptions(live_id, status, created_at DESC);

COMMIT;
