-- ============================================
-- SCRIPT PARA AÑADIR CONTEO DE PARTICIPANTES
-- ============================================

-- 1. Añadir la columna participant_count si no existe
ALTER TABLE public.voice_rooms 
ADD COLUMN IF NOT EXISTS participant_count int DEFAULT 0;

-- 2. (Opcional) Resetear a 0 todos los contadores
UPDATE public.voice_rooms SET participant_count = 0;

-- ============================================
-- FUNCIONES PARA INCREMENTAR/DECREMENTAR (SEGURO)
-- ============================================

-- Función para incrementar
CREATE OR REPLACE FUNCTION increment_participant_count(room_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
  UPDATE public.voice_rooms
  SET participant_count = participant_count + 1
  WHERE id = room_id;
END;
$$;

-- Función para decrementar
CREATE OR REPLACE FUNCTION decrement_participant_count(room_id uuid)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
  UPDATE public.voice_rooms
  SET participant_count = GREATEST(0, participant_count - 1)
  WHERE id = room_id;
END;
$$;
