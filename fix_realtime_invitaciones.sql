-- HABILITAR REALTIME PARA INVITACIONES DE GRUPOS Y COMUNIDADES
-- Este script permite que los integrantes vean los grupos/comunidades al instante sin reiniciar la app.

DO $$
DECLARE
    tbl_name TEXT;
    tables_to_add TEXT[] := ARRAY['group_members', 'community_members'];
BEGIN
    FOREACH tbl_name IN ARRAY tables_to_add
    LOOP
        -- 1. Añadir a la publicación de Realtime si no están ya
        IF NOT EXISTS (
            SELECT 1 FROM pg_publication_tables 
            WHERE pubname = 'supabase_realtime' 
            AND schemaname = 'public' 
            AND tablename = tbl_name
        ) THEN
            EXECUTE format('ALTER PUBLICATION supabase_realtime ADD TABLE public.%I', tbl_name);
        END IF;

        -- 2. Asegurar Replica Identity Full para que los filtros de Realtime funcionen correctamente
        EXECUTE format('ALTER TABLE public.%I REPLICA IDENTITY FULL', tbl_name);
    END LOOP;
END $$;

-- Recargar esquema
NOTIFY pgrst, 'reload schema';

SELECT '✅ Realtime habilitado para invitaciones de Grupos y Comunidades.' as resultado;
