-- ==========================================
-- POLÍTICAS DE SEGURIDAD PARA STORAGE
-- Ejecuta esto en el SQL Editor de Supabase
-- ==========================================

-- 1. Permitir que cualquiera vea las imágenes (Lectura pública)
CREATE POLICY "Acceso público de lectura en groups"
ON storage.objects FOR SELECT
TO public
USING (bucket_id = 'groups');

CREATE POLICY "Acceso público de lectura en communities"
ON storage.objects FOR SELECT
TO public
USING (bucket_id = 'communities');

-- 2. Permitir que usuarios puedan subir imágenes
-- Usamos 'TO public' y 'CHECK (true)' para máxima compatibilidad inicial
CREATE POLICY "Permitir subida en groups"
ON storage.objects FOR INSERT
TO public
WITH CHECK (bucket_id = 'groups');

CREATE POLICY "Permitir subida en communities"
ON storage.objects FOR INSERT
TO public
WITH CHECK (bucket_id = 'communities');

-- 3. Permitir UPDATE (necesario a veces para la subida de Ktor)
CREATE POLICY "Permitir update en groups"
ON storage.objects FOR UPDATE
TO public
USING (bucket_id = 'groups');

CREATE POLICY "Permitir update en communities"
ON storage.objects FOR UPDATE
TO public
USING (bucket_id = 'communities');
