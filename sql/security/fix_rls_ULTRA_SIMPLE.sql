-- ==========================================
-- FIX ULTRA SIMPLIFICADO - SIN RECURSIÓN
-- Enfoque: Políticas minimalistas que permiten las operaciones necesarias
-- ==========================================

-- ========================================
-- PASO 1: LIMPIAR TODO
-- ========================================

-- Deshabilitar RLS temporalmente
ALTER TABLE public.group_members DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_members DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.group_messages DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_messages DISABLE ROW LEVEL SECURITY;

-- Eliminar TODAS las políticas existentes dinámicamente
DO $$ 
DECLARE pol RECORD;
BEGIN
    -- group_members
    FOR pol IN SELECT policyname FROM pg_policies WHERE tablename = 'group_members' AND schemaname = 'public'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.group_members', pol.policyname);
    END LOOP;
    
    -- community_members
    FOR pol IN SELECT policyname FROM pg_policies WHERE tablename = 'community_members' AND schemaname = 'public'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.community_members', pol.policyname);
    END LOOP;
    
    -- group_messages
    FOR pol IN SELECT policyname FROM pg_policies WHERE tablename = 'group_messages' AND schemaname = 'public'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.group_messages', pol.policyname);
    END LOOP;
    
    -- community_messages
    FOR pol IN SELECT policyname FROM pg_policies WHERE tablename = 'community_messages' AND schemaname = 'public'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.community_messages', pol.policyname);
    END LOOP;
END $$;

-- ========================================
-- PASO 2: FUNCIONES HELPER MUY SIMPLES
-- ========================================

CREATE OR REPLACE FUNCTION public.user_is_group_member(_group_id uuid, _user_id uuid)
RETURNS boolean 
LANGUAGE sql
SECURITY DEFINER
STABLE
AS $$
  SELECT EXISTS (
    SELECT 1 FROM public.group_members
    WHERE group_id = _group_id AND user_id = _user_id
  );
$$;

CREATE OR REPLACE FUNCTION public.user_is_community_member(_community_id uuid, _user_id uuid)
RETURNS boolean 
LANGUAGE sql
SECURITY DEFINER
STABLE
AS $$
  SELECT EXISTS (
    SELECT 1 FROM public.community_members
    WHERE community_id = _community_id AND user_id = _user_id
  );
$$;

-- ========================================
-- PASO 3: POLÍTICAS ULTRA SIMPLIFICADAS
-- ========================================

-- ==================== GROUP_MEMBERS ====================

CREATE POLICY "group_members_select_simple" ON public.group_members
FOR SELECT 
TO authenticated
USING (
    -- Puedo ver mi propia membresía
    user_id = auth.uid()
    OR
    -- Puedo ver otros miembros si yo también soy miembro
    public.user_is_group_member(group_id, auth.uid())
);

CREATE POLICY "group_members_insert_simple" ON public.group_members
FOR INSERT 
TO authenticated
WITH CHECK (
    -- Caso 1: El creador puede agregar miembros (verifica en tabla groups)
    EXISTS (
        SELECT 1 FROM public.groups 
        WHERE id = group_id 
        AND creator_id = auth.uid()
    )
    OR
    -- Caso 2: Un admin puede agregar miembros (sin recursión: usa función SECURITY DEFINER)
    (
        public.user_is_group_member(group_id, auth.uid())
        AND EXISTS (
            SELECT 1 FROM public.group_members gm
            WHERE gm.group_id = group_members.group_id 
            AND gm.user_id = auth.uid() 
            AND gm.role = 'admin'
        )
    )
);

CREATE POLICY "group_members_update_simple" ON public.group_members
FOR UPDATE 
TO authenticated
USING (
    public.user_is_group_member(group_id, auth.uid())
);

CREATE POLICY "group_members_delete_simple" ON public.group_members
FOR DELETE 
TO authenticated
USING (
    user_id = auth.uid() -- Puedo salirme del grupo
);

-- ==================== COMMUNITY_MEMBERS ====================

CREATE POLICY "community_members_select_simple" ON public.community_members
FOR SELECT 
TO authenticated
USING (
    -- Mi propia membresía
    user_id = auth.uid()
    OR
    -- Miembros de comunidades públicas
    EXISTS (SELECT 1 FROM public.communities WHERE id = community_id AND is_public = true)
    OR
    -- Otros miembros si soy miembro (función SECURITY DEFINER)
    public.user_is_community_member(community_id, auth.uid())
);

