-- ============================================
-- POSTS AND POST LIKES SCHEMA
-- ============================================
-- This schema enables Facebook-style posts with images, likes, and comments
-- for the profile feed sections feature.

-- 1. Create posts table
CREATE TABLE IF NOT EXISTS public.posts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    user_name TEXT NOT NULL,
    user_profile_image TEXT,
    content TEXT DEFAULT '',
    media_url TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    like_count INT DEFAULT 0,
    comment_count INT DEFAULT 0,
    is_verified BOOLEAN DEFAULT FALSE
);

-- 2. Create post_likes table
CREATE TABLE IF NOT EXISTS public.post_likes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id UUID NOT NULL REFERENCES public.posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(post_id, user_id) -- Prevent duplicate likes
);

-- 3. Create indexes for better performance
CREATE INDEX IF NOT EXISTS idx_posts_user_id ON public.posts(user_id);
CREATE INDEX IF NOT EXISTS idx_posts_created_at ON public.posts(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_post_likes_post_id ON public.post_likes(post_id);
CREATE INDEX IF NOT EXISTS idx_post_likes_user_id ON public.post_likes(user_id);

-- 4. Row Level Security (RLS) Policies for posts
ALTER TABLE public.posts ENABLE ROW LEVEL SECURITY;

-- Allow anyone to read posts (public feed)
CREATE POLICY "Posts are viewable by everyone"
ON public.posts FOR SELECT
USING (true);

-- Allow authenticated users to create posts
CREATE POLICY "Authenticated users can create posts"
ON public.posts FOR INSERT
TO authenticated
WITH CHECK (auth.uid() = user_id);

-- Allow users to delete their own posts
CREATE POLICY "Users can delete their own posts"
ON public.posts FOR DELETE
TO authenticated
USING (auth.uid() = user_id);

-- Allow users to update their own posts (for editing)
CREATE POLICY "Users can update their own posts"
ON public.posts FOR UPDATE
TO authenticated
USING (auth.uid() = user_id);

-- 5. Row Level Security (RLS) Policies for post_likes
ALTER TABLE public.post_likes ENABLE ROW LEVEL SECURITY;

-- Allow anyone to read likes
CREATE POLICY "Post likes are viewable by everyone"
ON public.post_likes FOR SELECT
USING (true);

-- Allow authenticated users to create likes
CREATE POLICY "Authenticated users can like posts"
ON public.post_likes FOR INSERT
TO authenticated
WITH CHECK (auth.uid() = user_id);

-- Allow users to delete their own likes (unlike)
CREATE POLICY "Users can unlike posts"
ON public.post_likes FOR DELETE
TO authenticated
USING (auth.uid() = user_id);

-- 6. Database Triggers to update like_count automatically
CREATE OR REPLACE FUNCTION update_post_like_count()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE public.posts
        SET like_count = like_count + 1
        WHERE id = NEW.post_id;
        RETURN NEW;
    ELSIF TG_OP = 'DELETE' THEN
        UPDATE public.posts
        SET like_count = GREATEST(0, like_count - 1)
        WHERE id = OLD.post_id;
        RETURN OLD;
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Create trigger for like count updates
DROP TRIGGER IF EXISTS trigger_update_post_like_count ON public.post_likes;
CREATE TRIGGER trigger_update_post_like_count
AFTER INSERT OR DELETE ON public.post_likes
FOR EACH ROW
EXECUTE FUNCTION update_post_like_count();

-- 7. Enable Realtime for posts (optional but recommended)
-- This allows the app to receive live updates when new posts are created
ALTER PUBLICATION supabase_realtime ADD TABLE public.posts;
ALTER PUBLICATION supabase_realtime ADD TABLE public.post_likes;

-- 8. Create storage bucket for post media (if not exists)
-- Note: This SQL won't work directly, you need to create this via Supabase Dashboard or API
-- Navigate to: Storage → Create a new bucket → Name: "post-media" → Public: Yes

-- Storage policies (apply via Supabase Dashboard or with SQL after bucket creation)
-- Policy 1: Allow authenticated users to upload to their own folder
-- INSERT policy: bucket_id = 'post-media' AND (storage.foldername(name))[1] = auth.uid()::text

-- Policy 2: Allow everyone to read/download
-- SELECT policy: bucket_id = 'post-media'

-- ============================================
-- VERIFICATION QUERIES
-- ============================================
-- Run these to verify the schema was created correctly:

-- Check posts table
-- SELECT * FROM public.posts LIMIT 5;

-- Check post_likes table
-- SELECT * FROM public.post_likes LIMIT 5;

-- Check policies
-- SELECT tablename, policyname, permissive, roles, cmd, qual 
-- FROM pg_policies 
-- WHERE tablename IN ('posts', 'post_likes');
