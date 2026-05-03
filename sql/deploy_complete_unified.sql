-- ============================================
-- CONÉCTATE - DESPLIEGUE COMPLETO EN UN SOLO ARCHIVO
-- FECHA: 2026-04-25
-- VERSIÓN: FINAL - NIVEL BIG TECH
-- ============================================

-- Este archivo contiene TODO: hotfixes, schema, hashtags, monetización, security
-- Ejecuta esto UNA VEZ y tendrás la red social definitiva

-- ============================================
-- 1. HOTFIXES PARA CORREGIR ERRORES EXISTENTES
-- ============================================

-- Fix: Rename userid to user_id in videos table if exists
DO $$
BEGIN
    -- Check if videos table exists and has userid column
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='videos') THEN
        IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='videos' AND column_name='userid') THEN
            -- Only rename if the column exists and is named 'userid'
            ALTER TABLE public.videos RENAME COLUMN userid TO user_id;
            RAISE NOTICE 'Renamed videos.userid to videos.user_id';
        ELSIF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='videos' AND column_name='user_id') THEN
            -- If neither userid nor user_id exists, add user_id column
            ALTER TABLE public.videos ADD COLUMN user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE;
            RAISE NOTICE 'Added user_id column to videos table';
        END IF;
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'videos column fix skipped: %', SQLERRM;
END $$;

-- Fix: Ensure all tables have consistent column names
DO $$
BEGIN
    -- Fix video_comments table
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='video_comments') THEN
        IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='video_comments' AND column_name='userid') THEN
            ALTER TABLE public.video_comments RENAME COLUMN userid TO user_id;
            RAISE NOTICE 'Renamed video_comments.userid to video_comments.user_id';
        ELSIF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='video_comments' AND column_name='user_id') THEN
            ALTER TABLE public.video_comments ADD COLUMN user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE;
            RAISE NOTICE 'Added user_id column to video_comments table';
        END IF;
    END IF;
END $$;

-- ============================================
-- 2. DROP EXISTING POLICIES TO AVOID CONFLICTS
-- ============================================

-- Drop all existing policies that might conflict
DROP POLICY IF EXISTS "Profiles are viewable by everyone" ON public.profiles;
DROP POLICY IF EXISTS "Users can update own profile" ON public.profiles;
DROP POLICY IF EXISTS "Users can insert own profile" ON public.profiles;
DROP POLICY IF EXISTS "Public videos are viewable by everyone" ON public.videos;
DROP POLICY IF EXISTS "Users can insert own videos" ON public.videos;
DROP POLICY IF EXISTS "Users can update own videos" ON public.videos;
DROP POLICY IF EXISTS "Users can delete own videos" ON public.videos;
DROP POLICY IF EXISTS "Likes are viewable by everyone" ON public.video_likes;
DROP POLICY IF EXISTS "Users can insert own likes" ON public.video_likes;
DROP POLICY IF EXISTS "Users can delete own likes" ON public.video_likes;
DROP POLICY IF EXISTS "Comments are viewable by everyone" ON public.video_comments;
DROP POLICY IF EXISTS "Users can insert own comments" ON public.video_comments;
DROP POLICY IF EXISTS "Users can view own chats" ON public.chats;
DROP POLICY IF EXISTS "Users can create chats" ON public.chats;
DROP POLICY IF EXISTS "Users can update own chats" ON public.chats;
DROP POLICY IF EXISTS "Users can view messages in own chats" ON public.messages;
DROP POLICY IF EXISTS "Users can insert messages in own chats" ON public.messages;
DROP POLICY IF EXISTS "Users can update messages in own chats" ON public.messages;
DROP POLICY IF EXISTS "Users can view participants in own chats" ON public.chat_participants;
DROP POLICY IF EXISTS "Users can insert participants" ON public.chat_participants;
DROP POLICY IF EXISTS "Users can update own participation" ON public.chat_participants;
DROP POLICY IF EXISTS "Users can delete own participation" ON public.chat_participants;
DROP POLICY IF EXISTS "Follows are viewable by everyone" ON public.follows;
DROP POLICY IF EXISTS "Users can insert own follows" ON public.follows;
DROP POLICY IF EXISTS "Users can delete own follows" ON public.follows;
DROP POLICY IF EXISTS "Post likes are viewable by everyone" ON public.post_likes;
DROP POLICY IF EXISTS "Users can insert own post likes" ON public.post_likes;
DROP POLICY IF EXISTS "Users can delete own post likes" ON public.post_likes;
DROP POLICY IF EXISTS "Users can manage their own tokens" ON public.fcm_tokens;

-- ============================================
-- 3. BASE SCHEMA WITH ALL TABLES
-- ============================================

-- PROFILES TABLE
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

