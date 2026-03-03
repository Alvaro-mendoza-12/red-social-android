-- ===================================
-- Schema para Online Status y Last Seen
-- ===================================

-- 1. Agregar columnas a la tabla users
ALTER TABLE users
ADD COLUMN IF NOT EXISTS is_online BOOLEAN DEFAULT false,
ADD COLUMN IF NOT EXISTS last_seen TIMESTAMP WITH TIME ZONE DEFAULT NOW();

-- 2. Crear índice para consultas rápidas de usuarios online
CREATE INDEX IF NOT EXISTS idx_users_online ON users(is_online) WHERE is_online = true;

-- 3. Crear índice para last_seen
CREATE INDEX IF NOT EXISTS idx_users_last_seen ON users(last_seen);

-- 4. Función para actualizar estado online
CREATE OR REPLACE FUNCTION update_user_online_status(
    user_id uuid,
    online_status boolean
)
RETURNS void AS $$
BEGIN
    UPDATE users
    SET 
        is_online = online_status,
        last_seen = CASE 
            WHEN online_status = false THEN NOW() 
            ELSE last_seen 
        END
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 5. Trigger para actualizar last_seen automáticamente cuando se desconecta
CREATE OR REPLACE FUNCTION auto_update_last_seen()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.is_online = false AND OLD.is_online = true THEN
        NEW.last_seen = NOW();
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_auto_update_last_seen ON users;
CREATE TRIGGER trigger_auto_update_last_seen
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION auto_update_last_seen();

-- 6. Políticas RLS para lectura de estado (cualquiera puede ver el estado de otros)
-- Ya deberían existir políticas para SELECT en users, pero aseguramos que puedan ver estos campos
-- No necesitamos crear nuevas políticas si ya existe una política general de SELECT

-- 7. Verificar la estructura
SELECT column_name, data_type, column_default
FROM information_schema.columns
WHERE table_name = 'users' 
  AND column_name IN ('is_online', 'last_seen');
