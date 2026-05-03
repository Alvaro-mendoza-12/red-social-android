-- ==========================================
-- FIX: CHAT VISIBILITY & RLS BYPASS
-- Soluciona que el chat "no aparezca" en la lista del receptor
-- ==========================================

-- 1. Usar la misma función segura creada anteriormente
-- (Nos aseguramos que exista y sea accesible)
CREATE OR REPLACE FUNCTION public.can_access_chat_secure(_chat_id uuid)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER 
SET search_path = public
AS $$
BEGIN
    RETURN EXISTS (
        SELECT 1 FROM public.chats
        WHERE id = _chat_id
        AND auth.uid()::text = ANY(participants)
    );
END;
$$;

-- 2. Actualizar la Política de LECTURA de CHATS
-- El problema raíz: El usuario recibía el mensaje, pero RLS le ocultaba el chat.
ALTER TABLE public.chats DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can view own chats" ON public.chats;
DROP POLICY IF EXISTS "select_own_chats" ON public.chats;

-- Nueva política usando el túnel seguro
CREATE POLICY "select_own_chats_secure" ON public.chats
FOR SELECT USING (
    public.can_access_chat_secure(id)
);

-- 3. Actualizar PARTICIPANTES también (para el contador no leídos)
ALTER TABLE public.chat_participants DISABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "Users can view participants in own chats" ON public.chat_participants;
DROP POLICY IF EXISTS "select_own_participation" ON public.chat_participants;

CREATE POLICY "select_participants_secure" ON public.chat_participants
FOR SELECT USING (
    user_id = auth.uid() 
    OR 
    public.can_access_chat_secure(chat_id)
);

-- 4. Reactivar Seguridad
ALTER TABLE public.chats ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.chat_participants ENABLE ROW LEVEL SECURITY;

-- 5. Verificar
SELECT '✅ VISIBILIDAD DE CHATS ARREGLADA' as status;
