CREATE TABLE IF NOT EXISTS fallback_message_pins (
  room_kind TEXT NOT NULL CHECK (room_kind IN ('chat', 'group', 'community')),
  room_id TEXT NOT NULL,
  message_id TEXT NOT NULL,
  pinned_by TEXT NOT NULL,
  pinned_by_name TEXT NOT NULL DEFAULT '',
  pinned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (room_kind, room_id, message_id)
);
ALTER TABLE fallback_message_pins ADD COLUMN IF NOT EXISTS pinned_by_name TEXT NOT NULL DEFAULT '';
CREATE INDEX IF NOT EXISTS idx_fallback_message_pins_room
  ON fallback_message_pins(room_kind, room_id, pinned_at DESC);

CREATE TABLE IF NOT EXISTS fallback_message_pin_events (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  room_kind TEXT NOT NULL,
  room_id TEXT NOT NULL,
  message_id TEXT NOT NULL,
  actor_id TEXT NOT NULL,
  action TEXT NOT NULL CHECK (action IN ('pin', 'unpin')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS fallback_saved_messages (
  user_id TEXT NOT NULL,
  room_kind TEXT NOT NULL CHECK (room_kind IN ('chat', 'group', 'community')),
  room_id TEXT NOT NULL,
  message_id TEXT NOT NULL,
  label TEXT NOT NULL DEFAULT '',
  saved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (user_id, room_kind, room_id, message_id)
);
CREATE INDEX IF NOT EXISTS idx_fallback_saved_messages_user
  ON fallback_saved_messages(user_id, saved_at DESC);
