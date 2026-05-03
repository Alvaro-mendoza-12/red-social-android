-- ==========================================================
-- REPARACIÓN NUCLEAR: ELIMINACIÓN DE TODA AMBIGÜEDAD (42P10)
-- ==========================================================

-- 1. LIMPIEZA TOTAL: Borramos cualquier rastro de funciones de votación anteriores
DO $$ 
DECLARE 
    r RECORD;
BEGIN
    -- Borrar cualquier función que se llame toggle_challenge_vote
    FOR r IN (SELECT oid::regprocedure as name FROM pg_proc WHERE proname = 'toggle_challenge_vote') LOOP
        BEGIN
            EXECUTE 'DROP FUNCTION ' || r.name;
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'No se pudo borrar %', r.name;
        END;
    END LOOP;

    -- Borrar cualquier función que se llame cast_vote_submission
    FOR r IN (SELECT oid::regprocedure as name FROM pg_proc WHERE proname = 'cast_vote_submission') LOOP
        BEGIN
            EXECUTE 'DROP FUNCTION ' || r.name;
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'No se pudo borrar %', r.name;
        END;
    END LOOP;
END $$;

-- 2. CREAR LA FUNCIÓN DEFINITIVA CON UN NOMBRE ÚNICO Y PARÁMETRO TEXT (Para evitar choques de tipos)
CREATE OR REPLACE FUNCTION public.execute_submission_vote(p_submission_id TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
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

    -- Casteo explícito a UUID para evitar errores de tipo internamente
    v_sub_id := p_submission_id::UUID;

    -- Obtener el active_challenge_id de la participación
    SELECT active_challenge_id INTO v_active_id 
    FROM challenge_submissions 
    WHERE id = v_sub_id;

    IF v_active_id IS NULL THEN
        RAISE EXCEPTION 'Participación no encontrada';
    END IF;

    -- Lógica de Toggle
    IF EXISTS (SELECT 1 FROM challenge_votes WHERE submission_id = v_sub_id AND voter_id = v_voter_id) THEN
        DELETE FROM challenge_votes WHERE submission_id = v_sub_id AND voter_id = v_voter_id;
        v_voted := false;
    ELSE
        INSERT INTO challenge_votes (submission_id, voter_id, active_challenge_id)
        VALUES (v_sub_id, v_voter_id, v_active_id)
        ON CONFLICT (submission_id, voter_id) DO NOTHING;
        v_voted := true;
    END IF;

    -- Recuento directo de la tabla de votos para precisión total
    SELECT count(*)::int INTO v_count FROM challenge_votes WHERE submission_id = v_sub_id;
    
    -- Actualizar columna para Realtime
    UPDATE challenge_submissions SET vote_count = v_count WHERE id = v_sub_id;
    
    RETURN jsonb_build_object(
        'vote_count', v_count,
        'voted_by_me', v_voted
    );
END;
$$;

-- 3. PERMISOS
GRANT EXECUTE ON FUNCTION public.execute_submission_vote(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.execute_submission_vote(TEXT) TO anon;
