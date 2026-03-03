-- ========================================
-- PASO 4: Decrementar contador 2 veces
-- ========================================
-- Simula que 2 usuarios salen de la sala

SELECT decrement_participant_count('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid);
SELECT decrement_participant_count('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid);

-- Ahora verifica el resultado:
SELECT id, name, current_participants 
FROM voice_rooms 
WHERE id = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid;

-- Resultado esperado: current_participants = 1 (era 3, decrementó 2)
