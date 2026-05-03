-- ==========================================================
-- COMPLETE NOTIFICATIONS SCHEMA FIX
-- Adds all missing columns and fixes everything
-- ==========================================================

-- 1. ADD ALL MISSING COLUMNS (if they don't exist)
DO $$ 
BEGIN
    -- Add target_type column
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_name='notifications' AND column_name='target_type') THEN
        ALTER TABLE public.notifications ADD COLUMN target_type TEXT;
        RAISE NOTICE 'Added target_type column';
    END IF;
    
    -- Add content column
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_name='notifications' AND column_name='content') THEN
        ALTER TABLE public.notifications ADD COLUMN content TEXT;
        RAISE NOTICE 'Added content column';
    END IF;
    
    -- Add extra_data column
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_name='notifications' AND column_name='extra_data') THEN
        ALTER TABLE public.notifications ADD COLUMN extra_data JSONB DEFAULT '{}'::jsonb;
        RAISE NOTICE 'Added extra_data column';
    END IF;
END $$;

-- 2. VERIFY SCHEMA
SELECT 
    column_name, 
    data_type, 
    is_nullable
FROM information_schema.columns 
WHERE table_name = 'notifications' 
  AND table_schema = 'public'
ORDER BY ordinal_position;

-- 3. FIX FOREIGN KEYS (if needed)
DO $$
BEGIN
    -- Drop old FKs if they point to auth.users
    IF EXISTS (SELECT 1 FROM information_schema.table_constraints 
               WHERE constraint_name = 'notifications_actor_id_fkey' 
               AND table_name = 'notifications') THEN
        ALTER TABLE public.notifications DROP CONSTRAINT notifications_actor_id_fkey;
    END IF;
    
    IF EXISTS (SELECT 1 FROM information_schema.table_constraints 
               WHERE constraint_name = 'notifications_recipient_id_fkey' 
               AND table_name = 'notifications') THEN
        ALTER TABLE public.notifications DROP CONSTRAINT notifications_recipient_id_fkey;
    END IF;
END $$;

-- Add new FKs to profiles
ALTER TABLE public.notifications 
    ADD CONSTRAINT notifications_actor_id_fkey 
    FOREIGN KEY (actor_id) REFERENCES public.profiles(id) ON DELETE CASCADE;

ALTER TABLE public.notifications 
    ADD CONSTRAINT notifications_recipient_id_fkey 
    FOREIGN KEY (recipient_id) REFERENCES public.profiles(id) ON DELETE CASCADE;

-- 4. RE-CREATE TRIGGERS (in case they were broken)

-- Follow trigger
CREATE OR REPLACE FUNCTION notify_on_follow()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.notifications (recipient_id, actor_id, type, target_id, target_type)
    VALUES (NEW.following_id, NEW.follower_id, 'follow', NEW.following_id, 'follow');
    RETURN NEW;
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'Error creating follow notification: %', SQLERRM;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_new_follow ON public.follows;
CREATE TRIGGER on_new_follow
    AFTER INSERT ON public.follows
    FOR EACH ROW
    EXECUTE FUNCTION notify_on_follow();

-- Post Like trigger
CREATE OR REPLACE FUNCTION notify_on_post_like()
RETURNS TRIGGER AS $$
DECLARE
    post_owner_id UUID;
BEGIN
    SELECT user_id INTO post_owner_id FROM public.posts WHERE id = NEW.post_id;
    
    IF post_owner_id IS NOT NULL AND post_owner_id != NEW.user_id THEN
        INSERT INTO public.notifications (recipient_id, actor_id, type, target_id, target_type)
        VALUES (post_owner_id, NEW.user_id, 'like_post', NEW.post_id, 'post');
    END IF;
    RETURN NEW;
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'Error creating post like notification: %', SQLERRM;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_new_post_like ON public.post_likes;
CREATE TRIGGER on_new_post_like
    AFTER INSERT ON public.post_likes
    FOR EACH ROW
    EXECUTE FUNCTION notify_on_post_like();

-- Video Like trigger
CREATE OR REPLACE FUNCTION notify_on_video_like()
RETURNS TRIGGER AS $$
DECLARE
    video_owner_id UUID;
BEGIN
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

DROP TRIGGER IF EXISTS on_new_video_like ON public.video_likes;
CREATE TRIGGER on_new_video_like
    AFTER INSERT ON public.video_likes
    FOR EACH ROW
    EXECUTE FUNCTION notify_on_video_like();

-- 5. VERIFY TRIGGERS
SELECT 
    trigger_name,
    event_object_table,
    action_timing,
    event_manipulation
FROM information_schema.triggers
WHERE trigger_name IN ('on_new_follow', 'on_new_post_like', 'on_new_video_like')
ORDER BY trigger_name;

-- 6. INSERT TEST NOTIFICATION
INSERT INTO public.notifications (
    recipient_id, 
    actor_id, 
    type, 
    target_id, 
    target_type,
    content
)
VALUES (
    '24eecc96-1c29-4183-bae9-bc6dd951e25b'::uuid,
    '24eecc96-1c29-4183-bae9-bc6dd951e25b'::uuid,
    'follow',
    '24eecc96-1c29-4183-bae9-bc6dd951e25b'::uuid,
    'follow',
    '¡Sistema de notificaciones activado!'
);

-- 7. VERIFY TEST NOTIFICATION WAS CREATED
SELECT 
    id,
    type,
    content,
    created_at
FROM public.notifications
ORDER BY created_at DESC
LIMIT 1;
