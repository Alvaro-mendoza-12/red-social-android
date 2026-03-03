-- SQL for enhancing Groups and Communities with Media and Moderation

-- 1. Addition of moderation setting in communities
ALTER TABLE communities 
ADD COLUMN IF NOT EXISTS only_admins_can_talk BOOLEAN DEFAULT FALSE;

-- 2. Ensure member roles exist
ALTER TABLE group_members ADD COLUMN IF NOT EXISTS role TEXT DEFAULT 'member';
ALTER TABLE community_members ADD COLUMN IF NOT EXISTS role TEXT DEFAULT 'member';

-- 3. Ensure group messages support media
ALTER TABLE group_messages 
ADD COLUMN IF NOT EXISTS content_type TEXT DEFAULT 'text',
ADD COLUMN IF NOT EXISTS media_url TEXT,
ADD COLUMN IF NOT EXISTS reply_to_id UUID REFERENCES group_messages(id),
ADD COLUMN IF NOT EXISTS is_edited BOOLEAN DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS deleted_for_all BOOLEAN DEFAULT FALSE;

-- 3. Ensure community messages support media
ALTER TABLE community_messages 
ADD COLUMN IF NOT EXISTS content_type TEXT DEFAULT 'text',
ADD COLUMN IF NOT EXISTS media_url TEXT,
ADD COLUMN IF NOT EXISTS reply_to_id UUID REFERENCES community_messages(id),
ADD COLUMN IF NOT EXISTS is_edited BOOLEAN DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS deleted_for_all BOOLEAN DEFAULT FALSE;

-- 4. Storage Buckets (Run these if they don't exist in Supabase Dashboard)
-- Note: 'group-media' and 'community-media' are the buckets used in the code.
-- These usually need to be created via the Supabase Dashboard, but here are the SQL commands if needed.
-- INSERT INTO storage.buckets (id, name, public) VALUES ('group-media', 'group-media', true) ON CONFLICT (id) DO NOTHING;
-- INSERT INTO storage.buckets (id, name, public) VALUES ('community-media', 'community-media', true) ON CONFLICT (id) DO NOTHING;

-- 5. Helper for Realtime (Ensure tables are in the realtime publication)
-- ALTER PUBLICATION supabase_realtime ADD TABLE communities;
-- ALTER PUBLICATION supabase_realtime ADD TABLE community_messages;
-- ALTER PUBLICATION supabase_realtime ADD TABLE groups;
-- ALTER PUBLICATION supabase_realtime ADD TABLE group_messages;
