-- Same idempotent schema as ensureProfileMusicTables. IDs use existing profile/account identities.
BEGIN;
CREATE TABLE IF NOT EXISTS fallback_profile_music_reactions (
  owner_id TEXT NOT NULL, music_key TEXT NOT NULL, actor_id TEXT NOT NULL,
  reaction TEXT CHECK (reaction IS NULL OR reaction IN ('LIKE','LOVE','FIRE','HAHA','WOW','SAD')),
  notified_at TIMESTAMPTZ, updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY(owner_id,music_key,actor_id)
);
CREATE INDEX IF NOT EXISTS idx_profile_music_reaction_summary
  ON fallback_profile_music_reactions(owner_id,music_key,reaction) WHERE reaction IS NOT NULL;
CREATE TABLE IF NOT EXISTS fallback_profile_music_preferences (
  owner_id TEXT NOT NULL, music_key TEXT NOT NULL,
  presentation TEXT NOT NULL DEFAULT 'audio' CHECK (presentation IN ('audio','disc')),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), PRIMARY KEY(owner_id,music_key)
);
COMMIT;
