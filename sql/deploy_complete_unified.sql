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

-- Las policies se eliminan justo antes de recrearlas, después de asegurar
-- que cada tabla existe. Así el archivo funciona en bases frescas y parciales.

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
    interests TEXT[] NOT NULL DEFAULT '{}',
    linked_games TEXT[] NOT NULL DEFAULT '{}',
    game_logo_urls JSONB NOT NULL DEFAULT '{}'::jsonb,
    favorite_music_url TEXT,
    favorite_book TEXT,
    favorite_sport TEXT,
    career TEXT,
    availability_schedule TEXT,
    followers_count INTEGER DEFAULT 0,
    following_count INTEGER DEFAULT 0,
    post_count INTEGER DEFAULT 0,
    status TEXT,
    last_name_change TIMESTAMP WITH TIME ZONE,
    is_private BOOLEAN DEFAULT FALSE,
    profile_visibility TEXT DEFAULT 'everyone',
    last_seen_visibility TEXT NOT NULL DEFAULT 'everyone',
    online_status_visibility TEXT NOT NULL DEFAULT 'everyone',
    is_admin BOOLEAN NOT NULL DEFAULT FALSE,
    voice_access_granted BOOLEAN NOT NULL DEFAULT FALSE,
    is_verified BOOLEAN NOT NULL DEFAULT FALSE,
    is_suspended BOOLEAN NOT NULL DEFAULT FALSE,
    suspended_until TIMESTAMP WITH TIME ZONE,
    suspension_end_date TIMESTAMP WITH TIME ZONE,
    suspension_reason TEXT,
    suspension_count INTEGER NOT NULL DEFAULT 0,
    privacy_policy_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    privacy_policy_accepted_at TIMESTAMP WITH TIME ZONE,
    is_online BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    balance DECIMAL(10,2) NOT NULL DEFAULT 0.00,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- CREATE TABLE IF NOT EXISTS no agrega columnas a instalaciones existentes.
-- Esta capa mantiene el deploy compatible con bases ya creadas parcialmente.
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS interests TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS linked_games TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS game_logo_urls JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS favorite_music_url TEXT;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS favorite_book TEXT;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS favorite_sport TEXT;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS career TEXT;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS availability_schedule TEXT;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS last_seen_visibility TEXT NOT NULL DEFAULT 'everyone';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS online_status_visibility TEXT NOT NULL DEFAULT 'everyone';
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS is_admin BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS voice_access_granted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS is_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS is_suspended BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS suspended_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS suspension_end_date TIMESTAMP WITH TIME ZONE;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS suspension_reason TEXT;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS suspension_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS privacy_policy_accepted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS privacy_policy_accepted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS is_online BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS last_seen TIMESTAMP WITH TIME ZONE DEFAULT NOW();
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS balance DECIMAL(10,2) NOT NULL DEFAULT 0.00;

UPDATE public.profiles
SET suspension_end_date = suspended_until
WHERE suspension_end_date IS NULL
  AND suspended_until IS NOT NULL;

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

-- Normaliza columnas creadas por versiones antiguas con camelCase sin comillas
-- (Postgres las guarda en minúsculas: userId -> userid, isPublic -> ispublic).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='videos' AND column_name='userid')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='videos' AND column_name='user_id') THEN
        ALTER TABLE public.videos RENAME COLUMN userid TO user_id;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='videos' AND column_name='username')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='videos' AND column_name='user_name') THEN
        ALTER TABLE public.videos RENAME COLUMN username TO user_name;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='videos' AND column_name='userprofileimage')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='videos' AND column_name='user_profile_image') THEN
        ALTER TABLE public.videos RENAME COLUMN userprofileimage TO user_profile_image;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='videos' AND column_name='videourl')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='videos' AND column_name='video_url') THEN
        ALTER TABLE public.videos RENAME COLUMN videourl TO video_url;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='videos' AND column_name='thumbnailurl')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='videos' AND column_name='thumbnail_url') THEN
        ALTER TABLE public.videos RENAME COLUMN thumbnailurl TO thumbnail_url;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='videos' AND column_name='ispublic')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='videos' AND column_name='is_public') THEN
        ALTER TABLE public.videos RENAME COLUMN ispublic TO is_public;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='videos' AND column_name='likecount')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='videos' AND column_name='like_count') THEN
        ALTER TABLE public.videos RENAME COLUMN likecount TO like_count;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='videos' AND column_name='commentcount')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='videos' AND column_name='comment_count') THEN
        ALTER TABLE public.videos RENAME COLUMN commentcount TO comment_count;
    END IF;
END $$;

ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE;
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS user_name TEXT DEFAULT '';
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS user_profile_image TEXT DEFAULT '';
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS title TEXT DEFAULT '';
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS description TEXT DEFAULT '';
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS video_url TEXT DEFAULT '';
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS thumbnail_url TEXT DEFAULT '';
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS duration BIGINT DEFAULT 0;
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS views INTEGER DEFAULT 0;
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS like_count INTEGER DEFAULT 0;
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS comment_count INTEGER DEFAULT 0;
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS country TEXT DEFAULT '';
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS city TEXT DEFAULT '';
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS is_public BOOLEAN DEFAULT TRUE;
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS tags TEXT[] DEFAULT '{}';

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

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='video_comments' AND column_name='videoid')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='video_comments' AND column_name='video_id') THEN
        ALTER TABLE public.video_comments RENAME COLUMN videoid TO video_id;
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='video_comments' AND column_name='userid')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='video_comments' AND column_name='user_id') THEN
        ALTER TABLE public.video_comments RENAME COLUMN userid TO user_id;
    END IF;
END $$;

ALTER TABLE public.video_comments ADD COLUMN IF NOT EXISTS video_id UUID REFERENCES public.videos(id) ON DELETE CASCADE;
ALTER TABLE public.video_comments ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE;
ALTER TABLE public.video_comments ADD COLUMN IF NOT EXISTS content TEXT DEFAULT '';

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

-- PROFILE_LOGIN_EMAILS TABLE
CREATE TABLE IF NOT EXISTS public.profile_login_emails (
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    email TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, email)
);

UPDATE public.profile_login_emails
SET email = LOWER(TRIM(email))
WHERE email <> LOWER(TRIM(email));

CREATE UNIQUE INDEX IF NOT EXISTS idx_profile_login_emails_unique_email
    ON public.profile_login_emails(email);

CREATE INDEX IF NOT EXISTS idx_profile_login_emails_user_created
    ON public.profile_login_emails(user_id, created_at DESC);

-- NOTIFICATIONS TABLE
CREATE TABLE IF NOT EXISTS public.notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    actor_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    type TEXT NOT NULL,
    target_id UUID,
    target_type TEXT,
    content TEXT,
    extra_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS recipient_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE;
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS actor_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE;
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS type TEXT DEFAULT 'message';
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS target_id UUID;
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS target_type TEXT;
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS content TEXT;
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS extra_data JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS is_read BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE public.notifications DROP CONSTRAINT IF EXISTS notifications_actor_id_fkey;
ALTER TABLE public.notifications ADD CONSTRAINT notifications_actor_id_fkey
    FOREIGN KEY (actor_id) REFERENCES public.profiles(id) ON DELETE CASCADE NOT VALID;
ALTER TABLE public.notifications DROP CONSTRAINT IF EXISTS notifications_recipient_id_fkey;
ALTER TABLE public.notifications ADD CONSTRAINT notifications_recipient_id_fkey
    FOREIGN KEY (recipient_id) REFERENCES public.profiles(id) ON DELETE CASCADE NOT VALID;

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

