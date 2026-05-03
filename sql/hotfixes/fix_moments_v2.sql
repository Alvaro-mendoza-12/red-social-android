-- 1. Asegurar que la tabla tiene las columnas necesarias para el overhaul
ALTER TABLE public.moments 
ADD COLUMN IF NOT EXISTS thumbnail_url TEXT,
ADD COLUMN IF NOT EXISTS visibility_type TEXT DEFAULT 'public',
ADD COLUMN IF NOT EXISTS duration_type TEXT DEFAULT '24h',
ADD COLUMN IF NOT EXISTS caption TEXT;

-- Nota: 'created_at' y 'expires_at' ya existen según el esquema visto previamente.

-- 2. Asegurar que el bucket 'moment-media' existe y es público
INSERT INTO storage.buckets (id, name, public)
VALUES ('moment-media', 'moment-media', true)
ON CONFLICT (id) DO NOTHING;

-- 3. Políticas de seguridad robustas (usando nombres únicos para evitar conflictos)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE policyname = 'Moments V2 Public Read') THEN
        CREATE POLICY "Moments V2 Public Read" ON storage.objects FOR SELECT USING (bucket_id = 'moment-media');
    END IF;
    
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE policyname = 'Moments V2 Authenticated Insert') THEN
        CREATE POLICY "Moments V2 Authenticated Insert" ON storage.objects FOR INSERT WITH CHECK (
            auth.role() = 'authenticated' AND bucket_id = 'moment-media'
        );
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE policyname = 'Moments V2 Owner Delete') THEN
        CREATE POLICY "Moments V2 Owner Delete" ON storage.objects FOR DELETE USING (
            auth.uid()::text = (storage.foldername(name))[1] AND bucket_id = 'moment-media'
        );
    END IF;
END
$$;
