-- ==========================================
-- FIX 4: Missing UPDATE Policies (Soft Delete)
-- ==========================================

-- Groups: Allow creators to update (e.g., soft delete, name change)
DROP POLICY IF EXISTS "Creators can update groups" ON public.groups;
CREATE POLICY "Creators can update groups" ON public.groups
    FOR UPDATE USING (auth.uid() = creator_id);

-- Communities: Allow creators to update
DROP POLICY IF EXISTS "Creators can update communities" ON public.communities;
CREATE POLICY "Creators can update communities" ON public.communities
    FOR UPDATE USING (auth.uid() = creator_id);

-- ==========================================
-- FIX 5: Ensure 'is_deleted' column exists (Safety Check)
-- ==========================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'groups' AND column_name = 'is_deleted') THEN
        ALTER TABLE public.groups ADD COLUMN is_deleted BOOLEAN DEFAULT FALSE;
    END IF;
    
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'communities' AND column_name = 'is_deleted') THEN
        ALTER TABLE public.communities ADD COLUMN is_deleted BOOLEAN DEFAULT FALSE;
    END IF;
END $$;

-- ==========================================
-- FIX 6: Precise Message Policies (Security)
-- ==========================================

-- Ensure users can only send messages if they are CURRENTLY members
DROP POLICY IF EXISTS "Users can send messages to their groups" ON public.group_messages;
CREATE POLICY "Users can send messages to their groups" ON public.group_messages
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.group_members 
            WHERE group_id = public.group_messages.group_id 
              AND user_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Users can send messages to communities they are members of" ON public.community_messages;
CREATE POLICY "Users can send messages to communities they are members of" ON public.community_messages
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.community_members 
            WHERE community_id = public.community_messages.community_id 
              AND user_id = auth.uid()
        )
    );
