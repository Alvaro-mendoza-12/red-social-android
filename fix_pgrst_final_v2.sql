-- ==========================================================
-- REPARACIÓN DEFINITIVA PGRST202: SINCRONIZACIÓN TOTAL
-- ==========================================================

-- 1. ASEGURAR ESQUEMA PÚBLICO Y PARÁMETROS EXPLÍCITOS
-- PostgREST a veces falla si el esquema no está explícito en la creación
CREATE OR REPLACE FUNCTION public.confirm_toggle_vote_v2(p_id_text text)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_count INTEGER;
    v_voted BOOLEAN;
    v_voter_id UUID := auth.uid();
    v_target_id UUID;
    v_active_id UUID;
BEGIN
    IF v_voter_id IS NULL THEN
        RAISE EXCEPTION 'No autenticado';
    END IF;

    -- Convertir texto a UUID
    BEGIN
        v_target_id := p_id_text::UUID;
    EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION 'ID de participación inválido';
    END;

    -- Obtener el active_challenge_id
    SELECT active_challenge_id INTO v_active_id 
    FROM challenge_submissions 
    WHERE id = v_target_id;

    IF v_active_id IS NULL THEN
        RAISE EXCEPTION 'Participación no encontrada';
    END IF;

    -- Lógica de Toggle
    IF EXISTS (SELECT 1 FROM challenge_votes WHERE submission_id = v_target_id AND voter_id = v_voter_id) THEN
        DELETE FROM challenge_votes WHERE submission_id = v_target_id AND voter_id = v_voter_id;
        v_voted := false;
    ELSE
        INSERT INTO challenge_votes (submission_id, voter_id, active_challenge_id)
        VALUES (v_target_id, v_voter_id, v_active_id)
        ON CONFLICT (submission_id, voter_id) DO NOTHING;
        v_voted := true;
    END IF;

    -- Recuento
    SELECT count(*)::int INTO v_count FROM challenge_votes WHERE submission_id = v_target_id;
    UPDATE challenge_submissions SET vote_count = v_count WHERE id = v_target_id;
    
    RETURN jsonb_build_object(
        'vote_count', v_count,
        'voted_by_me', v_voted
    );
END;
$$;

-- 2. PERMISOS TOTALES
GRANT USAGE ON SCHEMA public TO anon, authenticated;
GRANT EXECUTE ON FUNCTION public.confirm_toggle_vote_v2(text) TO authenticated;
GRANT EXECUTE ON FUNCTION public.confirm_toggle_vote_v2(text) TO anon;

-- 3. FORZAR RECARGA DEL ESQUEMA (Paso Vital para PGRST202)
-- Esto "despierta" a la API para que vea la nueva función
NOTIFY pgrst, 'reload schema';
SELECT pg_sleep(1); -- Esperar un segundo para que la caché se limpie
NOTIFY pgrst, 'reload schema';
