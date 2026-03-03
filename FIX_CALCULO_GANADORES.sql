-- ================================================================
-- FIX: CALCULAR GANADORES AL FINALIZAR VOTACIÓN
-- ================================================================
-- El problema: La función get_or_generate_active_challenge
-- cambia el status a 'completed' pero NO calcula los winner_ids
-- ================================================================

-- Recrear la función con el cálculo de ganadores
DROP FUNCTION IF EXISTS public.get_or_generate_active_challenge();

CREATE OR REPLACE FUNCTION public.get_or_generate_active_challenge()
RETURNS TABLE (
    active_id UUID,
    challenge_id UUID,
    expires_at TIMESTAMP WITH TIME ZONE,
    voting_expires_at TIMESTAMP WITH TIME ZONE,
    status TEXT,
    winner_ids UUID[]
) 
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    current_active active_challenges;
    v_winner_ids UUID[];
BEGIN
    -- Buscar el reto activo más reciente
    SELECT ac.* INTO current_active 
    FROM active_challenges ac
    WHERE ac.status IN ('active', 'voting', 'completed')
    ORDER BY ac.created_at DESC 
    LIMIT 1;

    -- TRANSICIÓN: De 'active' a 'voting'
    IF FOUND AND current_active.status = 'active' AND current_active.expires_at <= (NOW() + INTERVAL '3 seconds') THEN
        UPDATE active_challenges 
        SET status = 'voting', 
            voting_expires_at = NOW() + INTERVAL '30 seconds'
        WHERE id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    -- TRANSICIÓN: De 'voting' a 'completed' CON CÁLCULO DE GANADORES
    IF FOUND AND current_active.status = 'voting' AND current_active.voting_expires_at <= (NOW() + INTERVAL '2 seconds') THEN
        
        -- ⭐ CALCULAR TOP 3 GANADORES BASADO EN VOTOS
        WITH top_voted AS (
            SELECT cs.user_id, COUNT(cv.id) as vote_count
            FROM challenge_submissions cs
            LEFT JOIN challenge_votes cv ON cv.submission_id = cs.id
            WHERE cs.active_challenge_id = current_active.id
            GROUP BY cs.user_id
            ORDER BY vote_count DESC, cs.created_at ASC  -- En caso de empate, el primero en participar gana
            LIMIT 3
        )
        SELECT ARRAY(SELECT user_id FROM top_voted) INTO v_winner_ids;
        
        -- Actualizar el reto con los ganadores
        UPDATE active_challenges 
        SET status = 'completed',
            expires_at = NOW() + INTERVAL '5 seconds',  -- Tiempo del podio
            winner_ids = v_winner_ids
        WHERE id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    -- TRANSICIÓN: De 'completed' a NULL (generar nuevo reto)
    IF FOUND AND current_active.status = 'completed' AND current_active.expires_at <= (NOW() + INTERVAL '1 second') THEN
        current_active := NULL;
    END IF;

    -- Generar nuevo reto si no hay ninguno activo
    IF current_active IS NULL THEN
        INSERT INTO active_challenges (challenge_id, expires_at, status)
        SELECT cm.id, NOW() + INTERVAL '5 minutes', 'active'
        FROM challenges_master cm
        ORDER BY RANDOM()
        LIMIT 1
        RETURNING * INTO current_active;
    END IF;

    -- Retornar el reto actual
    RETURN QUERY SELECT 
        current_active.id as active_id,
        current_active.challenge_id,
        current_active.expires_at,
        current_active.voting_expires_at,
        current_active.status,
        current_active.winner_ids;
END;
$$ LANGUAGE plpgsql;

-- Otorgar permisos
GRANT EXECUTE ON FUNCTION get_or_generate_active_challenge() TO authenticated, anon;

-- Verificación: Mostrar información del reto actual
DO $$
DECLARE
    current_status TEXT;
    current_winners UUID[];
BEGIN
    SELECT status, winner_ids INTO current_status, current_winners
    FROM active_challenges
    ORDER BY created_at DESC
    LIMIT 1;
    
    RAISE NOTICE '================================================';
    RAISE NOTICE '✅ FUNCIÓN DE GANADORES ACTUALIZADA';
    RAISE NOTICE '================================================';
    RAISE NOTICE '';
    RAISE NOTICE 'Reto actual:';
    RAISE NOTICE '  Estado: %', COALESCE(current_status, 'No hay reto activo');
    RAISE NOTICE '  Ganadores: %', COALESCE(array_length(current_winners, 1), 0);
    RAISE NOTICE '';
    RAISE NOTICE 'Cambios aplicados:';
    RAISE NOTICE '  ✅ Al finalizar votación se calculan TOP 3';
    RAISE NOTICE '  ✅ Se basa en el conteo de votos';
    RAISE NOTICE '  ✅ En empate, gana quien participó primero';
    RAISE NOTICE '  ✅ Podio dura 5 segundos';
    RAISE NOTICE '';
    RAISE NOTICE '📋 SIGUIENTE PASO:';
    RAISE NOTICE '  1. Espera a que termine un reto actual (si hay)';
    RAISE NOTICE '  2. O participa en el siguiente reto';
    RAISE NOTICE '  3. Vota durante la fase de votación';
    RAISE NOTICE '  4. Al finalizar, verás el podio con ganadores ✅';
    RAISE NOTICE '================================================';
END $$;
