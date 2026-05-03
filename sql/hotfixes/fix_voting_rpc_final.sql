-- 1. Agresiva limpieza de cualquier rastro de la función anterior
DO $$ 
DECLARE 
    r RECORD;
BEGIN
    FOR r IN (SELECT oid::regprocedure as name FROM pg_proc WHERE proname = 'toggle_challenge_vote') LOOP
        EXECUTE 'DROP FUNCTION ' || r.name;
    END LOOP;
END $$;

-- 2. Crear la función con un nombre LIGERAMENTE distinto para evitar el caché de PostgREST
CREATE OR REPLACE FUNCTION public.cast_vote_submission(p_submission_id UUID)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_count INTEGER;
    v_voted BOOLEAN;
    v_voter_id UUID := auth.uid();
    v_active_id UUID;
BEGIN
    IF v_voter_id IS NULL THEN
        RAISE EXCEPTION 'No autenticado';
    END IF;

    -- Obtener el active_challenge_id
    SELECT active_challenge_id INTO v_active_id 
    FROM challenge_submissions 
    WHERE id = p_submission_id;

    IF v_active_id IS NULL THEN
        RAISE EXCEPTION 'Participación no encontrada';
    END IF;

    -- Toggle de voto
    IF EXISTS (SELECT 1 FROM challenge_votes WHERE submission_id = p_submission_id AND voter_id = v_voter_id) THEN
        DELETE FROM challenge_votes WHERE submission_id = p_submission_id AND voter_id = v_voter_id;
        v_voted := false;
    ELSE
        INSERT INTO challenge_votes (submission_id, voter_id, active_challenge_id)
        VALUES (p_submission_id, v_voter_id, v_active_id)
        ON CONFLICT (submission_id, voter_id) DO NOTHING;
        v_voted := true;
    END IF;

    -- Recuento exacto
    SELECT count(*)::int INTO v_count FROM challenge_votes WHERE submission_id = p_submission_id;
    
    -- Sincronizar columna
    UPDATE challenge_submissions SET vote_count = v_count WHERE id = p_submission_id;
    
    RETURN jsonb_build_object(
        'vote_count', v_count,
        'voted_by_me', v_voted
    );
END;
$$;

-- Permisos
GRANT EXECUTE ON FUNCTION public.cast_vote_submission(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.cast_vote_submission(uuid) TO anon;