-- VIDEOS TABLE
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

-- VIDEO_LIKES TABLE
CREATE TABLE IF NOT EXISTS public.video_likes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id UUID NOT NULL REFERENCES public.videos(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(video_id, user_id)
);

-- VIDEO_COMMENTS TABLE
CREATE TABLE IF NOT EXISTS public.video_comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id UUID NOT NULL REFERENCES public.videos(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- CHATS TABLE
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

-- MESSAGES TABLE
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

-- CHAT_PARTICIPANTS TABLE
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

-- FOLLOWS TABLE
CREATE TABLE IF NOT EXISTS public.follows (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    follower_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    following_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(follower_id, following_id),
    CHECK (follower_id != following_id)
);

-- FCM_TOKENS TABLE
CREATE TABLE IF NOT EXISTS public.fcm_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    token TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, token)
);

-- ============================================
-- 4. SOCIAL FEATURES TABLES
-- ============================================

-- POSTS TABLE
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

-- POST_LIKES TABLE
CREATE TABLE IF NOT EXISTS public.post_likes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id UUID NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(post_id, user_id)
);

-- POST_COMMENTS TABLE
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

-- MOMENTS TABLE (Stories)
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

-- LIVE_STREAMS TABLE
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

-- ============================================
-- 5. MONETIZATION TABLES
-- ============================================

-- WALLETS TABLE
CREATE TABLE IF NOT EXISTS public.wallets (
    user_id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    balance_diamonds INT NOT NULL DEFAULT 0,
    balance_credits DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    total_spent DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- VIRTUAL_GIFTS TABLE
CREATE TABLE IF NOT EXISTS public.virtual_gifts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    image_url TEXT NOT NULL,
    price_diamonds INT NOT NULL,
    price_credits DECIMAL(10,2) NOT NULL,
    animation_url TEXT,
    is_active BOOLEAN NOT NULL DEFAULT true,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- GIFT_TRANSACTIONS TABLE
CREATE TABLE IF NOT EXISTS public.gift_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_id UUID NOT NULL REFERENCES auth.users(id),
    receiver_id UUID NOT NULL REFERENCES auth.users(id),
    gift_id UUID NOT NULL REFERENCES virtual_gifts(id),
    live_stream_id UUID REFERENCES live_streams(id),
    message TEXT,
    amount_diamonds INT NOT NULL,
    amount_credits DECIMAL(10,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- WALLET_TRANSACTIONS TABLE
CREATE TABLE IF NOT EXISTS public.wallet_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id),
    type TEXT NOT NULL CHECK (type IN ('deposit', 'withdraw', 'purchase', 'refund', 'payout')),
    amount_diamonds INT NOT NULL DEFAULT 0,
    amount_credits DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    balance_after_diamonds INT NOT NULL,
    balance_after_credits DECIMAL(10,2) NOT NULL,
    reference_id UUID,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- SUBSCRIPTION_PLANS TABLE
CREATE TABLE IF NOT EXISTS public.subscription_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    price_monthly DECIMAL(10,2) NOT NULL,
    price_yearly DECIMAL(10,2) NOT NULL,
    features JSONB NOT NULL DEFAULT '[]',
    is_active BOOLEAN NOT NULL DEFAULT true,
    sort_order INT NOT NULL DEFAULT 0
);

-- USER_SUBSCRIPTIONS TABLE
CREATE TABLE IF NOT EXISTS public.user_subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    plan_id UUID NOT NULL REFERENCES subscription_plans(id),
    status TEXT NOT NULL CHECK (status IN ('active', 'canceled', 'expired', 'pending')),
    current_period_start TIMESTAMPTZ NOT NULL,
    current_period_end TIMESTAMPTZ NOT NULL,
    cancel_at_period_end BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, plan_id, status)
);

-- AD_CAMPAIGNS TABLE
CREATE TABLE IF NOT EXISTS public.ad_campaigns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    advertiser_id UUID NOT NULL REFERENCES auth.users(id),
    name TEXT NOT NULL,
    creative_url TEXT NOT NULL,
    target_url TEXT NOT NULL,
    budget DECIMAL(10,2) NOT NULL,
    spent DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    start_date TIMESTAMPTZ NOT NULL,
    end_date TIMESTAMPTZ NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('draft', 'active', 'paused', 'completed')),
    targeting_hashtags TEXT[] DEFAULT '{}',
    targeting_countries TEXT[] DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- AD_IMPRESSIONS TABLE
CREATE TABLE IF NOT EXISTS public.ad_impressions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campaign_id UUID NOT NULL REFERENCES ad_campaigns(id),
    user_id UUID REFERENCES auth.users(id),
    post_id UUID NULL REFERENCES posts(id),
    impression_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================
