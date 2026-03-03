-- =====================================================
-- FORCE REALTIME ENABLEMENT FOR NOTIFICATIONS
-- Run this if real-time notifications are not appearing
-- =====================================================

-- 1. Ensure Replica Identity is FULL
-- This ensures the entire record (including recipient_id) is sent in the Realtime payload
ALTER TABLE public.notifications REPLICA IDENTITY FULL;

-- 2. Ensure the table is in the Realtime publication
-- First, remove it if it exists (to avoid errors) then add it back
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' 
        AND schemaname = 'public' 
        AND tablename = 'notifications'
    ) THEN
        ALTER PUBLICATION supabase_realtime DROP TABLE public.notifications;
    END IF;
END $$;

ALTER PUBLICATION supabase_realtime ADD TABLE public.notifications;

-- 3. Verify RLS for Realtime
-- The SELECT policy is what allows Realtime to filter and send events to the right user.
-- It already exists, but "FOR ALL" can sometimes be more robust for Realtime checks.
-- For now, we assume the existing SELECT policy is sufficient.

-- 4. Grant access to authenticated users to use Realtime on this table (internal Supabase requirement)
GRANT SELECT ON public.notifications TO authenticated;
GRANT SELECT ON public.notifications TO anon;
