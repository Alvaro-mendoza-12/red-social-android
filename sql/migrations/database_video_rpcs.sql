-- RPC for atomic view count increment
-- Run this in the Supabase SQL Editor

-- First, drop if exists to avoid parameter name conflicts
DROP FUNCTION IF EXISTS increment_video_views(UUID);

CREATE OR REPLACE FUNCTION increment_video_views(video_id UUID)
RETURNS void AS $$
BEGIN
  UPDATE public.videos
  SET views = COALESCE(views, 0) + 1
  WHERE id = video_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- RPC for atomic like count increment (with delta)
DROP FUNCTION IF EXISTS increment_like_count(UUID, INTEGER);

CREATE OR REPLACE FUNCTION increment_like_count(video_id UUID, delta INTEGER)
RETURNS void AS $$
BEGIN
  UPDATE public.videos
  SET like_count = GREATEST(0, COALESCE(like_count, 0) + delta)
  WHERE id = video_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