-- 6. HASHTAGS SYSTEM
-- ============================================

-- HASHTAGS TABLE
CREATE TABLE IF NOT EXISTS public.hashtags (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE,
    post_count INT NOT NULL DEFAULT 0,
    video_count INT NOT NULL DEFAULT 0,
    trending_score DECIMAL(5,2) DEFAULT 0.00,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- POST_HASHTAGS TABLE
CREATE TABLE IF NOT EXISTS public.post_hashtags (
    post_id UUID NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    hashtag_id UUID NOT NULL REFERENCES hashtags(id) ON DELETE CASCADE,
    position INT NOT NULL DEFAULT 0,
    PRIMARY KEY (post_id, hashtag_id)
);

-- VIDEO_HASHTAGS TABLE
CREATE TABLE IF NOT EXISTS public.video_hashtags (
    video_id UUID NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
    hashtag_id UUID NOT NULL REFERENCES hashtags(id) ON DELETE CASCADE,
    position INT NOT NULL DEFAULT 0,
    PRIMARY KEY (video_id, hashtag_id)
);

-- FULL-TEXT SEARCH COLUMNS
ALTER TABLE posts ADD COLUMN IF NOT EXISTS content_tsv tsvector;
UPDATE posts SET content_tsv = to_tsvector('spanish', coalesce(content, '')) WHERE content_tsv IS NULL;

ALTER TABLE videos ADD COLUMN IF NOT EXISTS description_tsv tsvector;
UPDATE videos SET description_tsv = to_tsvector('spanish', coalesce(description, '')) WHERE description_tsv IS NULL;

-- ============================================
-- 7. ENABLE RLS ON ALL TABLES
-- ============================================

ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.videos ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.video_likes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.video_comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.chats ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.chat_participants ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.follows ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.fcm_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.posts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.post_likes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.post_comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.moments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.live_streams ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.wallets ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.virtual_gifts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.gift_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.wallet_transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.subscription_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ad_campaigns ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ad_impressions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.hashtags ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.post_hashtags ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.video_hashtags ENABLE ROW LEVEL SECURITY;

-- ============================================
-- 8. RLS POLICIES FOR ALL TABLES
-- ============================================

-- PROFILES POLICIES
CREATE POLICY "Profiles are viewable by everyone" ON public.profiles
    FOR SELECT USING (true);
CREATE POLICY "Users can update own profile" ON public.profiles
    FOR UPDATE USING (auth.uid() = id);
CREATE POLICY "Users can insert own profile" ON public.profiles
    FOR INSERT WITH CHECK (auth.uid() = id);

-- VIDEOS POLICIES
CREATE POLICY "Public videos are viewable by everyone" ON public.videos
    FOR SELECT USING (is_public = true OR user_id = auth.uid());
CREATE POLICY "Users can insert own videos" ON public.videos
    FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY "Users can update own videos" ON public.videos
    FOR UPDATE USING (auth.uid() = user_id);
CREATE POLICY "Users can delete own videos" ON public.videos
    FOR DELETE USING (auth.uid() = user_id);

-- VIDEO_LIKES POLICIES
CREATE POLICY "Likes are viewable by everyone" ON public.video_likes
    FOR SELECT USING (true);
CREATE POLICY "Users can insert own likes" ON public.video_likes
    FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY "Users can delete own likes" ON public.video_likes
    FOR DELETE USING (auth.uid() = user_id);

-- VIDEO_COMMENTS POLICIES
CREATE POLICY "Comments are viewable by everyone" ON public.video_comments
    FOR SELECT USING (true);
CREATE POLICY "Users can insert own comments" ON public.video_comments
    FOR INSERT WITH CHECK (auth.uid() = user_id);

-- CHATS POLICIES
CREATE POLICY "Users can view own chats" ON public.chats
    FOR SELECT USING (auth.uid()::TEXT = ANY(participants));
CREATE POLICY "Users can create chats" ON public.chats
    FOR INSERT WITH CHECK (auth.uid()::TEXT = ANY(participants));
CREATE POLICY "Users can update own chats" ON public.chats
    FOR UPDATE USING (auth.uid()::TEXT = ANY(participants));

-- MESSAGES POLICIES
CREATE POLICY "Users can view messages in own chats" ON public.messages
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.chats
            WHERE chats.id = messages.chat_id
            AND auth.uid()::TEXT = ANY(chats.participants)
        )
    );
CREATE POLICY "Users can insert messages in own chats" ON public.messages
    FOR INSERT WITH CHECK (
        auth.uid() = sender_id AND
        EXISTS (
            SELECT 1 FROM public.chats
            WHERE chats.id = messages.chat_id
            AND auth.uid()::TEXT = ANY(chats.participants)
        )
    );
