-- ==========================================
-- SCRIPT DE REPARACIÓN NUCLEAR PARA CHATS CRUZADOS (v4)
-- ==========================================

-- 1. Limpieza de dependencias (Índice primero, Función después)
-- Eliminamos el índice que depende de la función antes de intentar borrarla.
DROP INDEX IF EXISTS public.idx_chats_unique_dm;
DROP FUNCTION IF EXISTS public.normalize_participants(text[]);

-- 2. Crear Función de normalización
CREATE OR REPLACE FUNCTION public.normalize_participants(p_participants text[])
RETURNS text[] AS $$
BEGIN
    RETURN ARRAY(SELECT unnest(p_participants) ORDER BY 1);
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- 3. VER CHATS PROBLEMÁTICOS
-- Esto te dirá cuántos chats están "sucios" (DMs con más de 2 personas)
SELECT id, participants, array_length(participants, 1) as cant_personas, group_name
FROM public.chats 
WHERE is_group = FALSE AND array_length(participants, 1) != 2;

-- 4. ELIMINACIÓN NUCLEAR DE CHATS CORRUPTOS
-- Borra los chats que tienen un número de participantes incorrecto para un DM.
DELETE FROM public.chats 
WHERE is_group = FALSE AND array_length(participants, 1) != 2;

-- 5. RE-ACTIVAR PROTECCIÓN DE UNICIDAD
-- Creamos el índice de nuevo para prevenir futuros duplicados.
CREATE UNIQUE INDEX idx_chats_unique_dm 
ON public.chats (normalize_participants(participants))
WHERE is_group = FALSE AND array_length(participants, 1) = 2;

-- 6. LIMPIAR MENSAJES HUÉRFANOS
DELETE FROM public.messages WHERE chat_id NOT IN (SELECT id FROM public.chats);

-- 7. VERIFICACIÓN FINAL: Debería salir vacío.
SELECT id, participants 
FROM public.chats 
WHERE is_group = FALSE AND array_length(participants, 1) != 2;

-- NOTA: Una vez ejecutado esto, la base de datos estará limpia.
-- Tu aplicación ya tiene el código para limpiar su caché interna automáticamente al abrirse.
