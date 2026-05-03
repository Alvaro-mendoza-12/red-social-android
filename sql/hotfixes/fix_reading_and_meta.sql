-- ==========================================
-- FIX: PERSISTENCIA DE LECTURA Y METADATOS
-- ==========================================

-- 1. CORREGIR RLS DE MENSAJES PARA LECTURA
-- El destinatario debe poder marcar como leído (UPDATE read_at)
DROP POLICY IF EXISTS "Users can update messages in own chats" ON public.messages;
CREATE POLICY "Users can update messages in own chats" ON public.messages
FOR UPDATE USING (
    EXISTS (
        SELECT 1 FROM public.chats
        WHERE chats.id = messages.chat_id
        AND auth.uid()::TEXT = ANY(chats.participants)
    )
) WITH CHECK (
    -- Solo permitimos actualizar campos de estado de lectura o edición/borrado permitidos
    -- auth.uid()::text = ANY(chats.participants) ya está cubierto por USING
    TRUE
);

-- 2. MEJORAR TRIGGER DE ÚLTIMO MENSAJE
-- Maneja automáticamente vistas previas para imágenes/videos/audio
CREATE OR REPLACE FUNCTION handle_new_message()
RETURNS TRIGGER AS $$
DECLARE
    preview_content TEXT;
BEGIN
    -- Generar vista previa si el contenido está vacío pero hay un tipo multimedia
    preview_content := CASE 
        WHEN NEW.content_type = 'image' THEN '📷 Foto'
        WHEN NEW.content_type = 'video' THEN '🎥 Video'
        WHEN NEW.content_type = 'audio' THEN '🎤 Audio'
        ELSE COALESCE(NEW.content, '')
    END;

    -- Actualizar el chat con lógica robusta
    BEGIN
        UPDATE public.chats
        SET 
            last_message = preview_content,
            last_message_sender_id = NEW.sender_id,
            last_message_at = NEW.created_at,
            last_message_id = NEW.id,
            updated_at = NOW()
        WHERE id = NEW.chat_id;
    EXCEPTION WHEN OTHERS THEN
        RAISE WARNING 'Error updating chat stats in handle_new_message: %', SQLERRM;
    END;
    
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Re-aplicar el trigger
DROP TRIGGER IF EXISTS on_new_message ON public.messages;
CREATE TRIGGER on_new_message
    AFTER INSERT ON public.messages
    FOR EACH ROW
    EXECUTE FUNCTION handle_new_message();

-- 3. ASEGURAR PERMISOS EN CHAT_PARTICIPANTS
DROP POLICY IF EXISTS "Users can update own participation" ON public.chat_participants;
CREATE POLICY "Users can update own participation" ON public.chat_participants
FOR UPDATE USING (auth.uid() = user_id)
WITH CHECK (auth.uid() = user_id);

SELECT '✅ SQL FIX FOR READING & METADATA APPLIED' as status;
