-- ==========================================================
-- CONSOLIDATED FIX: DUPLICATES & MESSAGING STABILITY
-- ==========================================================

-- 0. Ensure extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. CLEAN DUPLICATES (Communities)
-- Keep only the oldest community for each (name, creator_id)
DELETE FROM public.communities
WHERE id IN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (PARTITION BY TRIM(LOWER(name)), creator_id ORDER BY created_at ASC) as row_num
        FROM public.communities
    ) t
    WHERE t.row_num > 1
);

-- 2. ADD UNIQUE CONSTRAINT (Communities)
-- Using a filter to ignore deleted if necessary, or just a simple unique
ALTER TABLE public.communities DROP CONSTRAINT IF EXISTS communities_name_creator_id_key;
ALTER TABLE public.communities ADD CONSTRAINT communities_name_creator_id_key UNIQUE (name, creator_id);

-- 3. CLEAN DUPLICATES (Groups)
DELETE FROM public.groups
WHERE id IN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (PARTITION BY TRIM(LOWER(name)), creator_id ORDER BY created_at ASC) as row_num
        FROM public.groups
    ) t
    WHERE t.row_num > 1
);

-- 4. ADD UNIQUE CONSTRAINT (Groups)
ALTER TABLE public.groups DROP CONSTRAINT IF EXISTS groups_name_creator_id_key;
ALTER TABLE public.groups ADD CONSTRAINT groups_name_creator_id_key UNIQUE (name, creator_id);

-- 5. ENSURE MEDIA AND SENDER COLUMNS EXIST
ALTER TABLE public.community_messages ADD COLUMN IF NOT EXISTS media_url TEXT;
ALTER TABLE public.community_messages ADD COLUMN IF NOT EXISTS sender_name TEXT;
ALTER TABLE public.community_messages ADD COLUMN IF NOT EXISTS sender_image TEXT;

ALTER TABLE public.group_messages ADD COLUMN IF NOT EXISTS media_url TEXT;
ALTER TABLE public.group_messages ADD COLUMN IF NOT EXISTS sender_name TEXT;
ALTER TABLE public.group_messages ADD COLUMN IF NOT EXISTS sender_image TEXT;

-- 6. STABILIZE LAST MESSAGE TRIGGERS

-- Function for Communities
CREATE OR REPLACE FUNCTION public.handle_community_last_message()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE public.communities
    SET last_message = CASE 
            WHEN NEW.content_type = 'text' THEN NEW.content 
            WHEN NEW.content_type = 'image' THEN '📷 Foto'
            WHEN NEW.content_type = 'video' THEN '🎥 Video'
            WHEN NEW.content_type = 'audio' THEN '🎤 Audio'
            ELSE 'Archivo adjunto'
        END,
        last_message_at = NEW.created_at
    WHERE id = NEW.community_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_community_message_sent ON public.community_messages;
CREATE TRIGGER on_community_message_sent
    AFTER INSERT ON public.community_messages
    FOR EACH ROW EXECUTE FUNCTION public.handle_community_last_message();

-- Function for Groups
CREATE OR REPLACE FUNCTION public.handle_group_last_message()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE public.groups
    SET last_message = CASE 
            WHEN NEW.content_type = 'text' THEN NEW.content 
            WHEN NEW.content_type = 'image' THEN '📷 Foto'
            WHEN NEW.content_type = 'video' THEN '🎥 Video'
            WHEN NEW.content_type = 'audio' THEN '🎤 Audio'
            ELSE 'Archivo adjunto'
        END,
        last_message_at = NEW.created_at
    WHERE id = NEW.group_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_group_message_sent ON public.group_messages;
CREATE TRIGGER on_group_message_sent
    AFTER INSERT ON public.group_messages
    FOR EACH ROW EXECUTE FUNCTION public.handle_group_last_message();

-- Function for 1-to-1 Chats (Messages Table)
CREATE OR REPLACE FUNCTION public.handle_chat_last_message()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE public.chats
    SET last_message = CASE 
            WHEN NEW.content_type = 'text' THEN NEW.content 
            WHEN NEW.content_type = 'image' THEN '📷 Foto'
            WHEN NEW.content_type = 'video' THEN '🎥 Video'
            WHEN NEW.content_type = 'audio' THEN '🎤 Audio'
            ELSE 'Archivo adjunto'
        END,
        last_message_sender_id = NEW.sender_id,
        last_message_at = NEW.created_at,
        last_message_id = NEW.id,
        updated_at = NOW()
    WHERE id = NEW.chat_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_new_message ON public.messages;
CREATE TRIGGER on_new_message
    AFTER INSERT ON public.messages
    FOR EACH ROW
    EXECUTE FUNCTION public.handle_chat_last_message();

-- 7. RE-STABILIZE RLS POLICIES (Avoid recursion and permission issues)

-- Community Messages
ALTER TABLE public.community_messages ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "community_messages_select_policy" ON public.community_messages;
CREATE POLICY "community_messages_select_policy" ON public.community_messages
    FOR SELECT TO authenticated USING (true);

DROP POLICY IF EXISTS "community_messages_insert_policy" ON public.community_messages;
CREATE POLICY "community_messages_insert_policy" ON public.community_messages
    FOR INSERT TO authenticated WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.community_members
            WHERE community_id = public.community_messages.community_id 
            AND user_id = auth.uid()
        )
    );

-- Group Messages
ALTER TABLE public.group_messages ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "group_messages_select_policy" ON public.group_messages;
CREATE POLICY "group_messages_select_policy" ON public.group_messages
    FOR SELECT TO authenticated USING (true);

DROP POLICY IF EXISTS "group_messages_insert_policy" ON public.group_messages;
CREATE POLICY "group_messages_insert_policy" ON public.group_messages
    FOR INSERT TO authenticated WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.group_members
            WHERE group_id = public.group_messages.group_id 
            AND user_id = auth.uid()
        )
    );

-- 8. ENABLE REALTIME FOR ALL TABLES SAFELY
DO $$
DECLARE
    tbl_name TEXT;
    tables_to_add TEXT[] := ARRAY['communities', 'groups', 'community_messages', 'group_messages', 'messages', 'chats'];
BEGIN
    FOREACH tbl_name IN ARRAY tables_to_add
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_publication_tables 
            WHERE pubname = 'supabase_realtime' 
            AND schemaname = 'public' 
            AND tablename = tbl_name
        ) THEN
            EXECUTE format('ALTER PUBLICATION supabase_realtime ADD TABLE public.%I', tbl_name);
        END IF;
    END LOOP;
END $$;

-- 9. REFRESH SCHEMA
NOTIFY pgrst, 'reload schema';

SELECT '✅ Estabilización completa: Duplicados eliminados, mensajería sincronizada, RLS corregido y REALTIME activado.' as resultado;
