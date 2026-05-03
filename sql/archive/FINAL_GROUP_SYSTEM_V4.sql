-- ========================================================
-- 🛡️ FINAL_GROUP_SYSTEM V4: SOLUCIÓN SIN RECURSIÓN (GARANTIZADA)
-- ========================================================
-- Elimina TODA dependencia circular usando funciones SECURITY DEFINER
-- para TODAS las validaciones que requieran leer tablas relacionadas.

BEGIN;

-- 1. LIMPIEZA NUCLEAR DE POLÍTICAS
DO $$ 
DECLARE
    r RECORD;
BEGIN
    -- Borrar TODAS las políticas de estas tablas sin importar el nombre
    FOR r IN (SELECT policyname, tablename FROM pg_policies 
              WHERE schemaname = 'public' 
              AND tablename IN ('groups', 'group_members', 'group_messages'))
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.%I', r.policyname, r.tablename);
    END LOOP;
END $$;

-- 2. ELIMINAR FUNCIONES PREVIAS
DROP FUNCTION IF EXISTS public.is_group_member(text);
DROP FUNCTION IF EXISTS public.is_group_member(uuid);
DROP FUNCTION IF EXISTS public.check_is_group_member(text);
DROP FUNCTION IF EXISTS public.check_is_group_member(uuid);
DROP FUNCTION IF EXISTS public.func_is_group_member(uuid, text);
DROP FUNCTION IF EXISTS public.func_is_group_member_v2(uuid, text);
DROP FUNCTION IF EXISTS public.func_is_group_member_v3(uuid, uuid);
DROP FUNCTION IF EXISTS public.func_is_group_admin_v2(uuid, text);
DROP FUNCTION IF EXISTS public.func_is_group_admin_v3(uuid, uuid);
DROP FUNCTION IF EXISTS public.func_is_group_creator(uuid, uuid);

-- 3. FUNCIONES SEGURAS (SECURITY DEFINER)
-- Estas funciones ignoran COMPLETAMENTE las políticas RLS.

-- Verifica si un usuario es miembro de un grupo
CREATE OR REPLACE FUNCTION public.func_is_group_member_v4(check_user_id UUID, check_group_id UUID)
RETURNS BOOLEAN AS $$
BEGIN
  RETURN EXISTS (
    SELECT 1 FROM public.group_members
    WHERE group_id = check_group_id AND user_id = check_user_id
  );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER STABLE;

-- Verifica si un usuario es admin de un grupo
CREATE OR REPLACE FUNCTION public.func_is_group_admin_v4(check_user_id UUID, check_group_id UUID)
RETURNS BOOLEAN AS $$
BEGIN
  RETURN EXISTS (
    SELECT 1 FROM public.group_members
    WHERE group_id = check_group_id 
    AND user_id = check_user_id 
    AND role IN ('admin', 'owner')
  );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER STABLE;

-- Verifica si un usuario es el creador de un grupo (SIN ACTIVAR POLÍTICAS DE GROUPS)
CREATE OR REPLACE FUNCTION public.func_is_group_creator_v4(check_user_id UUID, check_group_id UUID)
RETURNS BOOLEAN AS $$
BEGIN
  RETURN EXISTS (
    SELECT 1 FROM public.groups
    WHERE id = check_group_id AND creator_id = check_user_id
  );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER STABLE;

-- 4. POLÍTICAS PARA GROUPS
ALTER TABLE public.groups ENABLE ROW LEVEL SECURITY;

-- SELECT: Puedo ver grupos donde soy creador O miembro
CREATE POLICY "pol_groups_select_v4" ON public.groups
    FOR SELECT USING (
        creator_id = auth.uid() 
        OR public.func_is_group_member_v4(auth.uid(), id)
    );

-- INSERT: Solo puedo insertar si soy el creador especificado
CREATE POLICY "pol_groups_insert_v4" ON public.groups
    FOR INSERT WITH CHECK (
        auth.uid() = creator_id
    );

-- UPDATE: Solo creador o admin del grupo
CREATE POLICY "pol_groups_update_v4" ON public.groups
    FOR UPDATE USING (
        creator_id = auth.uid() 
        OR public.func_is_group_admin_v4(auth.uid(), id)
    );

-- DELETE: Solo el creador
CREATE POLICY "pol_groups_delete_v4" ON public.groups
    FOR DELETE USING (
        creator_id = auth.uid()
    );

-- 5. POLÍTICAS PARA GROUP_MEMBERS
ALTER TABLE public.group_members ENABLE ROW LEVEL SECURITY;

-- SELECT: Veo mi membresía O membresías de grupos donde participo
CREATE POLICY "pol_members_select_v4" ON public.group_members
    FOR SELECT USING (
        user_id = auth.uid() 
        OR public.func_is_group_member_v4(auth.uid(), group_id)
    );

-- INSERT: El creador del grupo O un admin pueden agregar miembros
-- CRÍTICO: Usamos func_is_group_creator_v4 que NO activa políticas de groups
CREATE POLICY "pol_members_insert_v4" ON public.group_members
    FOR INSERT WITH CHECK (
        public.func_is_group_creator_v4(auth.uid(), group_id)
        OR
        public.func_is_group_admin_v4(auth.uid(), group_id)
    );

-- DELETE: Puedo salirme yo mismo O ser expulsado por creador/admin
CREATE POLICY "pol_members_delete_v4" ON public.group_members
    FOR DELETE USING (
        user_id = auth.uid()
        OR
        public.func_is_group_creator_v4(auth.uid(), group_id)
        OR
        public.func_is_group_admin_v4(auth.uid(), group_id)
    );

-- 6. POLÍTICAS PARA GROUP_MESSAGES
ALTER TABLE public.group_messages ENABLE ROW LEVEL SECURITY;

-- SELECT: Solo miembros activos
CREATE POLICY "pol_messages_select_v4" ON public.group_messages
    FOR SELECT USING (
        public.func_is_group_member_v4(auth.uid(), group_id)
    );

-- INSERT: Solo miembros activos
CREATE POLICY "pol_messages_insert_v4" ON public.group_messages
    FOR INSERT WITH CHECK (
        public.func_is_group_member_v4(auth.uid(), group_id)
    );

COMMIT;

-- EXPLICACIÓN DEL FIX:
-- La V3 fallaba porque la política INSERT de group_members hacía:
--   EXISTS (SELECT FROM groups WHERE creator_id = ...)
-- Esto activaba la política SELECT de groups, que llamaba a func_is_group_member,
-- creando un ciclo durante la creación del primer miembro.
--
-- SOLUCIÓN V4:
-- func_is_group_creator_v4 lee DIRECTAMENTE de 'groups' sin pasar por sus políticas,
-- rompiendo completamente el ciclo.
