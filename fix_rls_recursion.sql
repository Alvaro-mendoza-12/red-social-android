-- ==========================================
-- FIX RLS INFINITE RECURSION (The Silver Bullet)
-- ==========================================

-- 1. Helper Function to safely check membership (Bypasses RLS)
CREATE OR REPLACE FUNCTION public.is_community_member(_community_id uuid)
RETURNS boolean AS $$
BEGIN
  -- This query runs with owner privileges (SECURITY DEFINER), 
  -- so it does NOT trigger the RLS policy again.
  RETURN EXISTS (
    SELECT 1 FROM public.community_members
    WHERE community_id = _community_id 
    AND user_id = auth.uid()
  );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;


-- 2. Clean Slate for Community Members Policies
DROP POLICY IF EXISTS "Enable insert for creators or public join" ON public.community_members;
DROP POLICY IF EXISTS "Enable select for members" ON public.community_members;
DROP POLICY IF EXISTS "Users can create communities" ON public.communities;
DROP POLICY IF EXISTS "Manage community membership" ON public.community_members;
DROP POLICY IF EXISTS "Public communities are viewable" ON public.communities;
DROP POLICY IF EXISTS "Members can view members" ON public.community_members;

-- 3. COMMUNITY MEMBERS POLICIES

-- INSERT: Public Join OR Creator Add
CREATE POLICY "Enable insert for community members" ON public.community_members
FOR INSERT WITH CHECK (
    -- Case 1: I am the creator (checked via communities table, safe)
    EXISTS (
        SELECT 1 FROM public.communities 
        WHERE id = community_id AND creator_id = auth.uid()
    )
    OR
    -- Case 2: I am joining a public community (checked via communities table, safe)
    (
       user_id = auth.uid() AND 
       EXISTS (
           SELECT 1 FROM public.communities 
           WHERE id = community_id AND is_public = true
       )
    )
);

-- SELECT: View Access
CREATE POLICY "Enable select for community members" ON public.community_members
FOR SELECT USING (
    -- I can see my own membership row
    user_id = auth.uid()
    OR
    -- I can see members of public communities
    EXISTS (
        SELECT 1 FROM public.communities 
        WHERE id = community_id AND is_public = true
    )
    OR
    -- I can see members if I am a member (Using SECURITY DEFINER to break recursion)
    public.is_community_member(community_id)
);

-- 4. Repost Groups Policies (Just in case)
DROP POLICY IF EXISTS "Enable insert for authenticated users only" ON public.groups;
CREATE POLICY "Enable insert for authenticated users only" ON public.groups FOR INSERT WITH CHECK (auth.role() = 'authenticated');
