-- ==========================================================
-- REPARACIÓN DEFINITIVA POSTGREST: FIX ERROR PGRST202
-- ==========================================================

-- 1. Asegurar que PostgREST encuentre la función (Debe ser pública y estar en el esquema correcto)
CREATE OR REPLACE FUNCTION public.v1_toggle_vote(id_objetivo UUID)
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
BEGIN
    IF v_voter_id IS NULL THEN
        RAISE EXCEPTION 'No autenticado';
    END IF;

    -- Obtener el active_challenge_id de la participación
    SELECT active_challenge_id INTO v_active_id 
    FROM challenge_submissions 
    WHERE id = id_objetivo;

    IF v_active_id IS NULL THEN
        RAISE EXCEPTION 'Participación no encontrada';
    END IF;

    -- Lógica de Toggle
    IF EXISTS (SELECT 1 FROM challenge_votes WHERE submission_id = id_objetivo AND voter_id = v_voter_id) THEN
        DELETE FROM challenge_votes WHERE submission_id = id_objetivo AND voter_id = v_voter_id;
        v_voted := false;
    ELSE
        INSERT INTO challenge_votes (submission_id, voter_id, active_challenge_id)
        VALUES (id_objetivo, v_voter_id, v_active_id)
        ON CONFLICT (submission_id, voter_id) DO NOTHING;
        v_voted := true;
    END IF;

    -- Recuento directo
    SELECT count(*)::int INTO v_count FROM challenge_votes WHERE submission_id = id_objetivo;
    
    -- Sincronizar columna denormalizada
    UPDATE challenge_submissions SET vote_count = v_count WHERE id = id_objetivo;
    
    RETURN jsonb_build_object(
        'vote_count', v_count,
        'voted_by_me', v_voted
    );
END;
$$;

-- 2. RECARGAR EL CACHÉ DE POSTGREST (OBLIGATORIO)
-- Esto fuerza a Supabase a reconocer la nueva función inmediatamente
NOTIFY pgrst, 'reload schema';

-- 3. PERMISOS
GRANT EXECUTE ON FUNCTION public.v1_toggle_vote(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.v1_toggle_vote(UUID) TO anon;
GRANT EXECUTE ON FUNCTION public.v1_toggle_vote(UUID) TO service_role;
