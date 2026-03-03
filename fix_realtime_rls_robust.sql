-- ==========================================
-- FIX: ROBUST RLS & REALTIME FOR ALL CHATS
-- This script fixes both standard messages (1-1, Groups)
-- and Community messages.
-- ==========================================

-- 1. MESSAGES TABLE (1-1 & Groups)
ALTER TABLE public.messages REPLICA IDENTITY FULL;

DROP POLICY IF EXISTS "select_messages_in_own_chats" ON public.messages;
CREATE POLICY "select_messages_in_own_chats" ON public.messages
FOR SELECT USING (
    EXISTS (
        SELECT 1 FROM public.chats
        WHERE chats.id = messages.chat_id
        AND (
            -- Case 1: 1-on-1 or Group using participants array
            auth.uid()::text = ANY(chats.participants)
            OR
            -- Case 2: Group explicitly (via group_members if needed)
            (chats.is_group = true AND EXISTS (SELECT 1 FROM public.group_members gm WHERE gm.group_id = chats.id AND gm.user_id = auth.uid()))
        )
    )
);

-- 2. COMMUNITY_MESSAGES TABLE (Communities)
ALTER TABLE public.community_messages REPLICA IDENTITY FULL;

DROP POLICY IF EXISTS "Users can see messages in communities they can see" ON public.community_messages;
CREATE POLICY "Users can see messages in communities they can see" ON public.community_messages
FOR SELECT USING (
    EXISTS (
        SELECT 1 FROM public.communities 
        WHERE id = public.community_messages.community_id AND (
            is_public = TRUE 
            OR 
            EXISTS (
                SELECT 1 FROM public.community_members 
                WHERE community_id = public.communities.id AND user_id = auth.uid()
            )
        )
    )
);

SELECT '✅ REALTIME & RLS UPDATED FOR ALL TABLES' as status;
