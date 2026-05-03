-- =================================================================
-- FIX FINAL DE MENSAJERÍA: PERSISTENCIA Y VISIBILIDAD TOTAL
-- =================================================================

-- 1. TABLA CHATS: Visibilidad y Edición para Participantes
ALTER TABLE public.chats ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Participants can select chats" ON public.chats;
CREATE POLICY "Participants can select chats" ON public.chats
FOR SELECT USING (
    auth.uid()::text = ANY(participants)
);

DROP POLICY IF EXISTS "Participants can update chats" ON public.chats;
CREATE POLICY "Participants can update chats" ON public.chats
FOR UPDATE USING (
    auth.uid()::text = ANY(participants)
);

DROP POLICY IF EXISTS "Users can create chats" ON public.chats;
CREATE POLICY "Users can create chats" ON public.chats
FOR INSERT WITH CHECK (
    auth.uid()::text = ANY(participants)
);

-- 2. TABLA MESSAGES: El núcleo del problema
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;

-- A. SELECT: Permitir ver mensajes si eres participante del chat
DROP POLICY IF EXISTS "Participants can select messages" ON public.messages;
CREATE POLICY "Participants can select messages" ON public.messages
FOR SELECT USING (
    auth.uid() = sender_id -- SIEMPRE ver mis propios mensajes
    OR
    EXISTS (
        SELECT 1 FROM public.chats
        WHERE chats.id = messages.chat_id
        AND auth.uid()::text = ANY(chats.participants)
    )
);

-- B. INSERT: Permitir enviar mensajes si eres participante
DROP POLICY IF EXISTS "Participants can insert messages" ON public.messages;
CREATE POLICY "Participants can insert messages" ON public.messages
FOR INSERT WITH CHECK (
    EXISTS (
        SELECT 1 FROM public.chats
        WHERE chats.id = messages.chat_id
        AND auth.uid()::text = ANY(chats.participants)
    )
);

-- C. UPDATE: Permitir marcar como leído (dueños del mensaje o participantes del chat para checks de lectura)
DROP POLICY IF EXISTS "Participants can update messages" ON public.messages;
CREATE POLICY "Participants can update messages" ON public.messages
FOR UPDATE USING (
    EXISTS (
        SELECT 1 FROM public.chats
        WHERE chats.id = messages.chat_id
        AND auth.uid()::text = ANY(chats.participants)
    )
);

-- 3. TABLA CHAT_PARTICIPANTS
ALTER TABLE public.chat_participants ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Participants can view own metadata" ON public.chat_participants;
CREATE POLICY "Participants can view own metadata" ON public.chat_participants
FOR SELECT USING (
    auth.uid() = user_id
);

DROP POLICY IF EXISTS "Participants can update own metadata" ON public.chat_participants;
CREATE POLICY "Participants can update own metadata" ON public.chat_participants
FOR UPDATE USING (
    auth.uid() = user_id
);

DROP POLICY IF EXISTS "Users can join chats" ON public.chat_participants;
CREATE POLICY "Users can join chats" ON public.chat_participants
FOR INSERT WITH CHECK (
    auth.uid() = user_id
);


-- 4. GRANT PERMISSIONS (Red de seguridad)
GRANT ALL ON TABLE public.chats TO authenticated;
GRANT ALL ON TABLE public.messages TO authenticated;
GRANT ALL ON TABLE public.chat_participants TO authenticated;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO authenticated;

SELECT '✅ FIX DE MENSAJERÍA APLICADO: Ahora todos los participantes tienen acceso total RW' as result;
