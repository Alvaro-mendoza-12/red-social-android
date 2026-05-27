-- ============================================
-- Post Comment Audio Support
-- Safe/idempotent migration for Sprint 1-5 social comments.
-- ============================================

ALTER TABLE public.post_comments
    ADD COLUMN IF NOT EXISTS media_url TEXT,
    ADD COLUMN IF NOT EXISTS content_type TEXT NOT NULL DEFAULT 'text';

ALTER TABLE public.post_comments
    DROP CONSTRAINT IF EXISTS post_comments_content_type_check;

ALTER TABLE public.post_comments
    ADD CONSTRAINT post_comments_content_type_check
    CHECK (content_type IN ('text', 'audio'));

CREATE INDEX IF NOT EXISTS idx_post_comments_content_type
    ON public.post_comments(post_id, content_type, created_at DESC);

INSERT INTO storage.buckets (id, name, public)
VALUES ('post-comments-media', 'post-comments-media', true)
ON CONFLICT (id) DO UPDATE SET
    public = EXCLUDED.public,
    updated_at = now();

DROP POLICY IF EXISTS "Give access to authenticated users 1jis3c_0" ON storage.objects;
DROP POLICY IF EXISTS "Give access to authenticated users 1jis3c_1" ON storage.objects;
DROP POLICY IF EXISTS "Give access to authenticated users 1jis3c_2" ON storage.objects;
DROP POLICY IF EXISTS "Give access to authenticated users 1jis3c_3" ON storage.objects;

DROP POLICY IF EXISTS post_comments_media_select_policy ON storage.objects;
CREATE POLICY post_comments_media_select_policy ON storage.objects
FOR SELECT TO authenticated
USING (bucket_id = 'post-comments-media');

DROP POLICY IF EXISTS post_comments_media_insert_own_folder_policy ON storage.objects;
CREATE POLICY post_comments_media_insert_own_folder_policy ON storage.objects
FOR INSERT TO authenticated
WITH CHECK (
    bucket_id = 'post-comments-media'
    AND auth.uid() IS NOT NULL
    AND (storage.foldername(name))[1] = auth.uid()::TEXT
);

DROP POLICY IF EXISTS post_comments_media_update_own_folder_policy ON storage.objects;
CREATE POLICY post_comments_media_update_own_folder_policy ON storage.objects
FOR UPDATE TO authenticated
USING (
    bucket_id = 'post-comments-media'
    AND auth.uid() IS NOT NULL
    AND (storage.foldername(name))[1] = auth.uid()::TEXT
)
WITH CHECK (
    bucket_id = 'post-comments-media'
    AND auth.uid() IS NOT NULL
    AND (storage.foldername(name))[1] = auth.uid()::TEXT
);

DROP POLICY IF EXISTS post_comments_media_delete_own_folder_policy ON storage.objects;
CREATE POLICY post_comments_media_delete_own_folder_policy ON storage.objects
FOR DELETE TO authenticated
USING (
    bucket_id = 'post-comments-media'
    AND auth.uid() IS NOT NULL
    AND (storage.foldername(name))[1] = auth.uid()::TEXT
);
