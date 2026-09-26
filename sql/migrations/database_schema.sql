-- ========================================
-- SUPABASE DATABASE SCHEMA
-- Red Social Android App
-- ========================================

-- ========================================
-- 1. PROFILES TABLE
-- ========================================
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    numeric_id BIGSERIAL UNIQUE,
    email TEXT NOT NULL,
    username TEXT UNIQUE,
    full_name TEXT,
    display_name TEXT,
    phone TEXT,
    bio TEXT,
    avatar_url TEXT,
    cover_url TEXT,
    is_admin BOOLEAN NOT NULL DEFAULT FALSE,
    is_verified BOOLEAN NOT NULL DEFAULT FALSE,
    country TEXT,
    city TEXT,
    followers_count INTEGER DEFAULT 0,
    following_count INTEGER DEFAULT 0,
    post_count INTEGER DEFAULT 0,
    status TEXT,
    last_name_change TIMESTAMP WITH TIME ZONE,
    is_private BOOLEAN DEFAULT FALSE,
    profile_visibility TEXT DEFAULT 'everyone',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Enable RLS for profiles
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

-- Policy: Users can read all profiles
DROP POLICY IF EXISTS "Profiles are viewable by everyone" ON public.profiles;
CREATE POLICY "Profiles are viewable by everyone" ON public.profiles
    FOR SELECT USING (true);

-- Policy: Users can update their own profile
DROP POLICY IF EXISTS "Users can update own profile" ON public.profiles;
CREATE POLICY "Users can update own profile" ON public.profiles
    FOR UPDATE USING (auth.uid() = id);

-- Policy: Users can insert their own profile
DROP POLICY IF EXISTS "Users can insert own profile" ON public.profiles;
CREATE POLICY "Users can insert own profile" ON public.profiles
    FOR INSERT WITH CHECK (auth.uid() = id);

-- ========================================
-- 2. VIDEOS TABLE
-- ========================================
CREATE TABLE IF NOT EXISTS public.videos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    user_name TEXT DEFAULT '',
    user_profile_image TEXT DEFAULT '',
    title TEXT DEFAULT '',
    description TEXT DEFAULT '',
    video_url TEXT NOT NULL,
    thumbnail_url TEXT DEFAULT '',
    duration BIGINT DEFAULT 0,
    views INTEGER DEFAULT 0,
    like_count INTEGER DEFAULT 0,
    comment_count INTEGER DEFAULT 0,
    country TEXT DEFAULT '',
    city TEXT DEFAULT '',
    is_public BOOLEAN DEFAULT TRUE,
    tags TEXT[] DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Enable RLS for videos
ALTER TABLE public.videos ENABLE ROW LEVEL SECURITY;

-- Policy: Public videos are viewable by everyone
DROP POLICY IF EXISTS "Public videos are viewable by everyone" ON public.videos;
CREATE POLICY "Public videos are viewable by everyone" ON public.videos
    FOR SELECT USING (is_public = true OR user_id = auth.uid());

-- Policy: Users can insert their own videos
DROP POLICY IF EXISTS "Users can insert own videos" ON public.videos;
CREATE POLICY "Users can insert own videos" ON public.videos
    FOR INSERT WITH CHECK (auth.uid() = user_id);

-- Policy: Users can update their own videos
DROP POLICY IF EXISTS "Users can update own videos" ON public.videos;
CREATE POLICY "Users can update own videos" ON public.videos
    FOR UPDATE USING (auth.uid() = user_id);

-- Policy: Users can delete their own videos
DROP POLICY IF EXISTS "Users can delete own videos" ON public.videos;
CREATE POLICY "Users can delete own videos" ON public.videos
    FOR DELETE USING (auth.uid() = user_id);

-- ========================================
-- 3. VIDEO_LIKES TABLE
-- ========================================
CREATE TABLE IF NOT EXISTS public.video_likes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id UUID NOT NULL REFERENCES public.videos(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(video_id, user_id)
);

-- Enable RLS for video_likes
ALTER TABLE public.video_likes ENABLE ROW LEVEL SECURITY;

-- Policy: Anyone can view likes
DROP POLICY IF EXISTS "Likes are viewable by everyone" ON public.video_likes;
CREATE POLICY "Likes are viewable by everyone" ON public.video_likes
    FOR SELECT USING (true);