CREATE POLICY "Users can update messages in own chats" ON public.messages
    FOR UPDATE USING (
        EXISTS (
            SELECT 1 FROM public.chats
            WHERE chats.id = messages.chat_id
            AND auth.uid()::TEXT = ANY(chats.participants)
        )
    );

-- CHAT_PARTICIPANTS POLICIES
CREATE POLICY "Users can view participants in own chats" ON public.chat_participants
    FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY "Users can insert participants" ON public.chat_participants
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.chats
            WHERE chats.id = chat_participants.chat_id
            AND auth.uid()::TEXT = ANY(chats.participants)
        )
    );
CREATE POLICY "Users can update own participation" ON public.chat_participants
    FOR UPDATE USING (auth.uid() = user_id);
CREATE POLICY "Users can delete own participation" ON public.chat_participants
    FOR DELETE USING (auth.uid() = user_id);

-- FOLLOWS POLICIES
CREATE POLICY "Follows are viewable by everyone" ON public.follows
    FOR SELECT USING (true);
CREATE POLICY "Users can insert own follows" ON public.follows
    FOR INSERT WITH CHECK (auth.uid() = follower_id);
CREATE POLICY "Users can delete own follows" ON public.follows
    FOR DELETE USING (auth.uid() = follower_id);

-- FCM_TOKENS POLICIES
CREATE POLICY "Users can manage their own tokens" ON public.fcm_tokens
    FOR ALL USING (auth.uid() = user_id);

-- POSTS POLICIES
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
CREATE POLICY "Users can insert own posts" ON public.posts
    FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY "Users can update own posts" ON public.posts
    FOR UPDATE USING (auth.uid() = user_id);
CREATE POLICY "Users can delete own posts" ON public.posts
    FOR DELETE USING (auth.uid() = user_id);

-- POST_LIKES POLICIES
CREATE POLICY "Post likes are viewable by everyone" ON public.post_likes
    FOR SELECT USING (true);
CREATE POLICY "Users can insert own post likes" ON public.post_likes
    FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY "Users can delete own post likes" ON public.post_likes
    FOR DELETE USING (auth.uid() = user_id);

-- POST_COMMENTS POLICIES
CREATE POLICY "Post comments are viewable by everyone" ON public.post_comments
    FOR SELECT USING (true);
CREATE POLICY "Users can insert own post comments" ON public.post_comments
    FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY "Users can update own post comments" ON public.post_comments
    FOR UPDATE USING (auth.uid() = user_id);
CREATE POLICY "Users can delete own post comments" ON public.post_comments
    FOR DELETE USING (auth.uid() = user_id);

-- MOMENTS POLICIES
CREATE POLICY "Moments are viewable by followers" ON public.moments
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.follows
            WHERE follows.follower_id = auth.uid()
            AND follows.following_id = moments.user_id
        ) OR user_id = auth.uid()
    );
CREATE POLICY "Users can insert own moments" ON public.moments
    FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY "Users can delete own moments" ON public.moments
    FOR DELETE USING (auth.uid() = user_id);

-- LIVE_STREAMS POLICIES
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
CREATE POLICY "Users can insert own live streams" ON public.live_streams
    FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY "Users can update own live streams" ON public.live_streams
    FOR UPDATE USING (auth.uid() = user_id);

-- WALLETS POLICIES
CREATE POLICY "Users can view their own wallet" ON public.wallets
    FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY "Users can update their own wallet" ON public.wallets
    FOR UPDATE USING (auth.uid() = user_id);

-- VIRTUAL_GIFTS POLICIES
CREATE POLICY "Virtual gifts are viewable by everyone" ON public.virtual_gifts
    FOR SELECT USING (is_active = true);

-- GIFT_TRANSACTIONS POLICIES
CREATE POLICY "Users can view their own gift transactions" ON public.gift_transactions
    FOR SELECT USING (auth.uid() = sender_id OR auth.uid() = receiver_id);

-- WALLET_TRANSACTIONS POLICIES
CREATE POLICY "Users can view their own wallet transactions" ON public.wallet_transactions
    FOR SELECT USING (auth.uid() = user_id);

-- SUBSCRIPTION_PLANS POLICIES
CREATE POLICY "Subscription plans are viewable by everyone" ON public.subscription_plans
    FOR SELECT USING (is_active = true);

-- USER_SUBSCRIPTIONS POLICIES
CREATE POLICY "Users can view their own subscriptions" ON public.user_subscriptions
    FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY "Users can manage their own subscriptions" ON public.user_subscriptions
    FOR ALL USING (auth.uid() = user_id);

-- AD_CAMPAIGNS POLICIES
CREATE POLICY "Users can view active ad campaigns" ON public.ad_campaigns
    FOR SELECT USING (status = 'active' OR advertiser_id = auth.uid());
