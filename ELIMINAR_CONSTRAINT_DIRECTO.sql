-- ================================================================
-- FIX SIMPLIFICADO: ELIMINAR EL CONSTRAINT EXACTO
-- ================================================================
-- Basado en el error que obtuviste, sabemos que el constraint es:
-- challenge_votes_active_challenge_id_voter_id_key
-- ================================================================

-- PASO 1: Mostrar constraints ANTES
DO $$
DECLARE
    constraint_record RECORD;
BEGIN
    RAISE NOTICE '================================================';
    RAISE NOTICE '📋 CONSTRAINTS ANTES DE LA ELIMINACIÓN:';
    RAISE NOTICE '================================================';
    
    FOR constraint_record IN 
        SELECT conname, pg_get_constraintdef(oid) as def
        FROM pg_constraint
        WHERE conrelid = 'challenge_votes'::regclass
        AND contype = 'u'
    LOOP
        RAISE NOTICE 'Constraint: % = %', constraint_record.conname, constraint_record.def;
    END LOOP;
    RAISE NOTICE '';
END $$;

-- PASO 2: ELIMINAR EL CONSTRAINT PROBLEMÁTICO
-- Este es el que causa el error 23505 (solo un voto por reto)
ALTER TABLE challenge_votes 
DROP CONSTRAINT IF EXISTS challenge_votes_active_challenge_id_voter_id_key CASCADE;

-- PASO 3: También eliminar otros posibles nombres
ALTER TABLE challenge_votes DROP CONSTRAINT IF EXISTS unique_vote_per_challenge CASCADE;
ALTER TABLE challenge_votes DROP CONSTRAINT IF EXISTS uq_one_vote_per_challenge CASCADE;
ALTER TABLE challenge_votes DROP CONSTRAINT IF EXISTS challenge_votes_voter_challenge_key CASCADE;

-- PASO 4: ASEGURAR que existe el constraint CORRECTO
-- Este permite votar múltiples submissions en el mismo reto
ALTER TABLE challenge_votes 
DROP CONSTRAINT IF EXISTS challenge_votes_submission_id_voter_id_key CASCADE;

ALTER TABLE challenge_votes 
ADD CONSTRAINT challenge_votes_submission_id_voter_id_key 
UNIQUE (submission_id, voter_id);

-- PASO 5: Mostrar constraints DESPUÉS
DO $$
DECLARE
    constraint_record RECORD;
    total_constraints INTEGER := 0;
    bad_constraint_found BOOLEAN := false;
BEGIN
    RAISE NOTICE '================================================';
    RAISE NOTICE '📋 CONSTRAINTS DESPUÉS DE LA ELIMINACIÓN:';
    RAISE NOTICE '================================================';
    
    FOR constraint_record IN 
        SELECT conname, pg_get_constraintdef(oid) as def
        FROM pg_constraint
        WHERE conrelid = 'challenge_votes'::regclass
        AND contype = 'u'
    LOOP
        total_constraints := total_constraints + 1;
        RAISE NOTICE 'Constraint %: % = %', total_constraints, constraint_record.conname, constraint_record.def;
        
        -- Verificar si hay algún constraint con active_challenge_id
        IF constraint_record.def LIKE '%active_challenge_id%' THEN
            bad_constraint_found := true;
        END IF;
    END LOOP;
    
    RAISE NOTICE '';
    RAISE NOTICE '================================================';
    
    IF bad_constraint_found THEN
        RAISE WARNING '❌ AÚN HAY UN CONSTRAINT CON active_challenge_id!';
        RAISE WARNING 'Este causará el error 23505 al votar múltiples';
    ELSIF total_constraints = 1 THEN
        RAISE NOTICE '✅✅✅ PERFECTO!';
        RAISE NOTICE '';
        RAISE NOTICE '🎉 VOTOS MÚLTIPLES HABILITADOS';
        RAISE NOTICE '';
        RAISE NOTICE 'Solo existe el constraint correcto:';
        RAISE NOTICE '  UNIQUE (submission_id, voter_id)';
        RAISE NOTICE '';
        RAISE NOTICE 'Ahora puedes:';
        RAISE NOTICE '  ✅ Votar por MÚLTIPLES submissions';
        RAISE NOTICE '  ✅ Votar por Álvaro Y por tu respuesta';
        RAISE NOTICE '  ✅ Poner y quitar votos libremente';
        RAISE NOTICE '  ✅ Sin límite de votos por reto';
    ELSIF total_constraints = 0 THEN
        RAISE WARNING '⚠️ NO HAY CONSTRAINTS! (raro pero no crítico)';
    ELSE
        RAISE WARNING '⚠️ HAY % CONSTRAINTS (esperado: 1)', total_constraints;
    END IF;
    
    RAISE NOTICE '================================================';
END $$;

-- PASO 6: Recargar schema
NOTIFY pgrst, 'reload schema';

-- PASO 7: Mensaje final
DO $$
BEGIN
    RAISE NOTICE '';
    RAISE NOTICE '🚀 SIGUIENTE PASO:';
    RAISE NOTICE '';
    RAISE NOTICE '1. Cierra COMPLETAMENTE la app (force stop)';
    RAISE NOTICE '2. Abre la app';
    RAISE NOTICE '3. Ve a Retos';  
    RAISE NOTICE '4. Vota por Álvaro ✅';
    RAISE NOTICE '5. Vota por TU respuesta ✅ (antes daba error 23505)';
    RAISE NOTICE '6. Vota por más respuestas ✅';
    RAISE NOTICE '';
    RAISE NOTICE 'Si funciona, el problema está resuelto! 🎉';
    RAISE NOTICE '';
END $$;