-- Policy: Users can insert their own likes
DROP POLICY IF EXISTS "Users can insert own likes" ON public.video_likes;
CREATE POLICY "Users can insert own likes" ON public.video_likes
    FOR INSERT WITH CHECK (auth.uid() = user_id);

-- Policy: Users can delete their own likes
DROP POLICY IF EXISTS "Users can delete own likes" ON public.video_likes;
CREATE POLICY "Users can delete own likes" ON public.video_likes
    FOR DELETE USING (auth.uid() = user_id);

-- ========================================
-- 4. VIDEO_COMMENTS TABLE
-- ========================================
CREATE TABLE IF NOT EXISTS public.video_comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id UUID NOT NULL REFERENCES public.videos(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Enable RLS for video_comments
ALTER TABLE public.video_comments ENABLE ROW LEVEL SECURITY;

-- Policy: Comments are viewable by everyone
DROP POLICY IF EXISTS "Comments are viewable by everyone" ON public.video_comments;
CREATE POLICY "Comments are viewable by everyone" ON public.video_comments
    FOR SELECT USING (true);

-- Policy: Users can insert their own comments
DROP POLICY IF EXISTS "Users can insert own comments" ON public.video_comments;
CREATE POLICY "Users can insert own comments" ON public.video_comments
    FOR INSERT WITH CHECK (auth.uid() = user_id);

-- ========================================
-- 5. CHATS TABLE
-- ========================================
CREATE TABLE IF NOT EXISTS public.chats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    participants TEXT[] NOT NULL,
    is_group BOOLEAN DEFAULT FALSE,
    group_name TEXT,
    group_image TEXT,
    last_message TEXT,
    last_message_at TIMESTAMP WITH TIME ZONE,
    last_message_sender_id UUID,
    last_message_id UUID,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Enable RLS for chats
ALTER TABLE public.chats ENABLE ROW LEVEL SECURITY;

-- Policy: Users can view chats they're part of
DROP POLICY IF EXISTS "Users can view own chats" ON public.chats;
CREATE POLICY "Users can view own chats" ON public.chats
    FOR SELECT USING (auth.uid()::TEXT = ANY(participants));

-- Policy: Users can create chats
DROP POLICY IF EXISTS "Users can create chats" ON public.chats;
CREATE POLICY "Users can create chats" ON public.chats
    FOR INSERT WITH CHECK (auth.uid()::TEXT = ANY(participants));

-- Policy: Users can update own chats (Required for Last Message updates)
DROP POLICY IF EXISTS "Users can update own chats" ON public.chats;
CREATE POLICY "Users can update own chats" ON public.chats
    FOR UPDATE USING (auth.uid()::TEXT = ANY(participants));

-- ========================================
-- 6. MESSAGES TABLE
-- ========================================
CREATE TABLE IF NOT EXISTS public.messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chat_id UUID NOT NULL REFERENCES public.chats(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    content TEXT DEFAULT '',
    content_type TEXT DEFAULT 'text',
    media_url TEXT,
    media_size BIGINT DEFAULT 0,
    status TEXT DEFAULT 'sent',
    read_at TIMESTAMP WITH TIME ZONE,
    reply_to_id UUID REFERENCES public.messages(id) ON DELETE SET NULL,
    is_edited BOOLEAN DEFAULT FALSE,
    deleted_for_all BOOLEAN DEFAULT FALSE,
    deleted_by UUID[] DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Enable RLS for messages
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;

-- Policy: Users can view messages in their chats
DROP POLICY IF EXISTS "Users can view messages in own chats" ON public.messages;
CREATE POLICY "Users can view messages in own chats" ON public.messages
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.chats
            WHERE chats.id = messages.chat_id
            AND auth.uid()::TEXT = ANY(chats.participants)
        )
    );

-- Policy: Users can insert messages in their chats
DROP POLICY IF EXISTS "Users can insert messages in own chats" ON public.messages;
CREATE POLICY "Users can insert messages in own chats" ON public.messages
    FOR INSERT WITH CHECK (
        auth.uid() = sender_id AND
        EXISTS (
            SELECT 1 FROM public.chats
            WHERE chats.id = messages.chat_id
            AND auth.uid()::TEXT = ANY(chats.participants)
        )
    );

-- Policy: Users can update messages in their chats (for marking as read)
DROP POLICY IF EXISTS "Users can update messages in own chats" ON public.messages;
CREATE POLICY "Users can update messages in own chats" ON public.messages
    FOR UPDATE USING (
        EXISTS (
            SELECT 1 FROM public.chats
            WHERE chats.id = messages.chat_id
            AND auth.uid()::TEXT = ANY(chats.participants)
        )
    );

