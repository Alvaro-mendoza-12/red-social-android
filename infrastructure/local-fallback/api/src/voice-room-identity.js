'use strict'

// A short code locates a room. It never replaces its membership/password checks.
async function ensureVoiceRoomCodes(pool) {
  await pool.query(`DO $voice_codes$
  BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('novvin-voice-room-codes'));
    CREATE SEQUENCE IF NOT EXISTS fallback_voice_room_code_seq
      MINVALUE 100000 MAXVALUE 9999999 START WITH 100000 NO CYCLE;
    ALTER TABLE fallback_voice_rooms ADD COLUMN IF NOT EXISTS legacy_readable_id TEXT;
    PERFORM setval('fallback_voice_room_code_seq', GREATEST(
      99999,
      (SELECT last_value FROM fallback_voice_room_code_seq),
      COALESCE((SELECT MAX(readable_id::bigint) FROM fallback_voice_rooms
        WHERE readable_id ~ '^[1-9][0-9]{5,6}$'), 99999)
    ), TRUE);
    WITH ranked AS (
      SELECT id, row_number() OVER (PARTITION BY readable_id ORDER BY created_at, id) AS position
      FROM fallback_voice_rooms
    )
    UPDATE fallback_voice_rooms room
      SET legacy_readable_id = COALESCE(room.legacy_readable_id, room.readable_id), readable_id = NULL
      FROM ranked
      WHERE room.id = ranked.id AND (room.readable_id IS NULL
        OR room.readable_id !~ '^[1-9][0-9]{5,6}$' OR ranked.position > 1);
    UPDATE fallback_voice_rooms SET readable_id = nextval('fallback_voice_room_code_seq')::text
      WHERE readable_id IS NULL;
    ALTER TABLE fallback_voice_rooms ALTER COLUMN readable_id
      SET DEFAULT nextval('fallback_voice_room_code_seq')::text;
    ALTER TABLE fallback_voice_rooms ALTER COLUMN readable_id SET NOT NULL;
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_voice_rooms_short_code
      ON fallback_voice_rooms (lower(readable_id));
    CREATE INDEX IF NOT EXISTS idx_fallback_voice_rooms_uuid_alias
      ON fallback_voice_rooms (lower(id::text));
    CREATE INDEX IF NOT EXISTS idx_fallback_voice_rooms_legacy_alias
      ON fallback_voice_rooms (lower(legacy_readable_id)) WHERE legacy_readable_id IS NOT NULL;
    CREATE INDEX IF NOT EXISTS idx_fallback_voice_rooms_channel_alias
      ON fallback_voice_rooms (lower(agora_channel_name));
  END $voice_codes$`)
}

module.exports = { ensureVoiceRoomCodes }
