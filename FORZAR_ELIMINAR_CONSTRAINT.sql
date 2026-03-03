-- ================================================================
-- FIX DEFINITIVO: FORZAR ELIMINACIÓN DEL CONSTRAINT PROBLEMÁTICO
-- ================================================================
-- Error 23505 = Violación de constraint único
-- Esto significa que unique_vote_per_challenge SIGUE ACTIVO
-- Este script FUERZA su eliminación sin importar el nombre
-- ================================================================

-- PASO 1: Ver todos los constraints actuales ANTES de borrar
DO $$
DECLARE
    constraint_record RECORD;
BEGIN
    RAISE NOTICE '================================================';
    RAISE NOTICE 'CONSTRAINTS ACTUALES EN challenge_votes:';
    RAISE NOTICE '================================================';
    
    FOR constraint_record IN 
        SELECT conname, pg_get_constraintdef(oid) as def
        FROM pg_constraint
        WHERE conrelid = 'challenge_votes'::regclass
        AND contype = 'u'
    LOOP
        RAISE NOTICE 'Constraint: % -> %', constraint_record.conname, constraint_record.def;
    END LOOP;
END $$;

-- PASO 2: ELIMINAR TODOS LOS CONSTRAINTS ÚNICOS POSIBLES
-- Nombres conocidos que pueden estar causando el problema
DROP INDEX IF EXISTS challenge_votes_active_challenge_id_voter_id_key CASCADE;
DROP INDEX IF EXISTS idx_challenge_votes_active_voter CASCADE;

ALTER TABLE challenge_votes DROP CONSTRAINT IF EXISTS unique_vote_per_challenge CASCADE;
ALTER TABLE challenge_votes DROP CONSTRAINT IF EXISTS challenge_votes_active_challenge_id_voter_id_key CASCADE;
ALTER TABLE challenge_votes DROP CONSTRAINT IF EXISTS uq_one_vote_per_challenge CASCADE;
ALTER TABLE challenge_votes DROP CONSTRAINT IF EXISTS challenge_votes_voter_challenge_key CASCADE;

-- PASO 3: Buscar y eliminar CUALQUIER constraint que incluya active_challenge_id + voter_id
DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    FOR constraint_name IN 
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'challenge_votes'::regclass
        AND contype = 'u'
        AND pg_get_constraintdef(oid) LIKE '%active_challenge_id%voter_id%'
    LOOP
        EXECUTE format('ALTER TABLE challenge_votes DROP CONSTRAINT IF EXISTS %I CASCADE', constraint_name);
        RAISE NOTICE '✅ Eliminado constraint: %', constraint_name;
    END LOOP;
END $$;

-- PASO 4: ASEGURAR que existe el constraint correcto (submission_id, voter_id)
-- Este PERMITE votar múltiples submissions en el mismo reto
DO $$
BEGIN
    -- Primero eliminar si existe para recrearlo limpio
    ALTER TABLE challenge_votes DROP CONSTRAINT IF EXISTS challenge_votes_submission_id_voter_id_key CASCADE;
    
    -- Recrear el constraint correcto
    ALTER TABLE challenge_votes 
    ADD CONSTRAINT challenge_votes_submission_id_voter_id_key 
    UNIQUE (submission_id, voter_id);
    
    RAISE NOTICE '✅ Constraint correcto creado: (submission_id, voter_id)';
END $$;

-- PASO 5: Crear índice para performance (opcional pero recomendado)
DROP INDEX IF EXISTS idx_challenge_votes_submission_voter CASCADE;
CREATE UNIQUE INDEX idx_challenge_votes_submission_voter 
ON challenge_votes (submission_id, voter_id);

-- PASO 6: VERIFICACIÓN FINAL - Mostrar constraints que quedaron
DO $$
DECLARE
    constraint_record RECORD;
    total_constraints INTEGER := 0;
BEGIN
    RAISE NOTICE '';
    RAISE NOTICE '================================================';
    RAISE NOTICE 'CONSTRAINTS FINALES EN challenge_votes:';
    RAISE NOTICE '================================================';
    
    FOR constraint_record IN 
        SELECT conname, pg_get_constraintdef(oid) as def
        FROM pg_constraint
        WHERE conrelid = 'challenge_votes'::regclass
        AND contype = 'u'
    LOOP
        total_constraints := total_constraints + 1;
        RAISE NOTICE 'Constraint %: % -> %', total_constraints, constraint_record.conname, constraint_record.def;
    END LOOP;
    
    RAISE NOTICE '';
    
    IF total_constraints = 1 THEN
        RAISE NOTICE '✅✅✅ PERFECTO! Solo 1 constraint único (el correcto)';
        RAISE NOTICE '';
        RAISE NOTICE '🎉 VOTOS MÚLTIPLES HABILITADOS CORRECTAMENTE';
        RAISE NOTICE '';
        RAISE NOTICE 'Ahora puedes:';
        RAISE NOTICE '  ✅ Votar por MÚLTIPLES submissions en el mismo reto';
        RAISE NOTICE '  ✅ Poner y quitar votos libremente';
        RAISE NOTICE '  ✅ No hay límite de votos por reto';
    ELSE
        RAISE WARNING '⚠️ ATENCIÓN: Hay % constraints únicos (esperado: 1)', total_constraints;
        RAISE WARNING 'Puede haber constraints adicionales que necesitan eliminarse';
    END IF;
    
    RAISE NOTICE '================================================';
END $$;

-- PASO 7: Recargar schema de PostgREST
NOTIFY pgrst, 'reload schema';

-- PASO 8: Mostrar mensaje final
DO $$
BEGIN
    RAISE NOTICE '';
    RAISE NOTICE '🚀 SCRIPT COMPLETADO';
    RAISE NOTICE '';
    RAISE NOTICE 'SIGUIENTE PASO:';
    RAISE NOTICE '1. Cierra COMPLETAMENTE la app (force stop)';
    RAISE NOTICE '2. Abre la app de nuevo';
    RAISE NOTICE '3. Intenta votar por MÚLTIPLES respuestas';
    RAISE NOTICE '4. Debería funcionar SIN error 23505';
    RAISE NOTICE '';
END $$;
