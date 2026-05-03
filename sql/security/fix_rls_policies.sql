-- ==========================================
-- FIX RLS POLICIES FOR GROUPS AND COMMUNITIES
-- ==========================================

-- 1. GROUPS POLICIES
-- Dropping potential conflicting policies to ensure clean slate
DROP POLICY IF EXISTS "Users can create groups" ON public.groups;
DROP POLICY IF EXISTS "Users can insert group members" ON public.group_members; 
DROP POLICY IF EXISTS "Creators can add members" ON public.group_members;

-- Allow Authenticated Users to Create Groups
CREATE POLICY "Users can create groups" ON public.groups
    FOR INSERT WITH CHECK (auth.uid() = creator_id);

-- Allow Creators to Add Members (Themselves and Others)
-- This fixes the issue where you couldn't add members because you weren't a member yet
CREATE POLICY "Creators can add members" ON public.group_members
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.groups 
            WHERE id = group_id AND creator_id = auth.uid()
        )
    );

-- 2. COMMUNITIES POLICIES
DROP POLICY IF EXISTS "Users can create communities" ON public.communities;
DROP POLICY IF EXISTS "Users can join communities" ON public.community_members;
DROP POLICY IF EXISTS "Creators can add themselves to community" ON public.community_members;

-- Allow Authenticated Users to Create Communities
CREATE POLICY "Users can create communities" ON public.communities
    FOR INSERT WITH CHECK (auth.uid() = creator_id);

-- Allow Users to Join Public Communities OR Creators to Add Themselves
CREATE POLICY "Manage community membership" ON public.community_members
    FOR INSERT WITH CHECK (
        -- Case 1: Creator adding initial member (themselves)
        EXISTS (
            SELECT 1 FROM public.communities 
            WHERE id = community_id AND creator_id = auth.uid()
        )
        OR
        -- Case 2: User joining a public community (Self-join)
        (
            user_id = auth.uid() AND
            EXISTS (
                SELECT 1 FROM public.communities 
                WHERE id = community_id AND is_public = TRUE
            )
        )
    );

-- 3. ENSURE SELECT POLICIES EXIST (Re-applying safe defaults just in case)
-- (Users should be able to see groups they created even if not a member yet? No, creator adds themselves immediately)
-- But we ensure the creator can SELECT the group they just made to return it to the client.

DROP POLICY IF EXISTS "Creators can view their own groups" ON public.groups;
CREATE POLICY "Creators can view their own groups" ON public.groups
    FOR SELECT USING (
        auth.uid() = creator_id OR
        EXISTS (
            SELECT 1 FROM public.group_members 
            WHERE group_id = public.groups.id AND user_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Creators can view their own communities" ON public.communities;
CREATE POLICY "Creators can view and public see communities" ON public.communities
    FOR SELECT USING (
        is_public = TRUE OR
        auth.uid() = creator_id OR
        EXISTS (
            SELECT 1 FROM public.community_members 
            WHERE community_id = public.communities.id AND user_id = auth.uid()
        )
    );
