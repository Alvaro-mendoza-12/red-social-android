-- Eliminar la función anterior para poder cambiar la firma del retorno
DROP FUNCTION IF EXISTS get_or_generate_active_challenge();

-- Función mejorada que retorna el challenge completo con JOIN
CREATE OR REPLACE FUNCTION get_or_generate_active_challenge()
RETURNS TABLE (
    active_id UUID,
    challenge_id UUID,
    challenge JSON,
    expires_at TIMESTAMP WITH TIME ZONE,
    voting_expires_at TIMESTAMP WITH TIME ZONE,
    status TEXT,
    winner_ids UUID[]
) AS $$
DECLARE
    current_active active_challenges;
    challenge_data JSON;
BEGIN
    -- 1. Buscar reto más reciente en cualquier fase (SIN filtro de tiempo)
    -- Esto permite que las transiciones ocurran correctamente
    SELECT ac.* INTO current_active 
    FROM active_challenges ac
    WHERE ac.status IN ('active', 'voting', 'completed')
    ORDER BY ac.created_at DESC 
    LIMIT 1;

    -- 2. Si hay un reto 'active' que ya expiró, pasarlo a 'voting' (30 seg)
    IF FOUND AND current_active.status = 'active' AND current_active.expires_at <= NOW() THEN
        UPDATE active_challenges 
        SET status = 'voting', 
            voting_expires_at = NOW() + INTERVAL '30 seconds'
        WHERE id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    -- 3. Si hay un reto 'voting' que ya expiró, pasar a 'completed' con 5 seg de PODIO
    IF FOUND AND current_active.status = 'voting' AND current_active.voting_expires_at <= NOW() THEN
        -- Calcular Top 3
        WITH top_voted AS (
            SELECT s.user_id, COUNT(*) as vote_count
            FROM challenge_submissions s
            JOIN challenge_votes v ON v.submission_id = s.id
            WHERE s.active_challenge_id = current_active.id
            GROUP BY s.user_id
            ORDER BY vote_count DESC
            LIMIT 3
        )
        UPDATE active_challenges 
        SET status = 'completed',
            winner_ids = ARRAY(SELECT user_id FROM top_voted),
            expires_at = NOW() + INTERVAL '5 seconds' -- DURACIÓN DEL PODIO
        WHERE id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    -- 4. Generar uno nuevo si no hay nada activo/voting/completed (o ya expiró el completed)
    IF current_active IS NULL OR (current_active.status = 'completed' AND current_active.expires_at <= NOW()) THEN
        INSERT INTO active_challenges (challenge_id, expires_at)
        SELECT id, NOW() + INTERVAL '10 minutes'
        FROM challenges_master
        ORDER BY RANDOM()
        LIMIT 1
        RETURNING * INTO current_active;
    END IF;

    -- 5. Construir JSON del challenge
    SELECT jsonb_build_object(
        'id', cm.id::TEXT,
        'title', cm.title,
        'description', cm.description,
        'challengeType', cm.challenge_type,
        'difficulty', cm.difficulty
    ) INTO challenge_data
    FROM challenges_master cm
    WHERE cm.id = current_active.challenge_id;

    -- 6. Retornar con challenge incluido
    RETURN QUERY SELECT 
        current_active.id,
        current_active.challenge_id,
        challenge_data,
        current_active.expires_at,
        current_active.voting_expires_at,
        current_active.status,
        current_active.winner_ids;
END;
$$ LANGUAGE plpgsql;
