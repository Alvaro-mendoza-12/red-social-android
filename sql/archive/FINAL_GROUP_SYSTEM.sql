-- ========================================================
-- 🛡️ FINAL_GROUP_SYSTEM: SOLUCIÓN DEFINITIVA (NUCLEAR) - V3
-- ========================================================
-- CORRECCIÓN: Tipos de datos (UUID vs TEXT) arreglados.

BEGIN;

-- 1. LIMPIEZA TOTAL
DROP POLICY IF EXISTS "groups_master_policy" ON public.groups;
DROP POLICY IF EXISTS "members_master_policy" ON public.group_members;
DROP POLICY IF EXISTS "messages_master_policy" ON public.group_messages;
DROP POLICY IF EXISTS "group_select_v16" ON public.groups;
DROP POLICY IF EXISTS "group_mem_select_v16" ON public.group_members;
DROP POLICY IF EXISTS "group_msg_select_v16" ON public.group_messages;
DROP POLICY IF EXISTS "groups_select_policy" ON public.groups;
DROP POLICY IF EXISTS "groups_insert_policy" ON public.groups;
DROP POLICY IF EXISTS "groups_update_policy" ON public.groups;
DROP POLICY IF EXISTS "groups_delete_policy" ON public.groups;
DROP POLICY IF EXISTS "members_select_policy" ON public.group_members;
DROP POLICY IF EXISTS "members_insert_policy" ON public.group_members;
DROP POLICY IF EXISTS "members_delete_policy" ON public.group_members;
DROP POLICY IF EXISTS "messages_select_policy" ON public.group_messages;
DROP POLICY IF EXISTS "messages_insert_policy" ON public.group_messages;

-- Borrar funciones previas (incluyendo versiones mal creadas)
DROP FUNCTION IF EXISTS public.is_group_member(text); 
DROP FUNCTION IF EXISTS public.is_group_member(uuid);
DROP FUNCTION IF EXISTS public.check_is_group_member(text);
DROP FUNCTION IF EXISTS public.func_is_group_member(uuid, text);
DROP FUNCTION IF EXISTS public.func_is_group_member_v2(uuid, text); -- Borrar la anterior incorrecta
DROP FUNCTION IF EXISTS public.func_is_group_admin_v2(uuid, text);

-- 2. FUNCIONES SEGURAS (SECURITY DEFINER) - AHORA CON UUID
-- Cambiamos group_uuid a tipo UUID para coincidir con la columna de la tabla.
CREATE OR REPLACE FUNCTION public.func_is_group_member_v3(user_uuid UUID, group_uuid UUID)
RETURNS BOOLEAN AS $$
BEGIN
  RETURN EXISTS (
    SELECT 1 FROM public.group_members
    WHERE group_id = group_uuid AND user_id = user_uuid
  );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION public.func_is_group_admin_v3(user_uuid UUID, group_uuid UUID)
RETURNS BOOLEAN AS $$
BEGIN
  RETURN EXISTS (
    SELECT 1 FROM public.group_members
    WHERE group_id = group_uuid 
    AND user_id = user_uuid 
    AND role IN ('admin', 'owner')
  );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 3. POLÍTICAS DE GRUPOS (groups)
ALTER TABLE public.groups ENABLE ROW LEVEL SECURITY;

CREATE POLICY "policy_groups_select_v3" ON public.groups
    FOR SELECT USING (
        creator_id = auth.uid() 
        OR public.func_is_group_member_v3(auth.uid(), id)
    );

CREATE POLICY "policy_groups_insert_v3" ON public.groups
    FOR INSERT WITH CHECK (
        auth.uid() = creator_id
    );

CREATE POLICY "policy_groups_update_v3" ON public.groups
    FOR UPDATE USING (
        creator_id = auth.uid() 
        OR public.func_is_group_admin_v3(auth.uid(), id)
    );

CREATE POLICY "policy_groups_delete_v3" ON public.groups
    FOR DELETE USING (
        creator_id = auth.uid()
    );

-- 4. POLÍTICAS DE MIEMBROS (group_members)
ALTER TABLE public.group_members ENABLE ROW LEVEL SECURITY;

CREATE POLICY "policy_members_select_v3" ON public.group_members
    FOR SELECT USING (
        user_id = auth.uid() 
        OR public.func_is_group_member_v3(auth.uid(), group_id)
    );

CREATE POLICY "policy_members_insert_v3" ON public.group_members
    FOR INSERT WITH CHECK (
        -- Soy el Creador del grupo
        EXISTS (
            SELECT 1 FROM public.groups 
            WHERE id = group_id AND creator_id = auth.uid()
        )
        OR
        -- O soy Admin del grupo
        public.func_is_group_admin_v3(auth.uid(), group_id)
    );

CREATE POLICY "policy_members_delete_v3" ON public.group_members
    FOR DELETE USING (
        user_id = auth.uid()
        OR
        EXISTS (
            SELECT 1 FROM public.groups 
            WHERE id = group_id AND creator_id = auth.uid()
        )
        OR
        public.func_is_group_admin_v3(auth.uid(), group_id)
    );

-- 5. POLÍTICAS DE MENSAJES (group_messages)
ALTER TABLE public.group_messages ENABLE ROW LEVEL SECURITY;

CREATE POLICY "policy_messages_select_v3" ON public.group_messages
    FOR SELECT USING (
        public.func_is_group_member_v3(auth.uid(), group_id)
    );

CREATE POLICY "policy_messages_insert_v3" ON public.group_messages
    FOR INSERT WITH CHECK (
        public.func_is_group_member_v3(auth.uid(), group_id)
    );

COMMIT;
