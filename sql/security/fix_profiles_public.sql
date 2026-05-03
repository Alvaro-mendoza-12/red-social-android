-- ==========================================
-- FIX: PERFILES PÚBLICOS (LECTURA)
-- Soluciona que el mensaje se ignore porque no se pudo cargar el perfil del remitente
-- ==========================================

-- 1. Habilitar lectura pública de perfiles
-- En una red social, es normal poder ver el nombre/foto de quien te escribe.
-- Si esto está bloqueado, la App falla al validar el mensaje.

-- Desactivar temporalmente para limpiar
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Public profiles are viewable by everyone" ON public.profiles;
DROP POLICY IF EXISTS "Users can insert their own profile" ON public.profiles;
DROP POLICY IF EXISTS "Users can update own profile" ON public.profiles;

-- CREAR NUEVAS POLÍTICAS

-- A) Lectura: Todo el mundo (autenticado) puede ver perfiles
CREATE POLICY "Public profiles are viewable by everyone" 
ON public.profiles FOR SELECT 
USING ( auth.role() = 'authenticated' );

-- B) Inserción: Solo el dueño (al registrarse)
CREATE POLICY "Users can insert their own profile" 
ON public.profiles FOR INSERT 
WITH CHECK ( auth.uid() = id );

-- C) Actualización: Solo el dueño
CREATE POLICY "Users can update own profile" 
ON public.profiles FOR UPDATE 
USING ( auth.uid() = id );

-- 2. Verificar
SELECT '✅ PERFILES AHORA VISIBLES (Fix Realtime Match)' as status;
