-- ==========================================
-- FIX: REALTIME DELIVERY & RLS BYPASS (CORREGIDO)
-- ==========================================

-- 1. (Omitido) Ya confirmamos que messages está en supabase_realtime.
-- Si quisieras forzarlo sin error, se usaría un bloque DO, pero ya está listo.

-- 2. Función de Seguridad "Blindada" (SECURITY DEFINER)
-- Esta función verifica si puedes ver un chat SIN restricciones de RLS.
-- Se ejecuta con permisos de administrador.
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

-- 3. Actualizar la Política de LECTURA de Mensajes
ALTER TABLE public.messages DISABLE ROW LEVEL SECURITY;

-- Eliminamos las políticas anteriores para evitar conflictos
DROP POLICY IF EXISTS "select_messages_in_own_chats" ON public.messages;
DROP POLICY IF EXISTS "Users can view messages in own chats" ON public.messages;
DROP POLICY IF EXISTS "select_messages_secure" ON public.messages; -- Por si se creó parcialmente

-- Creamos la nueva política usando la función segura
CREATE POLICY "select_messages_secure" ON public.messages
FOR SELECT USING (
    public.can_access_chat_secure(chat_id)
);

-- 4. Reactivar Seguridad
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;

-- 5. Verificar
SELECT '✅ SISTEMA DE ENTREGA APLICADO CORRECTAMENTE' as status;
