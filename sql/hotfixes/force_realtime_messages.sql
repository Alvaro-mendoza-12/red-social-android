-- ==========================================
-- FORCE ENABLE REALTIME FOR MESSAGES (v2)
-- Compatibilidad mejorada para PostgreSQL
-- ==========================================

-- 1. Asegurar que la publicación 'supabase_realtime' existe
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime') THEN
        CREATE PUBLICATION supabase_realtime;
    END IF;
END $$;

-- 2. Agregar tablas a la publicación de forma segura
-- Nota: 'ALTER PUBLICATION ... ADD TABLE' fallará si la tabla ya existe en la publicación.
-- Usamos un bloque DO para verificarlo antes.

DO $$
BEGIN
    -- Intentar agregar 'messages'
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' 
        AND schemaname = 'public' 
        AND tablename = 'messages'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.messages;
    END IF;

    -- Intentar agregar 'community_messages'
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' 
        AND schemaname = 'public' 
        AND tablename = 'community_messages'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.community_messages;
    END IF;
END $$;

-- 3. Asegurar REPLICA IDENTITY FULL (Vital para UPDATE/DELETE)
ALTER TABLE public.messages REPLICA IDENTITY FULL;
ALTER TABLE public.community_messages REPLICA IDENTITY FULL;

SELECT '✅ REALTIME FORCE-ENABLED FOR MESSAGES (v2)' as status;
