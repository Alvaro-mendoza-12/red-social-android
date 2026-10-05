-- Idempotent Ubuntu migration; applied under the existing API schema lock.
CREATE TABLE IF NOT EXISTS fallback_raffles (
  id UUID PRIMARY KEY,
  room_kind TEXT NOT NULL CHECK (room_kind IN ('group','community')),
  room_id TEXT NOT NULL,
  name TEXT NOT NULL CHECK (char_length(name) BETWEEN 1 AND 120),
  description TEXT NOT NULL DEFAULT '',
  creator_id TEXT NOT NULL,
  creator_local_id TEXT NOT NULL,
  creator_name TEXT NOT NULL,
  creation_key UUID NOT NULL,
  creation_hash TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('draft','prepared','running','completed','cancelled')),
  version INTEGER NOT NULL DEFAULT 0,
  participant_count INTEGER NOT NULL CHECK (participant_count BETWEEN 1 AND 1000),
  round_count INTEGER NOT NULL DEFAULT 0,
  message_id UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (creator_local_id, creation_key)
);
CREATE INDEX IF NOT EXISTS idx_raffles_room_created ON fallback_raffles(room_kind,room_id,created_at DESC,id DESC);
CREATE TABLE IF NOT EXISTS fallback_raffle_participants (
  id UUID PRIMARY KEY,
  raffle_id UUID NOT NULL REFERENCES fallback_raffles(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('REGISTERED_MEMBER','MANUAL_PARTICIPANT')),
  user_id TEXT,
  name TEXT NOT NULL,
  avatar_url TEXT NOT NULL DEFAULT '',
  ordinal INTEGER NOT NULL,
  CHECK ((kind='REGISTERED_MEMBER' AND user_id IS NOT NULL) OR (kind='MANUAL_PARTICIPANT' AND user_id IS NULL)),
  UNIQUE (raffle_id,ordinal),
  UNIQUE (raffle_id,id)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_raffle_registered_unique ON fallback_raffle_participants(raffle_id,user_id) WHERE user_id IS NOT NULL;
CREATE TABLE IF NOT EXISTS fallback_raffle_rounds (
  id UUID PRIMARY KEY,
  raffle_id UUID NOT NULL REFERENCES fallback_raffles(id) ON DELETE CASCADE,
  number INTEGER NOT NULL CHECK (number>0),
  status TEXT NOT NULL CHECK (status IN ('running','completed','cancelled')),
  participants JSONB NOT NULL,
  initiated_by TEXT NOT NULL,
  initiator_local_id TEXT NOT NULL,
  automatic BOOLEAN NOT NULL DEFAULT FALSE,
  next_draw_at TIMESTAMPTZ,
  started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  finished_at TIMESTAMPTZ,
  UNIQUE (raffle_id,number),
  UNIQUE (raffle_id,id)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_raffles_one_running_round ON fallback_raffle_rounds(raffle_id) WHERE status='running';
CREATE INDEX IF NOT EXISTS idx_raffles_due ON fallback_raffle_rounds(next_draw_at) WHERE status='running' AND automatic=TRUE;
CREATE TABLE IF NOT EXISTS fallback_raffle_results (
  raffle_id UUID NOT NULL,
  round_id UUID NOT NULL,
  participant_id UUID NOT NULL,
  position INTEGER NOT NULL CHECK (position>0),
  selected_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (round_id,position),
  UNIQUE (round_id,participant_id),
  FOREIGN KEY (raffle_id,round_id) REFERENCES fallback_raffle_rounds(raffle_id,id) ON DELETE CASCADE,
  FOREIGN KEY (raffle_id,participant_id) REFERENCES fallback_raffle_participants(raffle_id,id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS fallback_raffle_commands (
  raffle_id UUID NOT NULL REFERENCES fallback_raffles(id) ON DELETE CASCADE,
  request_id UUID NOT NULL,
  actor_id TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  response JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (raffle_id,request_id)
);
