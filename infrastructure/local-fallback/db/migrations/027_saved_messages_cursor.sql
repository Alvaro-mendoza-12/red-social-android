CREATE INDEX IF NOT EXISTS idx_fallback_saved_messages_cursor
  ON fallback_saved_messages(user_id, saved_at DESC, room_kind DESC, room_id DESC, message_id DESC);
