-- ======================================================
-- LIMPIEZA FINAL Y SINCRONIZACIÓN REAL
-- ======================================================

-- 1. Asegurar que 'profiles' tenga el dato REAL que tiene la tabla 'users'
-- Esto borrará cualquier dato de "ejemplo" que haya quedado de pruebas manuales.
UPDATE profiles p
SET 
    is_online = u.is_online,
    last_seen = u.last_seen
FROM users u
WHERE p.id = u.id;

-- 2. Verificar datos reales actuales (Primeros 10 usuarios)
-- Mira los resultados abajo para confirmar que NO son los ejemplos.
SELECT id, username, is_online, last_seen
FROM profiles
LIMIT 10;
