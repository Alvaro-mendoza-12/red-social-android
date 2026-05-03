-- ========================================================
-- REPARACIÓN NUCLEAR V16: SOLUCIÓN A RECURSIÓN INFINITA
-- ========================================================

-- Objetivo: Resolver el error "infinite recursion detected in policy"
-- Causa: La política de 'group_members' se llamaba a sí misma.

BEGIN;

-- 1. ELIMINAR POLÍTICAS PROBLEMÁTICAS
DROP POLICY IF EXISTS "group_select_v15" ON public.groups;
DROP POLICY IF EXISTS "group_mem_select_v15" ON public.group_members;
DROP POLICY IF EXISTS "group_msg_select_v14" ON public.group_messages;

-- 2. CREAR FUNCIÓN AUXILIAR (SECURITY DEFINER)
-- Esta función se ejecuta con privilegios de sistema, saltándose el RLS.
-- Es la forma recomendada por Supabase para evitar recursión.
CREATE OR REPLACE FUNCTION public.check_is_group_member(gid TEXT)
RETURNS BOOLEAN AS $$
BEGIN
  RETURN EXISTS (
    SELECT 1 FROM public.group_members
    WHERE group_id = gid AND user_id = auth.uid()
  );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 3. NUEVA POLÍTICA PARA 'groups'
-- Solo puedes ver el grupo si eres el creador o si la función confirma membresía.
CREATE POLICY "group_select_v16" ON public.groups
    FOR SELECT USING (
        creator_id = auth.uid() 
        OR 
        public.check_is_group_member(id)
    );

-- 4. NUEVA POLÍTICA PARA 'group_members'
-- Puedes ver tu propia fila O las de otros si la función confirma que eres del grupo.
CREATE POLICY "group_mem_select_v16" ON public.group_members
    FOR SELECT USING (
        user_id = auth.uid()
        OR 
        public.check_is_group_member(group_id)
    );

-- 5. NUEVA POLÍTICA PARA 'group_messages'
-- Tienes acceso si la función confirma membresía.
CREATE POLICY "group_msg_select_v16" ON public.group_messages
    FOR SELECT USING (
        public.check_is_group_member(group_id)
    );

COMMIT;

-- EXPLICACIÓN:
-- Al usar 'SECURITY DEFINER', la consulta dentro de 'check_is_group_member' 
-- no activa las políticas RLS de 'group_members', rompiendo el ciclo infinito.
-- Ahora las tres tablas (groups, members, messages) usan esta función segura.
