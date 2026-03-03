-- ========================================
-- PASO 2: Verificar estado inicial (debe ser 0)
-- ========================================

SELECT id, name, current_participants 
FROM voice_rooms 
WHERE id = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid;

-- Resultado esperado: current_participants = 0
