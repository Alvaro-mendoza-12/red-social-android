-- ========================================
-- PASO 1: Crear/Reiniciar sala de prueba
-- ========================================
-- Ejecuta esto primero, luego ve al PASO 2

INSERT INTO voice_rooms (id, name, description, creator_id, is_active, is_public, current_participants)
VALUES (
  'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid,
  'Sala de Prueba',
  'Sala para probar contador de participantes',
  (SELECT id FROM users LIMIT 1),
  true,
  true,
  0
)
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  current_participants = 0;

-- Deberías ver: "1 row affected" o similar
