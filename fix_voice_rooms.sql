-- Agregar columnas para gestión avanzada de salas
ALTER TABLE voice_rooms 
ADD COLUMN IF NOT EXISTS readable_id VARCHAR(5) UNIQUE,
ADD COLUMN IF NOT EXISTS password TEXT,
ADD COLUMN IF NOT EXISTS settings JSONB DEFAULT '{}'::jsonb;

-- Función para generar ID único de 5 caracteres (letras y números) en mayúsculas
CREATE OR REPLACE FUNCTION generate_unique_room_id() 
RETURNS VARCHAR AS $$
DECLARE
  chars TEXT := 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
  result VARCHAR := '';
  i INTEGER;
  exists_count INTEGER;
BEGIN
  LOOP
    result := '';
    FOR i IN 1..5 LOOP
      result := result || substr(chars, floor(random() * length(chars) + 1)::integer, 1);
    END LOOP;
    
    SELECT count(*) INTO exists_count FROM voice_rooms WHERE readable_id = result;
    IF exists_count = 0 THEN
      EXIT;
    END IF;
  END LOOP;
  RETURN result;
END;
$$ LANGUAGE plpgsql;

-- Trigger para asignar readable_id automáticamente al crear
CREATE OR REPLACE FUNCTION set_room_readable_id()
RETURNS TRIGGER AS $$
BEGIN
  IF NEW.readable_id IS NULL THEN
    NEW.readable_id := generate_unique_room_id();
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_set_readable_id ON voice_rooms;
CREATE TRIGGER trigger_set_readable_id
BEFORE INSERT ON voice_rooms
FOR EACH ROW
EXECUTE FUNCTION set_room_readable_id();

-- Actualizar salas existentes que no tengan readable_id
UPDATE voice_rooms SET readable_id = generate_unique_room_id() WHERE readable_id IS NULL;
