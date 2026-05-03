-- ==========================================
-- FIX COMPLETO: GROUPS, COMMUNITIES Y SUS MENSAJES
-- ==========================================

-- ========================================
-- PASO 1: DESHABILITAR RLS EN TODAS LAS TABLAS
-- ========================================
ALTER TABLE public.group_members DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_members DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.group_messages DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_messages DISABLE ROW LEVEL SECURITY;

-- ========================================
-- PASO 2: ELIMINAR TODAS LAS POLÍTICAS EXISTENTES
-- ========================================

-- Eliminar políticas de group_members
DO $$ 
DECLARE pol RECORD;
BEGIN
    FOR pol IN 
        SELECT policyname FROM pg_policies 
        WHERE tablename = 'group_members' AND schemaname = 'public'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.group_members', pol.policyname);
    END LOOP;
END $$;

-- Eliminar políticas de community_members
DO $$ 
DECLARE pol RECORD;
BEGIN
    FOR pol IN 
        SELECT policyname FROM pg_policies 
        WHERE tablename = 'community_members' AND schemaname = 'public'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.community_members', pol.policyname);
    END LOOP;
END $$;

-- Eliminar políticas de group_messages
DO $$ 
DECLARE pol RECORD;
BEGIN
    FOR pol IN 
        SELECT policyname FROM pg_policies 
        WHERE tablename = 'group_messages' AND schemaname = 'public'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.group_messages', pol.policyname);
    END LOOP;
END $$;

-- Eliminar políticas de community_messages
DO $$ 
DECLARE pol RECORD;
BEGIN
    FOR pol IN 
        SELECT policyname FROM pg_policies 
        WHERE tablename = 'community_messages' AND schemaname = 'public'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.community_messages', pol.policyname);
    END LOOP;
END $$;

-- ========================================
-- PASO 3: CREAR/RECREAR FUNCIONES HELPER
-- ========================================

-- Función: verificar si soy miembro de un grupo
CREATE OR REPLACE FUNCTION public.is_group_member(_group_id uuid)
RETURNS boolean 
LANGUAGE plpgsql 
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  RETURN EXISTS (
    SELECT 1 FROM public.group_members
    WHERE group_id = _group_id AND user_id = auth.uid()
  );
END;
$$;

-- Función: verificar si soy admin de un grupo
CREATE OR REPLACE FUNCTION public.is_group_admin(_group_id uuid)
RETURNS boolean 
LANGUAGE plpgsql 
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  RETURN EXISTS (
    SELECT 1 FROM public.group_members
    WHERE group_id = _group_id AND user_id = auth.uid() AND role = 'admin'
  );
END;
$$;

-- Función: verificar si soy miembro de una comunidad
CREATE OR REPLACE FUNCTION public.is_community_member(_community_id uuid)
RETURNS boolean 
LANGUAGE plpgsql 
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  RETURN EXISTS (
    SELECT 1 FROM public.community_members
    WHERE community_id = _community_id AND user_id = auth.uid()
  );
END;
$$;

-- Función: verificar si soy admin/owner de una comunidad
CREATE OR REPLACE FUNCTION public.is_community_admin(_community_id uuid)
RETURNS boolean 
LANGUAGE plpgsql 
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  RETURN EXISTS (
    SELECT 1 FROM public.community_members
    WHERE community_id = _community_id 
    AND user_id = auth.uid() 
    AND role IN ('owner', 'admin')
  );
END;
$$;

-- ========================================
-- PASO 4: POLÍTICAS PARA GROUP_MEMBERS
-- ========================================

CREATE POLICY "group_members_select" ON public.group_members
FOR SELECT USING (
    user_id = auth.uid() OR public.is_group_member(group_id)
);

CREATE POLICY "group_members_insert" ON public.group_members
FOR INSERT WITH CHECK (
    EXISTS (SELECT 1 FROM public.groups WHERE id = group_id AND creator_id = auth.uid())
    OR public.is_group_admin(group_id)
);

CREATE POLICY "group_members_update" ON public.group_members
FOR UPDATE USING (public.is_group_admin(group_id));

CREATE POLICY "group_members_delete" ON public.group_members
FOR DELETE USING (
    public.is_group_admin(group_id) OR user_id = auth.uid()
);

