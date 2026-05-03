-- 1. Limpiar y redefinir la función RPC con SECURITY DEFINER para evitar errores 403/RLS
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
    -- 1. Buscar el último reto no completado
    SELECT ac.* INTO current_active 
    FROM active_challenges ac
    WHERE ac.status IN ('active', 'voting', 'completed')
    ORDER BY ac.created_at DESC 
    LIMIT 1;

    -- 2. Transición: Active -> Voting (30s)
    IF FOUND AND current_active.status = 'active' AND current_active.expires_at <= (NOW() + INTERVAL '3 seconds') THEN
        UPDATE active_challenges 
        SET status = 'voting', 
            voting_expires_at = NOW() + INTERVAL '30 seconds'
        WHERE active_challenges.id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    -- 3. Transición: Voting -> Completed (Podio, 5s)
    IF FOUND AND current_active.status = 'voting' AND current_active.voting_expires_at <= (NOW() + INTERVAL '3 seconds') THEN
        WITH top_voted AS (
            SELECT s.user_id, COUNT(v.id) as vote_count
            FROM challenge_submissions s
            LEFT JOIN challenge_votes v ON v.submission_id = s.id
            WHERE s.active_challenge_id = current_active.id
            GROUP BY s.user_id
            ORDER BY vote_count DESC
            LIMIT 3
        )
        UPDATE active_challenges 
        SET status = 'completed',
            winner_ids = ARRAY(SELECT user_id FROM top_voted),
            expires_at = NOW() + INTERVAL '5 seconds'
        WHERE active_challenges.id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    -- 4. Transición: Completed -> Nuevo Reto
    IF FOUND AND current_active.status = 'completed' AND current_active.expires_at <= (NOW() + INTERVAL '3 seconds') THEN
        current_active := NULL;
    END IF;

    -- 5. Generar Reto Nuevo (10 min)
    IF current_active IS NULL THEN
        INSERT INTO active_challenges (challenge_id, expires_at, status)
        SELECT cm.id, NOW() + INTERVAL '10 minutes', 'active'
        FROM challenges_master cm
        ORDER BY RANDOM()
        LIMIT 1
        RETURNING * INTO current_active;
    END IF;

    -- 6. Retornar columnas
    RETURN QUERY SELECT 
        current_active.id,
        current_active.challenge_id,
        current_active.expires_at,
        current_active.voting_expires_at,
        current_active.status,
        current_active.winner_ids;
END;
$$ LANGUAGE plpgsql;

-- Permisos
GRANT EXECUTE ON FUNCTION get_or_generate_active_challenge() TO authenticated, anon;
