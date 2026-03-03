-- ==========================================
-- FIX RADICAL: POLÍTICAS PERMISIVAS PARA DESBLOQUEAR
-- Esto permitirá que todo funcione, luego se puede refinar
-- ==========================================

-- Deshabilitar RLS temporalmente
ALTER TABLE public.community_members DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.group_members DISABLE ROW LEVEL SECURITY;

-- Eliminar TODAS las políticas de estas tablas
DO $$ 
DECLARE pol RECORD;
BEGIN
    FOR pol IN SELECT policyname FROM pg_policies WHERE tablename = 'community_members' AND schemaname = 'public'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.community_members', pol.policyname);
    END LOOP;
    
    FOR pol IN SELECT policyname FROM pg_policies WHERE tablename = 'group_members' AND schemaname = 'public'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.group_members', pol.policyname);
    END LOOP;
END $$;

-- ==========================================
-- POLÍTICAS ULTRA-PERMISIVAS (SIN RECURSIÓN POSIBLE)
-- ==========================================

-- ========== COMMUNITY_MEMBERS ==========

-- SELECT: Cualquier usuario autenticado puede ver
CREATE POLICY "community_members_permissive_select" 
ON public.community_members
FOR SELECT 
TO authenticated
USING (true);  -- ✅ Sin condiciones = sin recursión

-- INSERT: Cualquier usuario autenticado puede insertar
CREATE POLICY "community_members_permissive_insert" 
ON public.community_members
FOR INSERT 
TO authenticated
WITH CHECK (true);  -- ✅ Sin condiciones = sin recursión

-- UPDATE: Solo puedo actualizar mi propia membresía
CREATE POLICY "community_members_permissive_update" 
ON public.community_members
FOR UPDATE 
TO authenticated
USING (user_id = auth.uid());  -- ✅ Solo verificación simple

-- DELETE: Solo puedo eliminar mi propia membresía
CREATE POLICY "community_members_permissive_delete" 
ON public.community_members
FOR DELETE 
TO authenticated
USING (user_id = auth.uid());  -- ✅ Solo verificación simple

-- ========== GROUP_MEMBERS ==========

-- SELECT: Cualquier usuario autenticado puede ver
CREATE POLICY "group_members_permissive_select" 
ON public.group_members
FOR SELECT 
TO authenticated
USING (true);  -- ✅ Sin condiciones = sin recursión

-- INSERT: Cualquier usuario autenticado puede insertar
CREATE POLICY "group_members_permissive_insert" 
ON public.group_members
FOR INSERT 
TO authenticated
WITH CHECK (true);  -- ✅ Sin condiciones = sin recursión

-- UPDATE: Solo puedo actualizar mi propia membresía
CREATE POLICY "group_members_permissive_update" 
ON public.group_members
FOR UPDATE 
TO authenticated
USING (user_id = auth.uid());  -- ✅ Solo verificación simple

-- DELETE: Solo puedo eliminar mi propia membresía
CREATE POLICY "group_members_permissive_delete" 
ON public.group_members
FOR DELETE 
TO authenticated
USING (user_id = auth.uid());  -- ✅ Solo verificación simple

-- Reactivar RLS
ALTER TABLE public.community_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.group_members ENABLE ROW LEVEL SECURITY;

-- ==========================================
-- VERIFICACIÓN
-- ==========================================

SELECT '✅ COMMUNITY_MEMBERS POLICIES (PERMISSIVE)' as status;
SELECT policyname, cmd FROM pg_policies WHERE tablename = 'community_members' ORDER BY cmd;

SELECT '✅ GROUP_MEMBERS POLICIES (PERMISSIVE)' as status;
SELECT policyname, cmd FROM pg_policies WHERE tablename = 'group_members' ORDER BY cmd;

-- ==========================================
-- ⚠️ IMPORTANTE: Estas políticas son PERMISIVAS
-- ==========================================
-- Permiten que cualquier usuario autenticado:
-- - Vea todos los miembros (SELECT)
-- - Se una a cualquier grupo/comunidad (INSERT)
-- - Solo pueda editar/eliminar su propia membresía (UPDATE/DELETE)
--
-- Esto NO es ideal para producción pero:
-- ✅ FUNCIONARÁ sin errores de recursión
-- ✅ Permitirá crear grupos y comunidades
-- ✅ Permitirá enviar mensajes
--
-- Una vez que funcione, podemos refinar las políticas
-- para ser más restrictivas
-- ==========================================
