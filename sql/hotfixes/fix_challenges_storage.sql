-- 1. Crear el bucket de almacenamiento para los retos
INSERT INTO storage.buckets (id, name, public)
VALUES ('challenges', 'challenges', true)
ON CONFLICT (id) DO NOTHING;

-- 2. Configurar políticas de seguridad para el bucket 'challenges'

-- Permitir lectura pública
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE policyname = 'Challenges Public Read Access'
    ) THEN
        CREATE POLICY "Challenges Public Read Access" ON storage.objects FOR SELECT USING (bucket_id = 'challenges');
    END IF;
END
$$;

-- Permitir que usuarios autenticados suban sus respuestas (dibujos, fotos, videos)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE policyname = 'Challenges Authenticated Upload'
    ) THEN
        CREATE POLICY "Challenges Authenticated Upload" ON storage.objects FOR INSERT WITH CHECK (
            auth.role() = 'authenticated' AND bucket_id = 'challenges'
        );
    END IF;
END
$$;

-- Permitir que los usuarios borren sus propias respuestas si es necesario
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE policyname = 'Challenges Owner Delete'
    ) THEN
        CREATE POLICY "Challenges Owner Delete" ON storage.objects FOR DELETE USING (
            auth.uid()::text = (storage.foldername(name))[1] AND bucket_id = 'challenges'
        );
    END IF;
END
$$;
