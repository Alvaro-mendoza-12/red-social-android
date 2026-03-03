-- ==========================================
-- FIX: SCHEMA COLUMNS & PERMISSIONS (V4)
-- ==========================================
-- Error PGRST204 indica que Supabase no encuentra la columna 'media_size'.
-- Este script asegura que existan TODAS las columnas requeridas por la app.

-- 1. AGREGAR COLUMNAS FALTANTES (Si no existen)
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS media_size BIGINT DEFAULT 0;
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS is_edited BOOLEAN DEFAULT FALSE;
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS deleted_for_all BOOLEAN DEFAULT FALSE;
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS reply_to_id UUID REFERENCES public.messages(id) ON DELETE SET NULL;
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS content_type TEXT DEFAULT 'text';
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS media_url TEXT;

-- 2. ASEGURAR PERMISOS (Por si acaso se perdieron con el cambio de RLS)
GRANT ALL ON TABLE public.messages TO postgres;
GRANT ALL ON TABLE public.messages TO service_role;
GRANT ALL ON TABLE public.messages TO authenticated;
GRANT ALL ON TABLE public.messages TO anon;

-- 3. RECARGAR CACHÉ DE ESQUEMA (Obligatorio para que PostgREST vea los cambios)
NOTIFY pgrst, 'reload schema';

SELECT '✅ ESQUEMA ACTUALIZADO Y COLUMNAS VERIFICADAS' as status;
