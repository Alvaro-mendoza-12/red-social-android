-- Advanced Comments: Threading and Deletion
BEGIN;

-- 1. Add parent_id for threaded comments (replies)
ALTER TABLE public.post_comments ADD COLUMN IF NOT EXISTS parent_id uuid REFERENCES public.post_comments(id) ON DELETE CASCADE;

-- 2. Update Deletion Policies
-- Drop existing delete policy if any
DROP POLICY IF EXISTS "Users can delete own comments" ON public.post_comments;
DROP POLICY IF EXISTS "Post owners can delete comments" ON public.post_comments;

-- Policy: Author can delete their own comment
CREATE POLICY "Users can delete own comments" ON public.post_comments
    FOR DELETE USING (auth.uid() = user_id);

-- Policy: Post owner can delete any comment on their post
-- We need to check the posts table for ownership
CREATE POLICY "Post owners can delete comments on their posts" ON public.post_comments
    FOR DELETE USING (
        EXISTS (
            SELECT 1 FROM public.posts
            WHERE posts.id = post_comments.post_id
            AND posts.user_id = auth.uid()
        )
    );

COMMIT;
