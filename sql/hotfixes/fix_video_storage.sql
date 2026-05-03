-- ==========================================
-- FIX STORAGE BUCKETS AND POLICIES
-- ==========================================

-- 1. Create Buckets if they don't exist
DO $$
BEGIN
    INSERT INTO storage.buckets (id, name, public)
    VALUES ('videos', 'videos', true)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO storage.buckets (id, name, public)
    VALUES ('thumbnails', 'thumbnails', true)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO storage.buckets (id, name, public)
    VALUES ('video-comments-media', 'video-comments-media', true)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO storage.buckets (id, name, public)
    VALUES ('avatars', 'avatars', true)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO storage.buckets (id, name, public)
    VALUES ('chat-media', 'chat-media', true)
    ON CONFLICT (id) DO NOTHING;
END $$;

-- 2. Drop existing policies to avoid conflicts
DROP POLICY IF EXISTS "Public Access" ON storage.objects;
DROP POLICY IF EXISTS "Anyone can upload" ON storage.objects;
DROP POLICY IF EXISTS "Anyone can update" ON storage.objects;
DROP POLICY IF EXISTS "Public Read Access" ON storage.objects;
DROP POLICY IF EXISTS "Public Upload Access" ON storage.objects;
DROP POLICY IF EXISTS "Public Update Access" ON storage.objects;

-- 3. Create General Policies
CREATE POLICY "Public Read Access"
ON storage.objects FOR SELECT
TO public
USING (bucket_id IN ('videos', 'thumbnails', 'video-comments-media', 'avatars', 'chat-media', 'groups', 'communities'));

CREATE POLICY "Public Upload Access"
ON storage.objects FOR INSERT
TO public
WITH CHECK (bucket_id IN ('videos', 'thumbnails', 'video-comments-media', 'avatars', 'chat-media', 'groups', 'communities'));

CREATE POLICY "Public Update Access"
ON storage.objects FOR UPDATE
TO public
USING (bucket_id IN ('videos', 'thumbnails', 'video-comments-media', 'avatars', 'chat-media', 'groups', 'communities'));

-- 4. Fix 'videos' table columns to match Kotlin models (snake_case)
-- We check if the target column exists before renaming to avoid "already exists" errors
DO $$
BEGIN
    -- user_id
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='videos' AND column_name='userid') 
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='videos' AND column_name='user_id') THEN
        ALTER TABLE public.videos RENAME COLUMN userid TO user_id;
    END IF;

    -- user_name
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='videos' AND column_name='username') 
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='videos' AND column_name='user_name') THEN
        ALTER TABLE public.videos RENAME COLUMN username TO user_name;
    END IF;

    -- user_profile_image
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='videos' AND column_name='userprofileimage') 
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='videos' AND column_name='user_profile_image') THEN
        ALTER TABLE public.videos RENAME COLUMN userprofileimage TO user_profile_image;
    END IF;

    -- video_url
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='videos' AND column_name='videourl') 
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='videos' AND column_name='video_url') THEN
        ALTER TABLE public.videos RENAME COLUMN videourl TO video_url;
    END IF;

    -- thumbnail_url
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='videos' AND column_name='thumbnailurl') 
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='videos' AND column_name='thumbnail_url') THEN
        ALTER TABLE public.videos RENAME COLUMN thumbnailurl TO thumbnail_url;
    END IF;

    -- is_public
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='videos' AND column_name='ispublic') 
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='videos' AND column_name='is_public') THEN
        ALTER TABLE public.videos RENAME COLUMN ispublic TO is_public;
    END IF;

    -- like_count
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='videos' AND column_name='likecount') 
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='videos' AND column_name='like_count') THEN
        ALTER TABLE public.videos RENAME COLUMN likecount TO like_count;
    END IF;

    -- comment_count
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='videos' AND column_name='commentcount') 
       AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='videos' AND column_name='comment_count') THEN
        ALTER TABLE public.videos RENAME COLUMN commentcount TO comment_count;
    END IF;

END $$;

-- 5. Ensure all necessary columns exist with correct names
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS user_name TEXT DEFAULT '';
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS user_profile_image TEXT DEFAULT '';
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS views INTEGER DEFAULT 0;
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS like_count INTEGER DEFAULT 0;
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS comment_count INTEGER DEFAULT 0;

-- Clean up obsolete columns if both exist (Optional safe check)
-- DO $$ BEGIN 
-- IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='videos' AND column_name='userid') 
--    AND EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='videos' AND column_name='user_id') THEN
--    ALTER TABLE public.videos DROP COLUMN userid;
-- END IF;
-- END $$;
