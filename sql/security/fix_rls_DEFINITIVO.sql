-- ==========================================
-- SOLUCIÓN DEFINITIVA RLS RECURSION
-- Este script FUERZA la eliminación total y reconstrucción
-- ==========================================

-- ========================================
-- PASO 1: DESHABILITAR RLS TEMPORALMENTE
-- ========================================
ALTER TABLE public.group_members DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_members DISABLE ROW LEVEL SECURITY;

-- ========================================
-- PASO 2: ELIMINAR TODAS LAS POLÍTICAS EXISTENTES
-- ========================================

-- Eliminar TODAS las políticas de group_members (sin importar el nombre)
DO $$ 
DECLARE
    pol RECORD;
BEGIN
    FOR pol IN 
        SELECT policyname 
        FROM pg_policies 
        WHERE tablename = 'group_members' AND schemaname = 'public'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.group_members', pol.policyname);
    END LOOP;
END $$;

-- Eliminar TODAS las políticas de community_members (sin importar el nombre)
DO $$ 
DECLARE
    pol RECORD;
BEGIN
    FOR pol IN 
        SELECT policyname 
        FROM pg_policies 
        WHERE tablename = 'community_members' AND schemaname = 'public'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.community_members', pol.policyname);
    END LOOP;
END $$;

-- ========================================
-- PASO 3: CREAR FUNCIONES HELPER (SECURITY DEFINER)
-- ========================================

-- Función para verificar membresía de grupos (SIN RECURSIÓN)
CREATE OR REPLACE FUNCTION public.is_group_member(_group_id uuid)
RETURNS boolean 
LANGUAGE plpgsql 
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  -- SECURITY DEFINER = corre con privilegios de owner, bypasses RLS
  RETURN EXISTS (
    SELECT 1 FROM public.group_members
    WHERE group_id = _group_id 
    AND user_id = auth.uid()
  );
END;
$$;

-- Función para verificar si soy admin de un grupo
CREATE OR REPLACE FUNCTION public.is_group_admin(_group_id uuid)
RETURNS boolean 
LANGUAGE plpgsql 
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  RETURN EXISTS (
    SELECT 1 FROM public.group_members
    WHERE group_id = _group_id 
    AND user_id = auth.uid() 
    AND role = 'admin'
  );
END;
$$;

-- Función para verificar membresía de comunidades (SIN RECURSIÓN)
CREATE OR REPLACE FUNCTION public.is_community_member(_community_id uuid)
RETURNS boolean 
LANGUAGE plpgsql 
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
  -- SECURITY DEFINER = corre con privilegios de owner, bypasses RLS
  RETURN EXISTS (
    SELECT 1 FROM public.community_members
    WHERE community_id = _community_id 
    AND user_id = auth.uid()
  );
END;
$$;

-- Función para verificar si soy admin/owner de una comunidad
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
-- PASO 4: CREAR POLÍTICAS PARA GROUP_MEMBERS
-- ========================================

-- SELECT: Ver miembros de grupos a los que pertenezco
CREATE POLICY "group_members_select_policy" 
ON public.group_members
FOR SELECT 
USING (
    user_id = auth.uid()  -- Mi propia membresía
    OR 
    public.is_group_member(group_id)  -- Soy miembro del grupo
);

-- INSERT: Solo creador o admin pueden agregar miembros
CREATE POLICY "group_members_insert_policy" 
ON public.group_members
FOR INSERT 
WITH CHECK (
    -- El creador del grupo puede agregar miembros
    EXISTS (
        SELECT 1 FROM public.groups 
        WHERE id = group_id 
        AND creator_id = auth.uid()
    )
    OR
    -- Un admin puede agregar miembros
    public.is_group_admin(group_id)
);

-- UPDATE: Solo admins pueden actualizar roles
CREATE POLICY "group_members_update_policy" 
ON public.group_members
FOR UPDATE 
USING (
    public.is_group_admin(group_id)
);

-- DELETE: Admin puede eliminar o usuario puede salirse
CREATE POLICY "group_members_delete_policy" 
ON public.group_members
FOR DELETE 
USING (
    public.is_group_admin(group_id)  -- Admin elimina
    OR 
    user_id = auth.uid()  -- Me salgo yo mismo
);

-- ========================================
-- PASO 5: CREAR POLÍTICAS PARA COMMUNITY_MEMBERS
-- ========================================

-- SELECT: Ver miembros de comunidades públicas o a las que pertenezco
CREATE POLICY "community_members_select_policy" 
ON public.community_members
FOR SELECT 
USING (
    user_id = auth.uid()  -- Mi propia membresía
    OR
    EXISTS (
        SELECT 1 FROM public.communities 
        WHERE id = community_id 
        AND is_public = true  -- Comunidad pública
    )
    OR
    public.is_community_member(community_id)  -- Soy miembro
);

-- INSERT: Creador, admin o auto-join en públicas
CREATE POLICY "community_members_insert_policy" 
ON public.community_members
FOR INSERT 
WITH CHECK (
    -- El creador de la comunidad puede agregar miembros
    EXISTS (
        SELECT 1 FROM public.communities 
        WHERE id = community_id 
        AND creator_id = auth.uid()
    )
    OR
    -- Un admin/owner puede agregar miembros
    public.is_community_admin(community_id)
    OR
    -- Me uno yo mismo a una comunidad pública
    (
        user_id = auth.uid() 
        AND EXISTS (
            SELECT 1 FROM public.communities 
            WHERE id = community_id 
            AND is_public = true
        )
    )
);

-- UPDATE: Solo owners/admins pueden actualizar roles
CREATE POLICY "community_members_update_policy" 
ON public.community_members
FOR UPDATE 
USING (
    public.is_community_admin(community_id)
);

-- DELETE: Owner/admin puede eliminar o usuario puede salirse
CREATE POLICY "community_members_delete_policy" 
ON public.community_members
FOR DELETE 
USING (
    public.is_community_admin(community_id)  -- Admin elimina
    OR 
    user_id = auth.uid()  -- Me salgo yo mismo
);

-- ========================================
-- PASO 6: REACTIVAR RLS
-- ========================================
ALTER TABLE public.group_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_members ENABLE ROW LEVEL SECURITY;

-- ========================================
-- PASO 7: VERIFICACIÓN COMPLETA
-- ========================================

-- Verificar funciones
SELECT 
    'Funciones creadas:' as titulo,
    proname as funcion 
FROM pg_proc 
WHERE proname IN (
    'is_group_member', 
    'is_group_admin',
    'is_community_member', 
    'is_community_admin'
)
AND pronamespace = 'public'::regnamespace;

-- Verificar políticas de group_members
SELECT 
    'Políticas de group_members:' as titulo,
    policyname,
    cmd as operacion
FROM pg_policies
WHERE tablename = 'group_members' 
AND schemaname = 'public'
ORDER BY cmd;

-- Verificar políticas de community_members
SELECT 
    'Políticas de community_members:' as titulo,
    policyname,
    cmd as operacion
FROM pg_policies
WHERE tablename = 'community_members' 
AND schemaname = 'public'
ORDER BY cmd;

-- Verificar que RLS está habilitado
SELECT 
    'RLS Status:' as titulo,
    tablename,
    CASE WHEN rowsecurity THEN 'ENABLED ✅' ELSE 'DISABLED ❌' END as rls_status
FROM pg_tables
WHERE tablename IN ('group_members', 'community_members')
AND schemaname = 'public';

-- ==========================================
-- ✅ FIN DEL SCRIPT DEFINITIVO
-- ==========================================
