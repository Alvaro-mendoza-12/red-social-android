-- ========================================
-- PASO 5: Probar que no baja de 0
-- ========================================
-- Primero bajamos a 0
SELECT decrement_participant_count('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid);

-- Ahora intentamos bajar más (debería quedarse en 0)
SELECT decrement_participant_count('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid);
SELECT decrement_participant_count('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid);

-- Verifica que sigue en 0 (NO -2):
SELECT id, name, current_participants 
FROM voice_rooms 
WHERE id = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid;

-- Resultado esperado: current_participants = 0 (NO negativo)
