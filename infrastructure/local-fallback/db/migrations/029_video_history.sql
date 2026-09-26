CREATE TABLE IF NOT EXISTS fallback_video_playback_history (
  user_id TEXT NOT NULL,
  video_id TEXT NOT NULL,
  position_ms BIGINT NOT NULL DEFAULT 0,
  duration_ms BIGINT NOT NULL DEFAULT 0,
  viewed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (user_id, video_id)
);
CREATE INDEX IF NOT EXISTS idx_fallback_video_history_cursor
  ON fallback_video_playback_history(user_id, viewed_at DESC, video_id DESC);
