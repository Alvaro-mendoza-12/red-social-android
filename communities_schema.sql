-- Communities Table
CREATE TABLE IF NOT EXISTS public.communities (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name TEXT NOT NULL,
    image TEXT,
    description TEXT,
    category TEXT, -- 'gaming', 'music', 'tech', etc.
    is_public BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    creator_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    last_message TEXT,
    last_message_at TIMESTAMPTZ DEFAULT NOW(),
    metadata JSONB DEFAULT '{}'::jsonb
);

-- Community Members Table
CREATE TABLE IF NOT EXISTS public.community_members (
    community_id UUID REFERENCES public.communities(id) ON DELETE CASCADE,
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    role TEXT DEFAULT 'member', -- 'owner', 'admin', 'member'
    joined_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (community_id, user_id)
);

-- Community Messages Table
CREATE TABLE IF NOT EXISTS public.community_messages (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    community_id UUID REFERENCES public.communities(id) ON DELETE CASCADE,
    sender_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    content TEXT NOT NULL,
    content_type TEXT DEFAULT 'text',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    metadata JSONB DEFAULT '{}'::jsonb
);

-- Enable RLS
ALTER TABLE public.communities ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_messages ENABLE ROW LEVEL SECURITY;

-- Policies for Communities
CREATE POLICY "Public communities are visible to everyone" ON public.communities
    FOR SELECT USING (is_public = TRUE);

CREATE POLICY "Private communities are visible to members" ON public.communities
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.community_members 
            WHERE community_id = public.communities.id AND user_id = auth.uid()
        )
    );

CREATE POLICY "Users can create communities" ON public.communities
    FOR INSERT WITH CHECK (auth.uid() = creator_id);

-- Policies for Community Members
CREATE POLICY "Everyone can see members of public communities" ON public.community_members
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.communities 
            WHERE id = public.community_members.community_id AND is_public = TRUE
        ) OR user_id = auth.uid()
    );

CREATE POLICY "Owners can manage community members" ON public.community_members
    FOR ALL USING (
        EXISTS (
            SELECT 1 FROM public.community_members 
            WHERE community_id = public.community_members.community_id AND user_id = auth.uid() AND role IN ('owner', 'admin')
        )
    );

-- Policies for Community Messages
CREATE POLICY "Users can see messages in communities they can see" ON public.community_messages
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.communities 
            WHERE id = public.community_messages.community_id AND (is_public = TRUE OR EXISTS (
                SELECT 1 FROM public.community_members WHERE community_id = public.communities.id AND user_id = auth.uid()
            ))
        )
    );

CREATE POLICY "Users can send messages to communities they are members of" ON public.community_messages
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.community_members 
            WHERE community_id = community_id AND user_id = auth.uid()
        )
    );

-- Trigger for last_message update
CREATE OR REPLACE FUNCTION public.update_community_last_message()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE public.communities
    SET last_message = NEW.content,
        last_message_at = NEW.created_at
    WHERE id = NEW.community_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER on_community_message_sent
    AFTER INSERT ON public.community_messages
    FOR EACH ROW EXECUTE FUNCTION public.update_community_last_message();
