-- Fix for voting accuracy and participation check robustness
CREATE OR REPLACE FUNCTION toggle_challenge_vote(p_submission_id UUID)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_count INTEGER;
    v_voted BOOLEAN;
    v_voter_id UUID := auth.uid();
    v_active_challenge_id UUID;
BEGIN
    IF v_voter_id IS NULL THEN
        RAISE EXCEPTION 'No autenticado';
    END IF;

    -- Obtener el active_challenge_id de la participación
    SELECT active_challenge_id INTO v_active_challenge_id 
    FROM challenge_submissions 
    WHERE id = p_submission_id;

    IF EXISTS (SELECT 1 FROM challenge_votes WHERE submission_id = p_submission_id AND voter_id = v_voter_id) THEN
        DELETE FROM challenge_votes WHERE submission_id = p_submission_id AND voter_id = v_voter_id;
        v_voted := false;
    ELSE
        INSERT INTO challenge_votes (submission_id, voter_id, active_challenge_id)
        VALUES (p_submission_id, v_voter_id, v_active_challenge_id)
        ON CONFLICT (submission_id, voter_id) DO NOTHING; -- Evitar duplicados por clicks rápidos
        v_voted := true;
    END IF;

    -- IMPORTANTE: Contar directamente de la tabla de votos para máxima precisión en el retorno
    SELECT count(*)::int INTO v_count FROM challenge_votes WHERE submission_id = p_submission_id;
    
    -- Actualizar el contador denormalizado para el feed (aunque el trigger también lo hace)
    UPDATE challenge_submissions SET vote_count = v_count WHERE id = p_submission_id;
    
    RETURN jsonb_build_object(
        'vote_count', v_count,
        'voted_by_me', v_voted
    );
END;
$$;
