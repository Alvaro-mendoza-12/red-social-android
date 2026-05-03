-- ================================================
-- Actualizar last_seen para usuarios existentes
-- ================================================
-- Esto asigna la fecha de creación como valor inicial para usuarios que no tienen last_seen

UPDATE users
SET last_seen = COALESCE(created_at, NOW())
WHERE last_seen IS NULL;

-- También aseguramos que is_online sea false para usuarios sin last_seen
UPDATE users
SET is_online = false
WHERE last_seen IS NULL;

-- Verificar el resultado
SELECT id, username, is_online, 
       last_seen,
       created_at
FROM users
ORDER BY created_at DESC
LIMIT 10;

