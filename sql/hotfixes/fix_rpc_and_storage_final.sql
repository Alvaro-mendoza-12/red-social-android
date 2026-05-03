-- 1. SOLUCIÓN ERROR 403 (Permisos de RPC)
-- Otorga permiso de ejecución a la función que genera retos
GRANT EXECUTE ON FUNCTION get_or_generate_active_challenge() TO authenticated, anon;
-- Por si acaso, otorgar a las tablas que consulta la función si no tuvieran RLS habilitado o permisos
GRANT SELECT ON public.challenges_master TO authenticated, anon;
GRANT SELECT, INSERT, UPDATE ON public.active_challenges TO authenticated, anon;

-- 2. SOLUCIÓN ERROR 400 (Storage Bucket)
-- Asegurar que el bucket existe, es público y no tiene restricciones extrañas
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES ('challenges', 'challenges', true, NULL, NULL)
ON CONFLICT (id) DO UPDATE SET public = true;

-- Eliminar políticas antiguas para evitar conflictos y recrear
DROP POLICY IF EXISTS "Challenges Public Read Access" ON storage.objects;
DROP POLICY IF EXISTS "Challenges Authenticated Upload" ON storage.objects;

-- Crear política de lectura pública (Fundamental para ver fotos/videos)
CREATE POLICY "Challenges Public Read Access" ON storage.objects 
FOR SELECT USING (bucket_id = 'challenges');

-- Crear política de subida para usuarios autenticados (Permite subir sin restricciones de nombre)
CREATE POLICY "Challenges Authenticated Upload" ON storage.objects 
FOR INSERT TO authenticated WITH CHECK (bucket_id = 'challenges');

-- 3. PERMISOS EXTRA PARA SUBMISSIONS Y VOTES
-- Asegurar que las tablas de negocio tengan los permisos correctos
GRANT SELECT, INSERT, UPDATE ON public.challenge_submissions TO authenticated;
GRANT SELECT, INSERT ON public.challenge_votes TO authenticated;

-- Notificación de éxito
DO $$
BEGIN
    RAISE NOTICE 'Permisos de desafío y almacenamiento actualizados correctamente';
END $$;
