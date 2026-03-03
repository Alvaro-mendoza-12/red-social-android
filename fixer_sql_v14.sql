-- ========================================================
-- REPARACIÓN NUCLEAR V14: PRIVACIDAD ESTILO WHATSAPP
-- ========================================================

-- Objetivo: Que un usuario expulsado NO reciba señales Realtime de mensajes.
-- Realtime de Supabase respeta las políticas SELECT. Si no puedes ver la fila,
-- no recibes el evento 'INSERT'.

BEGIN;

-- 1. LIMPIAR POLÍTICAS PREVIAS DEMASIADO PERMISIVAS
DROP POLICY IF EXISTS "group_msg_select_v12" ON public.group_messages;
DROP POLICY IF EXISTS "group_mem_select_v12" ON public.group_members;

-- 2. POLÍTICA ESTRICTA PARA MENSAJES DE GRUPO
-- Solo puedes VER (y por tanto recibir por Realtime) mensajes si eres miembro activo.
CREATE POLICY "group_msg_select_v14" ON public.group_messages
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.group_members 
            WHERE group_id = public.group_messages.group_id 
              AND user_id = auth.uid()
        )
    );

-- 3. POLÍTICA ESTRICTA PARA MIEMBROS DE GRUPO
-- Solo puedes ver la lista de miembros si tú mismo eres miembro.
CREATE POLICY "group_mem_select_v14" ON public.group_members
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.group_members AS m
            WHERE m.group_id = public.group_members.group_id 
              AND m.user_id = auth.uid()
        )
    );

-- 4. ASEGURAR REPLICA IDENTITY FULL (Para que Realtime envíe todos los campos en UPDATE/DELETE)
-- Especialmente útil para detectar quién fue borrado en membresías.
ALTER TABLE public.group_members REPLICA IDENTITY FULL;
ALTER TABLE public.group_messages REPLICA IDENTITY FULL;

COMMIT;

-- VERIFICACIÓN:
-- Si un usuario es expulsado (se borra su fila en group_members),
-- el 'EXISTS' de la política de mensajes fallará inmediatamente.
-- Supabase dejará de enviarle señales de nuevos mensajes a su socket de Realtime.
