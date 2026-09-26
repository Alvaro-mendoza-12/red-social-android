-- PlayMatch identity is intentionally separate from the user's social profile.
ALTER TABLE fallback_gaming_profiles
  ADD COLUMN IF NOT EXISTS gamer_display_name TEXT;

ALTER TABLE fallback_gaming_profiles
  ADD COLUMN IF NOT EXISTS gamer_avatar_url TEXT;

ALTER TABLE fallback_gaming_profiles
  ADD COLUMN IF NOT EXISTS gamer_avatar_media_type TEXT;

COMMENT ON COLUMN fallback_gaming_profiles.gamer_display_name IS
  'Display name used only inside PlayMatch; null falls back to the social profile until configured.';

COMMENT ON COLUMN fallback_gaming_profiles.gamer_avatar_url IS
  'Avatar used only inside PlayMatch; null falls back to the social profile until configured.';

COMMENT ON COLUMN fallback_gaming_profiles.gamer_avatar_media_type IS
  'Media type for the PlayMatch avatar (image, gif or video); null inherits the social profile type.';