-- ========================================
-- 7. CHAT_PARTICIPANTS TABLE
-- ========================================
CREATE TABLE IF NOT EXISTS public.chat_participants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chat_id UUID NOT NULL REFERENCES public.chats(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    joined_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_read_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    last_read_message_id UUID REFERENCES public.messages(id) ON DELETE SET NULL,
    is_muted BOOLEAN DEFAULT FALSE,
    is_pinned BOOLEAN DEFAULT FALSE,
    UNIQUE(chat_id, user_id)
);

-- Enable RLS for chat_participants
ALTER TABLE public.chat_participants ENABLE ROW LEVEL SECURITY;

-- Policy: Users can view participants in their chats
DROP POLICY IF EXISTS "Users can view participants in own chats" ON public.chat_participants;
CREATE POLICY "Users can view participants in own chats" ON public.chat_participants
    FOR SELECT USING (auth.uid() = user_id);

-- Policy: Users can insert participants for chats they're part of
DROP POLICY IF EXISTS "Users can insert participants" ON public.chat_participants;
CREATE POLICY "Users can insert participants" ON public.chat_participants
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.chats
            WHERE chats.id = chat_participants.chat_id
            AND auth.uid()::TEXT = ANY(chats.participants)
        )
    );

-- Policy: Users can update their own participation
DROP POLICY IF EXISTS "Users can update own participation" ON public.chat_participants;
CREATE POLICY "Users can update own participation" ON public.chat_participants
    FOR UPDATE USING (auth.uid() = user_id);

-- Policy: Users can delete their own participation (for "delete chat" feature)
DROP POLICY IF EXISTS "Users can delete own participation" ON public.chat_participants;
CREATE POLICY "Users can delete own participation" ON public.chat_participants
    FOR DELETE USING (auth.uid() = user_id);

-- ========================================
-- 8. FOLLOWS TABLE
-- ========================================
CREATE TABLE IF NOT EXISTS public.follows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    follower_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    following_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(follower_id, following_id),
    CHECK (follower_id != following_id)
);

-- Enable RLS for follows
ALTER TABLE public.follows ENABLE ROW LEVEL SECURITY;

-- Policy: Anyone can view follows
DROP POLICY IF EXISTS "Follows are viewable by everyone" ON public.follows;
CREATE POLICY "Follows are viewable by everyone" ON public.follows
    FOR SELECT USING (true);

-- Policy: Users can insert their own follows
DROP POLICY IF EXISTS "Users can insert own follows" ON public.follows;
CREATE POLICY "Users can insert own follows" ON public.follows
    FOR INSERT WITH CHECK (auth.uid() = follower_id);

-- Policy: Users can delete their own follows
DROP POLICY IF EXISTS "Users can delete own follows" ON public.follows;
CREATE POLICY "Users can delete own follows" ON public.follows
    FOR DELETE USING (auth.uid() = follower_id);

-- ========================================
-- RPC FUNCTIONS
-- ========================================

