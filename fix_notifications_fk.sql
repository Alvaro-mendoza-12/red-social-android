-- FIX NOTIFICATIONS FOREIGN KEYS
-- Enables PostgREST joins between notifications and profiles
-- =====================================================

-- 1. DROP EXISTING CONSTRAINTS
-- These originally referenced auth.users which prevents direct joining with profiles in PostgREST
ALTER TABLE public.notifications DROP CONSTRAINT IF EXISTS notifications_recipient_id_fkey;
ALTER TABLE public.notifications DROP CONSTRAINT IF EXISTS notifications_actor_id_fkey;

-- 2. ADD CORRECT CONSTRAINTS
-- Reference public.profiles(id) instead. Logic remains the same (UUIDs match auth.users).
ALTER TABLE public.notifications ADD CONSTRAINT notifications_recipient_id_fkey 
    FOREIGN KEY (recipient_id) REFERENCES public.profiles(id) ON DELETE CASCADE;

ALTER TABLE public.notifications ADD CONSTRAINT notifications_actor_id_fkey 
    FOREIGN KEY (actor_id) REFERENCES public.profiles(id) ON DELETE CASCADE;

-- 3. ENSURE REALTIME IS ENABLED (Already done in schema but good to verify)
-- ALTER PUBLICATION supabase_realtime ADD TABLE public.notifications;
