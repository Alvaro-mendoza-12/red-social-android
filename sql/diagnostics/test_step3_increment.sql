-- ========================================
-- PASO 3: Incrementar contador 3 veces
-- ========================================
-- Simula que 3 usuarios entran a la sala

SELECT increment_participant_count('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid);
SELECT increment_participant_count('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid);
SELECT increment_participant_count('a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid);

-- Ahora verifica el resultado:
SELECT id, name, current_participants 
FROM voice_rooms 
WHERE id = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid;

-- Resultado esperado: current_participants = 3