ALTER TABLE public.posts ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE;
ALTER TABLE public.posts ADD COLUMN IF NOT EXISTS content TEXT DEFAULT '';
ALTER TABLE public.posts ADD COLUMN IF NOT EXISTS media_urls JSONB DEFAULT '[]';
ALTER TABLE public.posts ADD COLUMN IF NOT EXISTS visibility TEXT DEFAULT 'public';
ALTER TABLE public.posts ADD COLUMN IF NOT EXISTS like_count INT DEFAULT 0;
ALTER TABLE public.posts ADD COLUMN IF NOT EXISTS comment_count INT DEFAULT 0;
ALTER TABLE public.posts ADD COLUMN IF NOT EXISTS share_count INT DEFAULT 0;
ALTER TABLE public.posts ADD COLUMN IF NOT EXISTS save_count INT DEFAULT 0;
ALTER TABLE public.posts ADD COLUMN IF NOT EXISTS tags TEXT[] DEFAULT '{}';
ALTER TABLE public.posts ADD COLUMN IF NOT EXISTS is_verified BOOLEAN DEFAULT FALSE;
ALTER TABLE public.posts ADD COLUMN IF NOT EXISTS is_promoted BOOLEAN DEFAULT FALSE;

-- POST_LIKES TABLE
CREATE TABLE IF NOT EXISTS public.post_likes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id UUID NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(post_id, user_id)
);

-- POST_REACTIONS TABLE
CREATE TABLE IF NOT EXISTS public.post_reactions (
    post_id UUID NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    reaction_type TEXT NOT NULL CHECK (reaction_type IN ('LIKE', 'LOVE', 'HAHA', 'WOW', 'SAD', 'ANGRY', 'CARE', 'FIRE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, user_id)
);

ALTER TABLE public.post_reactions ADD COLUMN IF NOT EXISTS post_id UUID REFERENCES public.posts(id) ON DELETE CASCADE;
ALTER TABLE public.post_reactions ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE;
ALTER TABLE public.post_reactions ADD COLUMN IF NOT EXISTS reaction_type TEXT NOT NULL DEFAULT 'LIKE';
ALTER TABLE public.post_reactions ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE public.post_reactions ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.post_reactions'::regclass
          AND conname = 'post_reactions_pkey'
    ) THEN
        ALTER TABLE public.post_reactions ADD CONSTRAINT post_reactions_pkey PRIMARY KEY (post_id, user_id);
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'post_reactions primary key skipped: %', SQLERRM;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'public.post_reactions'::regclass
          AND conname = 'post_reactions_reaction_type_check'
    ) THEN
        ALTER TABLE public.post_reactions
            ADD CONSTRAINT post_reactions_reaction_type_check
            CHECK (reaction_type IN ('LIKE', 'LOVE', 'HAHA', 'WOW', 'SAD', 'ANGRY', 'CARE', 'FIRE'));
    END IF;
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'post_reactions reaction_type check skipped: %', SQLERRM;
END $$;

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
    thumbnail_url TEXT,
    duration_hours INT DEFAULT 24,
    duration_type TEXT DEFAULT '24h',
    visibility TEXT DEFAULT 'all',
    visibility_type TEXT DEFAULT 'public',
    excluded_ids UUID[] DEFAULT '{}',
    caption TEXT,
    allow_replies BOOLEAN DEFAULT TRUE,
    view_count INT DEFAULT 0,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT (NOW() + INTERVAL '24 hours'),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='moments' AND column_name='expire_at')
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='moments' AND column_name='expires_at') THEN
        ALTER TABLE public.moments RENAME COLUMN expire_at TO expires_at;
    END IF;
END $$;

ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS user_name TEXT DEFAULT '';
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS user_profile_image TEXT DEFAULT '';
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS content TEXT;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS media_url TEXT;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS media_type TEXT DEFAULT 'image';
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS thumbnail_url TEXT;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS duration_hours INT DEFAULT 24;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS duration_type TEXT DEFAULT '24h';
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS visibility TEXT DEFAULT 'all';
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS visibility_type TEXT DEFAULT 'public';
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS excluded_ids UUID[] DEFAULT '{}';
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS caption TEXT;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS allow_replies BOOLEAN DEFAULT TRUE;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS view_count INT DEFAULT 0;
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP WITH TIME ZONE DEFAULT (NOW() + INTERVAL '24 hours');

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='moments' AND column_name='expire_at')
       AND EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema='public' AND table_name='moments' AND column_name='expires_at') THEN
        EXECUTE 'UPDATE public.moments SET expires_at = expire_at WHERE expires_at IS NULL AND expire_at IS NOT NULL';
    END IF;
END $$;

UPDATE public.moments
SET expires_at = COALESCE(expires_at, created_at + INTERVAL '24 hours', NOW() + INTERVAL '24 hours')
WHERE expires_at IS NULL;

ALTER TABLE public.moments ALTER COLUMN expires_at SET DEFAULT (NOW() + INTERVAL '24 hours');
ALTER TABLE public.moments ALTER COLUMN expires_at SET NOT NULL;

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

ALTER TABLE public.live_streams ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE;
ALTER TABLE public.live_streams ADD COLUMN IF NOT EXISTS title TEXT DEFAULT '';
ALTER TABLE public.live_streams ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE public.live_streams ADD COLUMN IF NOT EXISTS stream_key TEXT;
ALTER TABLE public.live_streams ADD COLUMN IF NOT EXISTS is_public BOOLEAN DEFAULT TRUE;
ALTER TABLE public.live_streams ADD COLUMN IF NOT EXISTS viewer_count INT DEFAULT 0;
ALTER TABLE public.live_streams ADD COLUMN IF NOT EXISTS max_viewers INT DEFAULT 100;
ALTER TABLE public.live_streams ADD COLUMN IF NOT EXISTS chat_enabled BOOLEAN DEFAULT TRUE;
ALTER TABLE public.live_streams ADD COLUMN IF NOT EXISTS gifts_enabled BOOLEAN DEFAULT TRUE;
ALTER TABLE public.live_streams ADD COLUMN IF NOT EXISTS status TEXT DEFAULT 'scheduled';
ALTER TABLE public.live_streams ADD COLUMN IF NOT EXISTS started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE public.live_streams ADD COLUMN IF NOT EXISTS ended_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE public.live_streams ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW();
ALTER TABLE public.live_streams ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW();

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

ALTER TABLE public.virtual_gifts ADD COLUMN IF NOT EXISTS name TEXT;
ALTER TABLE public.virtual_gifts ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE public.virtual_gifts ADD COLUMN IF NOT EXISTS image_url TEXT NOT NULL DEFAULT '';
ALTER TABLE public.virtual_gifts ADD COLUMN IF NOT EXISTS price_diamonds INT NOT NULL DEFAULT 0;
ALTER TABLE public.virtual_gifts ADD COLUMN IF NOT EXISTS price_credits DECIMAL(10,2) NOT NULL DEFAULT 0.00;
ALTER TABLE public.virtual_gifts ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE public.virtual_gifts ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0;
ALTER TABLE public.virtual_gifts ADD COLUMN IF NOT EXISTS animation_url TEXT;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
        WHERE c.conrelid = 'public.virtual_gifts'::regclass
          AND c.contype = 'u'
          AND array_length(c.conkey, 1) = 1
          AND a.attname = 'name'
    ) THEN
        ALTER TABLE public.virtual_gifts ADD CONSTRAINT virtual_gifts_name_key UNIQUE (name);
    END IF;
END $$;

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

ALTER TABLE public.subscription_plans ADD COLUMN IF NOT EXISTS name TEXT;
ALTER TABLE public.subscription_plans ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE public.subscription_plans ADD COLUMN IF NOT EXISTS price_monthly DECIMAL(10,2) NOT NULL DEFAULT 0.00;
ALTER TABLE public.subscription_plans ADD COLUMN IF NOT EXISTS price_yearly DECIMAL(10,2) NOT NULL DEFAULT 0.00;
ALTER TABLE public.subscription_plans ADD COLUMN IF NOT EXISTS features JSONB NOT NULL DEFAULT '[]';
ALTER TABLE public.subscription_plans ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE public.subscription_plans ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
        WHERE c.conrelid = 'public.subscription_plans'::regclass
          AND c.contype = 'u'
          AND array_length(c.conkey, 1) = 1
          AND a.attname = 'name'
    ) THEN
        ALTER TABLE public.subscription_plans ADD CONSTRAINT subscription_plans_name_key UNIQUE (name);
    END IF;
