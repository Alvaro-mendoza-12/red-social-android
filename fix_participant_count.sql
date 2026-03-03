-- Asegurar que existe la columna current_participants
ALTER TABLE voice_rooms 
ADD COLUMN IF NOT EXISTS current_participants INTEGER DEFAULT 0;

-- Función para incrementar participantes
CREATE OR REPLACE FUNCTION increment_participant_count(room_id uuid)
RETURNS void AS $$
BEGIN
  UPDATE voice_rooms
  SET current_participants = current_participants + 1
  WHERE id = room_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Función para decrementar participantes
CREATE OR REPLACE FUNCTION decrement_participant_count(room_id uuid)
RETURNS void AS $$
BEGIN
  UPDATE voice_rooms
  SET current_participants = GREATEST(current_participants - 1, 0)
  WHERE id = room_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
