-- ========================================================
-- REPARACIÓN INTEGRAL DE COMUNIDADES Y GRUPOS (POLÍTICAS)
-- ========================================================

-- 1. Asegurar que las columnas existen
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'groups' AND column_name = 'is_deleted') THEN
        ALTER TABLE public.groups ADD COLUMN is_deleted BOOLEAN DEFAULT FALSE;
    END IF;
    
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'communities' AND column_name = 'is_deleted') THEN
        ALTER TABLE public.communities ADD COLUMN is_deleted BOOLEAN DEFAULT FALSE;
    END IF;
END $$;

-- 2. Habilitar RLS (por si acaso)
ALTER TABLE public.communities ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.groups ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.group_members ENABLE ROW LEVEL SECURITY;

-- 3. POLÍTICAS PARA COMUNIDADES
DROP POLICY IF EXISTS "Public communities are viewable by everyone" ON public.communities;
CREATE POLICY "Public communities are viewable by everyone" ON public.communities
    FOR SELECT USING (is_deleted = FALSE OR auth.uid() = creator_id);

DROP POLICY IF EXISTS "Authenticated users can create communities" ON public.communities;
CREATE POLICY "Authenticated users can create communities" ON public.communities
    FOR INSERT WITH CHECK (auth.role() = 'authenticated');

DROP POLICY IF EXISTS "Creators can update communities" ON public.communities;
CREATE POLICY "Creators can update communities" ON public.communities
    FOR UPDATE USING (auth.uid() = creator_id);

-- 4. POLÍTICAS PARA GRUPOS
DROP POLICY IF EXISTS "Groups are viewable by members" ON public.groups;
CREATE POLICY "Groups are viewable by members" ON public.groups
    FOR SELECT USING (
        (is_deleted = FALSE AND EXISTS (
            SELECT 1 FROM public.group_members 
            WHERE group_id = public.groups.id AND user_id = auth.uid()
        ))
        OR creator_id = auth.uid()
    );

DROP POLICY IF EXISTS "Authenticated users can create groups" ON public.groups;
CREATE POLICY "Authenticated users can create groups" ON public.groups
    FOR INSERT WITH CHECK (auth.role() = 'authenticated');

DROP POLICY IF EXISTS "Creators can update groups" ON public.groups;
CREATE POLICY "Creators can update groups" ON public.groups
    FOR UPDATE USING (auth.uid() = creator_id);

-- 5. POLÍTICAS PARA MIEMBROS DE COMUNIDAD
DROP POLICY IF EXISTS "Members are viewable by members of the community" ON public.community_members;
CREATE POLICY "Members are viewable by members of the community" ON public.community_members
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.communities 
            WHERE id = community_id 
              AND is_deleted = FALSE -- No mostrar miembros si la comunidad está borrada
              AND (is_public = TRUE OR EXISTS (
                SELECT 1 FROM public.community_members 
                WHERE community_id = public.community_members.community_id AND user_id = auth.uid()
            ))
        )
    );

DROP POLICY IF EXISTS "Users can join public communities" ON public.community_members;
CREATE POLICY "Users can join public communities" ON public.community_members
    FOR INSERT WITH CHECK (
        auth.uid() = user_id AND
        EXISTS (
            SELECT 1 FROM public.communities 
            WHERE id = community_id AND is_public = TRUE AND is_deleted = FALSE
        )
    );

DROP POLICY IF EXISTS "Creators can add themselves as owner" ON public.community_members;
CREATE POLICY "Creators can add themselves as owner" ON public.community_members
    FOR INSERT WITH CHECK (
        auth.uid() = user_id AND
        (role = 'owner' OR role = 'member') AND
        EXISTS (
            SELECT 1 FROM public.communities 
            WHERE id = community_id AND creator_id = auth.uid()
        )
    );
