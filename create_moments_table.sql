-- Create moments table
CREATE TABLE IF NOT EXISTS public.moments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    media_url TEXT NOT NULL,
    media_type TEXT NOT NULL, -- 'image' or 'video'
    duration_hours INTEGER DEFAULT 24,
    visibility TEXT DEFAULT 'all', -- 'all', 'friends', 'followers', 'selective'
    excluded_ids UUID[] DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

-- Index for expiration
CREATE INDEX IF NOT EXISTS moments_expires_at_idx ON public.moments (expires_at);
CREATE INDEX IF NOT EXISTS moments_user_id_idx ON public.moments (user_id);

-- Create moment_views table
CREATE TABLE IF NOT EXISTS public.moment_views (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    moment_id UUID REFERENCES public.moments(id) ON DELETE CASCADE,
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    viewed_at TIMESTAMPTZ DEFAULT now(),
    UNIQUE(moment_id, user_id)
);

-- Enable RLS
ALTER TABLE public.moments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.moment_views ENABLE ROW LEVEL SECURITY;

-- Policies for public.moments
DROP POLICY IF EXISTS "Moments are viewable by authorized users" ON public.moments;
CREATE POLICY "Moments are viewable by authorized users" ON public.moments
FOR SELECT USING (
    expires_at > now() AND (
        auth.uid() = user_id OR (
            NOT (auth.uid() = ANY(excluded_ids)) AND (
                visibility = 'all' OR
                (visibility = 'friends' AND EXISTS (
                    SELECT 1 FROM public.friends 
                    WHERE ((sender_id = auth.uid() AND receiver_id = moments.user_id) OR (sender_id = moments.user_id AND receiver_id = auth.uid()))
                    AND status = 'accepted'
                )) OR
                (visibility = 'followers' AND EXISTS (
                    SELECT 1 FROM public.follows 
                    WHERE follower_id = auth.uid() AND following_id = moments.user_id
                ))
            )
        )
    )
);

DROP POLICY IF EXISTS "Users can create their own moments" ON public.moments;
CREATE POLICY "Users can create their own moments" ON public.moments
FOR INSERT WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can delete their own moments" ON public.moments;
CREATE POLICY "Users can delete their own moments" ON public.moments
FOR DELETE USING (auth.uid() = user_id);

-- Policies for public.moment_views
DROP POLICY IF EXISTS "Users can see views for their own moments" ON public.moment_views;
CREATE POLICY "Users can see views for their own moments" ON public.moment_views
FOR SELECT USING (
    EXISTS (
        SELECT 1 FROM public.moments 
        WHERE id = moment_views.moment_id AND user_id = auth.uid()
    )
);

DROP POLICY IF EXISTS "Users can record their own views" ON public.moment_views;
CREATE POLICY "Users can record their own views" ON public.moment_views
FOR INSERT WITH CHECK (auth.uid() = user_id);
