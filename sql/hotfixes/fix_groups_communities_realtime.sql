-- ===============================================
-- REALTIME SOLO PARA GRUPOS Y COMUNIDADES
-- No afecta a chats privados (1-a-1)
-- ===============================================

-- 1. Configurar Identidad de Réplica (vital para UPDATE/DELETE)
ALTER TABLE public.group_messages REPLICA IDENTITY FULL;
ALTER TABLE public.community_messages REPLICA IDENTITY FULL;

-- 2. Asegurar que la publicación 'supabase_realtime' existe
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime') THEN
        CREATE PUBLICATION supabase_realtime;
    END IF;
END $$;

-- 3. Agregar tablas a la publicación de forma segura (sin errores si ya existen)
DO $$
BEGIN
    -- TABLA: group_messages
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' 
        AND schemaname = 'public' 
        AND tablename = 'group_messages'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.group_messages;
    END IF;

    -- TABLA: community_messages
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' 
        AND schemaname = 'public' 
        AND tablename = 'community_messages'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.community_messages;
    END IF;
END $$;

SELECT '✅ REALTIME ACTIVADO PARA GRUPOS Y COMUNIDADES' as status;
