-- ========================================================
-- REPARACIÓN NUCLEAR V10: GRUPOS SINCRONIZACIÓN TOTAL
-- ========================================================

-- 1. POLÍTICAS DE MIEMBROS DE GRUPO (V10)
-- Para que el Realtime funcione al instante, el usuario que está siendo añadido 
-- debe tener permiso de SELECT sobre su propia fila (o sobre todas para simplificar como en comunidades).
-- En la V7 pusimos "true", vamos a reafirmarlo y asegurar que no haya bloqueos.

DROP POLICY IF EXISTS "group_mem_select_v7" ON public.group_members;
DROP POLICY IF EXISTS "group_mem_select_v10" ON public.group_members;

-- Permitimos SELECT a todos los autenticados. La App se encarga de filtrar.
-- Esto garantiza que el evento de Realtime "INSERT" llegue al dispositivo del nuevo miembro.
CREATE POLICY "group_mem_select_v10" ON public.group_members
    FOR SELECT USING (auth.role() = 'authenticated');

-- 2. POLÍTICAS DE MENSAJES DE GRUPO (REFINAMIENTO)
-- Asegurar que los miembros puedan ver mensajes incluso si el grupo acaba de ser creado.
DROP POLICY IF EXISTS "group_msg_select_v7" ON public.group_messages;
DROP POLICY IF EXISTS "group_msg_select_v10" ON public.group_messages;

CREATE POLICY "group_msg_select_v10" ON public.group_messages
    FOR SELECT USING (
        EXISTS (SELECT 1 FROM group_members WHERE group_id = public.group_messages.group_id AND user_id = auth.uid()) OR
        EXISTS (SELECT 1 FROM groups WHERE id = group_id AND creator_id = auth.uid())
    );

-- 3. ASEGURAR REALTIME (POR SI ACASO)
ALTER TABLE public.group_members REPLICA IDENTITY FULL;
