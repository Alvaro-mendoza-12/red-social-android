-- SOLUCIÓN FINAL REALTIME: Moments
-- 1. Asegurar que la publicación existe
DO $$ 
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime') THEN
        CREATE PUBLICATION supabase_realtime;
    END IF;
END $$;

-- 2. Agregar tablas a la publicación de forma segura
-- Usamos un bloque DO para capturar el error si ya existen en la publicación
DO $$
BEGIN
    BEGIN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.moments;
    EXCEPTION
        WHEN duplicate_object THEN
            NULL; -- Ya existe, ignorar
    END;
    
    BEGIN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.moment_views;
    EXCEPTION
        WHEN duplicate_object THEN
            NULL; -- Ya existe, ignorar
    END;
END $$;

-- 3. CRÍTICO: Configurar REPLICA IDENTITY FULL
-- Esto SIEMPRE debe ejecutarse para asegurar que los DELETE envíen el ID
ALTER TABLE public.moments REPLICA IDENTITY FULL;
