-- ==========================================
-- FIX RLS POLICIES V2 (ROBUST)
-- ==========================================

-- 1. GROUPS: Allow Insert and Update
-- Dropping existing policies to avoid conflicts
DROP POLICY IF EXISTS "Users can create groups" ON public.groups;
DROP POLICY IF EXISTS "Creators can update groups" ON public.groups;

-- Policy to Allow INSERT
CREATE POLICY "Users can create groups" ON public.groups
    FOR INSERT WITH CHECK (auth.uid() = creator_id);

-- Policy to Allow UPDATE (Needed for triggers updating last_message)
CREATE POLICY "Creators can update groups" ON public.groups
    FOR UPDATE USING (auth.uid() = creator_id);


-- 2. GROUP MEMBERS: Allow Creators to Add Members
DROP POLICY IF EXISTS "Creators can add members" ON public.group_members;

CREATE POLICY "Creators can add members" ON public.group_members
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.groups 
            WHERE id = group_id AND creator_id = auth.uid()
        )
    );


-- 3. COMMUNITIES: Allow Insert and Update
DROP POLICY IF EXISTS "Users can create communities" ON public.communities;

CREATE POLICY "Users can create communities" ON public.communities
    FOR INSERT WITH CHECK (auth.uid() = creator_id);


-- 4. COMMUNITY MEMBERS: Allow Insert
DROP POLICY IF EXISTS "Manage community membership" ON public.community_members;

CREATE POLICY "Manage community membership" ON public.community_members
    FOR INSERT WITH CHECK (
        -- Creator adding members (including themselves)
        EXISTS (
            SELECT 1 FROM public.communities 
            WHERE id = community_id AND creator_id = auth.uid()
        )
        OR
        -- User joining public community
        (
            user_id = auth.uid() AND
            EXISTS (
                SELECT 1 FROM public.communities 
                WHERE id = community_id AND is_public = TRUE
            )
        )
    );
