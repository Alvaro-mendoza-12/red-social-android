-- =================================================================
-- MASTER FIX: PERMITIR ACTUALIZAR CHATS Y MENSAJES (RLS UNLOCK)
-- =================================================================

-- 1. CHATS: Permitir que los participantes actualicen last_message_at
-- Actualmente es probable que solo el creador o nadie pueda editarlo.
DROP POLICY IF EXISTS "Participants can update chats" ON public.chats;

CREATE POLICY "Participants can update chats" ON public.chats
FOR UPDATE USING (
    -- El usuario debe ser uno de los participantes
    auth.uid()::text = ANY(participants)
) WITH CHECK (
    -- Solo permitimos si sigue siendo participante
    auth.uid()::text = ANY(participants)
);

-- 2. MESSAGES: Permitir marcar como leído (UPDATE read_at)
-- Esta política ya la intentamos poner, pero la reforzamos aqui.
DROP POLICY IF EXISTS "Users can update messages in own chats" ON public.messages;

CREATE POLICY "Users can update messages in own chats" ON public.messages
FOR UPDATE USING (
    EXISTS (
        SELECT 1 FROM public.chats
        WHERE chats.id = messages.chat_id
        AND auth.uid()::text = ANY(chats.participants)
    )
);

-- 3. CHAT PARTICIPANTS: Permitir actualizar timestamps de lectura
DROP POLICY IF EXISTS "Users can update own participation" ON public.chat_participants;

CREATE POLICY "Users can update own participation" ON public.chat_participants
FOR UPDATE USING (auth.uid() = user_id);

-- 4. VERIFICACIÓN DE COLUMNAS
-- Asegurarnos de que existan las columnas de metadatos en chats
ALTER TABLE public.chats ADD COLUMN IF NOT EXISTS last_message TEXT;
ALTER TABLE public.chats ADD COLUMN IF NOT EXISTS last_message_at TIMESTAMPTZ DEFAULT NOW();
ALTER TABLE public.chats ADD COLUMN IF NOT EXISTS last_message_sender_id UUID;
ALTER TABLE public.chats ADD COLUMN IF NOT EXISTS last_message_id UUID;

SELECT '✅ MASTER RLS FIX APPLIED: Chats updates are now allowed' as status;
