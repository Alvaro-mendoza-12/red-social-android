-- ==========================================
-- FIX: Agregar columnas faltantes para trigger handle_new_message()
-- ==========================================
-- 
-- PROBLEMA: El trigger handle_new_message() intenta actualizar columnas
-- que NO existen en la tabla chats:
--   1. last_message_sender_id
--   2. last_message_id
-- 
-- ERRORES:
--   - column "last_message_sender_id" of relation "chats" does not exist
--   - column "last_message_id" of relation "chats" does not exist
--

-- Agregar AMBAS columnas faltantes
DO $$ 
BEGIN
    -- 1. Agregar last_message_sender_id
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' 
        AND table_name = 'chats' 
        AND column_name = 'last_message_sender_id'
    ) THEN
        ALTER TABLE public.chats 
        ADD COLUMN last_message_sender_id UUID;
        
        RAISE NOTICE '✅ Columna last_message_sender_id agregada';
    ELSE
        RAISE NOTICE 'ℹ️  Columna last_message_sender_id ya existe';
    END IF;

    -- 2. Agregar last_message_id
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' 
        AND table_name = 'chats' 
        AND column_name = 'last_message_id'
    ) THEN
        ALTER TABLE public.chats 
        ADD COLUMN last_message_id UUID;
        
        RAISE NOTICE '✅ Columna last_message_id agregada';
    ELSE
        RAISE NOTICE 'ℹ️  Columna last_message_id ya existe';
    END IF;
END $$;

-- Verificar que AMBAS columnas existen ahora
SELECT 
    column_name, 
    data_type, 
    is_nullable
FROM information_schema.columns
WHERE table_schema = 'public' 
  AND table_name = 'chats'
  AND column_name IN ('last_message_sender_id', 'last_message_id')
ORDER BY column_name;

-- Verificar que el trigger existe
SELECT 
    trigger_name, 
    event_manipulation, 
    event_object_table,
    action_statement
FROM information_schema.triggers
WHERE event_object_table = 'messages'
  AND trigger_name = 'on_new_message';