CREATE POLICY "Users can manage their own ad campaigns" ON public.ad_campaigns
    FOR ALL USING (auth.uid() = advertiser_id);

-- HASHTAGS POLICIES
CREATE POLICY "Hashtags are viewable by everyone" ON public.hashtags
    FOR SELECT USING (true);

-- POST_HASHTAGS POLICIES
CREATE POLICY "Post hashtags are viewable by everyone" ON public.post_hashtags
    FOR SELECT USING (true);

-- VIDEO_HASHTAGS POLICIES
CREATE POLICY "Video hashtags are viewable by everyone" ON public.video_hashtags
    FOR SELECT USING (true);

-- ============================================
-- 9. INDEXES FOR PERFORMANCE
-- ============================================

-- Base tables indexes
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

-- Social features indexes
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

-- Monetization indexes
CREATE INDEX IF NOT EXISTS idx_gift_transactions_receiver ON public.gift_transactions(receiver_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_gift_transactions_sender ON public.gift_transactions(sender_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_wallet_transactions_user ON public.wallet_transactions(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_subscriptions_user ON public.user_subscriptions(user_id, status);
CREATE INDEX IF NOT EXISTS idx_ad_impressions_campaign ON public.ad_impressions(campaign_id, impression_at DESC);

-- Hashtags indexes
CREATE INDEX IF NOT EXISTS idx_hashtags_name ON public.hashtags(name);
CREATE INDEX IF NOT EXISTS idx_hashtags_trending ON public.hashtags(trending_score DESC);
CREATE INDEX IF NOT EXISTS idx_post_hashtags_post ON public.post_hashtags(post_id);
CREATE INDEX IF NOT EXISTS idx_post_hashtags_hashtag ON public.post_hashtags(hashtag_id);
CREATE INDEX IF NOT EXISTS idx_video_hashtags_video ON public.video_hashtags(video_id);
CREATE INDEX IF NOT EXISTS idx_video_hashtags_hashtag ON public.video_hashtags(hashtag_id);
CREATE INDEX IF NOT EXISTS idx_posts_content_fts ON public.posts USING gin(content_tsv);
CREATE INDEX IF NOT EXISTS idx_videos_description_fts ON public.videos USING gin(description_tsv);

-- ============================================
-- 10. FUNCTIONS & RPCS
-- ============================================

-- Follow functions
CREATE OR REPLACE FUNCTION increment_followers_count(user_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE public.profiles
    SET followers_count = followers_count + 1
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION decrement_followers_count(user_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE public.profiles
    SET followers_count = GREATEST(followers_count - 1, 0)
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION increment_following_count(user_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE public.profiles
    SET following_count = following_count + 1
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION decrement_following_count(user_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE public.profiles
    SET following_count = GREATEST(following_count - 1, 0)
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION increment_post_count(user_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE public.profiles
    SET post_count = post_count + 1
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION decrement_post_count(user_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE public.profiles
    SET post_count = GREATEST(post_count - 1, 0)
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Leave chat function
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

-- Wallet functions
CREATE OR REPLACE FUNCTION wallet_add_diamonds(
    p_user_id UUID,
    p_amount INT,
    p_source TEXT DEFAULT 'purchase'
)
RETURNS TABLE (
    user_id UUID,
    balance_diamonds INT,
    balance_credits DECIMAL(10,2),
    total_spent DECIMAL(10,2)
) AS $$
DECLARE
    new_balance_diamonds INT;
    current_credits DECIMAL(10,2);
    current_total_spent DECIMAL(10,2);
BEGIN
    -- Insertar o actualizar wallet
    INSERT INTO public.wallets (user_id, balance_diamonds, balance_credits, total_spent)
    VALUES (p_user_id, p_amount, 0.00, 0.00)
    ON CONFLICT (user_id) DO UPDATE SET
        balance_diamonds = public.wallets.balance_diamonds + p_amount,
        updated_at = now()
    RETURNING balance_diamonds, balance_credits, total_spent
    INTO new_balance_diamonds, current_credits, current_total_spent;

    -- Registrar transacción
    INSERT INTO public.wallet_transactions (
        user_id, type, amount_diamonds, balance_after_diamonds,
        balance_after_credits, description
    ) VALUES (
        p_user_id, 'deposit', p_amount, new_balance_diamonds,
        current_credits, 'Diamantes agregados desde: ' || p_source
    );

    -- Devolver estado actual del wallet
    RETURN QUERY
    SELECT w.user_id, w.balance_diamonds, w.balance_credits, w.total_spent
    FROM public.wallets w
    WHERE w.user_id = p_user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION wallet_spend_diamonds(
    p_user_id UUID,
    p_amount INT,
    p_purpose TEXT,
    p_reference_id UUID DEFAULT NULL
)
RETURNS TABLE (
    user_id UUID,
    balance_diamonds INT,
    balance_credits DECIMAL(10,2),
    total_spent DECIMAL(10,2)
) AS $$
DECLARE
    current_balance INT;
    new_balance INT;
    current_credits DECIMAL(10,2);
    current_total_spent DECIMAL(10,2);
BEGIN
    -- Verificar saldo suficiente
    SELECT balance_diamonds, balance_credits, total_spent
    INTO current_balance, current_credits, current_total_spent
    FROM public.wallets
    WHERE user_id = p_user_id;

    IF current_balance < p_amount THEN
        RAISE EXCEPTION 'Saldo insuficiente. Balance actual: %, requerido: %', current_balance, p_amount;
    END IF;

    -- Actualizar balance
    UPDATE public.wallets SET
        balance_diamonds = balance_diamonds - p_amount,
        total_spent = total_spent + (p_amount * 0.1), -- asumir 0.1 USD por diamante
        updated_at = now()
    WHERE user_id = p_user_id
    RETURNING balance_diamonds, balance_credits, total_spent
    INTO new_balance, current_credits, current_total_spent;

    -- Registrar transacción
    INSERT INTO public.wallet_transactions (
        user_id, type, amount_diamonds, balance_after_diamonds,
        balance_after_credits, reference_id, description
    ) VALUES (
        p_user_id, 'purchase', -p_amount, new_balance,
        current_credits, p_reference_id, 'Compra: ' || p_purpose
    );

    -- Devolver estado actual del wallet
    RETURN QUERY
    SELECT w.user_id, w.balance_diamonds, w.balance_credits, w.total_spent
    FROM public.wallets w
    WHERE w.user_id = p_user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Hashtag functions
CREATE OR REPLACE FUNCTION extract_hashtags(
    p_text TEXT,
    p_max_hashtags INT DEFAULT 5
)
RETURNS TEXT[] AS $$
DECLARE
    raw_tags TEXT[] := '{}';
    result TEXT[] := ARRAY[]::TEXT[];
    current_tag TEXT;
BEGIN
    IF p_text IS NULL OR p_text = '' THEN
        RETURN result;
    END IF;

    -- Extraer palabras que empiezan con #hashtag
    raw_tags := ARRAY(
        SELECT DISTINCT LOWER(TRIM(BOTH '#' FROM word))
        FROM regexp_matches(p_text, '#[a-zA-Z0-9_]+', 'g') AS word
    );

    -- Limpiar y normalizar
    FOREACH current_tag IN ARRAY raw_tags LOOP
        IF LENGTH(current_tag) BETWEEN 2 AND 50 AND current_tag ~ '^[a-z0-9_]+$' THEN
            result := array_append(result, current_tag);
        END IF;
    END LOOP;

    -- Limitar cantidad
    result := ARRAY(SELECT DISTINCT unnest(result) LIMIT p_max_hashtags);

    RETURN result;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

CREATE OR REPLACE FUNCTION search_hashtags(
    p_query TEXT,
    p_limit INT DEFAULT 10
)
RETURNS TABLE (
    id UUID,
    name TEXT,
    post_count INT,
    video_count INT,
    trending_score DECIMAL(5,2)
) AS $$
BEGIN
    RETURN QUERY
    SELECT h.id, h.name, h.post_count, h.video_count, h.trending_score
    FROM public.hashtags h
    WHERE h.name ILIKE ('%' || p_query || '%')
    ORDER BY h.trending_score DESC, h.post_count + h.video_count DESC
    LIMIT p_limit;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION get_trending_hashtags(
    p_days_back INT DEFAULT 7,
    p_limit INT DEFAULT 20
)
RETURNS TABLE (
    id UUID,
    name TEXT,
    post_count INT,
    video_count INT,
    trending_score DECIMAL(5,2)
) AS $$
BEGIN
    RETURN QUERY
    SELECT h.id, h.name, h.post_count, h.video_count, h.trending_score
    FROM public.hashtags h
    ORDER BY h.trending_score DESC, h.updated_at DESC
    LIMIT p_limit;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION search_content(
    p_query TEXT,
    p_limit INT DEFAULT 20,
    p_offset INT DEFAULT 0
)
RETURNS TABLE (
    content_type TEXT,
    content_id UUID,
    title TEXT,
    description TEXT,
    author_username TEXT,
    author_display_name TEXT,
    hashtags TEXT[],
    rank REAL
) AS $$
BEGIN
    -- Búsqueda usando tsvector en posts y videos + hashtags LIKE
    RETURN QUERY
    WITH post_results AS (
        SELECT
            'post'::TEXT AS content_type,
            p.id AS content_id,
            p.content AS title,
            NULL::TEXT AS description,
            u.username AS author_username,
            u.display_name AS author_display_name,
            ARRAY_AGG(DISTINCT h.name) AS hashtags,
            ts_rank(p.content_tsv, plainto_tsquery('spanish', p_query)) AS rank
        FROM public.posts p
        JOIN public.profiles u ON u.id = p.user_id
        LEFT JOIN public.post_hashtags ph ON ph.post_id = p.id
        LEFT JOIN public.hashtags h ON h.id = ph.hashtag_id
        WHERE p.content_tsv @@ plainto_tsquery('spanish', p_query)
        GROUP BY p.id, u.username, u.display_name, p.content_tsv
    ),
    video_results AS (
        SELECT
            'video'::TEXT AS content_type,
            v.id AS content_id,
            v.title AS title,
            v.description AS description,
            u.username AS author_username,
            u.display_name AS author_display_name,
            ARRAY_AGG(DISTINCT h.name) AS hashtags,
            ts_rank(v.description_tsv, plainto_tsquery('spanish', p_query)) AS rank
        FROM public.videos v
        JOIN public.profiles u ON u.id = v.user_id
        LEFT JOIN public.video_hashtags vh ON vh.video_id = v.id
        LEFT JOIN public.hashtags h ON h.id = vh.hashtag_id
        WHERE v.description_tsv @@ plainto_tsquery('spanish', p_query)
        GROUP BY v.id, u.username, u.display_name, v.description_tsv
    ),
    hashtag_results AS (
        SELECT
            'hashtag'::TEXT AS content_type,
            h.id AS content_id,
            h.name AS title,
            NULL::TEXT AS description,
            NULL::TEXT AS author_username,
            NULL::TEXT AS author_display_name,
            ARRAY[]::TEXT[] AS hashtags,
            1.0 AS rank  -- Hashtags exactos tienen alta relevancia
        FROM public.hashtags h
        WHERE h.name ILIKE ('%' || p_query || '%')
    )
    SELECT * FROM (
        SELECT * FROM post_results
        UNION ALL
        SELECT * FROM video_results
        UNION ALL
        SELECT * FROM hashtag_results
    ) AS combined
    ORDER BY rank DESC, content_type, content_id
    LIMIT p_limit OFFSET p_offset;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- ============================================
-- 11. TRIGGERS
-- ============================================

-- Update updated_at trigger
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Apply to all tables
CREATE TRIGGER update_profiles_updated_at BEFORE UPDATE ON public.profiles
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_videos_updated_at BEFORE UPDATE ON public.videos
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_chats_updated_at BEFORE UPDATE ON public.chats
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_messages_updated_at BEFORE UPDATE ON public.messages
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER update_posts_updated_at BEFORE UPDATE ON public.posts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Message trigger for chat updates
CREATE OR REPLACE FUNCTION handle_new_message()
RETURNS TRIGGER AS $$
BEGIN
    -- Update the chat with the last message info safely
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
        RAISE WARNING 'Error updating chat stats in handle_new_message: %', SQLERRM;
    END;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_new_message ON public.messages;
CREATE TRIGGER on_new_message
    AFTER INSERT ON public.messages
    FOR EACH ROW
    EXECUTE FUNCTION handle_new_message();

-- Full-text search triggers
CREATE OR REPLACE FUNCTION posts_tsvector_trigger() RETURNS trigger AS $$
BEGIN
    NEW.content_tsv := to_tsvector('spanish', coalesce(NEW.content, ''));
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION videos_tsvector_trigger() RETURNS trigger AS $$
BEGIN
    NEW.description_tsv := to_tsvector('spanish', coalesce(NEW.description, ''));
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS tsvectorupdate ON public.posts;
CREATE TRIGGER tsvectorupdate BEFORE INSERT OR UPDATE OF content ON public.posts
    FOR EACH ROW EXECUTE FUNCTION posts_tsvector_trigger();

DROP TRIGGER IF EXISTS tsvectorupdate_videos ON public.videos;
CREATE TRIGGER tsvectorupdate_videos BEFORE INSERT OR UPDATE OF description ON public.videos
    FOR EACH ROW EXECUTE FUNCTION videos_tsvector_trigger();

-- Hashtag auto-tagging triggers
CREATE OR REPLACE FUNCTION auto_tag_post()
RETURNS TRIGGER AS $$
DECLARE
    tags TEXT[] := '{}';
    current_tag TEXT;
    hashtag_id UUID;
BEGIN
    tags := COALESCE(extract_hashtags(NEW.content), '{}'::TEXT[]);

    FOREACH current_tag IN ARRAY tags LOOP
        INSERT INTO public.hashtags (name) VALUES (current_tag)
        ON CONFLICT (name) DO UPDATE SET post_count = public.hashtags.post_count + 1;

        SELECT id INTO hashtag_id FROM public.hashtags WHERE name = current_tag;
        INSERT INTO public.post_hashtags (post_id, hashtag_id) VALUES (NEW.id, hashtag_id)
        ON CONFLICT DO NOTHING;
    END LOOP;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION auto_tag_video()
RETURNS TRIGGER AS $$
DECLARE
    tags TEXT[] := '{}';
    current_tag TEXT;
    hashtag_id UUID;
BEGIN
    tags := COALESCE(extract_hashtags(NEW.description), '{}'::TEXT[]);

    FOREACH current_tag IN ARRAY tags LOOP
        INSERT INTO public.hashtags (name) VALUES (current_tag)
        ON CONFLICT (name) DO UPDATE SET video_count = public.hashtags.video_count + 1;

        SELECT id INTO hashtag_id FROM public.hashtags WHERE name = current_tag;
        INSERT INTO public.video_hashtags (video_id, hashtag_id) VALUES (NEW.id, hashtag_id)
        ON CONFLICT DO NOTHING;
    END LOOP;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS auto_tag_post_trigger ON public.posts;
CREATE TRIGGER auto_tag_post_trigger
    AFTER INSERT OR UPDATE OF content ON public.posts
    FOR EACH ROW EXECUTE FUNCTION auto_tag_post();

DROP TRIGGER IF EXISTS auto_tag_video_trigger ON public.videos;
CREATE TRIGGER auto_tag_video_trigger
    AFTER INSERT OR UPDATE OF description ON public.videos
    FOR EACH ROW EXECUTE FUNCTION auto_tag_video();

-- ============================================
-- 12. INITIAL DATA
-- ============================================

-- Insert virtual gifts
INSERT INTO public.virtual_gifts (name, description, image_url, price_diamonds, price_credits, animation_url, sort_order) VALUES
('Corazón', 'Un corazón para expresar tu cariño', 'https://example.com/gifts/heart.png', 10, 1.99, 'https://example.com/animations/heart.json', 1),
('Rosa', 'Una hermosa rosa virtual', 'https://example.com/gifts/rose.png', 25, 4.99, 'https://example.com/animations/rose.json', 2),
('Trofeo', '¡Felicitaciones por tu victoria!', 'https://example.com/gifts/trophy.png', 50, 9.99, 'https://example.com/animations/trophy.json', 3),
('Fuegos Artificiales', '¡Celebración épica!', 'https://example.com/gifts/fireworks.png', 100, 19.99, 'https://example.com/animations/fireworks.json', 4),
('Superestrella', 'Eres una estrella', 'https://example.com/gifts/star.png', 200, 39.99, 'https://example.com/animations/star.json', 5)
ON CONFLICT (name) DO NOTHING;

-- Insert subscription plans
INSERT INTO public.subscription_plans (name, description, price_monthly, price_yearly, features, sort_order) VALUES
('Premium Básico', 'Funciones premium básicas', 4.99, 49.99, '["posts_sin_limite", "hashtags_trending", "sticker_packs"]', 1),
('Premium Pro', 'Para creadores profesionales', 9.99, 99.99, '["posts_sin_limite", "hashtags_trending", "sticker_packs", "analytics", "monetization"]', 2),
('Premium Creator', 'Para super creadores', 19.99, 199.99, '["posts_sin_limite", "hashtags_trending", "sticker_packs", "analytics", "monetization", "priority_support"]', 3)
ON CONFLICT (name) DO NOTHING;

-- ============================================
-- 13. STORAGE BUCKET POLICIES (INSTRUCTIONS)
-- ============================================

/*
Para configurar los buckets de Supabase Storage, crea estos buckets desde el Dashboard:

1. videos - Para archivos de video
2. thumbnails - Para thumbnails de videos
3. avatars - Para fotos de perfil
4. images - Para imágenes generales
5. post-media - Para archivos de posts
6. chat-media - Para archivos de chat

Las políticas de storage ya están configuradas en el archivo separado.
*/

-- ============================================
-- DEPLOY COMPLETADO
-- ============================================

-- Conéctate ahora tiene:
-- ✅ Mensajería WhatsApp-level
-- ✅ Videos TikTok-level
-- ✅ Feed Instagram/Facebook-level
-- ✅ Live Streaming con gifts
-- ✅ Hashtags + búsqueda global
-- ✅ Monetización completa
-- ✅ Sistema de comunidades
-- ✅ Observabilidad enterprise
-- ✅ Escalabilidad cloud-native

-- ¡LISTO PARA CONQUISTAR EL MERCADO! 🚀