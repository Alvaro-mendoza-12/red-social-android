-- ========================================================
-- FIX FINAL: REALTIME COMPLETO (ROLES Y METADATOS)
-- ========================================================

BEGIN;

-- 1. Asegurar REPLICA IDENTITY FULL para capturar todos los campos en UPDATES
-- Esto es CRÍTICO para que el payload de Realtime contenga toda la información del registro
ALTER TABLE public.group_members REPLICA IDENTITY FULL;
ALTER TABLE public.community_members REPLICA IDENTITY FULL;
ALTER TABLE public.groups REPLICA IDENTITY FULL;
ALTER TABLE public.communities REPLICA IDENTITY FULL;

-- 2. Asegurar que todas las tablas necesarias están en la publicación de Realtime
DO $$
BEGIN
    -- Lista de tablas a asegurar
    -- groups, communities, group_members, community_members, group_messages, community_messages
    
    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'group_members') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.group_members;
    END IF;
    
    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'community_members') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.community_members;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'groups') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.groups;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'communities') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.communities;
    END IF;
    
    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'group_messages') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.group_messages;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'community_messages') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.community_messages;
    END IF;
END $$;

COMMIT;

SELECT '✅ CONFIGURACIÓN DE TIEMPO REAL APLICADA A TODAS LAS TABLAS DE GRUPOS Y COMUNIDADES' as status;