CREATE POLICY "community_members_insert_simple" ON public.community_members
FOR INSERT 
TO authenticated
WITH CHECK (
    -- Caso 1: El creador puede agregar miembros
    EXISTS (
        SELECT 1 FROM public.communities 
        WHERE id = community_id 
        AND creator_id = auth.uid()
    )
    OR
    -- Caso 2: Auto-join en comunidad pública
    (
        user_id = auth.uid() 
        AND EXISTS (SELECT 1 FROM public.communities WHERE id = community_id AND is_public = true)
    )
    OR
    -- Caso 3: Admin/Owner puede agregar miembros
    (
        public.user_is_community_member(community_id, auth.uid())
        AND EXISTS (
            SELECT 1 FROM public.community_members cm
            WHERE cm.community_id = community_members.community_id 
            AND cm.user_id = auth.uid() 
            AND cm.role IN ('owner', 'admin')
        )
    )
);

CREATE POLICY "community_members_update_simple" ON public.community_members
FOR UPDATE 
TO authenticated
USING (
    public.user_is_community_member(community_id, auth.uid())
);

CREATE POLICY "community_members_delete_simple" ON public.community_members
FOR DELETE 
TO authenticated
USING (
    user_id = auth.uid() -- Puedo salirme de la comunidad
);

-- ==================== GROUP_MESSAGES ====================

CREATE POLICY "group_messages_select_simple" ON public.group_messages
FOR SELECT 
TO authenticated
USING (
    public.user_is_group_member(group_id, auth.uid())
);

CREATE POLICY "group_messages_insert_simple" ON public.group_messages
FOR INSERT 
TO authenticated
WITH CHECK (
    sender_id = auth.uid() 
    AND public.user_is_group_member(group_id, auth.uid())
);

CREATE POLICY "group_messages_update_simple" ON public.group_messages
FOR UPDATE 
TO authenticated
USING (sender_id = auth.uid());

CREATE POLICY "group_messages_delete_simple" ON public.group_messages
FOR DELETE 
TO authenticated
USING (sender_id = auth.uid());

-- ==================== COMMUNITY_MESSAGES ====================

CREATE POLICY "community_messages_select_simple" ON public.community_messages
FOR SELECT 
TO authenticated
USING (
    -- Comunidades públicas
    EXISTS (SELECT 1 FROM public.communities WHERE id = community_id AND is_public = true)
    OR
    -- Soy miembro
    public.user_is_community_member(community_id, auth.uid())
);

CREATE POLICY "community_messages_insert_simple" ON public.community_messages
FOR INSERT 
TO authenticated
WITH CHECK (
    sender_id = auth.uid() 
    AND public.user_is_community_member(community_id, auth.uid())
);

CREATE POLICY "community_messages_update_simple" ON public.community_messages
FOR UPDATE 
TO authenticated
USING (sender_id = auth.uid());

CREATE POLICY "community_messages_delete_simple" ON public.community_messages
FOR DELETE 
TO authenticated
USING (sender_id = auth.uid());

-- ========================================
-- PASO 4: REACTIVAR RLS
-- ========================================
ALTER TABLE public.group_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.group_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_messages ENABLE ROW LEVEL SECURITY;

-- ========================================
-- ACIÓN: VERIFICAR
-- ========================================

SELECT '✅ FUNCIONES CREADAS' as status;
SELECT proname, prosecdef as security_definer FROM pg_proc WHERE proname LIKE 'user_is_%_member';

SELECT '✅ GROUP_MEMBERS POLICIES' as status;
SELECT policyname, cmd FROM pg_policies WHERE tablename = 'group_members' ORDER BY cmd;

SELECT '✅ COMMUNITY_MEMBERS POLICIES' as status;
SELECT policyname, cmd FROM pg_policies WHERE tablename = 'community_members' ORDER BY cmd;

SELECT '✅ GROUP_MESSAGES POLICIES' as status;
SELECT policyname, cmd FROM pg_policies WHERE tablename = 'group_messages' ORDER BY cmd;

SELECT '✅ COMMUNITY_MESSAGES POLICIES' as status;
SELECT policyname, cmd FROM pg_policies WHERE tablename = 'community_messages' ORDER BY cmd;

-- ==========================================
-- ✅ ESTE ES EL FIX MÁS SIMPLE Y DIRECTO POSIBLE
-- Las funciones son SQL puro (más rápido que PL/pgSQL)
-- Las políticas son minimalistas
-- NO HAY RECURSIÓN porque las funciones usan SECURITY DEFINER
-- ==========================================
