-- ========================================
-- SUPABASE DATABASE FIX
-- Solución para el error: "Could not find the 'bio' column"
-- ========================================

-- Ejecutar estos comandos en: Supabase Dashboard → SQL Editor

-- 1️⃣ VERIFICAR QUE LA COLUMNA BIO EXISTE
SELECT column_name, data_type, is_nullable
FROM information_schema.columns 
WHERE table_schema = 'public' 
  AND table_name = 'profiles' 
  AND column_name = 'bio';
-- Esperado: Debería retornar una fila mostrando: bio | text | YES

-- 2️⃣ REFRESCAR LA CACHÉ DE POSTGREST
-- Esto fuerza a PostgREST a recargar el esquema de la base de datos
NOTIFY pgrst, 'reload schema';

-- 3️⃣ VERIFICAR LAS POLÍTICAS RLS PARA UPDATE
SELECT schemaname, tablename, policyname, cmd, qual, with_check
FROM pg_policies 
WHERE tablename = 'profiles' AND cmd = 'UPDATE';
-- Esperado: Debería mostrar la política "Users can update own profile"

-- 4️⃣ (SI ES NECESARIO) RECREAR LA POLÍTICA DE UPDATE
-- Solo ejecutar si la política no existe o está mal configurada
DROP POLICY IF EXISTS "Users can update own profile" ON public.profiles;

CREATE POLICY "Users can update own profile" ON public.profiles
    FOR UPDATE 
    USING (auth.uid() = id);

-- 5️⃣ VERIFICAR QUE LA TABLA ESTÁ CORRECTA
SELECT 
    id, 
    email, 
    full_name, 
    bio, 
    avatar_url,
    created_at
FROM public.profiles
ORDER BY created_at DESC
LIMIT 5;

-- ========================================
-- NOTAS IMPORTANTES
-- ========================================
-- ⚠️ Si después de ejecutar NOTIFY pgrst aún tienes el error:
--    1. Ve a Settings → API en Supabase Dashboard
--    2. Haz clic en "Restart API server"
--    3. Espera 30 segundos y prueba nuevamente
-- ========================================
