-- Migration to support multiple images in posts

-- 1. Add media_urls column as an array of text
ALTER TABLE public.posts ADD COLUMN IF NOT EXISTS media_urls text[] DEFAULT '{}';

-- 2. Migrate existing single images to the new array column
UPDATE public.posts 
SET media_urls = ARRAY[media_url] 
WHERE media_url IS NOT NULL AND (media_urls IS NULL OR media_urls = '{}');

-- 3. (Optional) Remove the old column later, or keep it for backward compatibility
-- ALTER TABLE public.posts DROP COLUMN media_url;
