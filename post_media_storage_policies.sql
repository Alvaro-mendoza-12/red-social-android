-- ============================================
-- STORAGE POLICIES FOR post-media BUCKET
-- ============================================
-- Ejecuta esto en el SQL Editor de Supabase

-- 1. Permitir que cualquiera vea/descargue las imágenes (Lectura pública)
CREATE POLICY "Public read access for post-media"
ON storage.objects FOR SELECT
TO public
USING (bucket_id = 'post-media');

-- 2. Permitir que usuarios autenticados suban imágenes a su propia carpeta
CREATE POLICY "Authenticated users can upload to their folder"
ON storage.objects FOR INSERT
TO authenticated
WITH CHECK (
    bucket_id = 'post-media' 
    AND (storage.foldername(name))[1] = auth.uid()::text
);

-- 3. Permitir UPDATE (necesario para la subida de archivos con Ktor)
CREATE POLICY "Authenticated users can update their files"
ON storage.objects FOR UPDATE
TO authenticated
USING (
    bucket_id = 'post-media' 
    AND (storage.foldername(name))[1] = auth.uid()::text
);

-- 4. Permitir que usuarios eliminen sus propios archivos
CREATE POLICY "Authenticated users can delete their files"
ON storage.objects FOR DELETE
TO authenticated
USING (
    bucket_id = 'post-media' 
    AND (storage.foldername(name))[1] = auth.uid()::text
);

-- ============================================
-- VERIFICACIÓN
-- ============================================
-- Para verificar que las políticas se crearon correctamente:
-- SELECT * FROM storage.objects WHERE bucket_id = 'post-media';
