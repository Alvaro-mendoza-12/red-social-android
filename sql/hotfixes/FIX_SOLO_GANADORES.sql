-- ================================================================
-- FIX MINIMAL: SOLO CALCULAR GANADORES EN EL PODIO
-- ================================================================
-- Este script SOLO arregla que el podio muestre ganadores
-- NO TOCA las transiciones (que ya funcionaban)
-- ================================================================

-- Actualizar la función get_or_generate_active_challenge
-- Solo agregamos el cálculo de winner_ids cuando pasa a 'completed'
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
    -- Buscar reto activo más reciente
    SELECT ac.* INTO current_active 
   FROM active_challenges ac
    WHERE ac.status IN ('active', 'voting', 'completed')
    ORDER BY ac.created_at DESC 
    LIMIT 1;

    -- Transición: active -> voting
    IF FOUND AND current_active.status = 'active' AND current_active.expires_at <= (NOW() + INTERVAL '3 seconds') THEN
        UPDATE active_challenges 
        SET status = 'voting', 
            voting_expires_at = NOW() + INTERVAL '30 seconds'
        WHERE id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    -- Transición: voting -> completed (AQUÍ SE CALCULAN LOS GANADORES)
    IF FOUND AND current_active.status = 'voting' AND current_active.voting_expires_at <= (NOW() + INTERVAL '2 seconds') THEN
        
        -- ⭐ CALCULAR TOP 3 GANADORES
        WITH top_voted AS (
            SELECT cs.user_id, COUNT(cv.id) as vote_count
            FROM challenge_submissions cs
            LEFT JOIN challenge_votes cv ON cv.submission_id = cs.id
            WHERE cs.active_challenge_id = current_active.id
            GROUP BY cs.user_id
            ORDER BY vote_count DESC, cs.created_at ASC
            LIMIT 3
        )
        SELECT ARRAY(SELECT user_id FROM top_voted) INTO v_winner_ids;
        
        -- Actualizar con los ganadores
        UPDATE active_challenges 
        SET status = 'completed',
            expires_at = NOW() + INTERVAL '5 seconds',
            winner_ids = COALESCE(v_winner_ids, '{}')
        WHERE id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    -- Transición: completed -> NULL (generar nuevo reto)
    IF FOUND AND current_active.status = 'completed' AND current_active.expires_at <= (NOW() + INTERVAL '1 second') THEN
        current_active := NULL;
    END IF;

    -- Generar nuevo reto si no hay ninguno
    IF current_active IS NULL THEN
        INSERT INTO active_challenges (challenge_id, expires_at, status, winner_ids)
        SELECT id, NOW() + INTERVAL '5 minutes', 'active', '{}'
        FROM challenges_master
        ORDER BY RANDOM()
        LIMIT 1
        RETURNING * INTO current_active;
    END IF;

    -- Retornar
    RETURN QUERY SELECT 
        current_active.id,
        current_active.challenge_id,
        current_active.expires_at,
        current_active.voting_expires_at,
        current_active.status,
        current_active.winner_ids;
END;
$$ LANGUAGE plpgsql;

-- Otorgar permisos
GRANT EXECUTE ON FUNCTION get_or_generate_active_challenge() TO authenticated, anon;

-- Mensaje de confirmación
DO $$
BEGIN
    RAISE NOTICE '================================================';
    RAISE NOTICE '✅ FIX APLICADO';
    RAISE NOTICE '================================================';
    RAISE NOTICE '';
    RAISE NOTICE 'Cambio realizado:';
    RAISE NOTICE '  • Al terminar votación, se calcula TOP 3';
    RAISE NOTICE '  • Los ganadores se guardan en winner_ids';
    RAISE NOTICE '  • El podio dura 5 segundos';
    RAISE NOTICE '';
    RAISE NOTICE 'NO se modificó:';
    RAISE NOTICE '  • Las transiciones automáticas (ya funcionaban)';
    RAISE NOTICE '  • El sistema de votación';
    RAISE NOTICE '  • La generación de retos';
    RAISE NOTICE '';
    RAISE NOTICE '📱 Recompila la app y prueba el podio';
    RAISE NOTICE '================================================';
END $$;
