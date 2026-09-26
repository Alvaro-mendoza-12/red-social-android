CREATE TABLE IF NOT EXISTS fallback_video_collections (
  id UUID PRIMARY KEY,
  user_id TEXT NOT NULL,
  name TEXT NOT NULL,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_video_collection_name
  ON fallback_video_collections(user_id, lower(name));
CREATE INDEX IF NOT EXISTS idx_fallback_video_collection_owner
  ON fallback_video_collections(user_id, sort_order, created_at);

CREATE TABLE IF NOT EXISTS fallback_video_collection_items (
  collection_id UUID NOT NULL REFERENCES fallback_video_collections(id) ON DELETE CASCADE,
  video_id TEXT NOT NULL,
  sort_order INTEGER NOT NULL DEFAULT 0,
  added_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (collection_id, video_id)
);
CREATE INDEX IF NOT EXISTS idx_fallback_video_collection_items_order
  ON fallback_video_collection_items(collection_id, sort_order, added_at, video_id);