-- Function to increment followers_count
CREATE OR REPLACE FUNCTION increment_followers_count(user_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE public.profiles
    SET followers_count = followers_count + 1
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- Function to decrement followers_count
CREATE OR REPLACE FUNCTION decrement_followers_count(user_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE public.profiles
    SET followers_count = GREATEST(followers_count - 1, 0)
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- Function to increment following_count
CREATE OR REPLACE FUNCTION increment_following_count(user_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE public.profiles
    SET following_count = following_count + 1
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- Function to decrement following_count
CREATE OR REPLACE FUNCTION decrement_following_count(user_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE public.profiles
    SET following_count = GREATEST(following_count - 1, 0)
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- Function to increment post_count
CREATE OR REPLACE FUNCTION increment_post_count(user_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE public.profiles
    SET post_count = post_count + 1
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- Function to decrement post_count
CREATE OR REPLACE FUNCTION decrement_post_count(user_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE public.profiles
    SET post_count = GREATEST(post_count - 1, 0)
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- ========================================
-- INDEXES FOR PERFORMANCE
-- ========================================

CREATE INDEX IF NOT EXISTS idx_profiles_numeric_id ON public.profiles(numeric_id);
CREATE INDEX IF NOT EXISTS idx_profiles_email ON public.profiles(email);
CREATE INDEX IF NOT EXISTS idx_profiles_username ON public.profiles(username);
CREATE INDEX IF NOT EXISTS idx_videos_user_id ON public.videos(user_id);
CREATE INDEX IF NOT EXISTS idx_videos_created_at ON public.videos(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_messages_chat_id ON public.messages(chat_id);
CREATE INDEX IF NOT EXISTS idx_messages_created_at ON public.messages(created_at);
CREATE INDEX IF NOT EXISTS idx_follows_follower_id ON public.follows(follower_id);
CREATE INDEX IF NOT EXISTS idx_follows_following_id ON public.follows(following_id);
CREATE INDEX IF NOT EXISTS idx_video_likes_video_id ON public.video_likes(video_id);
CREATE INDEX IF NOT EXISTS idx_video_likes_user_id ON public.video_likes(user_id);

-- ========================================
-- STORAGE BUCKETS
-- ========================================
-- These should be created via Supabase Dashboard or Storage API
-- Required buckets:
-- - videos (for video files)
-- - thumbnails (for video thumbnails)
-- - avatars (for profile pictures)
-- - images (for general images)
-- - chat-media (for chat attachments)

-- ========================================
-- TRIGGERS
-- ========================================

-- Trigger to update updated_at on profiles
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_profiles_updated_at BEFORE UPDATE ON public.profiles
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_videos_updated_at BEFORE UPDATE ON public.videos
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_chats_updated_at BEFORE UPDATE ON public.chats
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_messages_updated_at BEFORE UPDATE ON public.messages
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ========================================
-- MESSAGING TRIGGERS (Added for WhatsApp-like behavior)
-- ========================================

-- Function to handle new message insertion
CREATE OR REPLACE FUNCTION handle_new_message()
RETURNS TRIGGER AS $$
BEGIN
    -- Update the chat with the last message info safely
    -- ERROR HANDLING: Ensure message insert never fails even if chat update fails
    BEGIN
        UPDATE public.chats
        SET 
            last_message = COALESCE(NEW.content, ''),
            last_message_sender_id = NEW.sender_id,
            last_message_at = NEW.created_at,
            last_message_id = NEW.id,
            updated_at = NOW()
        WHERE id = NEW.chat_id;
    EXCEPTION WHEN OTHERS THEN
        -- Log warning but do not abort transaction
        RAISE WARNING 'Error updating chat stats in handle_new_message: %', SQLERRM;
    END;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

-- Trigger to execute handle_new_message after insertion
DROP TRIGGER IF EXISTS on_new_message ON public.messages;
CREATE TRIGGER on_new_message
    AFTER INSERT ON public.messages
    FOR EACH ROW
    EXECUTE FUNCTION handle_new_message();

-- ========================================
-- RPC FUNCTION FOR LEAVING CHAT (Fixes Deletion Issue)
-- ========================================

-- Function to safely leave a chat
CREATE OR REPLACE FUNCTION leave_chat(chat_id_param UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    current_user_id TEXT;
BEGIN
    current_user_id := auth.uid()::TEXT;
    
    -- Remove user from participants array
    UPDATE public.chats
    SET participants = array_remove(participants, current_user_id)
    WHERE id = chat_id_param;
    
    -- Optional: Clean up empty chats
    DELETE FROM public.chats
    WHERE id = chat_id_param AND cardinality(participants) = 0;
END;
$$;

-- ========================================
-- 8. FCM_TOKENS TABLE (Phase 2)
-- ========================================
CREATE TABLE IF NOT EXISTS public.fcm_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    token TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, token)
);

-- Enable RLS for fcm_tokens
ALTER TABLE public.fcm_tokens ENABLE ROW LEVEL SECURITY;

-- Policy: Users can manage their own tokens
DROP POLICY IF EXISTS "Users can manage their own tokens" ON public.fcm_tokens;
CREATE POLICY "Users can manage their own tokens" ON public.fcm_tokens
    FOR ALL USING (auth.uid() = user_id);

-- ========================================
-- 9. POSTS TABLE (Added for Social Features)
-- ========================================
CREATE TABLE IF NOT EXISTS public.posts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    user_name TEXT DEFAULT '',
    user_profile_image TEXT DEFAULT '',
    content TEXT NOT NULL,
    media_urls JSONB DEFAULT '[]',
    visibility TEXT DEFAULT 'public' CHECK (visibility IN ('public', 'followers', 'private')),
    like_count INT DEFAULT 0,
    comment_count INT DEFAULT 0,
    share_count INT DEFAULT 0,
    save_count INT DEFAULT 0,
    tags TEXT[] DEFAULT '{}',
    is_verified BOOLEAN DEFAULT FALSE,
    is_promoted BOOLEAN DEFAULT FALSE,
    promoted_by UUID REFERENCES public.profiles(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Enable RLS for posts
ALTER TABLE public.posts ENABLE ROW LEVEL SECURITY;

-- Policy: Posts are viewable based on visibility
DROP POLICY IF EXISTS "Posts are viewable by visibility" ON public.posts;
CREATE POLICY "Posts are viewable by visibility" ON public.posts
    FOR SELECT USING (
        visibility = 'public' OR
        (visibility = 'followers' AND EXISTS (
            SELECT 1 FROM public.follows
            WHERE follows.follower_id = auth.uid()
            AND follows.following_id = posts.user_id
        )) OR
        user_id = auth.uid()
    );

-- Policy: Users can insert their own posts
DROP POLICY IF EXISTS "Users can insert own posts" ON public.posts;
CREATE POLICY "Users can insert own posts" ON public.posts
    FOR INSERT WITH CHECK (auth.uid() = user_id);

-- Policy: Users can update their own posts
DROP POLICY IF EXISTS "Users can update own posts" ON public.posts;
CREATE POLICY "Users can update own posts" ON public.posts
    FOR UPDATE USING (auth.uid() = user_id);

-- Policy: Users can delete their own posts
DROP POLICY IF EXISTS "Users can delete own posts" ON public.posts;
CREATE POLICY "Users can delete own posts" ON public.posts
    FOR DELETE USING (auth.uid() = user_id);

-- ========================================
-- 10. POST_LIKES TABLE
-- ========================================
CREATE TABLE IF NOT EXISTS public.post_likes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id UUID NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(post_id, user_id)
);

-- Enable RLS for post_likes
ALTER TABLE public.post_likes ENABLE ROW LEVEL SECURITY;

-- Policy: Anyone can view likes
DROP POLICY IF EXISTS "Post likes are viewable by everyone" ON public.post_likes;
CREATE POLICY "Post likes are viewable by everyone" ON public.post_likes
    FOR SELECT USING (true);

-- Policy: Users can insert their own likes
DROP POLICY IF EXISTS "Users can insert own post likes" ON public.post_likes;
CREATE POLICY "Users can insert own post likes" ON public.post_likes
    FOR INSERT WITH CHECK (auth.uid() = user_id);

-- Policy: Users can delete their own likes
DROP POLICY IF EXISTS "Users can delete own post likes" ON public.post_likes;
CREATE POLICY "Users can delete own post likes" ON public.post_likes
    FOR DELETE USING (auth.uid() = user_id);

-- ========================================
-- 11. POST_COMMENTS TABLE
-- ========================================
CREATE TABLE IF NOT EXISTS public.post_comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id UUID NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    user_name TEXT DEFAULT '',
    user_profile_image TEXT DEFAULT '',
    content TEXT NOT NULL,
    reply_to_id UUID REFERENCES public.post_comments(id) ON DELETE SET NULL,
    like_count INT DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Enable RLS for post_comments
ALTER TABLE public.post_comments ENABLE ROW LEVEL SECURITY;

-- Policy: Comments are viewable by everyone
DROP POLICY IF EXISTS "Post comments are viewable by everyone" ON public.post_comments;
CREATE POLICY "Post comments are viewable by everyone" ON public.post_comments
    FOR SELECT USING (true);

-- Policy: Users can insert their own comments
DROP POLICY IF EXISTS "Users can insert own post comments" ON public.post_comments;
CREATE POLICY "Users can insert own post comments" ON public.post_comments
    FOR INSERT WITH CHECK (auth.uid() = user_id);

-- Policy: Users can update their own comments
DROP POLICY IF EXISTS "Users can update own post comments" ON public.post_comments;
CREATE POLICY "Users can update own post comments" ON public.post_comments
    FOR UPDATE USING (auth.uid() = user_id);

-- Policy: Users can delete their own comments
DROP POLICY IF EXISTS "Users can delete own post comments" ON public.post_comments;
CREATE POLICY "Users can delete own post comments" ON public.post_comments
    FOR DELETE USING (auth.uid() = user_id);

-- ========================================
-- 12. MOMENTS TABLE (Stories)
-- ========================================
CREATE TABLE IF NOT EXISTS public.moments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    user_name TEXT DEFAULT '',
    user_profile_image TEXT DEFAULT '',
    content TEXT,
    media_url TEXT,
    media_type TEXT DEFAULT 'image' CHECK (media_type IN ('image', 'video')),
    view_count INT DEFAULT 0,
    expire_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Enable RLS for moments
ALTER TABLE public.moments ENABLE ROW LEVEL SECURITY;

-- Policy: Moments are viewable by followers (Instagram/Facebook style)
DROP POLICY IF EXISTS "Moments are viewable by followers" ON public.moments;
CREATE POLICY "Moments are viewable by followers" ON public.moments
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.follows
            WHERE follows.follower_id = auth.uid()
            AND follows.following_id = moments.user_id
        ) OR user_id = auth.uid()
    );

-- Policy: Users can insert their own moments
DROP POLICY IF EXISTS "Users can insert own moments" ON public.moments;
CREATE POLICY "Users can insert own moments" ON public.moments
    FOR INSERT WITH CHECK (auth.uid() = user_id);

-- Policy: Users can delete their own moments
DROP POLICY IF EXISTS "Users can delete own moments" ON public.moments;
CREATE POLICY "Users can delete own moments" ON public.moments
    FOR DELETE USING (auth.uid() = user_id);

-- ========================================
-- 13. LIVE_STREAMS TABLE
-- ========================================
CREATE TABLE IF NOT EXISTS public.live_streams (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    description TEXT,
    stream_key TEXT UNIQUE NOT NULL,
    is_public BOOLEAN DEFAULT TRUE,
    viewer_count INT DEFAULT 0,
    max_viewers INT DEFAULT 100,
    chat_enabled BOOLEAN DEFAULT TRUE,
    gifts_enabled BOOLEAN DEFAULT TRUE,
    status TEXT DEFAULT 'scheduled' CHECK (status IN ('scheduled', 'live', 'ended', 'cancelled')),
    started_at TIMESTAMP WITH TIME ZONE,
    ended_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Enable RLS for live_streams
ALTER TABLE public.live_streams ENABLE ROW LEVEL SECURITY;

-- Policy: Public streams are viewable by everyone, private by followers
DROP POLICY IF EXISTS "Live streams are viewable by visibility" ON public.live_streams;
CREATE POLICY "Live streams are viewable by visibility" ON public.live_streams
    FOR SELECT USING (
        is_public = true OR
        (is_public = false AND EXISTS (
            SELECT 1 FROM public.follows
            WHERE follows.follower_id = auth.uid()
            AND follows.following_id = live_streams.user_id
        )) OR
        user_id = auth.uid()
    );

-- Policy: Users can insert their own streams
DROP POLICY IF EXISTS "Users can insert own live streams" ON public.live_streams;
CREATE POLICY "Users can insert own live streams" ON public.live_streams
    FOR INSERT WITH CHECK (auth.uid() = user_id);

-- Policy: Users can update their own streams
DROP POLICY IF EXISTS "Users can update own live streams" ON public.live_streams;
CREATE POLICY "Users can update own live streams" ON public.live_streams
    FOR UPDATE USING (auth.uid() = user_id);

-- ========================================
-- ADDITIONAL INDEXES
-- ========================================
CREATE INDEX IF NOT EXISTS idx_posts_user_id ON public.posts(user_id);
CREATE INDEX IF NOT EXISTS idx_posts_created_at ON public.posts(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_posts_visibility ON public.posts(visibility);
CREATE INDEX IF NOT EXISTS idx_post_likes_post_id ON public.post_likes(post_id);
CREATE INDEX IF NOT EXISTS idx_post_likes_user_id ON public.post_likes(user_id);
CREATE INDEX IF NOT EXISTS idx_post_comments_post_id ON public.post_comments(post_id);
CREATE INDEX IF NOT EXISTS idx_moments_user_id ON public.moments(user_id);
CREATE INDEX IF NOT EXISTS idx_moments_expire_at ON public.moments(expire_at);
CREATE INDEX IF NOT EXISTS idx_live_streams_user_id ON public.live_streams(user_id);
CREATE INDEX IF NOT EXISTS idx_live_streams_status ON public.live_streams(status);
CREATE INDEX IF NOT EXISTS idx_live_streams_started_at ON public.live_streams(started_at DESC);


