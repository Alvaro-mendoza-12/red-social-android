-- Create the shares table to handle reposts/shares of posts and videos
-- Using explicit columns for post and video to enable efficient Supabase/Postgrest joins
CREATE TABLE IF NOT EXISTS public.shares (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    post_id UUID REFERENCES public.posts(id) ON DELETE CASCADE,
    video_id UUID REFERENCES public.videos(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now()) NOT NULL,
    
    -- Ensure only one of post_id or video_id is set
    CONSTRAINT check_only_one_target CHECK (
        (post_id IS NOT NULL AND video_id IS NULL) OR
        (post_id IS NULL AND video_id IS NOT NULL)
    ),
    
    -- Prevent duplicate shares of the same content by the same user
    UNIQUE(user_id, post_id),
    UNIQUE(user_id, video_id)
);

-- Index for performance on the Compartidos tab
CREATE INDEX IF NOT EXISTS idx_shares_user_id ON public.shares(user_id);
CREATE INDEX IF NOT EXISTS idx_shares_post_id ON public.shares(post_id) WHERE post_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_shares_video_id ON public.shares(video_id) WHERE video_id IS NOT NULL;

-- Enable RLS
ALTER TABLE public.shares ENABLE ROW LEVEL SECURITY;

-- Policies
-- Everyone can see shared content to build public shared feeds
CREATE POLICY "Shares are viewable by everyone" 
ON public.shares FOR SELECT 
USING (true);

-- Users can share (insert) and unshare (delete) their own actions
CREATE POLICY "Users can manage their own shares" 
ON public.shares FOR ALL 
USING (auth.uid() = user_id)
WITH CHECK (auth.uid() = user_id);
