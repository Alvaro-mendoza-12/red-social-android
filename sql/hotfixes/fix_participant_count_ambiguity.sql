-- Eliminar la función duplicada que acepta TEXT
-- Solo mantener la que acepta UUID
DROP FUNCTION IF EXISTS public.increment_participant_count(room_id text);
DROP FUNCTION IF EXISTS public.decrement_participant_count(room_id text);

-- Asegurar que solo existan las funciones con UUID
CREATE OR REPLACE FUNCTION increment_participant_count(room_id uuid)
RETURNS void AS $$
BEGIN
  UPDATE voice_rooms
  SET current_participants = current_participants + 1
  WHERE id = room_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION decrement_participant_count(room_id uuid)
RETURNS void AS $$
BEGIN
  UPDATE voice_rooms
  SET current_participants = GREATEST(current_participants - 1, 0)
  WHERE id = room_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
