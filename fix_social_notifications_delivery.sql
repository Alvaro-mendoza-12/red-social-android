-- ==========================================================
-- FIX SOCIAL NOTIFICATIONS DELIVERY (v1.0)
-- This SQL ensures that all records in the 'notifications' table
-- are sent via FCM to the notification bar.
-- ==========================================================

-- 1. Ensure the Edge Function URL is correct
-- If you are using Supabase Dashboard, create a WEBHOOK for 'public.notifications' 
-- on INSERT that triggers the Edge Function: push-notification

-- Alternatively, use this SQL to ensure triggers are correct for all social actions:

-- A. LIKE VIDEO (Point to notifications table)
CREATE OR REPLACE FUNCTION notify_on_video_like()
RETURNS TRIGGER AS $$
DECLARE
    video_owner_id UUID;
BEGIN
    SELECT user_id INTO video_owner_id FROM public.videos WHERE id = NEW.video_id;
    
    -- Insert into notifications table (which then triggers the Edge Function)
    IF video_owner_id IS NOT NULL AND video_owner_id != NEW.user_id THEN
        INSERT INTO public.notifications (recipient_id, actor_id, type, target_id, target_type)
        VALUES (video_owner_id, NEW.user_id, 'like_video', NEW.video_id, 'video');
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- B. FOLLOW
CREATE OR REPLACE FUNCTION notify_on_follow()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.notifications (recipient_id, actor_id, type, target_id, target_type)
    VALUES (NEW.following_id, NEW.follower_id, 'follow', NEW.following_id, 'follow');
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- C. ENABLE REALTIME FOR NOTIFICATIONS
-- This helps the app receive notifications in the foreground immediately
ALTER TABLE public.notifications REPLICA IDENTITY FULL;
-- Check if table is in realtime publication
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_publication_tables 
    WHERE pubname = 'supabase_realtime' AND schemaname = 'public' AND tablename = 'notifications'
  ) THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.notifications;
  END IF;
END $$;

-- IMPORTANT: 
-- In the Supabase Dashboard, go to Database -> Webhooks -> Add New
-- Table: notifications
-- Events: INSERT
-- Type: Edge Function
-- Function: push-notification
