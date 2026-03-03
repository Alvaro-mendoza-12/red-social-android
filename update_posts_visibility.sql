-- Add visibility column to posts table
ALTER TABLE public.posts ADD COLUMN IF NOT EXISTS visibility text DEFAULT 'public';

-- Optional: Add index for performance if filtering by visibility often
CREATE INDEX IF NOT EXISTS posts_visibility_idx ON public.posts (visibility);

-- Create post_comments table if it doesn't exist
CREATE TABLE IF NOT EXISTS public.post_comments (
    id uuid DEFAULT gen_random_uuid() PRIMARY KEY,
    post_id uuid REFERENCES public.posts(id) ON DELETE CASCADE,
    user_id uuid REFERENCES public.users(id) ON DELETE CASCADE, -- Assuming users table, or profiles. Usually auth.users but referenced in public schemas via profiles usually.
    content text NOT NULL,
    created_at timestamptz DEFAULT now()
);

-- Policy to allow authenticated users to comment
ALTER TABLE public.post_comments ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Users can create comments" ON public.post_comments
    FOR INSERT WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users can view comments" ON public.post_comments
    FOR SELECT USING (true);
    
-- Function to increment comment count on post
CREATE OR REPLACE FUNCTION public.increment_comment_count()
RETURNS TRIGGER AS $$
BEGIN
  UPDATE public.posts
  SET comment_count = comment_count + 1
  WHERE id = NEW.post_id;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE TRIGGER on_comment_created
  AFTER INSERT ON public.post_comments
  FOR EACH ROW EXECUTE PROCEDURE public.increment_comment_count();
