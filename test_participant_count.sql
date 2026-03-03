-- Script de prueba para las funciones increment/decrement_participant_count
-- Ejecuta esto en tu consola SQL de Supabase para verificar que las funciones funcionan

-- 1. Crear una sala de prueba
INSERT INTO voice_rooms (id, name, description, creator_id, is_active, is_public)
VALUES (
  'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid,
  'Sala de Prueba',
  'Sala para probar contador de participantes',
  (SELECT id FROM users LIMIT 1),
  true,
  true
)
ON CONFLICT (id) DO UPDATE SET
  name = EXCLUDED.name,
  current_participants = 0;

-- 2. Verificar el estado inicial (debe ser 0)
SELECT id, name, current_participants FROM voice_rooms 
WHERE id = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid;

-- 3. Probar incremento (simular que 3 usuarios entran)
SELECT increment_participant_count('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid);
SELECT increment_participant_count('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid);
SELECT increment_participant_count('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid);

-- 4. Verificar que el contador aumentó a 3
SELECT id, name, current_participants FROM voice_rooms 
WHERE id = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid;
-- Resultado esperado: current_participants = 3

-- 5. Probar decremento (simular que 2 usuarios salen)
SELECT decrement_participant_count('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid);
SELECT decrement_participant_count('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid);

-- 6. Verificar que el contador disminuyó a 1
SELECT id, name, current_participants FROM voice_rooms 
WHERE id = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid;
-- Resultado esperado: current_participants = 1

-- 7. Probar decremento hasta llegar a 0
SELECT decrement_participant_count('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid);

-- 8. Verificar que el contador está en 0
SELECT id, name, current_participants FROM voice_rooms 
WHERE id = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid;
-- Resultado esperado: current_participants = 0

-- 9. Probar que no baja de 0 (GREATEST evita negativos)
SELECT decrement_participant_count('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid);
SELECT decrement_participant_count('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid);

-- 10. Verificar que sigue en 0 (no permite negativos)
SELECT id, name, current_participants FROM voice_rooms 
WHERE id = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid;
-- Resultado esperado: current_participants = 0 (no -2)

-- 11. Limpiar la sala de prueba (opcional)
-- DELETE FROM voice_rooms WHERE id = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid;

-- ¡Si todos los pasos muestran los resultados esperados, las funciones están funcionando correctamente!
