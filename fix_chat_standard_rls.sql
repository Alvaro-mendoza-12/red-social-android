-- ==========================================
-- FIX: STANDARD RLS FOR CHATS & MESSAGES
-- Solves issue where recipient does not receive messages in 1-on-1 chats
-- ==========================================

-- 1. Disable RLS momentarily to avoid lockouts during apply
ALTER TABLE public.chats DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.messages DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.chat_participants DISABLE ROW LEVEL SECURITY;

-- 2. Drop EXISTING policies to avoid conflicts
-- (We use DO block to iterate and drop all policies for these tables)
DO $$ 
DECLARE pol RECORD;
BEGIN
    -- CHATS
    FOR pol IN SELECT policyname FROM pg_policies WHERE tablename = 'chats' AND schemaname = 'public'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.chats', pol.policyname);
    END LOOP;

    -- MESSAGES
    FOR pol IN SELECT policyname FROM pg_policies WHERE tablename = 'messages' AND schemaname = 'public'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.messages', pol.policyname);
    END LOOP;

    -- CHAT_PARTICIPANTS
    FOR pol IN SELECT policyname FROM pg_policies WHERE tablename = 'chat_participants' AND schemaname = 'public'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.chat_participants', pol.policyname);
    END LOOP;
END $$;

-- 3. CHATS POLICIES
-- Goal: Users can see chats they are part of.
-- Note: 'participants' is a TEXT[] array of user IDs.

CREATE POLICY "select_own_chats" ON public.chats
FOR SELECT USING (
    auth.uid()::text = ANY(participants)
);

CREATE POLICY "insert_own_chats" ON public.chats
FOR INSERT WITH CHECK (
    auth.uid()::text = ANY(participants)
);

CREATE POLICY "update_own_chats" ON public.chats
FOR UPDATE USING (
    auth.uid()::text = ANY(participants)
);

-- Note: No DELETE policy for now to prevent accidental deletions. 
-- Leaving a chat is handled by updating 'participants' or rpc.

-- 4. MESSAGES POLICIES
-- Goal: Users can see messages if they are in the chat 'participants' list.
-- This is CRITICAL for Realtime. If RLS fails here, no event is sent.

CREATE POLICY "select_messages_in_own_chats" ON public.messages
FOR SELECT USING (
    EXISTS (
        SELECT 1 FROM public.chats
        WHERE chats.id = messages.chat_id
        AND auth.uid()::text = ANY(chats.participants)
    )
);

CREATE POLICY "insert_messages_in_own_chats" ON public.messages
FOR INSERT WITH CHECK (
    auth.uid() = sender_id 
    AND EXISTS (
        SELECT 1 FROM public.chats
        WHERE chats.id = messages.chat_id
        AND auth.uid()::text = ANY(chats.participants)
    )
);

CREATE POLICY "update_own_messages" ON public.messages
FOR UPDATE USING (
    auth.uid() = sender_id
);

-- Allow users to "Delete for everyone" if they are sender, or "Delete for me" (via update)
CREATE POLICY "delete_own_messages" ON public.messages
FOR DELETE USING (
    auth.uid() = sender_id
);

-- 5. CHAT_PARTICIPANTS POLICIES
-- Used for unread counts, typing status, etc.

CREATE POLICY "select_own_participation" ON public.chat_participants
FOR SELECT USING (
    user_id = auth.uid()
);

CREATE POLICY "insert_own_participation" ON public.chat_participants
FOR INSERT WITH CHECK (
    -- Can add self or others if creating a chat? 
    -- Usually better to allow if you are part of the target chat.
    EXISTS (
        SELECT 1 FROM public.chats
        WHERE chats.id = chat_participants.chat_id
        AND auth.uid()::text = ANY(chats.participants)
    )
);

CREATE POLICY "update_own_participation" ON public.chat_participants
FOR UPDATE USING (
    user_id = auth.uid()
);

CREATE POLICY "delete_own_participation" ON public.chat_participants
FOR DELETE USING (
    user_id = auth.uid()
);

-- 6. RE-ENABLE RLS
ALTER TABLE public.chats ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.chat_participants ENABLE ROW LEVEL SECURITY;

-- 7. VERIFICATION
SELECT '✅ RLS ENABLED AND POLICIES APPLIED' as status;