END $$;

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
ALTER TABLE public.hashtags ADD COLUMN IF NOT EXISTS name TEXT;
ALTER TABLE public.hashtags ADD COLUMN IF NOT EXISTS post_count INT NOT NULL DEFAULT 0;
ALTER TABLE public.hashtags ADD COLUMN IF NOT EXISTS video_count INT NOT NULL DEFAULT 0;
ALTER TABLE public.hashtags ADD COLUMN IF NOT EXISTS trending_score DECIMAL(5,2) DEFAULT 0.00;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
        WHERE c.conrelid = 'public.hashtags'::regclass
          AND c.contype = 'u'
          AND array_length(c.conkey, 1) = 1
          AND a.attname = 'name'
    ) THEN
        ALTER TABLE public.hashtags ADD CONSTRAINT hashtags_name_key UNIQUE (name);
    END IF;
END $$;

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
ALTER TABLE public.profile_login_emails ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.posts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.post_likes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.post_reactions ENABLE ROW LEVEL SECURITY;
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
DROP POLICY IF EXISTS "Profiles are viewable by everyone" ON public.profiles;
CREATE POLICY "Profiles are viewable by everyone" ON public.profiles
    FOR SELECT USING (true);
DROP POLICY IF EXISTS "Users can update own profile" ON public.profiles;
CREATE POLICY "Users can update own profile" ON public.profiles
    FOR UPDATE USING (auth.uid() = id);
DROP POLICY IF EXISTS "Users can insert own profile" ON public.profiles;
CREATE POLICY "Users can insert own profile" ON public.profiles
    FOR INSERT WITH CHECK (auth.uid() = id);

-- VIDEOS POLICIES
DROP POLICY IF EXISTS "Public videos are viewable by everyone" ON public.videos;
CREATE POLICY "Public videos are viewable by everyone" ON public.videos
    FOR SELECT USING (is_public = true OR user_id = auth.uid());
DROP POLICY IF EXISTS "Users can insert own videos" ON public.videos;
CREATE POLICY "Users can insert own videos" ON public.videos
    FOR INSERT WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "Users can update own videos" ON public.videos;
CREATE POLICY "Users can update own videos" ON public.videos
    FOR UPDATE USING (auth.uid() = user_id);
DROP POLICY IF EXISTS "Users can delete own videos" ON public.videos;
CREATE POLICY "Users can delete own videos" ON public.videos
    FOR DELETE USING (auth.uid() = user_id);

-- VIDEO_LIKES POLICIES
DROP POLICY IF EXISTS "Likes are viewable by everyone" ON public.video_likes;
CREATE POLICY "Likes are viewable by everyone" ON public.video_likes
    FOR SELECT USING (true);
DROP POLICY IF EXISTS "Users can insert own likes" ON public.video_likes;
CREATE POLICY "Users can insert own likes" ON public.video_likes
    FOR INSERT WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "Users can delete own likes" ON public.video_likes;
CREATE POLICY "Users can delete own likes" ON public.video_likes
    FOR DELETE USING (auth.uid() = user_id);

-- VIDEO_COMMENTS POLICIES
DROP POLICY IF EXISTS "Comments are viewable by everyone" ON public.video_comments;
CREATE POLICY "Comments are viewable by everyone" ON public.video_comments
    FOR SELECT USING (true);
DROP POLICY IF EXISTS "Users can insert own comments" ON public.video_comments;
CREATE POLICY "Users can insert own comments" ON public.video_comments
    FOR INSERT WITH CHECK (auth.uid() = user_id);

-- CHATS POLICIES
DROP POLICY IF EXISTS "Users can view own chats" ON public.chats;
CREATE POLICY "Users can view own chats" ON public.chats
    FOR SELECT USING (auth.uid()::TEXT = ANY(participants));
DROP POLICY IF EXISTS "Users can create chats" ON public.chats;
CREATE POLICY "Users can create chats" ON public.chats
    FOR INSERT WITH CHECK (auth.uid()::TEXT = ANY(participants));
DROP POLICY IF EXISTS "Users can update own chats" ON public.chats;
CREATE POLICY "Users can update own chats" ON public.chats
    FOR UPDATE USING (auth.uid()::TEXT = ANY(participants));

-- MESSAGES POLICIES
DROP POLICY IF EXISTS "Users can view messages in own chats" ON public.messages;
CREATE POLICY "Users can view messages in own chats" ON public.messages
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.chats
            WHERE chats.id = messages.chat_id
            AND auth.uid()::TEXT = ANY(chats.participants)
        )
    );
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
DROP POLICY IF EXISTS "Users can update messages in own chats" ON public.messages;
CREATE POLICY "Users can update messages in own chats" ON public.messages
    FOR UPDATE USING (
        EXISTS (
            SELECT 1 FROM public.chats
            WHERE chats.id = messages.chat_id
            AND auth.uid()::TEXT = ANY(chats.participants)
        )
    );

-- CHAT_PARTICIPANTS POLICIES
DROP POLICY IF EXISTS "Users can view participants in own chats" ON public.chat_participants;
CREATE POLICY "Users can view participants in own chats" ON public.chat_participants
    FOR SELECT USING (auth.uid() = user_id);
DROP POLICY IF EXISTS "Users can insert participants" ON public.chat_participants;
CREATE POLICY "Users can insert participants" ON public.chat_participants
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.chats
            WHERE chats.id = chat_participants.chat_id
            AND auth.uid()::TEXT = ANY(chats.participants)
        )
    );
DROP POLICY IF EXISTS "Users can update own participation" ON public.chat_participants;
CREATE POLICY "Users can update own participation" ON public.chat_participants
    FOR UPDATE USING (auth.uid() = user_id);
DROP POLICY IF EXISTS "Users can delete own participation" ON public.chat_participants;
CREATE POLICY "Users can delete own participation" ON public.chat_participants
    FOR DELETE USING (auth.uid() = user_id);

-- FOLLOWS POLICIES
DROP POLICY IF EXISTS "Follows are viewable by everyone" ON public.follows;
CREATE POLICY "Follows are viewable by everyone" ON public.follows
    FOR SELECT USING (true);
DROP POLICY IF EXISTS "Users can insert own follows" ON public.follows;
CREATE POLICY "Users can insert own follows" ON public.follows
    FOR INSERT WITH CHECK (auth.uid() = follower_id);
DROP POLICY IF EXISTS "Users can delete own follows" ON public.follows;
CREATE POLICY "Users can delete own follows" ON public.follows
    FOR DELETE USING (auth.uid() = follower_id);

-- FCM_TOKENS POLICIES
DROP POLICY IF EXISTS "Users can manage their own tokens" ON public.fcm_tokens;
CREATE POLICY "Users can manage their own tokens" ON public.fcm_tokens
    FOR ALL USING (auth.uid() = user_id);

-- PROFILE_LOGIN_EMAILS POLICIES
DROP POLICY IF EXISTS profile_login_emails_select_own ON public.profile_login_emails;
CREATE POLICY profile_login_emails_select_own
    ON public.profile_login_emails
    FOR SELECT USING (auth.uid() = user_id);
DROP POLICY IF EXISTS profile_login_emails_insert_own ON public.profile_login_emails;
CREATE POLICY profile_login_emails_insert_own
    ON public.profile_login_emails
    FOR INSERT WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS profile_login_emails_delete_own ON public.profile_login_emails;
CREATE POLICY profile_login_emails_delete_own
    ON public.profile_login_emails
    FOR DELETE USING (auth.uid() = user_id);

