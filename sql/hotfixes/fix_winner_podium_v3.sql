-- ================================================================
-- FIX: PODIO DE GANADORES Y DURACIÓN
-- ================================================================
-- 1. Aumentamos la duración del podio a 30 segundos para que dé tiempo a verlo
-- 2. Aseguramos que el cálculo de ganadores use el vote_count real
-- 3. Optimizamos la transición de estados
-- ================================================================

DROP FUNCTION IF EXISTS get_or_generate_active_challenge();

CREATE OR REPLACE FUNCTION get_or_generate_active_challenge()
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
BEGIN
    -- 1. Buscar el reto más reciente (cualquier estado)
    SELECT ac.* INTO current_active 
    FROM active_challenges ac
    WHERE ac.status IN ('active', 'voting', 'completed')
    ORDER BY ac.created_at DESC 
    LIMIT 1;

    -- 2. Transición: Active -> Voting (30s de votación intensa)
    IF FOUND AND current_active.status = 'active' AND current_active.expires_at <= (NOW() + INTERVAL '1 second') THEN
        UPDATE active_challenges 
        SET status = 'voting', 
            voting_expires_at = NOW() + INTERVAL '30 seconds'
        WHERE active_challenges.id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    -- 3. Transición: Voting -> Completed (Podio, 30s para que sea épico)
    IF FOUND AND current_active.status = 'voting' AND current_active.voting_expires_at <= (NOW() + INTERVAL '1 second') THEN
        WITH top_voted AS (
            -- Agrupamos por usuario y sumamos sus votos (por si tienen varias participaciones, aunque no deberían)
            SELECT s.user_id, SUM(s.vote_count) as total_votes
            FROM challenge_submissions s
            WHERE s.active_challenge_id = current_active.id
            GROUP BY s.user_id
            HAVING SUM(s.vote_count) >= 0 -- Permitimos hasta 0 votos para llenar el podio si hay participantes
            ORDER BY total_votes DESC
            LIMIT 3
        )
        UPDATE active_challenges 
        SET status = 'completed',
            winner_ids = ARRAY(SELECT user_id FROM top_voted),
            expires_at = NOW() + INTERVAL '30 seconds' -- Duración generosa del podio
        WHERE active_challenges.id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    -- 4. Transición: Completed -> Nuevo Reto
    IF FOUND AND current_active.status = 'completed' AND current_active.expires_at <= NOW() THEN
        current_active := NULL;
    END IF;

    -- 5. Generar Reto Nuevo si no hay nada o el anterior expiró
    IF current_active IS NULL THEN
        INSERT INTO active_challenges (challenge_id, expires_at, status)
        SELECT cm.id, NOW() + INTERVAL '10 minutes', 'active'
        FROM challenges_master cm
        ORDER BY RANDOM()
        LIMIT 1
        RETURNING * INTO current_active;
    END IF;

    -- 6. Retornar los resultados
    RETURN QUERY SELECT 
        current_active.id,
        current_active.challenge_id,
        current_active.expires_at,
        current_active.voting_expires_at,
        current_active.status,
        current_active.winner_ids;
END;
$$ LANGUAGE plpgsql;

-- Asegurar permisos
GRANT EXECUTE ON FUNCTION get_or_generate_active_challenge() TO authenticated, anon;

-- Notificar recarga de esquema por si acaso
NOTIFY pgrst, 'reload schema';
