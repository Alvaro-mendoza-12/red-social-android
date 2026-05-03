-- 1. Añadir columna de comentario (caption) a la tabla de momentos
ALTER TABLE public.moments ADD COLUMN IF NOT EXISTS caption TEXT;

-- 2. Crear el bucket de almacenamiento para los momentos
INSERT INTO storage.buckets (id, name, public)
VALUES ('moment-media', 'moment-media', true)
ON CONFLICT (id) DO NOTHING;

-- 3. Configurar políticas de seguridad para el bucket 'moment-media' con nombres únicos
-- Nota: Usamos nombres específicos para evitar conflictos con otras políticas existentes.

-- Permitir lectura pública de los momentos
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE policyname = 'Moments Media Public Read Access'
    ) THEN
        CREATE POLICY "Moments Media Public Read Access" ON storage.objects FOR SELECT USING (bucket_id = 'moment-media');
    END IF;
END
$$;

-- Permitir que usuarios autenticados suban sus momentos
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE policyname = 'Moments Media Authenticated Upload'
    ) THEN
        CREATE POLICY "Moments Media Authenticated Upload" ON storage.objects FOR INSERT WITH CHECK (
            auth.role() = 'authenticated' AND bucket_id = 'moment-media'
        );
    END IF;
END
$$;

-- Permitir que los usuarios borren sus propios momentos
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE policyname = 'Moments Media Owner Delete'
    ) THEN
        CREATE POLICY "Moments Media Owner Delete" ON storage.objects FOR DELETE USING (
            auth.uid()::text = (storage.foldername(name))[1] AND bucket_id = 'moment-media'
        );
    END IF;
END
$$;