-- NOTIFICATIONS POLICIES
DROP POLICY IF EXISTS "Users can view own notifications" ON public.notifications;
CREATE POLICY "Users can view own notifications" ON public.notifications
    FOR SELECT USING (auth.uid() = recipient_id);
DROP POLICY IF EXISTS "Users can update own notifications" ON public.notifications;
CREATE POLICY "Users can update own notifications" ON public.notifications
    FOR UPDATE USING (auth.uid() = recipient_id)
    WITH CHECK (auth.uid() = recipient_id);
DROP POLICY IF EXISTS "Authenticated users can create notifications" ON public.notifications;
CREATE POLICY "Authenticated users can create notifications" ON public.notifications
    FOR INSERT WITH CHECK (auth.uid() = actor_id);

-- POSTS POLICIES
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
DROP POLICY IF EXISTS "Users can insert own posts" ON public.posts;
CREATE POLICY "Users can insert own posts" ON public.posts
    FOR INSERT WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "Users can update own posts" ON public.posts;
CREATE POLICY "Users can update own posts" ON public.posts
    FOR UPDATE USING (auth.uid() = user_id);
DROP POLICY IF EXISTS "Users can delete own posts" ON public.posts;
CREATE POLICY "Users can delete own posts" ON public.posts
    FOR DELETE USING (auth.uid() = user_id);

-- POST_LIKES POLICIES
DROP POLICY IF EXISTS "Post likes are viewable by everyone" ON public.post_likes;
CREATE POLICY "Post likes are viewable by everyone" ON public.post_likes
    FOR SELECT USING (true);
DROP POLICY IF EXISTS "Users can insert own post likes" ON public.post_likes;
CREATE POLICY "Users can insert own post likes" ON public.post_likes
    FOR INSERT WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "Users can delete own post likes" ON public.post_likes;
CREATE POLICY "Users can delete own post likes" ON public.post_likes
    FOR DELETE USING (auth.uid() = user_id);

-- POST_REACTIONS POLICIES
DROP POLICY IF EXISTS "Post reactions are viewable by everyone" ON public.post_reactions;
CREATE POLICY "Post reactions are viewable by everyone" ON public.post_reactions
    FOR SELECT USING (true);
DROP POLICY IF EXISTS "Users can insert own post reactions" ON public.post_reactions;
CREATE POLICY "Users can insert own post reactions" ON public.post_reactions
    FOR INSERT WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "Users can update own post reactions" ON public.post_reactions;
CREATE POLICY "Users can update own post reactions" ON public.post_reactions
    FOR UPDATE USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "Users can delete own post reactions" ON public.post_reactions;
CREATE POLICY "Users can delete own post reactions" ON public.post_reactions
    FOR DELETE USING (auth.uid() = user_id);

-- POST_COMMENTS POLICIES
DROP POLICY IF EXISTS "Post comments are viewable by everyone" ON public.post_comments;
CREATE POLICY "Post comments are viewable by everyone" ON public.post_comments
    FOR SELECT USING (true);
DROP POLICY IF EXISTS "Users can insert own post comments" ON public.post_comments;
CREATE POLICY "Users can insert own post comments" ON public.post_comments
    FOR INSERT WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "Users can update own post comments" ON public.post_comments;
CREATE POLICY "Users can update own post comments" ON public.post_comments
    FOR UPDATE USING (auth.uid() = user_id);
DROP POLICY IF EXISTS "Users can delete own post comments" ON public.post_comments;
CREATE POLICY "Users can delete own post comments" ON public.post_comments
    FOR DELETE USING (auth.uid() = user_id);

-- MOMENTS POLICIES
DROP POLICY IF EXISTS "Moments are viewable by followers" ON public.moments;
CREATE POLICY "Moments are viewable by followers" ON public.moments
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.follows
            WHERE follows.follower_id = auth.uid()
            AND follows.following_id = moments.user_id
        ) OR user_id = auth.uid()
    );
DROP POLICY IF EXISTS "Users can insert own moments" ON public.moments;
CREATE POLICY "Users can insert own moments" ON public.moments
    FOR INSERT WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "Users can delete own moments" ON public.moments;
CREATE POLICY "Users can delete own moments" ON public.moments
    FOR DELETE USING (auth.uid() = user_id);

-- LIVE_STREAMS POLICIES
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
DROP POLICY IF EXISTS "Users can insert own live streams" ON public.live_streams;
CREATE POLICY "Users can insert own live streams" ON public.live_streams
    FOR INSERT WITH CHECK (auth.uid() = user_id);
DROP POLICY IF EXISTS "Users can update own live streams" ON public.live_streams;
CREATE POLICY "Users can update own live streams" ON public.live_streams
    FOR UPDATE USING (auth.uid() = user_id);

-- WALLETS POLICIES
DROP POLICY IF EXISTS "Users can view their own wallet" ON public.wallets;
CREATE POLICY "Users can view their own wallet" ON public.wallets
    FOR SELECT USING (auth.uid() = user_id);
DROP POLICY IF EXISTS "Users can update their own wallet" ON public.wallets;
CREATE POLICY "Users can update their own wallet" ON public.wallets
    FOR UPDATE USING (auth.uid() = user_id);

-- VIRTUAL_GIFTS POLICIES
DROP POLICY IF EXISTS "Virtual gifts are viewable by everyone" ON public.virtual_gifts;
CREATE POLICY "Virtual gifts are viewable by everyone" ON public.virtual_gifts
    FOR SELECT USING (is_active = true);

-- GIFT_TRANSACTIONS POLICIES
DROP POLICY IF EXISTS "Users can view their own gift transactions" ON public.gift_transactions;
CREATE POLICY "Users can view their own gift transactions" ON public.gift_transactions
    FOR SELECT USING (auth.uid() = sender_id OR auth.uid() = receiver_id);

-- WALLET_TRANSACTIONS POLICIES
DROP POLICY IF EXISTS "Users can view their own wallet transactions" ON public.wallet_transactions;
CREATE POLICY "Users can view their own wallet transactions" ON public.wallet_transactions
    FOR SELECT USING (auth.uid() = user_id);

-- SUBSCRIPTION_PLANS POLICIES
DROP POLICY IF EXISTS "Subscription plans are viewable by everyone" ON public.subscription_plans;
CREATE POLICY "Subscription plans are viewable by everyone" ON public.subscription_plans
    FOR SELECT USING (is_active = true);

-- USER_SUBSCRIPTIONS POLICIES
DROP POLICY IF EXISTS "Users can view their own subscriptions" ON public.user_subscriptions;
CREATE POLICY "Users can view their own subscriptions" ON public.user_subscriptions
    FOR SELECT USING (auth.uid() = user_id);
DROP POLICY IF EXISTS "Users can manage their own subscriptions" ON public.user_subscriptions;
CREATE POLICY "Users can manage their own subscriptions" ON public.user_subscriptions
    FOR ALL USING (auth.uid() = user_id);

-- AD_CAMPAIGNS POLICIES
DROP POLICY IF EXISTS "Users can view active ad campaigns" ON public.ad_campaigns;
CREATE POLICY "Users can view active ad campaigns" ON public.ad_campaigns
    FOR SELECT USING (status = 'active' OR advertiser_id = auth.uid());
DROP POLICY IF EXISTS "Users can manage their own ad campaigns" ON public.ad_campaigns;
CREATE POLICY "Users can manage their own ad campaigns" ON public.ad_campaigns
    FOR ALL USING (auth.uid() = advertiser_id);

-- HASHTAGS POLICIES
DROP POLICY IF EXISTS "Hashtags are viewable by everyone" ON public.hashtags;
CREATE POLICY "Hashtags are viewable by everyone" ON public.hashtags
    FOR SELECT USING (true);

-- POST_HASHTAGS POLICIES
DROP POLICY IF EXISTS "Post hashtags are viewable by everyone" ON public.post_hashtags;
CREATE POLICY "Post hashtags are viewable by everyone" ON public.post_hashtags
    FOR SELECT USING (true);

