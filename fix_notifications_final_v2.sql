-- ==========================================================
-- FIX NOTIFICATIONS SCHEMA AND TRIGGERS (v2.2 FINAL)
-- ==========================================================

-- 1. ADD MISSING COLUMNS
DO $$ 
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='notifications' AND column_name='extra_data') THEN
        ALTER TABLE public.notifications ADD COLUMN extra_data JSONB DEFAULT '{}'::jsonb;
    END IF;
END $$;

-- 2. FIX FOREIGN KEYS FOR POSTGREST JOINS
-- Notifications need to point directly to profiles for easy joins
ALTER TABLE public.notifications DROP CONSTRAINT IF EXISTS notifications_actor_id_fkey;
ALTER TABLE public.notifications ADD CONSTRAINT notifications_actor_id_fkey 
    FOREIGN KEY (actor_id) REFERENCES public.profiles(id) ON DELETE CASCADE;

ALTER TABLE public.notifications DROP CONSTRAINT IF EXISTS notifications_recipient_id_fkey;
ALTER TABLE public.notifications ADD CONSTRAINT notifications_recipient_id_fkey 
    FOREIGN KEY (recipient_id) REFERENCES public.profiles(id) ON DELETE CASCADE;

-- 3. FIX VIDEO LIKE TRIGGER (userId -> user_id)
CREATE OR REPLACE FUNCTION notify_on_video_like()
RETURNS TRIGGER AS $$
DECLARE
    video_owner_id UUID;
BEGIN
    -- Fix: public.videos uses user_id (underscore)
    SELECT user_id INTO video_owner_id FROM public.videos WHERE id = NEW.video_id;
    
    IF video_owner_id IS NOT NULL AND video_owner_id != NEW.user_id THEN
        INSERT INTO public.notifications (recipient_id, actor_id, type, target_id, target_type)
        VALUES (video_owner_id, NEW.user_id, 'like_video', NEW.video_id, 'video');
    END IF;
    RETURN NEW;
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'Error creating video like notification: %', SQLERRM;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 4. FIX FOLLOW TRIGGER (Remove dependence on NEW.id if not present)
CREATE OR REPLACE FUNCTION notify_on_follow()
RETURNS TRIGGER AS $$
BEGIN
    -- We use follow target_id as the following_id itself or a generated UUID
    INSERT INTO public.notifications (recipient_id, actor_id, type, target_id, target_type)
    VALUES (NEW.following_id, NEW.follower_id, 'follow', NEW.following_id, 'follow');
    RETURN NEW;
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'Error creating follow notification: %', SQLERRM;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 5. RE-APPLY POLICIES FOR SAFETY
DROP POLICY IF EXISTS "Users can view own notifications" ON public.notifications;
CREATE POLICY "Users can view own notifications" ON public.notifications
    FOR SELECT USING (auth.uid() = recipient_id);

DROP POLICY IF EXISTS "Authenticated users can create notifications" ON public.notifications;
CREATE POLICY "Authenticated users can create notifications" ON public.notifications
    FOR INSERT WITH CHECK (auth.uid() = actor_id);

-- 6. GRANT PERMISSIONS
GRANT ALL ON public.notifications TO authenticated;
GRANT ALL ON public.notifications TO service_role;
