-- ==========================================
-- TEST NUCLEAR: ACCESO PÚBLICO TEMPORAL
-- ==========================================
-- ATENCIÓN: Este script elimina las restricciones de privacidad TEMPORALMENTE.
-- El objetivo es saber si el problema es de PERMISOS o de la APP.

-- 1. Tablas de Mensajería: Acceso Total para usuarios autenticados
-- CHATS
DROP POLICY IF EXISTS "select_own_chats_secure" ON public.chats;
DROP POLICY IF EXISTS "Users can view own chats" ON public.chats;
DROP POLICY IF EXISTS "nuclear_access_chats" ON public.chats;

CREATE POLICY "nuclear_access_chats" ON public.chats
FOR ALL -- Select, Insert, Update, Delete
USING (auth.role() = 'authenticated')
WITH CHECK (auth.role() = 'authenticated');

-- MESSAGES
DROP POLICY IF EXISTS "select_messages_secure" ON public.messages;
DROP POLICY IF EXISTS "Users can view messages in own chats" ON public.messages;
DROP POLICY IF EXISTS "nuclear_access_messages" ON public.messages;

CREATE POLICY "nuclear_access_messages" ON public.messages
FOR ALL
USING (auth.role() = 'authenticated')
WITH CHECK (auth.role() = 'authenticated');

-- PARTICIPANTS
DROP POLICY IF EXISTS "select_participants_secure" ON public.chat_participants;
DROP POLICY IF EXISTS "Users can view participants in own chats" ON public.chat_participants;
DROP POLICY IF EXISTS "nuclear_access_participants" ON public.chat_participants;

CREATE POLICY "nuclear_access_participants" ON public.chat_participants
FOR ALL
USING (auth.role() = 'authenticated')
WITH CHECK (auth.role() = 'authenticated');

-- 2. Asegurar que RLS sigue activo (pero permisivo gracias a las políticas de arriba)
ALTER TABLE public.chats ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.chat_participants ENABLE ROW LEVEL SECURITY;

SELECT '⚠️ MODO NUCLEAR ACTIVADO: ACCESO TOTAL A CHATS PARA PRUEBAS' as status;
