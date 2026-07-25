-- Corrección de Políticas de Eliminación y Restricciones en Cascada (ON DELETE CASCADE)
-- Ejecuta este script en el SQL Editor de Supabase.

BEGIN;

-- 1. Asegurar que los post_likes tengan ON DELETE CASCADE
ALTER TABLE public.post_likes
DROP CONSTRAINT IF EXISTS post_likes_post_id_fkey,
ADD CONSTRAINT post_likes_post_id_fkey
FOREIGN KEY (post_id) REFERENCES public.posts(id) ON DELETE CASCADE;

ALTER TABLE public.post_likes
DROP CONSTRAINT IF EXISTS post_likes_user_id_fkey,
ADD CONSTRAINT post_likes_user_id_fkey
FOREIGN KEY (user_id) REFERENCES public.profiles(id) ON DELETE CASCADE;

-- 2. Asegurar que los post_comments tengan ON DELETE CASCADE
ALTER TABLE public.post_comments
DROP CONSTRAINT IF EXISTS post_comments_post_id_fkey,
ADD CONSTRAINT post_comments_post_id_fkey
FOREIGN KEY (post_id) REFERENCES public.posts(id) ON DELETE CASCADE;

ALTER TABLE public.post_comments
DROP CONSTRAINT IF EXISTS post_comments_user_id_fkey,
ADD CONSTRAINT post_comments_user_id_fkey
FOREIGN KEY (user_id) REFERENCES public.profiles(id) ON DELETE CASCADE;

-- 3. Asegurar las tablas de interacciones de videos
-- Video Comments
ALTER TABLE public.video_comments
DROP CONSTRAINT IF EXISTS video_comments_video_id_fkey,
ADD CONSTRAINT video_comments_video_id_fkey
FOREIGN KEY (video_id) REFERENCES public.videos(id) ON DELETE CASCADE;

ALTER TABLE public.video_comments
DROP CONSTRAINT IF EXISTS video_comments_user_id_fkey,
ADD CONSTRAINT video_comments_user_id_fkey
FOREIGN KEY (user_id) REFERENCES public.profiles(id) ON DELETE CASCADE;

-- 4. Arreglar permisos para borrar videos y publicaciones (RLS)
-- Políticas para posts
DROP POLICY IF EXISTS "Users can delete own posts" ON public.posts;
CREATE POLICY "Users can delete own posts" ON public.posts
FOR DELETE USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can update own posts" ON public.posts;
CREATE POLICY "Users can update own posts" ON public.posts
FOR UPDATE USING (auth.uid() = user_id);

-- Políticas para video_comments
DROP POLICY IF EXISTS "Users can delete own video comments" ON public.video_comments;
CREATE POLICY "Users can delete own video comments" ON public.video_comments
FOR DELETE USING (auth.uid() = user_id);

-- Políticas para post_comments
DROP POLICY IF EXISTS "Users can delete own post comments" ON public.post_comments;
CREATE POLICY "Users can delete own post comments" ON public.post_comments
FOR DELETE USING (auth.uid() = user_id);

COMMIT;