-- ========================================
-- PASO 5: POLÍTICAS PARA COMMUNITY_MEMBERS
-- ========================================

CREATE POLICY "community_members_select" ON public.community_members
FOR SELECT USING (
    user_id = auth.uid()
    OR EXISTS (SELECT 1 FROM public.communities WHERE id = community_id AND is_public = true)
    OR public.is_community_member(community_id)
);

CREATE POLICY "community_members_insert" ON public.community_members
FOR INSERT WITH CHECK (
    EXISTS (SELECT 1 FROM public.communities WHERE id = community_id AND creator_id = auth.uid())
    OR public.is_community_admin(community_id)
    OR (
        user_id = auth.uid() 
        AND EXISTS (SELECT 1 FROM public.communities WHERE id = community_id AND is_public = true)
    )
);

CREATE POLICY "community_members_update" ON public.community_members
FOR UPDATE USING (public.is_community_admin(community_id));

CREATE POLICY "community_members_delete" ON public.community_members
FOR DELETE USING (
    public.is_community_admin(community_id) OR user_id = auth.uid()
);

-- ========================================
-- PASO 6: POLÍTICAS PARA GROUP_MESSAGES
-- ========================================

CREATE POLICY "group_messages_select" ON public.group_messages
FOR SELECT USING (
    public.is_group_member(group_id)
);

CREATE POLICY "group_messages_insert" ON public.group_messages
FOR INSERT WITH CHECK (
    auth.uid() = sender_id AND public.is_group_member(group_id)
);

CREATE POLICY "group_messages_update" ON public.group_messages
FOR UPDATE USING (
    auth.uid() = sender_id
);

CREATE POLICY "group_messages_delete" ON public.group_messages
FOR DELETE USING (
    auth.uid() = sender_id OR public.is_group_admin(group_id)
);

-- ========================================
-- PASO 7: POLÍTICAS PARA COMMUNITY_MESSAGES
-- ========================================

CREATE POLICY "community_messages_select" ON public.community_messages
FOR SELECT USING (
    EXISTS (SELECT 1 FROM public.communities WHERE id = community_id AND is_public = true)
    OR public.is_community_member(community_id)
);

CREATE POLICY "community_messages_insert" ON public.community_messages
FOR INSERT WITH CHECK (
    auth.uid() = sender_id AND public.is_community_member(community_id)
);

CREATE POLICY "community_messages_update" ON public.community_messages
FOR UPDATE USING (
    auth.uid() = sender_id
);

CREATE POLICY "community_messages_delete" ON public.community_messages
FOR DELETE USING (
    auth.uid() = sender_id OR public.is_community_admin(community_id)
);

-- ========================================
-- PASO 8: REACTIVAR RLS
-- ========================================
ALTER TABLE public.group_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.group_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_messages ENABLE ROW LEVEL SECURITY;

-- ========================================
-- PASO 9: VERIFICACIÓN
-- ========================================

SELECT '✅ POLÍTICAS DE GROUP_MEMBERS' as status;
SELECT policyname, cmd FROM pg_policies WHERE tablename = 'group_members' ORDER BY cmd;

SELECT '✅ POLÍTICAS DE COMMUNITY_MEMBERS' as status;
SELECT policyname, cmd FROM pg_policies WHERE tablename = 'community_members' ORDER BY cmd;

SELECT '✅ POLÍTICAS DE GROUP_MESSAGES' as status;
SELECT policyname, cmd FROM pg_policies WHERE tablename = 'group_messages' ORDER BY cmd;

SELECT '✅ POLÍTICAS DE COMMUNITY_MESSAGES' as status;
SELECT policyname, cmd FROM pg_policies WHERE tablename = 'community_messages' ORDER BY cmd;

SELECT '✅ FUNCIONES HELPER' as status;
SELECT proname FROM pg_proc WHERE proname LIKE 'is_%_member' OR proname LIKE 'is_%_admin';

-- ==========================================
-- ✅ COMPLETADO
-- Ahora deberías poder:
-- 1. Crear grupos ✅
-- 2. Enviar mensajes en grupos ✅
-- 3. Crear comunidades ✅
-- 4. Enviar mensajes en comunidades ✅
-- ==========================================
