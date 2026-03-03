-- Fix the broken foreign key in post_comments
-- It was incorrectly referencing 'users' instead of 'profiles'

BEGIN;

-- 1. Try to drop the old constraint if it exists (might be named differently depending on PG)
ALTER TABLE public.post_comments DROP CONSTRAINT IF EXISTS post_comments_user_id_fkey;

-- 2. Add the correct constraint
ALTER TABLE public.post_comments 
ADD CONSTRAINT post_comments_user_id_fkey 
FOREIGN KEY (user_id) REFERENCES public.profiles(id) ON DELETE CASCADE;

-- 3. Ensure post_id also has a valid constraint just in case
ALTER TABLE public.post_comments DROP CONSTRAINT IF EXISTS post_comments_post_id_fkey;
ALTER TABLE public.post_comments 
ADD CONSTRAINT post_comments_post_id_fkey 
FOREIGN KEY (post_id) REFERENCES public.posts(id) ON DELETE CASCADE;

COMMIT;
