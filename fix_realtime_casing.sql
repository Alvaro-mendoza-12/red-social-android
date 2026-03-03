-- ==========================================
-- FIX: REALTIME CASING & RLS (V3 - DEFINITIVO)
-- ==========================================
-- Este script soluciona TODOS los problemas de acceso debidos a mayúsculas/minúsculas.
-- 1. Sanitiza los datos (convierte participants a lowercase).
-- 2. Crea función segura case-insensitive.
-- 3. Actualiza políticas de MESSAGES y CHATS (Crucial!).

-- 1. SANITIZACIÓN DE DATOS (Por si acaso quedó algo pendiente)
UPDATE public.chats
SET participants = ARRAY(
    SELECT lower(x) 
    FROM unnest(participants) x
);

-- 2. Función de Seguridad "Blindada" (Case Insensitive)
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
        -- Comparamos ID de usuario normalizado vs el array de participantes
        AND lower(auth.uid()::text) = ANY(participants)
    );
END;
$$;

-- 3. ACTUALIZAR POLÍTICAS DE CHATS (Para que getUserChats devuelva datos!)
ALTER TABLE public.chats DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can view own chats" ON public.chats;
DROP POLICY IF EXISTS "Users can update own chats" ON public.chats;

CREATE POLICY "Users can view own chats" ON public.chats
    FOR SELECT USING (lower(auth.uid()::TEXT) = ANY(participants));

CREATE POLICY "Users can update own chats" ON public.chats
    FOR UPDATE USING (lower(auth.uid()::TEXT) = ANY(participants));
    
ALTER TABLE public.chats ENABLE ROW LEVEL SECURITY;


-- 4. ACTUALIZAR POLÍTICAS DE MESSAGES (Reutilizamos la función segura)
ALTER TABLE public.messages DISABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "select_messages_secure" ON public.messages;
DROP POLICY IF EXISTS "Users can view messages in own chats" ON public.messages;

-- Usamos la función segura para SELECT
CREATE POLICY "select_messages_secure" ON public.messages
    FOR SELECT USING (public.can_access_chat_secure(chat_id));

-- Para INSERT, también verificamos case-insensitive
CREATE POLICY "insert_messages_secure" ON public.messages
    FOR INSERT WITH CHECK (
        -- El sender debe ser el auth user (normalizado)
        lower(auth.uid()::text) = lower(sender_id::text) AND
        public.can_access_chat_secure(chat_id)
    );

ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;

-- 5. Recargar esquema
NOTIFY pgrst, 'reload schema';

SELECT '✅ V3: FIX COMPLETO (CHATS + MESSAGES) APLICADO' as status;
