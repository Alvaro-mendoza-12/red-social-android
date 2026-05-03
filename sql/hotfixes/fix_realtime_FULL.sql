-- ========================================================
-- FIX FINAL: REALTIME COMPLETO (ROLES Y CONFIGURACIÓN)
-- ========================================================

-- Este script asegura que Supabase envíe TODOS los datos en cada evento de tiempo real.
-- Sin esto, los cambios de rol o de configuración (como el candado de admin) 
-- pueden llegar vacíos o con valores por defecto.

BEGIN;

-- 1. Configurar REPLICA IDENTITY FULL para todas las tablas relevantes
-- Esto obliga a Supabase a incluir todos los campos en los payloads de UPDATE y DELETE
ALTER TABLE public.communities REPLICA IDENTITY FULL;
ALTER TABLE public.groups REPLICA IDENTITY FULL;
ALTER TABLE public.community_members REPLICA IDENTITY FULL;
ALTER TABLE public.group_members REPLICA IDENTITY FULL;

-- 2. Asegurar que las tablas están en la publicación de Realtime
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'communities') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.communities;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'groups') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.groups;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'community_members') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.community_members;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'group_members') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.group_members;
    END IF;
END $$;

COMMIT;

SELECT '✅ TIEMPO REAL CONFIGURADO AL 100% PARA ROLES Y AJUSTES' as status;
