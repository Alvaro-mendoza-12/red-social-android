-- ==========================================================
-- VERIFY SUPABASE WEBHOOKS FOR NOTIFICATIONS
-- Run this in your Supabase SQL Editor to check if Webhooks are active
-- ==========================================================

-- 1. Check if the 'notifications' table exists and has RLS enabled
SELECT 
    tablename, 
    rowsecurity 
FROM pg_tables 
WHERE schemaname = 'public' AND tablename = 'notifications';

-- 2. Check for the Webhook triggers
-- Supabase Webhooks are actually Postgres triggers that call the net.http_post extension
SELECT 
    trigger_name,
    event_manipulation,
    event_object_table,
    action_statement
FROM information_schema.triggers
WHERE event_object_table IN ('notifications', 'messages')
AND trigger_name LIKE 'supabase_realtime%';

-- 3. Check for the specific Webhook configuration (if visible in meta)
-- Note: Some webhook details are managed by Supabase internal schemas
SELECT * FROM pg_catalog.pg_trigger 
WHERE tgname LIKE '%webhook%' OR tgname LIKE '%notification%';

-- ==========================================================
-- HOW TO MANUALLY ADD THE WEBHOOK IF MISSING (Dashboard is safer):
-- 1. Go to Database -> Webhooks -> Create New
-- 2. Name: "notify_on_social_activity"
-- 3. Table: "notifications"
-- 4. Events: INSERT
-- 5. Type: Supabase Edge Function
-- 6. Function: "push-notification" (the one we just updated)
-- ==========================================================
