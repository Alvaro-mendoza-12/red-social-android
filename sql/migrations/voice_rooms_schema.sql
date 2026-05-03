-- ============================================
-- SCRIPT COMPLETO: Tabla voice_rooms (FINAL)
-- ============================================
-- Este script incluye TODAS las columnas que la app Android necesita

-- 1. BORRAR TABLA ANTERIOR (por si quedó incompleta)
DROP TABLE IF EXISTS public.voice_rooms CASCADE;

-- 2. CREAR TABLA COMPLETA con todas las columnas
CREATE TABLE public.voice_rooms (
  id uuid NOT NULL PRIMARY KEY,
  name text NOT NULL,
  description text,
  creator_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  is_public boolean DEFAULT true,
  password text,
  agora_channel_name text,
  is_active boolean DEFAULT true,
  max_participants int DEFAULT 10,
  created_at timestamptz DEFAULT now(),
  updated_at timestamptz DEFAULT now()
);

-- 3. CREAR ÍNDICES para mejorar rendimiento
CREATE INDEX idx_voice_rooms_creator ON public.voice_rooms(creator_id);
CREATE INDEX idx_voice_rooms_active ON public.voice_rooms(is_active) WHERE is_active = true;
CREATE INDEX idx_voice_rooms_created_at ON public.voice_rooms(created_at DESC);

-- 4. TRIGGER para actualizar 'updated_at' automáticamente
CREATE OR REPLACE FUNCTION update_voice_rooms_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_update_voice_rooms_updated_at
BEFORE UPDATE ON public.voice_rooms
FOR EACH ROW
EXECUTE FUNCTION update_voice_rooms_updated_at();

-- 5. HABILITAR ROW LEVEL SECURITY
ALTER TABLE public.voice_rooms ENABLE ROW LEVEL SECURITY;

-- 6. POLÍTICAS DE SEGURIDAD (RLS)

-- A. Cualquier usuario autenticado puede VER salas activas
CREATE POLICY "Ver salas activas" ON public.voice_rooms
FOR SELECT TO authenticated
USING (is_active = true);

-- B. Cualquier usuario autenticado puede CREAR una sala
CREATE POLICY "Crear sala" ON public.voice_rooms
FOR INSERT TO authenticated
WITH CHECK (auth.uid() = creator_id);

-- C. Solo el creador puede EDITAR su propia sala
CREATE POLICY "Editar propia sala" ON public.voice_rooms
FOR UPDATE TO authenticated
USING (auth.uid() = creator_id);

-- D. Solo el creador puede BORRAR su sala
CREATE POLICY "Borrar propia sala" ON public.voice_rooms
FOR DELETE TO authenticated
USING (auth.uid() = creator_id);

-- 7. HABILITAR REALTIME (para que las salas aparezcan en tiempo real)
ALTER PUBLICATION supabase_realtime ADD TABLE public.voice_rooms;

-- 8. PERMISOS FINALES
GRANT ALL ON public.voice_rooms TO authenticated;
GRANT SELECT ON public.voice_rooms TO anon;

-- ============================================
-- FIN DEL SCRIPT
-- ============================================
-- INSTRUCCIONES:
-- 1. Copia TODO este contenido
-- 2. Pégalo en Supabase SQL Editor
-- 3. Dale a "RUN"
-- 4. Verifica que diga "Success"
-- ============================================
