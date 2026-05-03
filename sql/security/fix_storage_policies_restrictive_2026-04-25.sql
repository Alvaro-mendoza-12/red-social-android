-- ============================================
-- Storage Policies Security Fix
-- FECHA: 2026-04-25
-- ISSUE: GRANT ALL a public en buckets de grupos/comunidades
-- RISK: Cualquiera puede subir/eliminar archivos en estos buckets
-- FIX: Restringir a usuarios autenticados + validar ownership
-- ============================================

-- antes de aplicar, verificar buckets existentes:
-- SELECT name FROM storage.buckets;

-- 1. REVOCAR permisos públicos excesivos
-- (Esto asume que hay buckets con políticas demasiado abiertas)
REVOKE ALL ON storage.objects FROM public;

-- 2. Crear política para avatars (lectura pública, escritura solo propio)
DROP POLICY IF EXISTS "Avatars públicos" ON storage.objects;
CREATE POLICY "Avatars públicos Lectura"
  ON storage.objects FOR SELECT
  USING (bucket_id = 'avatars' AND auth.role() = 'authenticated');

DROP POLICY IF EXISTS "Avatars upload propio" ON storage.objects;
CREATE POLICY "Avatars upload propio"
  ON storage.objects FOR INSERT
  WITH CHECK (
    bucket_id = 'avatars' AND
    auth.uid()::text = (storage.foldername(name))[1]
  );

-- 3. Política para videos de perfil (similar a avatars)
DROP POLICY IF EXISTS "Profile videos acceso" ON storage.objects;
CREATE POLICY "Profile videos acceso"
  ON storage.objects FOR SELECT
  USING (bucket_id = 'profile-videos' AND auth.role() = 'authenticated');

DROP POLICY IF EXISTS "Profile videos upload propio" ON storage.objects;
CREATE POLICY "Profile videos upload propio"
  ON storage.objects FOR INSERT
  WITH CHECK (
    bucket_id = 'profile-videos' AND
    auth.uid()::text = (storage.foldername(name))[1]
  );

-- 4. Política para media de posts (lectura pública, escritura solo propio)
DROP POLICY IF EXISTS "Post media lectura" ON storage.objects;
CREATE POLICY "Post media lectura"
  ON storage.objects FOR SELECT
  USING (bucket_id = 'post-media' AND auth.role() = 'authenticated');

DROP POLICY IF EXISTS "Post media upload propio" ON storage.objects;
CREATE POLICY "Post media upload propio"
  ON storage.objects FOR INSERT
  WITH CHECK (
    bucket_id = 'post-media' AND
    auth.uid()::text = (storage.foldername(name))[1]
  );

-- 5. bucket de grupos: SOLO miembros pueden leer/escribir
-- Primero, asegurar que exista tabla group_members con RLS
ALTER TABLE group_members ENABLE ROW LEVEL SECURITY;

-- Política para grupos: solo miembros pueden ver media
DROP POLICY IF EXISTS "Grupo media miembros" ON storage.objects;
CREATE POLICY "Grupo media miembros"
  ON storage.objects FOR SELECT
  USING (
    bucket_id = 'groups' AND
    EXISTS (
      SELECT 1 FROM public.group_members gm
      WHERE gm.group_id::text = (storage.foldername(name))[2]
        AND gm.user_id = auth.uid()
    )
  );

-- Política para subir a grupo: solo miembros
DROP POLICY IF EXISTS "Grupo media upload miembros" ON storage.objects;
CREATE POLICY "Grupo media upload miembros"
  ON storage.objects FOR INSERT
  WITH CHECK (
    bucket_id = 'groups' AND
    EXISTS (
      SELECT 1 FROM public.group_members gm
      WHERE gm.group_id::text = (storage.foldername(name))[2]
        AND gm.user_id = auth.uid()
    )
  );

-- 6. bucket de comunidades: similar a grupos
ALTER TABLE community_members ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Comunidad media miembros" ON storage.objects;
CREATE POLICY "Comunidad media miembros"
  ON storage.objects FOR SELECT
  USING (
    bucket_id = 'communities' AND
    EXISTS (
      SELECT 1 FROM public.community_members cm
      WHERE cm.community_id::text = (storage.foldername(name))[2]
        AND cm.user_id = auth.uid()
    )
  );

DROP POLICY IF EXISTS "Comunidad media upload miembros" ON storage.objects;
CREATE POLICY "Comunidad media upload miembros"
  ON storage.objects FOR INSERT
  WITH CHECK (
    bucket_id = 'communities' AND
    EXISTS (
      SELECT 1 FROM public.community_members cm
      WHERE cm.community_id::text = (storage.foldername(name))[2]
        AND cm.user_id = auth.uid()
    )
  );

-- 7. bucket de videos (tipo TikTok): lectura pública, escritura solo propio
-- Nota: si quieres que todos lean videos públicos, ajustar policy
DROP POLICY IF EXISTS "Videos lectura pública" ON storage.objects;
CREATE POLICY "Videos lectura pública"
  ON storage.objects FOR SELECT
  USING (bucket_id = 'videos');

DROP POLICY IF EXISTS "Videos upload propio" ON storage.objects;
CREATE POLICY "Videos upload propio"
  ON storage.objects FOR INSERT
  WITH CHECK (
    bucket_id = 'videos' AND
    auth.uid()::text = (storage.foldername(name))[1]
  );

-- 8. Meter las políticas anteriores en el release manifest
-- (este archivo debe incluirse en sql/release-manifest.txt)

-- ============================================
-- VALIDACIÓN POST-APLICACIÓN
-- ============================================
/*
-- Verificar que no hay GRANT ALL a public:
SELECT schemaname, tablename, privilegetype, grantee
FROM information_schema.role_table_grants
WHERE grantee = 'public'
  AND table_name = 'objects'
  AND schemaname = 'storage';

-- Probar que usuario autenticado puede subir a su propio folder:
-- 1. Login como user_id=xyz
-- 2. Subir archivo a: groups/xyz/group123/photo.jpg → debería funcionar
-- 3. Subir archivo a: groups/abc/group123/photo.jpg → debería fallar (uid != abc)

-- Probar que no autenticado NO puede subir:
-- 1. Sin auth token
-- 2. Intentar subir a cualquier bucket → 401/403
*/