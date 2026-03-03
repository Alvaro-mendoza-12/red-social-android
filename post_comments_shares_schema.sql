-- =====================================================
-- POST COMMENTS AND SHARES TABLES
-- Required for complete notifications system
-- =====================================================

-- 1. POST COMMENTS TABLE
CREATE TABLE IF NOT EXISTS public.post_comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id UUID NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    parent_id UUID REFERENCES public.post_comments(id) ON DELETE CASCADE, -- For nested replies
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- 2. SHARES TABLE  
CREATE TABLE IF NOT EXISTS public.shares (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    post_id UUID REFERENCES public.posts(id) ON DELETE CASCADE,
    video_id UUID REFERENCES public.videos(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    -- User can share a post OR a video, not both
    CHECK ((post_id IS NOT NULL AND video_id IS NULL) OR (post_id IS NULL AND video_id IS NOT NULL))
);

-- 3. INDEXES
CREATE INDEX IF NOT EXISTS idx_post_comments_post_id ON public.post_comments(post_id);
CREATE INDEX IF NOT EXISTS idx_post_comments_user_id ON public.post_comments(user_id);
CREATE INDEX IF NOT EXISTS idx_post_comments_parent_id ON public.post_comments(parent_id);
CREATE INDEX IF NOT EXISTS idx_shares_user_id ON public.shares(user_id);
CREATE INDEX IF NOT EXISTS idx_shares_post_id ON public.shares(post_id);
CREATE INDEX IF NOT EXISTS idx_shares_video_id ON public.shares(video_id);

-- 4. RLS POLICIES FOR POST_COMMENTS
ALTER TABLE public.post_comments ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Comments are viewable by everyone" ON public.post_comments;
CREATE POLICY "Comments are viewable by everyone" ON public.post_comments
    FOR SELECT USING (true);

DROP POLICY IF EXISTS "Authenticated users can create comments" ON public.post_comments;
CREATE POLICY "Authenticated users can create comments" ON public.post_comments
    FOR INSERT TO authenticated
    WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can delete their own comments" ON public.post_comments;
CREATE POLICY "Users can delete their own comments" ON public.post_comments
    FOR DELETE TO authenticated
    USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can update their own comments" ON public.post_comments;
CREATE POLICY "Users can update their own comments" ON public.post_comments
    FOR UPDATE TO authenticated
    USING (auth.uid() = user_id);

-- 5. RLS POLICIES FOR SHARES
ALTER TABLE public.shares ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Shares are viewable by everyone" ON public.shares;
CREATE POLICY "Shares are viewable by everyone" ON public.shares
    FOR SELECT USING (true);

DROP POLICY IF EXISTS "Authenticated users can share" ON public.shares;
CREATE POLICY "Authenticated users can share" ON public.shares
    FOR INSERT TO authenticated
    WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can delete their own shares" ON public.shares;
CREATE POLICY "Users can delete their own shares" ON public.shares
    FOR DELETE TO authenticated
    USING (auth.uid() = user_id);

-- 6. TRIGGERS TO UPDATE COMMENT_COUNT
CREATE OR REPLACE FUNCTION update_post_comment_count()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE public.posts
        SET comment_count = comment_count + 1
        WHERE id = NEW.post_id;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE public.posts
        SET comment_count = GREATEST(0, comment_count - 1)
        WHERE id = OLD.post_id;
        RETURN OLD;
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS trigger_update_post_comment_count ON public.post_comments;
CREATE TRIGGER trigger_update_post_comment_count
AFTER INSERT OR DELETE ON public.post_comments
FOR EACH ROW
EXECUTE FUNCTION update_post_comment_count();

-- 7. ENABLE REALTIME
ALTER PUBLICATION supabase_realtime ADD TABLE public.post_comments;
ALTER PUBLICATION supabase_realtime ADD TABLE public.shares;

-- =====================================================
-- NOTIFICATION TRIGGERS FOR COMMENTS AND SHARES
-- Run this AFTER notifications_schema.sql has been executed
-- =====================================================

-- POST COMMENT NOTIFICATION TRIGGER
CREATE OR REPLACE FUNCTION notify_on_post_comment()
RETURNS TRIGGER AS $$
DECLARE
    post_owner_id UUID;
BEGIN
    -- Get the owner of the post
    SELECT user_id INTO post_owner_id FROM public.posts WHERE id = NEW.post_id;
    
    -- Don't notify if user comments on their own post
    IF post_owner_id IS NOT NULL AND post_owner_id != NEW.user_id THEN
        INSERT INTO public.notifications (recipient_id, actor_id, type, target_id, target_type, content)
        VALUES (post_owner_id, NEW.user_id, 'comment_post', NEW.post_id, 'post', NEW.content);
    END IF;
    RETURN NEW;
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'Error creating post comment notification: %', SQLERRM;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_new_post_comment ON public.post_comments;
CREATE TRIGGER on_new_post_comment
    AFTER INSERT ON public.post_comments
    FOR EACH ROW
    EXECUTE FUNCTION notify_on_post_comment();

-- POST SHARE NOTIFICATION TRIGGER
CREATE OR REPLACE FUNCTION notify_on_post_share()
RETURNS TRIGGER AS $$
DECLARE
    post_owner_id UUID;
BEGIN
    -- Only for post shares (not videos)
    IF NEW.post_id IS NOT NULL THEN
        -- Get the owner of the post
        SELECT user_id INTO post_owner_id FROM public.posts WHERE id = NEW.post_id;
        
        -- Don't notify if user shares their own post
        IF post_owner_id IS NOT NULL AND post_owner_id != NEW.user_id THEN
            INSERT INTO public.notifications (recipient_id, actor_id, type, target_id, target_type)
            VALUES (post_owner_id, NEW.user_id, 'share_post', NEW.post_id, 'post');
        END IF;
    END IF;
    RETURN NEW;
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'Error creating post share notification: %', SQLERRM;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_new_post_share ON public.shares;
CREATE TRIGGER on_new_post_share
    AFTER INSERT ON public.shares
    FOR EACH ROW
    EXECUTE FUNCTION notify_on_post_share();
