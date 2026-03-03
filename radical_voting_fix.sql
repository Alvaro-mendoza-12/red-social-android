-- ==========================================================
-- REPARACIÓN DEFINITIVA: ELIMINACIÓN DE AMBIGÜEDAD (42P10)
-- ==========================================================

-- 1. LIMPIEZA RADICAL: Borramos cualquier rastro de funciones previas
-- Borramos por nombre sin importar los parámetros
DO $$ 
DECLARE 
    r RECORD;
BEGIN
    FOR r IN (SELECT oid::regprocedure as name FROM pg_proc WHERE proname IN (
        'v9_super_toggle',
        'confirm_toggle_vote_v2',
        'v1_toggle_vote',
        'execute_submission_vote',
        'cast_vote_submission',
        'toggle_challenge_vote',
        'challenge_v3_vote_toggle'
    )) LOOP
        BEGIN
            EXECUTE 'DROP FUNCTION ' || r.name;
        EXCEPTION WHEN OTHERS THEN 
            RAISE NOTICE 'No se pudo borrar %', r.name;
        END;
    END LOOP;
END $$;

-- 2. CREACIÓN DE LA FUNCIÓN CON NOMBRE ÚNICO Y PARÁMETRO TEXT
CREATE OR REPLACE FUNCTION public.challenge_v3_vote_toggle(target_id_text TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_count INTEGER;
    v_voted BOOLEAN;
    v_voter_id UUID := auth.uid();
    v_target_uuid UUID;
    v_active_id UUID;
BEGIN
    IF v_voter_id IS NULL THEN
        RAISE EXCEPTION 'No autenticado';
    END IF;

    -- Convertir a UUID
    v_target_uuid := target_id_text::UUID;

    -- Obtener el active_challenge_id
    SELECT active_challenge_id INTO v_active_id 
    FROM challenge_submissions 
    WHERE id = v_target_uuid;

    IF v_active_id IS NULL THEN
        RAISE EXCEPTION 'Participación no encontrada';
    END IF;

    -- Toggle de voto
    IF EXISTS (SELECT 1 FROM challenge_votes WHERE submission_id = v_target_uuid AND voter_id = v_voter_id) THEN
        DELETE FROM challenge_votes WHERE submission_id = v_target_uuid AND voter_id = v_voter_id;
        v_voted := false;
    ELSE
        INSERT INTO challenge_votes (submission_id, voter_id, active_challenge_id)
        VALUES (v_target_uuid, v_voter_id, v_active_id)
        ON CONFLICT (submission_id, voter_id) DO NOTHING;
        v_voted := true;
    END IF;

    -- Recuento exacto
    SELECT count(*)::int INTO v_count FROM challenge_votes WHERE submission_id = v_target_uuid;
    UPDATE challenge_submissions SET vote_count = v_count WHERE id = v_target_uuid;
    
    RETURN jsonb_build_object(
        'vote_count', v_count,
        'voted_by_me', v_voted
    );
END;
$$;

-- 3. PERMISOS Y RECARGA
GRANT EXECUTE ON FUNCTION public.challenge_v3_vote_toggle(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.challenge_v3_vote_toggle(TEXT) TO anon;
NOTIFY pgrst, 'reload schema';
