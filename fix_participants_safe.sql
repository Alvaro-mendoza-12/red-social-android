-- ==========================================
-- FIX: SAFE PARTICIPANTS CLEANUP (Solo Chats Privados)
-- ==========================================

-- Este script arregla el formato de los participantes SOLO en chats 1 a 1.
-- NO toca Grupos (is_group = true).
-- NO toca Comunidades (tienen sus propias tablas).

-- Conversión segura: forzar minúsculas y quitar espacios en el array 'participants'
UPDATE public.chats
SET participants = ARRAY(
    SELECT LOWER(TRIM(unnested_id))
    FROM unnest(participants) AS unnested_id
)
WHERE is_group = FALSE;  -- <-- FILTRO DE SEGURIDAD: Solo chats privados

-- Verificación de que no tocamos grupos
SELECT count(*) as "Grupos Intactos" FROM public.chats WHERE is_group = TRUE;
SELECT count(*) as "Chats Privados Revisados" FROM public.chats WHERE is_group = FALSE;

SELECT '✅ CHATS PRIVADOS CORREGIDOS' as status;
