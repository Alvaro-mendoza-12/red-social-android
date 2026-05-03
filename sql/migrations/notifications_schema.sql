-- =====================================================
-- NOTIFICATIONS SYSTEM - Compatible with Existing Schema
-- =====================================================

-- 1. CREATE NOTIFICATIONS TABLE
CREATE TABLE IF NOT EXISTS public.notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    actor_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    type TEXT NOT NULL CHECK (type IN ('follow', 'like_post', 'comment_post', 'share_post', 'like_video', 'comment_video', 'message')),
    target_id UUID, -- post_id, video_id, comment_id, etc.
    target_type TEXT, -- 'post', 'video', 'comment'
    content TEXT, -- For comment notifications, stores the comment text
    is_read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 2. ENABLE RLS
ALTER TABLE public.notifications ENABLE ROW LEVEL SECURITY;

-- 3. RLS POLICIES

-- Users can view their own notifications
CREATE POLICY "Users can view own notifications" ON public.notifications
    FOR SELECT USING (auth.uid() = recipient_id);

-- Users can update their own notifications (mark as read)
CREATE POLICY "Users can update own notifications" ON public.notifications
    FOR UPDATE USING (auth.uid() = recipient_id);

-- Any authenticated user can create notifications
CREATE POLICY "Authenticated users can create notifications" ON public.notifications
    FOR INSERT WITH CHECK (auth.uid() = actor_id);

-- 4. INDEXES FOR PERFORMANCE
CREATE INDEX IF NOT EXISTS idx_notifications_recipient ON public.notifications(recipient_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_actor ON public.notifications(actor_id);
CREATE INDEX IF NOT EXISTS idx_notifications_target ON public.notifications(target_id);
CREATE INDEX IF NOT EXISTS idx_notifications_unread ON public.notifications(recipient_id, is_read) WHERE is_read = FALSE;

-- 5. REPLICA IDENTITY FOR REALTIME
ALTER TABLE public.notifications REPLICA IDENTITY FULL;

-- =====================================================
-- TRIGGER FUNCTIONS FOR AUTOMATIC NOTIFICATIONS
-- =====================================================

-- FOLLOW NOTIFICATION TRIGGER
CREATE OR REPLACE FUNCTION notify_on_follow()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.notifications (recipient_id, actor_id, type, target_id, target_type)
    VALUES (NEW.following_id, NEW.follower_id, 'follow', NEW.id, 'follow');
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

-- POST LIKE NOTIFICATION TRIGGER
CREATE OR REPLACE FUNCTION notify_on_post_like()
RETURNS TRIGGER AS $$
DECLARE
    post_owner_id UUID;
BEGIN
    -- Get the owner of the post
    SELECT user_id INTO post_owner_id FROM public.posts WHERE id = NEW.post_id;
    
    -- Don't notify if user likes their own post
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

-- VIDEO LIKE NOTIFICATION TRIGGER
CREATE OR REPLACE FUNCTION notify_on_video_like()
RETURNS TRIGGER AS $$
DECLARE
    video_owner_id UUID;
BEGIN
    -- Get the owner of the video
    SELECT userId INTO video_owner_id FROM public.videos WHERE id = NEW.video_id;
    
    -- Don't notify if user likes their own video
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

-- =====================================================
-- RPC FUNCTION FOR MARK ALL AS READ
-- =====================================================
CREATE OR REPLACE FUNCTION mark_all_notifications_as_read()
RETURNS VOID AS $$
BEGIN
    UPDATE public.notifications
    SET is_read = TRUE
    WHERE recipient_id = auth.uid() AND is_read = FALSE;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- =====================================================
-- ENABLE REALTIME FOR NOTIFICATIONS
-- =====================================================
ALTER PUBLICATION supabase_realtime ADD TABLE public.notifications;
