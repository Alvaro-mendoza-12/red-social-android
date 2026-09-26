CREATE TABLE IF NOT EXISTS fallback_video_watch_later (
  user_id TEXT NOT NULL,
  video_id TEXT NOT NULL,
  added_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (user_id, video_id)
);
CREATE INDEX IF NOT EXISTS idx_fallback_video_watch_later_cursor
  ON fallback_video_watch_later(user_id, added_at DESC, video_id DESC);
