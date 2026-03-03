-- ==========================================
-- FIX 1: Missing 'is_verified' column in videos
-- ==========================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'videos' AND column_name = 'is_verified') THEN
        ALTER TABLE public.videos ADD COLUMN is_verified BOOLEAN DEFAULT FALSE;
    END IF;
END $$;

-- ==========================================
-- FIX 2: Community Policies (Joining/Creation)
-- ==========================================

-- Allow users to insert THEMSELVES as members (Joining)
DROP POLICY IF EXISTS "Users can join public communities" ON public.community_members;
CREATE POLICY "Users can join public communities" ON public.community_members
    FOR INSERT WITH CHECK (
        auth.uid() = user_id AND -- Can only add themselves
        EXISTS (
            SELECT 1 FROM public.communities 
            WHERE id = community_id AND is_public = TRUE
        )
    );

-- Allow creators to add themselves when creating the community
DROP POLICY IF EXISTS "Creators can add themselves as owner" ON public.community_members;
CREATE POLICY "Creators can add themselves as owner" ON public.community_members
    FOR INSERT WITH CHECK (
        auth.uid() = user_id AND
        role = 'owner' AND
        EXISTS (
            SELECT 1 FROM public.communities 
            WHERE id = community_id AND creator_id = auth.uid()
        )
    );

-- ==========================================
-- FIX 3: Group Policies (Creation/Member Add)
-- ==========================================

-- Allow creators to insert group members (when creating group or adding users)
DROP POLICY IF EXISTS "Creators/Admins can add members" ON public.group_members;
CREATE POLICY "Creators/Admins can add members" ON public.group_members
    FOR INSERT WITH CHECK (
        -- User is the creator of the group
        EXISTS (
            SELECT 1 FROM public.groups
            WHERE id = group_id AND creator_id = auth.uid()
        )
        OR
        -- OR User is an admin of the group
        EXISTS (
            SELECT 1 FROM public.group_members
            WHERE group_id = public.group_members.group_id 
            AND user_id = auth.uid() 
            AND role = 'admin'
        )
    );

-- Allow users to remove THEMSELVES (Leave group) - already covered by delete own participation?
-- Let's ensure it exists
DROP POLICY IF EXISTS "Users can leave groups" ON public.group_members;
CREATE POLICY "Users can leave groups" ON public.group_members
    FOR DELETE USING (auth.uid() = user_id);

-- Allow Admins to remove members
DROP POLICY IF EXISTS "Admins can remove members" ON public.group_members;
CREATE POLICY "Admins can remove members" ON public.group_members
    FOR DELETE USING (
        EXISTS (
            SELECT 1 FROM public.group_members
            WHERE group_id = public.group_members.group_id 
            AND user_id = auth.uid() 
            AND role = 'admin'
        )
    );
