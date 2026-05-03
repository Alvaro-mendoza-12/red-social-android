-- =====================================================
-- DIAGNOSTIC SCRIPT FOR NOTIFICATIONS SYSTEM
-- =====================================================
-- Run this in Supabase SQL Editor to diagnose issues

-- 1. Check if notifications table exists and its structure
SELECT 
    column_name, 
    data_type, 
    is_nullable,
    column_default
FROM information_schema.columns 
WHERE table_name = 'notifications' 
  AND table_schema = 'public'
ORDER BY ordinal_position;

-- 2. Check Foreign Keys
SELECT
    tc.constraint_name,
    tc.table_name,
    kcu.column_name,
    ccu.table_name AS foreign_table_name,
    ccu.column_name AS foreign_column_name
FROM information_schema.table_constraints AS tc
JOIN information_schema.key_column_usage AS kcu
  ON tc.constraint_name = kcu.constraint_name
  AND tc.table_schema = kcu.table_schema
JOIN information_schema.constraint_column_usage AS ccu
  ON ccu.constraint_name = tc.constraint_name
  AND ccu.table_schema = tc.table_schema
WHERE tc.constraint_type = 'FOREIGN KEY' 
  AND tc.table_name = 'notifications';

-- 3. Check RLS Policies
SELECT 
    schemaname,
    tablename,
    policyname,
    permissive,
    roles,
    cmd,
    qual,
    with_check
FROM pg_policies 
WHERE tablename = 'notifications';

-- 4. Check if RLS is enabled
SELECT 
    schemaname,
    tablename,
    rowsecurity
FROM pg_tables 
WHERE tablename = 'notifications';

-- 5. Check existing triggers
SELECT 
    trigger_name,
    event_manipulation,
    event_object_table,
    action_statement
FROM information_schema.triggers
WHERE trigger_name LIKE '%notif%'
   OR event_object_table IN ('follows', 'post_likes', 'video_likes');

-- 6. Count existing notifications (as superuser, this bypasses RLS)
SELECT COUNT(*) as total_notifications FROM public.notifications;

-- 7. Check recent notifications
SELECT 
    id,
    recipient_id,
    actor_id,
    type,
    created_at
FROM public.notifications
ORDER BY created_at DESC
LIMIT 5;

-- 8. Test manual notification insert (replace USER_ID with actual UUID)
-- INSERT INTO public.notifications (recipient_id, actor_id, type, target_id, target_type)
-- VALUES (
--     'YOUR_USER_ID_HERE'::uuid,
--     'YOUR_USER_ID_HERE'::uuid,
--     'follow',
--     'YOUR_USER_ID_HERE'::uuid,
--     'follow'
-- );
