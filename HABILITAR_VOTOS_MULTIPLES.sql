-- ================================================================
-- HABILITAR VOTOS MÚLTIPLES EN RETOS
-- ================================================================
-- Este script permite que los usuarios puedan:
-- ✅ Votar por TODAS las submissions que quieran
-- ✅ Poner y quitar votos (toggle) en cualquier submission
-- ✅ No hay límite de votos por reto
-- ================================================================

-- 1. ELIMINAR EL CONSTRAINT QUE LIMITA A UN VOTO POR RETO
-- Este constraint impedía votar múltiples submissions en el mismo reto
ALTER TABLE challenge_votes 
DROP CONSTRAINT IF EXISTS unique_vote_per_challenge;

-- 2. ASEGURAR QUE EXISTE EL CONSTRAINT PARA EVITAR VOTAR DOS VECES LA MISMA SUBMISSION
-- Esto previene que votes la misma submission dos veces, pero permite votar otras
DO $$
BEGIN
    -- Verificar si el constraint ya existe
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint 
        WHERE conname = 'challenge_votes_submission_id_voter_id_key'
        AND conrelid = 'challenge_votes'::regclass
    ) THEN
        -- Si no existe, crearlo
        ALTER TABLE challenge_votes 
        ADD CONSTRAINT challenge_votes_submission_id_voter_id_key 
        UNIQUE (submission_id, voter_id);
    END IF;
END $$;

-- 3. LIMPIAR ÍNDICES DUPLICADOS (por si acaso)
DROP INDEX IF EXISTS idx_challenge_votes_unique;
DROP INDEX IF EXISTS idx_challenge_votes_submission_voter;

-- 4. CREAR ÍNDICE ÚNICO PARA MEJORAR PERFORMANCE
CREATE UNIQUE INDEX IF NOT EXISTS idx_challenge_votes_submission_voter 
ON challenge_votes (submission_id, voter_id);

-- 5. VERIFICAR QUE LA FUNCIÓN v2026_challenge_vote EXISTE
-- (Ya debería existir del SQL que ejecutaste ayer)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_proc 
        WHERE proname = 'v2026_challenge_vote'
        AND pg_get_function_arguments(oid) = 'p_id text'
    ) THEN
        RAISE EXCEPTION 'La función v2026_challenge_vote no existe. Ejecuta primero FIX_VOTACIONES_FINAL.sql';
    END IF;
END $$;

-- 6. VERIFICAR CONFIGURACIÓN FINAL
DO $$
DECLARE
    constraint_count INTEGER;
BEGIN
    -- Contar cuántos constraints de unicidad quedan
    SELECT COUNT(*) INTO constraint_count
    FROM pg_constraint
    WHERE conrelid = 'challenge_votes'::regclass
    AND contype = 'u'; -- u = unique constraint
    
    RAISE NOTICE '================================================';
    RAISE NOTICE '✅ VOTOS MÚLTIPLES HABILITADOS CORRECTAMENTE';
    RAISE NOTICE '================================================';
    RAISE NOTICE '';
    RAISE NOTICE 'Configuración actual:';
    RAISE NOTICE '- Constraints únicos activos: %', constraint_count;
    RAISE NOTICE '';
    RAISE NOTICE '✅ Los usuarios ahora pueden:';
    RAISE NOTICE '   • Votar por TODAS las submissions que quieran';
    RAISE NOTICE '   • Hacer toggle (poner/quitar) en cada voto';
    RAISE NOTICE '   • No hay límite de votos por reto';
    RAISE NOTICE '';
    RAISE NOTICE '🔒 Protecciones que se mantienen:';
    RAISE NOTICE '   • No pueden votar la misma submission dos veces';
    RAISE NOTICE '   • RLS activo para autenticación';
    RAISE NOTICE '';
    RAISE NOTICE '================================================';
END $$;

-- 7. MOSTRAR CONSTRAINTS ACTUALES (para verificación)
SELECT 
    conname AS "Constraint",
    pg_get_constraintdef(oid) AS "Definición"
FROM pg_constraint
WHERE conrelid = 'challenge_votes'::regclass
AND contype = 'u'
ORDER BY conname;
