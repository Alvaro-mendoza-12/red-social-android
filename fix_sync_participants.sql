-- ==========================================
-- FIX: SYNC PARTICIPANTS ARRAY
-- Reconstruye la lista 'participants' en la tabla 'chats' usando la tabla 'chat_participants' (Fuente de Verdad)
-- ==========================================

-- 1. Actualización masiva:
-- Para cada chat, buscamos sus miembros en 'chat_participants', los convertimos a texto,
-- y actualizamos el array 'participants' en la tabla principal.
UPDATE public.chats c
SET participants = sub.member_array
FROM (
    SELECT 
        chat_id, 
        ARRAY_AGG(user_id::text) as member_array
    FROM public.chat_participants
    GROUP BY chat_id
) sub
WHERE c.id = sub.chat_id;

-- 2. Verificación de seguridad
-- Asegurar que nadie se quede con participantes NULL
UPDATE public.chats
SET participants = '{}'
WHERE participants IS NULL;

-- 3. Confirmación
SELECT '✅ ACTUALIZADO: Participants Array sincronizado con Tabla de Unión' as status;
