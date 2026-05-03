-- Add audio support to post_comments table
ALTER TABLE public.post_comments ADD COLUMN IF NOT EXISTS media_url TEXT;
ALTER TABLE public.post_comments ADD COLUMN IF NOT EXISTS content_type TEXT DEFAULT 'text';

-- Allow authenticated users to upload to post-comments-media bucket
INSERT INTO storage.buckets (id, name, public) 
VALUES ('post-comments-media', 'post-comments-media', true)
ON CONFLICT (id) DO NOTHING;

CREATE POLICY "Give access to authenticated users 1jis3c_0" ON storage.objects FOR SELECT TO authenticated USING (bucket_id = 'post-comments-media');
CREATE POLICY "Give access to authenticated users 1jis3c_1" ON storage.objects FOR INSERT TO authenticated WITH CHECK (bucket_id = 'post-comments-media');
CREATE POLICY "Give access to authenticated users 1jis3c_2" ON storage.objects FOR UPDATE TO authenticated USING (bucket_id = 'post-comments-media');
CREATE POLICY "Give access to authenticated users 1jis3c_3" ON storage.objects FOR DELETE TO authenticated USING (bucket_id = 'post-comments-media');
