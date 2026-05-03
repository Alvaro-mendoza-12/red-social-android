-- ======================================================
-- SINCRONIZACIÓN DE PRESENCIA: users -> profiles
-- ======================================================

-- 1. Agregar columnas a la tabla profiles
ALTER TABLE profiles
ADD COLUMN IF NOT EXISTS is_online BOOLEAN DEFAULT false,
ADD COLUMN IF NOT EXISTS last_seen TIMESTAMP WITH TIME ZONE DEFAULT NOW();

-- 2. Copiar datos actuales de users a profiles (si coinciden los IDs)
UPDATE profiles p
SET 
    is_online = u.is_online,
    last_seen = u.last_seen
FROM users u
WHERE p.id = u.id;

-- 3. Actualizar la función RPC para que afecte a AMBAS tablas
-- O mejor aún, que afecte a 'profiles' que es la que el App lee predominantemente
CREATE OR REPLACE FUNCTION update_user_online_status(
    user_id uuid,
    online_status boolean
)
RETURNS void AS $$
BEGIN
    -- Actualizar en la tabla users
    UPDATE users
    SET 
        is_online = online_status,
        last_seen = CASE 
            WHEN online_status = false THEN NOW() 
            ELSE last_seen 
        END
    WHERE id = user_id;

    -- Actualizar también en la tabla profiles
    UPDATE profiles
    SET 
        is_online = online_status,
        last_seen = CASE 
            WHEN online_status = false THEN NOW() 
            ELSE last_seen 
        END
    WHERE id = user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 4. Asegurar que el trigger de auto_update también esté en profiles si es necesario
-- (Opcional, pero recomendado para consistencia)
DROP TRIGGER IF EXISTS trigger_auto_update_last_seen_profiles ON profiles;
CREATE TRIGGER trigger_auto_update_last_seen_profiles
    BEFORE UPDATE ON profiles
    FOR EACH ROW
    EXECUTE FUNCTION auto_update_last_seen();

-- 5. Verificar que ambas tengan las columnas
SELECT table_name, column_name, data_type 
FROM information_schema.columns 
WHERE column_name IN ('is_online', 'last_seen')
  AND table_name IN ('users', 'profiles');
