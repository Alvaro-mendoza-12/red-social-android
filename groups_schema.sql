-- Groups Table
CREATE TABLE IF NOT EXISTS public.groups (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name TEXT NOT NULL,
    image TEXT,
    description TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    creator_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    last_message TEXT,
    last_message_at TIMESTAMPTZ DEFAULT NOW(),
    metadata JSONB DEFAULT '{}'::jsonb
);

-- Group Members Table
CREATE TABLE IF NOT EXISTS public.group_members (
    group_id UUID REFERENCES public.groups(id) ON DELETE CASCADE,
    user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
    role TEXT DEFAULT 'member', -- 'admin', 'member'
    joined_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (group_id, user_id)
);

-- Group Messages Table
CREATE TABLE IF NOT EXISTS public.group_messages (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    group_id UUID REFERENCES public.groups(id) ON DELETE CASCADE,
    sender_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    content TEXT NOT NULL,
    content_type TEXT DEFAULT 'text', -- 'text', 'image', 'video', 'audio', 'file'
    created_at TIMESTAMPTZ DEFAULT NOW(),
    status TEXT DEFAULT 'sent',
    metadata JSONB DEFAULT '{}'::jsonb
);

-- Enable RLS
ALTER TABLE public.groups ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.group_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.group_messages ENABLE ROW LEVEL SECURITY;

-- Policies for Groups
CREATE POLICY "Users can see groups they are members of" ON public.groups
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.group_members 
            WHERE group_id = public.groups.id AND user_id = auth.uid()
        )
    );

CREATE POLICY "Users can create groups" ON public.groups
    FOR INSERT WITH CHECK (auth.uid() = creator_id);

-- Policies for Group Members
CREATE POLICY "Users can see members of their groups" ON public.group_members
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.group_members 
            WHERE group_id = public.group_members.group_id AND user_id = auth.uid()
        )
    );

CREATE POLICY "Admins can manage members" ON public.group_members
    FOR ALL USING (
        EXISTS (
            SELECT 1 FROM public.group_members 
            WHERE group_id = public.group_members.group_id AND user_id = auth.uid() AND role = 'admin'
        )
    );

-- Policies for Group Messages
CREATE POLICY "Users can see messages in their groups" ON public.group_messages
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.group_members 
            WHERE group_id = public.group_messages.group_id AND user_id = auth.uid()
        )
    );

CREATE POLICY "Users can send messages to their groups" ON public.group_messages
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.group_members 
            WHERE group_id = group_id AND user_id = auth.uid()
        )
    );

-- Triggers for last_message update in groups
CREATE OR REPLACE FUNCTION public.update_group_last_message()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE public.groups
    SET last_message = NEW.content,
        last_message_at = NEW.created_at
    WHERE id = NEW.group_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER on_group_message_sent
    AFTER INSERT ON public.group_messages
    FOR EACH ROW EXECUTE FUNCTION public.update_group_last_message();
