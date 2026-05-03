-- ==========================================
-- FINAL RLS FIXES (Breaking Recursion & Simplifying)
-- ==========================================

-- 1. GROUPS & MEMBERS
-- Drop all existing policies to start fresh
DROP POLICY IF EXISTS "Users can create groups" ON public.groups;
DROP POLICY IF EXISTS "Creators can update groups" ON public.groups;
DROP POLICY IF EXISTS "Serve groups to members" ON public.groups;
DROP POLICY IF EXISTS "Public groups are viewable" ON public.groups;
DROP POLICY IF EXISTS "Creators can add members" ON public.group_members;
DROP POLICY IF EXISTS "Members can view members" ON public.group_members;

-- GROUPS: Insert & Update (Creator only) & Select (Members only)
CREATE POLICY "Enable insert for authenticated users only" ON public.groups FOR INSERT WITH CHECK (auth.role() = 'authenticated');
CREATE POLICY "Enable update for creators" ON public.groups FOR UPDATE USING (auth.uid() = creator_id);
CREATE POLICY "Enable select for members" ON public.groups FOR SELECT USING (
    EXISTS (SELECT 1 FROM public.group_members WHERE group_id = id AND user_id = auth.uid()) 
    OR 
    creator_id = auth.uid() -- Creator always sees it even if not in members table (fail-safe)
);

-- GROUP MEMBERS: Insert (Creator adds users) & Select (Members view list)
CREATE POLICY "Enable insert for creators" ON public.group_members FOR INSERT WITH CHECK (
    EXISTS (SELECT 1 FROM public.groups WHERE id = group_id AND creator_id = auth.uid()) OR auth.uid() = user_id
);
CREATE POLICY "Enable select for members" ON public.group_members FOR SELECT USING (
    EXISTS (SELECT 1 FROM public.group_members gm WHERE gm.group_id = group_id AND gm.user_id = auth.uid())
);


-- 2. COMMUNITIES & MEMBERS
-- Drop existing policies
DROP POLICY IF EXISTS "Users can create communities" ON public.communities;
DROP POLICY IF EXISTS "Manage community membership" ON public.community_members;
DROP POLICY IF EXISTS "Public communities are viewable" ON public.communities;
DROP POLICY IF EXISTS "Members can view members" ON public.community_members;

-- COMMUNITIES: Public Read, Auth Insert
CREATE POLICY "Enable insert for authenticated users" ON public.communities FOR INSERT WITH CHECK (auth.role() = 'authenticated');
CREATE POLICY "Enable update for creators" ON public.communities FOR UPDATE USING (auth.uid() = creator_id);
CREATE POLICY "Enable select for everyone" ON public.communities FOR SELECT USING (true); -- Communities are public for discovery

-- COMMUNITY MEMBERS: Insert (Join Public OR Creator Add)
-- This was the source of recursion. We split logical checks to avoid referring to community_members table itself for the insert permissions if possible, 
-- OR use a simpler definer function if needed. But for now, direct table checks:
CREATE POLICY "Enable insert for creators or public join" ON public.community_members FOR INSERT WITH CHECK (
    -- Case 1: I am the creator of the community (adding myself or others)
    EXISTS (SELECT 1 FROM public.communities WHERE id = community_id AND creator_id = auth.uid())
    OR
    -- Case 2: I am joining a public community (inserting myself)
    (
       user_id = auth.uid() AND 
       EXISTS (SELECT 1 FROM public.communities WHERE id = community_id AND is_public = true)
    )
);

CREATE POLICY "Enable select for members" ON public.community_members FOR SELECT USING (
    -- Members can see who else is in the community
    EXISTS (SELECT 1 FROM public.community_members cm WHERE cm.community_id = community_id AND cm.user_id = auth.uid())
    OR
    -- Or if I am looking at a public community directory (Optional, maybe restricted?)
    -- Let's stick to members-only visibility for member lists to be safe, or public if community is public
    EXISTS (SELECT 1 FROM public.communities c WHERE c.id = community_id AND c.is_public = true)
);
