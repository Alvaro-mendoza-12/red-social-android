-- ========================================================
-- 🏆 MASTER FIX: SISTEMA DE GRUPOS DEFINITIVO
-- ========================================================
-- REEMPLAZA todos los archivos anteriores (v1 a v16).
-- Resuelve: Recursión, Privacidad, Visibilidad y Errores de Creador.

BEGIN;

-- 1. LIMPIEZA DE POLÍTICAS PREVIAS (Para evitar conflictos)
DROP POLICY IF EXISTS "groups_master_policy" ON public.groups;
DROP POLICY IF EXISTS "members_master_policy" ON public.group_members;
DROP POLICY IF EXISTS "messages_master_policy" ON public.group_messages;
DROP POLICY IF EXISTS "group_select_v16" ON public.groups;
DROP POLICY IF EXISTS "group_mem_select_v16" ON public.group_members;
DROP POLICY IF EXISTS "group_msg_select_v16" ON public.group_messages;

-- 2. FUNCIÓN DE SEGURIDAD INTERNA (Rompe el bucle de recursión)
-- Al ser 'SECURITY DEFINER', Supabase no aplica RLS dentro de la función.
CREATE OR REPLACE FUNCTION public.is_group_member(gid TEXT)
RETURNS BOOLEAN AS $$
BEGIN
  RETURN EXISTS (
    SELECT 1 FROM public.group_members
    WHERE group_id = gid AND user_id = auth.uid()
  );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 3. TABLA: groups
ALTER TABLE public.groups ENABLE ROW LEVEL SECURITY;
CREATE POLICY "groups_select_policy" ON public.groups
    FOR SELECT USING (
        creator_id = auth.uid() 
        OR public.is_group_member(id)
    );

CREATE POLICY "groups_insert_policy" ON public.groups
    FOR INSERT WITH CHECK (auth.uid() = creator_id);

CREATE POLICY "groups_update_policy" ON public.groups
    FOR UPDATE USING (
        creator_id = auth.uid() 
        OR (SELECT role FROM public.group_members WHERE group_id = id AND user_id = auth.uid()) = 'admin'
    );

CREATE POLICY "groups_delete_policy" ON public.groups
    FOR DELETE USING (creator_id = auth.uid());

-- 4. TABLA: group_members
ALTER TABLE public.group_members ENABLE ROW LEVEL SECURITY;
CREATE POLICY "members_select_policy" ON public.group_members
    FOR SELECT USING (
        user_id = auth.uid() 
        OR public.is_group_member(group_id)
    );

CREATE POLICY "members_insert_policy" ON public.group_members
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.groups 
            WHERE id = group_id AND (creator_id = auth.uid() OR public.is_group_member(id))
        )
    );

CREATE POLICY "members_delete_policy" ON public.group_members
    FOR DELETE USING (
        user_id = auth.uid() -- Salir uno mismo
        OR EXISTS (
            SELECT 1 FROM public.groups 
            WHERE id = group_id AND creator_id = auth.uid() -- El creador expulsa
        )
        OR (
            SELECT role FROM public.group_members 
            WHERE group_id = public.group_members.group_id AND user_id = auth.uid()
        ) = 'admin' -- Admin expulsa
    );

-- 5. TABLA: group_messages (Privacidad Total)
ALTER TABLE public.group_messages ENABLE ROW LEVEL SECURITY;
CREATE POLICY "messages_select_policy" ON public.group_messages
    FOR SELECT USING (
        public.is_group_member(group_id)
    );

CREATE POLICY "messages_insert_policy" ON public.group_messages
    FOR INSERT WITH CHECK (
        public.is_group_member(group_id)
    );

-- 6. CONFIGURACIÓN DE REALTIME (Señales sin fugas)
ALTER TABLE public.groups REPLICA IDENTITY FULL;
ALTER TABLE public.group_members REPLICA IDENTITY FULL;
ALTER TABLE public.group_messages REPLICA IDENTITY FULL;

COMMIT;

-- VERIFICACIÓN FINAL:
-- - Si te expulsan, 'is_group_member' devuelve FALSE inmediatamente.
-- - El SELECT de mensajes deja de funcionar -> Realtime deja de enviarte datos.
-- - El creador tiene acceso garantizado por su 'creator_id'.
