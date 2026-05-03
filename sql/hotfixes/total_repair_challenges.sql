-- ==========================================================
-- SCRIPT DE REPARACIÓN TOTAL: VOTACIÓN Y TEMPORIZADORES
-- ==========================================================

-- 1. Limpieza de funciones antiguas para evitar conflictos 42P10 (mismatch de parámetros)
DROP FUNCTION IF EXISTS public.toggle_challenge_vote(uuid);
DROP FUNCTION IF EXISTS public.toggle_challenge_vote(text);

-- 2. Nueva función de Votación ultra-robusta (Acepta TEXT para evitar errores de tipo desde el cliente)
CREATE OR REPLACE FUNCTION public.toggle_challenge_vote(p_submission_id TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_count INTEGER;
    v_voted BOOLEAN;
    v_voter_id UUID := auth.uid();
    v_active_id UUID;
    v_sub_id UUID;
BEGIN
    IF v_voter_id IS NULL THEN
        RAISE EXCEPTION 'No autenticado';
    END IF;

    -- Casteo seguro
    v_sub_id := p_submission_id::UUID;

    -- Obtener el active_challenge_id de la participación
    SELECT active_challenge_id INTO v_active_id 
    FROM challenge_submissions 
    WHERE id = v_sub_id;

    IF v_active_id IS NULL THEN
        RAISE EXCEPTION 'Participación no encontrada';
    END IF;

    -- Toggle de voto
    IF EXISTS (SELECT 1 FROM challenge_votes WHERE submission_id = v_sub_id AND voter_id = v_voter_id) THEN
        DELETE FROM challenge_votes WHERE submission_id = v_sub_id AND voter_id = v_voter_id;
        v_voted := false;
    ELSE
        INSERT INTO challenge_votes (submission_id, voter_id, active_challenge_id)
        VALUES (v_sub_id, v_voter_id, v_active_id)
        ON CONFLICT (submission_id, voter_id) DO NOTHING;
        v_voted := true;
    END IF;

    -- Recuento exacto
    SELECT count(*)::int INTO v_count FROM challenge_votes WHERE submission_id = v_sub_id;
    
    -- Sincronizar columna denormalizada
    UPDATE challenge_submissions SET vote_count = v_count WHERE id = v_sub_id;
    
    RETURN jsonb_build_object(
        'vote_count', v_count,
        'voted_by_me', v_voted
    );
END;
$$;

-- 3. Asegurar permisos
GRANT EXECUTE ON FUNCTION public.toggle_challenge_vote(text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.toggle_challenge_vote(text) TO anon;

-- 4. Reparar la función master para asegurar que voting_expires_at se genera bien
DROP FUNCTION IF EXISTS get_or_generate_active_challenge();

CREATE OR REPLACE FUNCTION public.get_or_generate_active_challenge()
RETURNS TABLE (
    id UUID,
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
    SELECT ac.* INTO current_active 
    FROM active_challenges ac
    WHERE ac.status IN ('active', 'voting', 'completed')
    ORDER BY ac.created_at DESC 
    LIMIT 1;

    -- Transición: Active -> Voting (30s)
    IF FOUND AND current_active.status = 'active' AND current_active.expires_at <= (NOW() + INTERVAL '3 seconds') THEN
        UPDATE active_challenges 
        SET status = 'voting', 
            voting_expires_at = NOW() + INTERVAL '35 seconds' -- 5s extra de margen
        WHERE active_challenges.id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    -- Transición: Voting -> Completed (5s)
    IF FOUND AND current_active.status = 'voting' AND current_active.voting_expires_at <= (NOW() + INTERVAL '2 seconds') THEN
        WITH top_voted AS (
            SELECT s.user_id, COUNT(v.id) as vc
            FROM challenge_submissions s
            LEFT JOIN challenge_votes v ON v.submission_id = s.id
            WHERE s.active_challenge_id = current_active.id
            GROUP BY s.user_id
            ORDER BY vc DESC
            LIMIT 3
        )
        UPDATE active_challenges 
        SET status = 'completed',
            winner_ids = ARRAY(SELECT user_id FROM top_voted),
            expires_at = NOW() + INTERVAL '6 seconds'
        WHERE active_challenges.id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    -- Transición: Completed -> Nuevo
    IF FOUND AND current_active.status = 'completed' AND current_active.expires_at <= (NOW() + INTERVAL '1 second') THEN
        current_active := NULL;
    END IF;

    IF current_active IS NULL THEN
        INSERT INTO active_challenges (challenge_id, expires_at, status)
        SELECT cm.id, NOW() + INTERVAL '10 minutes', 'active'
        FROM challenges_master cm
        ORDER BY RANDOM()
        LIMIT 1
        RETURNING * INTO current_active;
    END IF;

    RETURN QUERY SELECT 
        current_active.id,
        current_active.challenge_id,
        current_active.expires_at,
        current_active.voting_expires_at,
        current_active.status,
        current_active.winner_ids;
END;
$$ LANGUAGE plpgsql;

GRANT EXECUTE ON FUNCTION get_or_generate_active_challenge() TO authenticated, anon;
