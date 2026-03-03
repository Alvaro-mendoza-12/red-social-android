-- ========================================================
-- 🛡️ FINAL_GROUP_SYSTEM V5: PERMISOS AMPLIADOS + ESTABILIDAD
-- ========================================================
-- 1. Permite que CUALQUIER MIEMBRO añada nuevos integrantes (petición de usuario).
-- 2. Mantiene la seguridad anti-recursión.

BEGIN;

-- 1. LIMPIEZA DINÁMICA DE V4
DO $$ 
DECLARE
    r RECORD;
BEGIN
    FOR r IN (SELECT policyname, tablename FROM pg_policies 
              WHERE schemaname = 'public' 
              AND tablename IN ('groups', 'group_members', 'group_messages'))
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.%I', r.policyname, r.tablename);
    END LOOP;
END $$;

-- 2. (RE)DEFINIR FUNCIONES - Nos aseguramos que existan y sean correctas
-- No borramos funciones si ya existen y son correctas, pero REEMPLAZAMOS para asegurar el código.

CREATE OR REPLACE FUNCTION public.func_is_group_member_v4(check_user_id UUID, check_group_id UUID)
RETURNS BOOLEAN AS $$
BEGIN
  RETURN EXISTS (
    SELECT 1 FROM public.group_members
    WHERE group_id = check_group_id AND user_id = check_user_id
  );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER STABLE;

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

CREATE OR REPLACE FUNCTION public.func_is_group_creator_v4(check_user_id UUID, check_group_id UUID)
RETURNS BOOLEAN AS $$
BEGIN
  RETURN EXISTS (
    SELECT 1 FROM public.groups
    WHERE id = check_group_id AND creator_id = check_user_id
  );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER STABLE;

-- 3. POLÍTICAS DE GRUPOS (groups) - V5
ALTER TABLE public.groups ENABLE ROW LEVEL SECURITY;

CREATE POLICY "pol_groups_select_v5" ON public.groups
    FOR SELECT USING (
        creator_id = auth.uid() 
        OR public.func_is_group_member_v4(auth.uid(), id)
    );

CREATE POLICY "pol_groups_insert_v5" ON public.groups
    FOR INSERT WITH CHECK (
        auth.uid() = creator_id
    );

CREATE POLICY "pol_groups_update_v5" ON public.groups
    FOR UPDATE USING (
        creator_id = auth.uid() 
        OR public.func_is_group_admin_v4(auth.uid(), id)
    );

CREATE POLICY "pol_groups_delete_v5" ON public.groups
    FOR DELETE USING (
        creator_id = auth.uid()
    );

-- 4. POLÍTICAS DE MIEMBROS (group_members) - V5
ALTER TABLE public.group_members ENABLE ROW LEVEL SECURITY;

CREATE POLICY "pol_members_select_v5" ON public.group_members
    FOR SELECT USING (
        user_id = auth.uid() 
        OR public.func_is_group_member_v4(auth.uid(), group_id)
    );

-- INSERT: MODIFICADO PARA PERMITIR A TODOS LOS MIEMBROS AÑADIR
CREATE POLICY "pol_members_insert_v5" ON public.group_members
    FOR INSERT WITH CHECK (
        -- 1. El Creador (para inicializar el grupo)
        public.func_is_group_creator_v4(auth.uid(), group_id)
        OR
        -- 2. Admins del grupo
        public.func_is_group_admin_v4(auth.uid(), group_id)
        OR
        -- 3. CUALQUIER Miembro existente (Petición: "todos los usuarios deben poder añadir")
        public.func_is_group_member_v4(auth.uid(), group_id)
    );

CREATE POLICY "pol_members_delete_v5" ON public.group_members
    FOR DELETE USING (
        user_id = auth.uid() -- Salir
        OR
        public.func_is_group_creator_v4(auth.uid(), group_id) -- Creador expulsa
        OR
        public.func_is_group_admin_v4(auth.uid(), group_id) -- Admin expulsa
    );

-- 5. POLÍTICAS DE MENSAJES (group_messages) - V5
ALTER TABLE public.group_messages ENABLE ROW LEVEL SECURITY;

CREATE POLICY "pol_messages_select_v5" ON public.group_messages
    FOR SELECT USING (
        public.func_is_group_member_v4(auth.uid(), group_id)
    );

CREATE POLICY "pol_messages_insert_v5" ON public.group_messages
    FOR INSERT WITH CHECK (
        public.func_is_group_member_v4(auth.uid(), group_id)
    );

COMMIT;
