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
CREATE POLICY "Profiles are viewable by everyone" ON public.profiles
    FOR SELECT USING (true);

-- Policy: Users can update their own profile
CREATE POLICY "Users can update own profile" ON public.profiles
    FOR UPDATE USING (auth.uid() = id);

-- Policy: Users can insert their own profile
CREATE POLICY "Users can insert own profile" ON public.profiles
    FOR INSERT WITH CHECK (auth.uid() = id);

-- ========================================
-- 2. VIDEOS TABLE
-- ========================================
CREATE TABLE IF NOT EXISTS public.videos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    userId UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    userName TEXT DEFAULT '',
    userProfileImage TEXT DEFAULT '',
    title TEXT DEFAULT '',
    description TEXT DEFAULT '',
    videoUrl TEXT NOT NULL,
    thumbnailUrl TEXT DEFAULT '',
    duration BIGINT DEFAULT 0,
    views INTEGER DEFAULT 0,
    likeCount INTEGER DEFAULT 0,
    commentCount INTEGER DEFAULT 0,
    country TEXT DEFAULT '',
    city TEXT DEFAULT '',
    isPublic BOOLEAN DEFAULT TRUE,
    tags TEXT[] DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Enable RLS for videos
ALTER TABLE public.videos ENABLE ROW LEVEL SECURITY;

-- Policy: Public videos are viewable by everyone
CREATE POLICY "Public videos are viewable by everyone" ON public.videos
    FOR SELECT USING (isPublic = true OR userId = auth.uid());

-- Policy: Users can insert their own videos
CREATE POLICY "Users can insert own videos" ON public.videos
    FOR INSERT WITH CHECK (auth.uid() = userId);

-- Policy: Users can update their own videos
CREATE POLICY "Users can update own videos" ON public.videos
    FOR UPDATE USING (auth.uid() = userId);

-- Policy: Users can delete their own videos
CREATE POLICY "Users can delete own videos" ON public.videos
    FOR DELETE USING (auth.uid() = userId);

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
CREATE POLICY "Likes are viewable by everyone" ON public.video_likes
    FOR SELECT USING (true);

-- Policy: Users can insert their own likes
CREATE POLICY "Users can insert own likes" ON public.video_likes
    FOR INSERT WITH CHECK (auth.uid() = user_id);

-- Policy: Users can delete their own likes
CREATE POLICY "Users can delete own likes" ON public.video_likes
    FOR DELETE USING (auth.uid() = user_id);

-- ========================================
-- 4. VIDEO_COMMENTS TABLE
-- ========================================
CREATE TABLE IF NOT EXISTS public.video_comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    videoId UUID NOT NULL REFERENCES public.videos(id) ON DELETE CASCADE,
    userId UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Enable RLS for video_comments
ALTER TABLE public.video_comments ENABLE ROW LEVEL SECURITY;

-- Policy: Comments are viewable by everyone
CREATE POLICY "Comments are viewable by everyone" ON public.video_comments
    FOR SELECT USING (true);

-- Policy: Users can insert their own comments
CREATE POLICY "Users can insert own comments" ON public.video_comments
    FOR INSERT WITH CHECK (auth.uid() = userId);

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
CREATE POLICY "Users can view own chats" ON public.chats
    FOR SELECT USING (auth.uid()::TEXT = ANY(participants));

-- Policy: Users can create chats
CREATE POLICY "Users can create chats" ON public.chats
    FOR INSERT WITH CHECK (auth.uid()::TEXT = ANY(participants));

-- Policy: Users can update own chats (Required for Last Message updates)
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
CREATE POLICY "Users can view messages in own chats" ON public.messages
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.chats
            WHERE chats.id = messages.chat_id
            AND auth.uid()::TEXT = ANY(chats.participants)
        )
    );

-- Policy: Users can insert messages in their chats
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
CREATE POLICY "Users can view participants in own chats" ON public.chat_participants
    FOR SELECT USING (auth.uid() = user_id);

-- Policy: Users can insert participants for chats they're part of
CREATE POLICY "Users can insert participants" ON public.chat_participants
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.chats
            WHERE chats.id = chat_participants.chat_id
            AND auth.uid()::TEXT = ANY(chats.participants)
        )
    );

-- Policy: Users can update their own participation
CREATE POLICY "Users can update own participation" ON public.chat_participants
    FOR UPDATE USING (auth.uid() = user_id);

-- Policy: Users can delete their own participation (for "delete chat" feature)
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
CREATE POLICY "Follows are viewable by everyone" ON public.follows
    FOR SELECT USING (true);

-- Policy: Users can insert their own follows
CREATE POLICY "Users can insert own follows" ON public.follows
    FOR INSERT WITH CHECK (auth.uid() = follower_id);

-- Policy: Users can delete their own follows
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
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Function to decrement followers_count
CREATE OR REPLACE FUNCTION decrement_followers_count(user_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE public.profiles
    SET followers_count = GREATEST(followers_count - 1, 0)
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Function to increment following_count
CREATE OR REPLACE FUNCTION increment_following_count(user_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE public.profiles
    SET following_count = following_count + 1
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Function to decrement following_count
CREATE OR REPLACE FUNCTION decrement_following_count(user_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE public.profiles
    SET following_count = GREATEST(following_count - 1, 0)
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Function to increment post_count
CREATE OR REPLACE FUNCTION increment_post_count(user_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE public.profiles
    SET post_count = post_count + 1
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Function to decrement post_count
CREATE OR REPLACE FUNCTION decrement_post_count(user_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE public.profiles
    SET post_count = GREATEST(post_count - 1, 0)
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- ========================================
-- INDEXES FOR PERFORMANCE
-- ========================================

CREATE INDEX IF NOT EXISTS idx_profiles_numeric_id ON public.profiles(numeric_id);
CREATE INDEX IF NOT EXISTS idx_profiles_email ON public.profiles(email);
CREATE INDEX IF NOT EXISTS idx_profiles_username ON public.profiles(username);
CREATE INDEX IF NOT EXISTS idx_videos_userId ON public.videos(userId);
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
$$ LANGUAGE plpgsql SECURITY DEFINER;

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
CREATE POLICY "Users can manage their own tokens" ON public.fcm_tokens
    FOR ALL USING (auth.uid() = user_id);


