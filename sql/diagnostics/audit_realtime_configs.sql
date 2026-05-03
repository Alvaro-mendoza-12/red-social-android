-- Ensure REPLICA IDENTITY FULL for all chat tables
-- This is critical for Supabase Realtime to include the full record in UPDATE/DELETE events

ALTER TABLE IF EXISTS public.messages REPLICA IDENTITY FULL;
ALTER TABLE IF EXISTS public.group_messages REPLICA IDENTITY FULL;
ALTER TABLE IF EXISTS public.community_messages REPLICA IDENTITY FULL;

-- Also verify publication contains these tables
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime') THEN
        CREATE PUBLICATION supabase_realtime;
    END IF;
    
    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'messages') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.messages;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'group_messages') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.group_messages;
    END IF;
    
    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'community_messages') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.community_messages;
    END IF;
END $$;
