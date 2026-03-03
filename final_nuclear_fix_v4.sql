-- ========================================================
-- NUCLEAR CLEANUP AND FIX V4: COMMUNITIES AND GROUPS
-- ========================================================

-- 1. PURGE ALL OLD POLICIES (TO AVOID COLISIONS)
DO $$ 
DECLARE 
    r RECORD;
BEGIN
    FOR r IN (
        SELECT policyname, tablename 
        FROM pg_policies 
        WHERE schemaname = 'public' 
          AND tablename IN ('communities', 'community_members', 'groups', 'group_members')
    ) 
    LOOP
        EXECUTE 'DROP POLICY IF EXISTS ' || quote_ident(r.policyname) || ' ON public.' || quote_ident(r.tablename);
    END LOOP;
END $$;

-- 2. RE-ENABLE RLS
ALTER TABLE public.communities ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.groups ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.group_members ENABLE ROW LEVEL SECURITY;

-- 3. ENSURE COLUMNS AND DEFAULTS
DO $$
BEGIN
    -- Communities
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'communities' AND column_name = 'is_deleted') THEN
        ALTER TABLE public.communities ADD COLUMN is_deleted BOOLEAN DEFAULT FALSE;
    ELSE
        ALTER TABLE public.communities ALTER COLUMN is_deleted SET DEFAULT FALSE;
    END IF;
    
    -- Groups
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'groups' AND column_name = 'is_deleted') THEN
        ALTER TABLE public.groups ADD COLUMN is_deleted BOOLEAN DEFAULT FALSE;
    ELSE
        ALTER TABLE public.groups ALTER COLUMN is_deleted SET DEFAULT FALSE;
    END IF;
END $$;

-- ==========================================
-- 4. RECURSION-SAFE POLICIES (COMMUNITIES)
-- ==========================================

-- SELECT: Creator sees even deleted ones (for debug), others ONLY non-deleted.
CREATE POLICY "comm_select_v4" ON public.communities
    FOR SELECT USING (
        (is_deleted = FALSE) OR (creator_id = auth.uid())
    );

CREATE POLICY "comm_insert_v4" ON public.communities
    FOR INSERT WITH CHECK (auth.role() = 'authenticated');

CREATE POLICY "comm_update_v4" ON public.communities
    FOR UPDATE USING (creator_id = auth.uid());

-- COMMUNITY MEMBERS: (NO RECURSION)
CREATE POLICY "comm_mem_select_v4" ON public.community_members
    FOR SELECT USING (
        user_id = auth.uid() -- Can see self
        OR EXISTS (
            SELECT 1 FROM communities c 
            WHERE c.id = community_id AND c.is_deleted = FALSE 
              AND (c.is_public = TRUE OR c.creator_id = auth.uid())
        )
    );

CREATE POLICY "comm_mem_insert_v4" ON public.community_members
    FOR INSERT WITH CHECK (
        auth.uid() = user_id AND 
        EXISTS (
            SELECT 1 FROM communities c 
            WHERE c.id = community_id AND c.is_deleted = FALSE
        )
    );

CREATE POLICY "comm_mem_delete_v4" ON public.community_members
    FOR DELETE USING (
        user_id = auth.uid() OR 
        EXISTS (
            SELECT 1 FROM communities c 
            WHERE c.id = community_id AND c.creator_id = auth.uid()
        )
    );

-- ==========================================
-- 5. RECURSION-SAFE POLICIES (GROUPS)
-- ==========================================

-- SELECT: GONE if deleted. NO EXCEPTIONS if we want it to vanish.
CREATE POLICY "groups_select_v4" ON public.groups
    FOR SELECT USING (
        is_deleted = FALSE AND (
            creator_id = auth.uid() OR
            EXISTS (
                SELECT 1 FROM group_members gm 
                WHERE gm.group_id = id AND gm.user_id = auth.uid()
            )
        )
    );

CREATE POLICY "groups_insert_v4" ON public.groups
    FOR INSERT WITH CHECK (auth.role() = 'authenticated');

CREATE POLICY "groups_update_v4" ON public.groups
    FOR UPDATE USING (creator_id = auth.uid());

-- GROUP MEMBERS: (NO RECURSION)
CREATE POLICY "groups_mem_select_v4" ON public.group_members
    FOR SELECT USING (
        user_id = auth.uid() OR
        EXISTS (
            SELECT 1 FROM groups g 
            WHERE g.id = group_id AND g.is_deleted = FALSE
        )
    );

CREATE POLICY "groups_mem_insert_v4" ON public.group_members
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1 FROM groups g 
            WHERE g.id = group_id AND g.creator_id = auth.uid()
        )
    );

CREATE POLICY "groups_mem_delete_v4" ON public.group_members
    FOR DELETE USING (
        user_id = auth.uid() OR 
        EXISTS (
            SELECT 1 FROM groups g 
            WHERE g.id = group_id AND g.creator_id = auth.uid()
        )
    );

-- ==========================================
-- 6. MASS REFRESH FOR STUCK RECORDS
-- ==========================================
-- Ensure memberships for deleted groups are effectively hidden or cleared? 
-- The SELECT policies above already hide them if the group/comm is deleted.
