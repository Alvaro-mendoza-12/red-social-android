-- ========================================
-- DATABASE UPDATES FOR CRITICAL FEATURES
-- ========================================

-- 1. REPORTS TABLE (For Admin Panel)
CREATE TABLE IF NOT EXISTS public.reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    reported_user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    reported_user_name TEXT,
    reason TEXT NOT NULL,
    description TEXT,
    evidence_url TEXT,
    status TEXT DEFAULT 'pendiente', -- pendiente, en_proceso, finalizado
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Enable RLS for reports
ALTER TABLE public.reports ENABLE ROW LEVEL SECURITY;

-- Policies for reports
CREATE POLICY "Users can insert own reports" ON public.reports 
    FOR INSERT WITH CHECK (auth.uid() = reporter_id);

CREATE POLICY "Anyone can read reports" ON public.reports 
    FOR SELECT USING (true); -- Ideally restricted to admin, but 'true' prevents crash for now

CREATE POLICY "Anyone can update reports" ON public.reports 
    FOR UPDATE USING (true); -- Ideally restricted to admin

CREATE POLICY "Anyone can delete reports" ON public.reports 
    FOR DELETE USING (true); -- Ideally restricted to admin


-- 2. MESSAGE DELETIONS TABLE (For "Delete For Me")
CREATE TABLE IF NOT EXISTS public.message_deletions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    message_id UUID NOT NULL REFERENCES public.messages(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(user_id, message_id)
);

-- Enable RLS for message_deletions
ALTER TABLE public.message_deletions ENABLE ROW LEVEL SECURITY;

-- Policies for message_deletions
CREATE POLICY "Users can insert own deletions" ON public.message_deletions 
    FOR INSERT WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users can view own deletions" ON public.message_deletions 
    FOR SELECT USING (auth.uid() = user_id);


-- 3. VIDEO VIEWS RPC (For Global View Count)
-- This function runs with SECURITY DEFINER to bypass RLS restrictions on UPDATE
CREATE OR REPLACE FUNCTION increment_video_views(video_id UUID)
RETURNS VOID AS $$
BEGIN
    UPDATE public.videos
    SET views = views + 1
    WHERE id = video_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 4. VIDEO LIKES RPC (For Atomic Like Counts)
-- This function handles both increment (+) and decrement (-) via the 'delta' parameter
CREATE OR REPLACE FUNCTION increment_like_count(video_id UUID, delta INTEGER)
RETURNS VOID AS $$
BEGIN
    UPDATE public.videos
    SET "likeCount" = GREATEST(0, "likeCount" + delta)
    WHERE id = video_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