-- VIDEO_HASHTAGS POLICIES
DROP POLICY IF EXISTS "Video hashtags are viewable by everyone" ON public.video_hashtags;
CREATE POLICY "Video hashtags are viewable by everyone" ON public.video_hashtags
    FOR SELECT USING (true);

-- ============================================
-- 9. INDEXES FOR PERFORMANCE
-- ============================================

-- Base tables indexes
CREATE INDEX IF NOT EXISTS idx_profiles_numeric_id ON public.profiles(numeric_id);
CREATE INDEX IF NOT EXISTS idx_profiles_email ON public.profiles(email);
CREATE INDEX IF NOT EXISTS idx_profiles_username ON public.profiles(username);
CREATE INDEX IF NOT EXISTS idx_profiles_online ON public.profiles(is_online) WHERE is_online = true;
CREATE INDEX IF NOT EXISTS idx_profiles_last_seen ON public.profiles(last_seen DESC);
CREATE INDEX IF NOT EXISTS idx_profiles_suspension_state ON public.profiles(is_suspended, COALESCE(suspension_end_date, suspended_until) DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_recipient ON public.notifications(recipient_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_actor ON public.notifications(actor_id);
CREATE INDEX IF NOT EXISTS idx_notifications_unread ON public.notifications(recipient_id, is_read) WHERE is_read = false;
CREATE INDEX IF NOT EXISTS idx_notifications_target ON public.notifications(target_id);
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
CREATE INDEX IF NOT EXISTS idx_post_reactions_post_id ON public.post_reactions(post_id);
CREATE INDEX IF NOT EXISTS idx_post_reactions_user_id ON public.post_reactions(user_id);
CREATE INDEX IF NOT EXISTS idx_post_reactions_type ON public.post_reactions(reaction_type);
CREATE INDEX IF NOT EXISTS idx_post_comments_post_id ON public.post_comments(post_id);
CREATE INDEX IF NOT EXISTS idx_moments_user_id ON public.moments(user_id);
CREATE INDEX IF NOT EXISTS idx_moments_expire_at ON public.moments(expires_at);
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

CREATE OR REPLACE FUNCTION public.enforce_profile_login_email_rules()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_primary_email TEXT;
    v_count INTEGER;
BEGIN
    NEW.email := LOWER(TRIM(COALESCE(NEW.email, '')));

    IF NEW.email = '' OR NEW.email !~ '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$' THEN
        RAISE EXCEPTION 'INVALID_EMAIL';
    END IF;

    SELECT LOWER(TRIM(p.email))
      INTO v_primary_email
      FROM public.profiles p
     WHERE p.id = NEW.user_id
     LIMIT 1;

    IF v_primary_email IS NOT NULL AND NEW.email = v_primary_email THEN
        RAISE EXCEPTION 'PRIMARY_EMAIL_NOT_ALLOWED';
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT COUNT(*)
          INTO v_count
          FROM public.profile_login_emails ple
         WHERE ple.user_id = NEW.user_id;

        IF v_count >= 3 THEN
            RAISE EXCEPTION 'MAX_ADDITIONAL_EMAILS_REACHED';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_profile_login_email_rules ON public.profile_login_emails;
CREATE TRIGGER trg_profile_login_email_rules
BEFORE INSERT OR UPDATE OF email ON public.profile_login_emails
FOR EACH ROW EXECUTE FUNCTION public.enforce_profile_login_email_rules();

CREATE OR REPLACE FUNCTION public.resolve_login_email(p_email TEXT)
RETURNS TEXT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_lookup TEXT := LOWER(TRIM(COALESCE(p_email, '')));
    v_user_id UUID;
    v_primary_email TEXT;
BEGIN
    IF v_lookup = '' THEN
        RETURN NULL;
    END IF;

    SELECT ple.user_id
      INTO v_user_id
      FROM public.profile_login_emails ple
     WHERE ple.email = v_lookup
     LIMIT 1;

    IF v_user_id IS NULL THEN
        RETURN v_lookup;
    END IF;

    SELECT LOWER(TRIM(p.email))
      INTO v_primary_email
      FROM public.profiles p
     WHERE p.id = v_user_id
     LIMIT 1;

    RETURN COALESCE(NULLIF(v_primary_email, ''), v_lookup);
END;
$$;

GRANT EXECUTE ON FUNCTION public.resolve_login_email(TEXT) TO anon, authenticated;

CREATE OR REPLACE FUNCTION public.update_user_online_status(
    user_id UUID,
    online_status BOOLEAN
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF auth.uid() IS NULL OR auth.uid() <> user_id THEN
        RAISE EXCEPTION 'FORBIDDEN';
    END IF;

    UPDATE public.profiles
    SET
        is_online = COALESCE(online_status, false),
        last_seen = CASE
            WHEN COALESCE(online_status, false) = false THEN NOW()
            ELSE COALESCE(last_seen, NOW())
        END,
        updated_at = NOW()
    WHERE id = user_id;
END;
$$;

GRANT EXECUTE ON FUNCTION public.update_user_online_status(UUID, BOOLEAN) TO authenticated;

CREATE OR REPLACE FUNCTION public.sync_profile_suspension_dates()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.suspension_end_date IS NOT NULL THEN
        NEW.suspended_until := NEW.suspension_end_date;
    ELSE
        NEW.suspension_end_date := NEW.suspended_until;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sync_profile_suspension_dates ON public.profiles;
CREATE TRIGGER trg_sync_profile_suspension_dates
BEFORE INSERT OR UPDATE OF suspended_until, suspension_end_date
ON public.profiles
FOR EACH ROW EXECUTE FUNCTION public.sync_profile_suspension_dates();

-- CREATE OR REPLACE no puede cambiar RETURNS TABLE en funciones existentes.
-- Eliminamos versiones antiguas de hotfixes para que el deploy sea repetible.
DROP FUNCTION IF EXISTS public.secure_admin_set_user_suspension(UUID, BOOLEAN, INTEGER, TEXT);
DROP FUNCTION IF EXISTS public.secure_admin_set_user_suspension(UUID, BOOLEAN, INTEGER);
DROP FUNCTION IF EXISTS public.secure_admin_set_user_suspension(UUID, BOOLEAN, INTEGER, TEXT, INTEGER, TEXT, BOOLEAN);
DROP FUNCTION IF EXISTS public.secure_get_my_suspension_state();

CREATE OR REPLACE FUNCTION public.secure_get_my_suspension_state()
RETURNS TABLE (
    is_suspended BOOLEAN,
    suspended_until TIMESTAMPTZ,
    suspension_end_date TIMESTAMPTZ,
    suspension_reason TEXT,
    suspension_count INTEGER
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    UPDATE public.profiles p
    SET
        is_suspended = false,
        suspended_until = NULL,
        suspension_end_date = NULL,
        suspension_reason = NULL,
        updated_at = NOW()
    WHERE p.id = v_uid
      AND COALESCE(p.is_suspended, false) = true
      AND COALESCE(p.suspension_end_date, p.suspended_until) IS NOT NULL
      AND COALESCE(p.suspension_end_date, p.suspended_until) <= NOW();

    RETURN QUERY
    SELECT
        COALESCE(p.is_suspended, false),
        p.suspended_until,
        COALESCE(p.suspension_end_date, p.suspended_until),
        p.suspension_reason,
        COALESCE(p.suspension_count, 0)
    FROM public.profiles p
    WHERE p.id = v_uid
    LIMIT 1;
END;
$$;

CREATE OR REPLACE FUNCTION public.secure_admin_set_user_suspension(
    p_user_id UUID,
    p_suspend BOOLEAN DEFAULT true,
    p_days INTEGER DEFAULT 7,
    p_reason TEXT DEFAULT NULL,
    p_duration_value INTEGER DEFAULT NULL,
    p_duration_unit TEXT DEFAULT 'days',
    p_permanent BOOLEAN DEFAULT false
)
RETURNS TABLE (
    user_id UUID,
    is_suspended BOOLEAN,
    suspended_until TIMESTAMPTZ,
    suspension_end_date TIMESTAMPTZ,
    suspension_reason TEXT,
    suspension_count INTEGER
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_actor UUID := auth.uid();
    v_is_admin BOOLEAN := false;
    v_until TIMESTAMPTZ;
    v_reason TEXT := NULLIF(TRIM(COALESCE(p_reason, '')), '');
    v_duration_value INTEGER := GREATEST(COALESCE(p_duration_value, p_days, 7), 1);
    v_duration_unit TEXT := LOWER(TRIM(COALESCE(p_duration_unit, 'days')));
BEGIN
    IF v_actor IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    SELECT
        COALESCE(p.is_admin, false)
        OR LOWER(TRIM(COALESCE(p.email, ''))) IN (
            'gamergamerff017@gmail.com',
            'alvaromendozamarin004@gmail.com'
        )
    INTO v_is_admin
    FROM public.profiles p
    WHERE p.id = v_actor
    LIMIT 1;

    IF NOT COALESCE(v_is_admin, false) THEN
        RAISE EXCEPTION 'ADMIN_REQUIRED';
    END IF;

    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'TARGET_USER_REQUIRED';
    END IF;

    IF COALESCE(p_suspend, true) THEN
        IF v_duration_unit NOT IN ('seconds', 'minutes', 'hours', 'days', 'years') THEN
            v_duration_unit := 'days';
        END IF;

        IF COALESCE(p_permanent, false) THEN
            v_until := NULL;
        ELSE
            v_until := CASE v_duration_unit
                WHEN 'seconds' THEN NOW() + make_interval(secs => v_duration_value)
                WHEN 'minutes' THEN NOW() + make_interval(mins => v_duration_value)
                WHEN 'hours' THEN NOW() + make_interval(hours => v_duration_value)
                WHEN 'years' THEN NOW() + make_interval(years => v_duration_value)
                ELSE NOW() + make_interval(days => v_duration_value)
            END;
        END IF;

        UPDATE public.profiles p
        SET
            is_suspended = true,
            suspended_until = v_until,
            suspension_end_date = v_until,
            suspension_reason = COALESCE(v_reason, 'Moderación administrativa'),
            suspension_count = GREATEST(COALESCE(p.suspension_count, 0), 0) + 1,
            updated_at = NOW()
        WHERE p.id = p_user_id;
    ELSE
        UPDATE public.profiles p
        SET
            is_suspended = false,
            suspended_until = NULL,
            suspension_end_date = NULL,
            suspension_reason = NULL,
            updated_at = NOW()
        WHERE p.id = p_user_id;
    END IF;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'TARGET_PROFILE_NOT_FOUND';
    END IF;

    RETURN QUERY
    SELECT
        p.id,
        COALESCE(p.is_suspended, false),
        p.suspended_until,
        COALESCE(p.suspension_end_date, p.suspended_until),
        p.suspension_reason,
        COALESCE(p.suspension_count, 0)
    FROM public.profiles p
    WHERE p.id = p_user_id
    LIMIT 1;
END;
$$;

GRANT EXECUTE ON FUNCTION public.secure_get_my_suspension_state() TO authenticated;
GRANT EXECUTE ON FUNCTION public.secure_admin_set_user_suspension(UUID, BOOLEAN, INTEGER, TEXT, INTEGER, TEXT, BOOLEAN) TO authenticated;

DROP FUNCTION IF EXISTS public.secure_mark_notifications_read(UUID[], BOOLEAN);

CREATE OR REPLACE FUNCTION public.secure_mark_notifications_read(
    p_notification_ids UUID[] DEFAULT NULL,
    p_mark_all BOOLEAN DEFAULT false
)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_processed INTEGER := 0;
    v_guard RECORD;
    v_ids_len INTEGER := COALESCE(array_length(p_notification_ids, 1), 0);
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'AUTH_REQUIRED';
    END IF;

    IF to_regprocedure('public.guard_rate_limit(uuid,text,text,integer,integer,integer)') IS NOT NULL THEN
        SELECT * INTO v_guard
        FROM public.guard_rate_limit(v_uid, 'notifications_read', 'notifications', 180, 60, 90);

        IF v_guard.allowed IS NOT TRUE THEN
            RAISE EXCEPTION 'RATE_LIMIT_NOTIFICATIONS_READ:%', COALESCE(v_guard.retry_after_ms, 0);
        END IF;
    END IF;

    IF COALESCE(p_mark_all, false) THEN
        UPDATE public.notifications
        SET is_read = true
        WHERE recipient_id = v_uid
          AND is_read = false;
    ELSE
        IF v_ids_len <= 0 THEN
            RAISE EXCEPTION 'NOTIFICATION_IDS_REQUIRED';
        END IF;

        UPDATE public.notifications
        SET is_read = true
        WHERE recipient_id = v_uid
          AND id = ANY(p_notification_ids)
          AND is_read = false;
    END IF;

    GET DIAGNOSTICS v_processed = ROW_COUNT;
    RETURN v_processed;
END;
$$;

GRANT EXECUTE ON FUNCTION public.secure_mark_notifications_read(UUID[], BOOLEAN) TO authenticated;

-- Secure self-service account deletion.
CREATE OR REPLACE FUNCTION public.delete_own_account()
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth
AS $$
DECLARE
    v_user_id UUID := auth.uid();
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'NOT_AUTHENTICATED';
    END IF;

    IF to_regclass('public.profiles') IS NOT NULL THEN
        UPDATE public.profiles
        SET
            status = 'deactivated',
            is_private = true,
            profile_visibility = 'connections',
            last_seen_visibility = 'nobody',
            online_status_visibility = 'nobody',
            updated_at = now()
        WHERE id = v_user_id;
    END IF;

    IF to_regclass('public.profile_login_emails') IS NOT NULL THEN
        DELETE FROM public.profile_login_emails
        WHERE user_id = v_user_id;
    END IF;

    IF to_regclass('public.gift_transactions') IS NOT NULL THEN
        DELETE FROM public.gift_transactions
        WHERE sender_id = v_user_id OR receiver_id = v_user_id;
    END IF;

    IF to_regclass('public.wallet_transactions') IS NOT NULL THEN
        DELETE FROM public.wallet_transactions
        WHERE user_id = v_user_id;
    END IF;

    IF to_regclass('public.ad_impressions') IS NOT NULL
       AND to_regclass('public.ad_campaigns') IS NOT NULL THEN
        DELETE FROM public.ad_impressions
        WHERE campaign_id IN (
            SELECT id FROM public.ad_campaigns WHERE advertiser_id = v_user_id
        );
    END IF;

    IF to_regclass('public.ad_impressions') IS NOT NULL THEN
        UPDATE public.ad_impressions
        SET user_id = NULL
        WHERE user_id = v_user_id;
    END IF;

    IF to_regclass('public.ad_campaigns') IS NOT NULL THEN
        DELETE FROM public.ad_campaigns
        WHERE advertiser_id = v_user_id;
    END IF;

    DELETE FROM auth.users
    WHERE id = v_user_id;
END;
$$;

REVOKE ALL ON FUNCTION public.delete_own_account() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.delete_own_account() TO authenticated;

-- Wallet, gifts and premium RPCs.
-- Estas funciones usan SECURITY DEFINER, por eso cada una valida auth.uid(),
-- rol de servicio o admin antes de mutar saldos/suscripciones.
CREATE OR REPLACE FUNCTION public.wallet_get_or_create()
RETURNS TABLE (
    user_id UUID,
    balance_diamonds INT,
    balance_credits DECIMAL(10,2),
    total_spent DECIMAL(10,2)
) AS $$
DECLARE
    current_user_id UUID := auth.uid();
BEGIN
    IF current_user_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    INSERT INTO public.wallets (user_id, balance_diamonds, balance_credits, total_spent)
    VALUES (current_user_id, 0, 0.00, 0.00)
    ON CONFLICT (user_id) DO NOTHING;

    RETURN QUERY
    SELECT w.user_id, w.balance_diamonds, w.balance_credits, w.total_spent
    FROM public.wallets w
    WHERE w.user_id = current_user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

CREATE OR REPLACE FUNCTION public.wallet_add_diamonds(
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
    caller_is_admin BOOLEAN := EXISTS (
        SELECT 1
        FROM public.profiles p
        WHERE p.id = auth.uid()
          AND COALESCE(p.is_admin, FALSE) = TRUE
    );
BEGIN
    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'Target user is required';
    END IF;

    IF COALESCE(p_amount, 0) <= 0 THEN
        RAISE EXCEPTION 'Amount must be greater than zero';
    END IF;

    IF COALESCE(auth.role(), '') <> 'service_role' AND NOT caller_is_admin THEN
        RAISE EXCEPTION 'Only service role or admins can add diamonds';
    END IF;

    INSERT INTO public.wallets (user_id, balance_diamonds, balance_credits, total_spent)
    VALUES (p_user_id, p_amount, 0.00, 0.00)
    ON CONFLICT (user_id) DO UPDATE SET
        balance_diamonds = public.wallets.balance_diamonds + p_amount,
        updated_at = now()
    RETURNING public.wallets.balance_diamonds, public.wallets.balance_credits, public.wallets.total_spent
    INTO new_balance_diamonds, current_credits, current_total_spent;

    INSERT INTO public.wallet_transactions (
        user_id,
        type,
        amount_diamonds,
        balance_after_diamonds,
        balance_after_credits,
        description
    ) VALUES (
        p_user_id,
        'deposit',
        p_amount,
        new_balance_diamonds,
        current_credits,
        'Diamantes agregados desde: ' || COALESCE(NULLIF(trim(p_source), ''), 'purchase')
    );

    RETURN QUERY
    SELECT w.user_id, w.balance_diamonds, w.balance_credits, w.total_spent
    FROM public.wallets w
    WHERE w.user_id = p_user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

CREATE OR REPLACE FUNCTION public.wallet_spend_diamonds(
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
    caller_is_admin BOOLEAN := EXISTS (
        SELECT 1
        FROM public.profiles p
        WHERE p.id = auth.uid()
          AND COALESCE(p.is_admin, FALSE) = TRUE
    );
BEGIN
    IF p_user_id IS NULL THEN
        RAISE EXCEPTION 'Target user is required';
    END IF;

    IF COALESCE(p_amount, 0) <= 0 THEN
        RAISE EXCEPTION 'Amount must be greater than zero';
    END IF;

    IF COALESCE(auth.role(), '') <> 'service_role'
       AND NOT caller_is_admin
       AND auth.uid() IS DISTINCT FROM p_user_id THEN
        RAISE EXCEPTION 'Users can only spend their own diamonds';
    END IF;

    INSERT INTO public.wallets (user_id, balance_diamonds, balance_credits, total_spent)
    VALUES (p_user_id, 0, 0.00, 0.00)
    ON CONFLICT (user_id) DO NOTHING;

    SELECT w.balance_diamonds, w.balance_credits, w.total_spent
    INTO current_balance, current_credits, current_total_spent
    FROM public.wallets w
    WHERE w.user_id = p_user_id
    FOR UPDATE;

    IF COALESCE(current_balance, 0) < p_amount THEN
        RAISE EXCEPTION 'Saldo insuficiente. Balance actual: %, requerido: %',
            COALESCE(current_balance, 0),
            p_amount;
    END IF;

    UPDATE public.wallets SET
        balance_diamonds = balance_diamonds - p_amount,
        total_spent = total_spent + (p_amount * 0.1),
        updated_at = now()
    WHERE public.wallets.user_id = p_user_id
    RETURNING public.wallets.balance_diamonds, public.wallets.balance_credits, public.wallets.total_spent
    INTO new_balance, current_credits, current_total_spent;

    INSERT INTO public.wallet_transactions (
        user_id,
        type,
        amount_diamonds,
        balance_after_diamonds,
        balance_after_credits,
        reference_id,
        description
    ) VALUES (
        p_user_id,
        'purchase',
        -p_amount,
        new_balance,
        current_credits,
        p_reference_id,
        'Compra: ' || COALESCE(NULLIF(trim(p_purpose), ''), 'wallet')
    );

    RETURN QUERY
    SELECT w.user_id, w.balance_diamonds, w.balance_credits, w.total_spent
    FROM public.wallets w
    WHERE w.user_id = p_user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

CREATE OR REPLACE FUNCTION public.send_virtual_gift(
    p_sender_id UUID,
    p_receiver_id UUID,
    p_gift_id UUID,
    p_message TEXT DEFAULT NULL,
    p_live_stream_id UUID DEFAULT NULL
)
RETURNS TABLE (
    transaction_id UUID,
    sender_balance_diamonds INT,
    receiver_wallet_created BOOLEAN
) AS $$
DECLARE
    gift_price INT;
    tx_id UUID;
    sender_balance INT;
    inserted_rows INT := 0;
    caller_is_admin BOOLEAN := EXISTS (
        SELECT 1
        FROM public.profiles p
        WHERE p.id = auth.uid()
          AND COALESCE(p.is_admin, FALSE) = TRUE
    );
BEGIN
    IF p_sender_id IS NULL OR p_receiver_id IS NULL OR p_gift_id IS NULL THEN
        RAISE EXCEPTION 'Sender, receiver and gift are required';
    END IF;

    IF COALESCE(auth.role(), '') <> 'service_role'
       AND NOT caller_is_admin
       AND auth.uid() IS DISTINCT FROM p_sender_id THEN
        RAISE EXCEPTION 'Users can only send gifts from their own account';
    END IF;

    SELECT vg.price_diamonds
    INTO gift_price
    FROM public.virtual_gifts vg
    WHERE vg.id = p_gift_id
      AND vg.is_active = TRUE;

    IF gift_price IS NULL THEN
        RAISE EXCEPTION 'Regalo no encontrado o inactivo';
    END IF;

    SELECT spent.balance_diamonds
    INTO sender_balance
    FROM public.wallet_spend_diamonds(p_sender_id, gift_price, 'regalo_virtual', p_gift_id) spent;

    INSERT INTO public.gift_transactions (
        sender_id,
        receiver_id,
        gift_id,
        live_stream_id,
        message,
        amount_diamonds,
        amount_credits
    ) VALUES (
        p_sender_id,
        p_receiver_id,
        p_gift_id,
        p_live_stream_id,
        NULLIF(trim(COALESCE(p_message, '')), ''),
        gift_price,
        0.00
    ) RETURNING id INTO tx_id;

    INSERT INTO public.wallets (user_id, balance_diamonds, balance_credits, total_spent)
    VALUES (p_receiver_id, 0, 0.00, 0.00)
    ON CONFLICT (user_id) DO NOTHING;

    GET DIAGNOSTICS inserted_rows = ROW_COUNT;

    RETURN QUERY SELECT tx_id, sender_balance, inserted_rows > 0;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

CREATE OR REPLACE FUNCTION public.subscribe_to_plan(
    p_user_id UUID,
    p_plan_id UUID,
    p_payment_method TEXT DEFAULT 'wallet'
)
RETURNS TABLE (
    subscription_id UUID,
    plan_name TEXT,
    current_period_start TIMESTAMPTZ,
    current_period_end TIMESTAMPTZ
) AS $$
DECLARE
    selected_plan_price DECIMAL(10,2);
    selected_plan_name TEXT;
    sub_id UUID;
    period_start TIMESTAMPTZ;
    period_end TIMESTAMPTZ;
    diamonds_cost INT;
    normalized_payment_method TEXT := COALESCE(NULLIF(trim(p_payment_method), ''), 'wallet');
    caller_is_admin BOOLEAN := EXISTS (
        SELECT 1
        FROM public.profiles p
        WHERE p.id = auth.uid()
          AND COALESCE(p.is_admin, FALSE) = TRUE
    );
BEGIN
    IF p_user_id IS NULL OR p_plan_id IS NULL THEN
        RAISE EXCEPTION 'User and plan are required';
    END IF;

    IF COALESCE(auth.role(), '') <> 'service_role'
       AND NOT caller_is_admin
       AND auth.uid() IS DISTINCT FROM p_user_id THEN
        RAISE EXCEPTION 'Users can only subscribe their own account';
    END IF;

    IF normalized_payment_method <> 'wallet'
       AND COALESCE(auth.role(), '') <> 'service_role'
       AND NOT caller_is_admin THEN
        RAISE EXCEPTION 'Only wallet payments are available from the client';
    END IF;

    SELECT sp.price_monthly, sp.name
    INTO selected_plan_price, selected_plan_name
    FROM public.subscription_plans sp
    WHERE sp.id = p_plan_id
      AND sp.is_active = TRUE;

    IF selected_plan_price IS NULL THEN
        RAISE EXCEPTION 'Plan no encontrado o inactivo';
    END IF;

    period_start := now();
    period_end := period_start + INTERVAL '1 month';
    diamonds_cost := CEIL(selected_plan_price * 10)::INT;

    IF normalized_payment_method = 'wallet' AND diamonds_cost > 0 THEN
        PERFORM public.wallet_spend_diamonds(
            p_user_id,
            diamonds_cost,
            'suscripcion_premium',
            p_plan_id
        );
    END IF;

    SELECT us.id
    INTO sub_id
    FROM public.user_subscriptions us
    WHERE us.user_id = p_user_id
      AND us.plan_id = p_plan_id
      AND us.status = 'active'
    ORDER BY us.current_period_end DESC
    LIMIT 1
    FOR UPDATE;

    IF sub_id IS NOT NULL THEN
        UPDATE public.user_subscriptions
        SET current_period_start = period_start,
            current_period_end = period_end,
            cancel_at_period_end = FALSE,
            updated_at = now()
        WHERE id = sub_id;

        RETURN QUERY SELECT sub_id, selected_plan_name, period_start, period_end;
        RETURN;
    END IF;

    UPDATE public.user_subscriptions
    SET status = 'canceled',
        cancel_at_period_end = TRUE,
        updated_at = now()
    WHERE user_id = p_user_id
      AND status = 'active';

    INSERT INTO public.user_subscriptions (
        user_id,
        plan_id,
        status,
        current_period_start,
        current_period_end,
        cancel_at_period_end,
        created_at,
        updated_at
    ) VALUES (
        p_user_id,
        p_plan_id,
        'active',
        period_start,
        period_end,
        FALSE,
        now(),
        now()
    )
    RETURNING id INTO sub_id;

    RETURN QUERY SELECT sub_id, selected_plan_name, period_start, period_end;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public;

REVOKE ALL ON FUNCTION public.wallet_get_or_create() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.wallet_add_diamonds(UUID, INT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.wallet_spend_diamonds(UUID, INT, TEXT, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.send_virtual_gift(UUID, UUID, UUID, TEXT, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.subscribe_to_plan(UUID, UUID, TEXT) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.wallet_get_or_create() TO authenticated;
GRANT EXECUTE ON FUNCTION public.wallet_add_diamonds(UUID, INT, TEXT) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.wallet_spend_diamonds(UUID, INT, TEXT, UUID) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.send_virtual_gift(UUID, UUID, UUID, TEXT, UUID) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.subscribe_to_plan(UUID, UUID, TEXT) TO authenticated, service_role;

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
DROP TRIGGER IF EXISTS update_profiles_updated_at ON public.profiles;
CREATE TRIGGER update_profiles_updated_at BEFORE UPDATE ON public.profiles
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
DROP TRIGGER IF EXISTS update_videos_updated_at ON public.videos;
CREATE TRIGGER update_videos_updated_at BEFORE UPDATE ON public.videos
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
DROP TRIGGER IF EXISTS update_chats_updated_at ON public.chats;
CREATE TRIGGER update_chats_updated_at BEFORE UPDATE ON public.chats
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
DROP TRIGGER IF EXISTS update_messages_updated_at ON public.messages;
CREATE TRIGGER update_messages_updated_at BEFORE UPDATE ON public.messages
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
DROP TRIGGER IF EXISTS update_posts_updated_at ON public.posts;
CREATE TRIGGER update_posts_updated_at BEFORE UPDATE ON public.posts
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
DROP TRIGGER IF EXISTS update_post_reactions_updated_at ON public.post_reactions;
CREATE TRIGGER update_post_reactions_updated_at BEFORE UPDATE ON public.post_reactions
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

-- Notification trigger functions
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
    FOR EACH ROW EXECUTE FUNCTION notify_on_follow();

CREATE OR REPLACE FUNCTION notify_on_post_reaction()
RETURNS TRIGGER AS $$
DECLARE
    post_owner_id UUID;
BEGIN
    SELECT user_id INTO post_owner_id FROM public.posts WHERE id = NEW.post_id;
    IF post_owner_id IS NOT NULL AND post_owner_id != NEW.user_id THEN
        INSERT INTO public.notifications (recipient_id, actor_id, type, target_id, target_type, extra_data)
        VALUES (
            post_owner_id,
            NEW.user_id,
            'reaction_post',
            NEW.post_id,
            'post',
            jsonb_build_object('reaction_type', NEW.reaction_type)
        );
    END IF;
    RETURN NEW;
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'Error creating post reaction notification: %', SQLERRM;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_new_post_reaction ON public.post_reactions;
CREATE TRIGGER on_new_post_reaction
    AFTER INSERT ON public.post_reactions
    FOR EACH ROW EXECUTE FUNCTION notify_on_post_reaction();

CREATE OR REPLACE FUNCTION notify_on_post_comment()
RETURNS TRIGGER AS $$
DECLARE
    post_owner_id UUID;
BEGIN
    SELECT user_id INTO post_owner_id FROM public.posts WHERE id = NEW.post_id;
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
    FOR EACH ROW EXECUTE FUNCTION notify_on_post_comment();

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
    FOR EACH ROW EXECUTE FUNCTION notify_on_video_like();

DO $$
BEGIN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.notifications;
EXCEPTION
    WHEN duplicate_object THEN NULL;
    WHEN undefined_object THEN NULL;
    WHEN OTHERS THEN
        RAISE NOTICE 'notifications realtime publication skipped: %', SQLERRM;
END $$;

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
('Corazón', 'Un corazón para expresar tu cariño', 'conectate://gift-assets/heart', 10, 1.99, NULL, 1),
('Rosa', 'Una hermosa rosa virtual', 'conectate://gift-assets/rose', 25, 4.99, NULL, 2),
('Trofeo', '¡Felicitaciones por tu victoria!', 'conectate://gift-assets/trophy', 50, 9.99, NULL, 3),
('Fuegos Artificiales', '¡Celebración épica!', 'conectate://gift-assets/fireworks', 100, 19.99, NULL, 4),
('Superestrella', 'Eres una estrella', 'conectate://gift-assets/star', 200, 39.99, NULL, 5)
ON CONFLICT (name) DO UPDATE SET
    description = EXCLUDED.description,
    image_url = EXCLUDED.image_url,
    price_diamonds = EXCLUDED.price_diamonds,
    price_credits = EXCLUDED.price_credits,
    animation_url = EXCLUDED.animation_url,
    is_active = true,
    sort_order = EXCLUDED.sort_order;

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

NOTIFY pgrst, 'reload schema';

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
