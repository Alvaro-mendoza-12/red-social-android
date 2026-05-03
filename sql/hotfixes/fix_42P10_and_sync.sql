-- ==========================================================
-- REPARACIÓN DEFINITIVA: ELIMINACIÓN DE DUPLICADOS Y FIX 42P10
-- ==========================================================

-- 1. ELIMINAR TODAS LAS VERSIONES POSIBLES PARA LIMPIAR EL ERROR 42P10
-- Intentamos borrar todas las firmas conocidas
DROP FUNCTION IF EXISTS public.toggle_challenge_vote(uuid);
DROP FUNCTION IF EXISTS public.toggle_challenge_vote(text);
DROP FUNCTION IF EXISTS public.toggle_challenge_vote();

-- 2. RE-CREAR CON UNA ÚNICA FIRMA (UUID es lo más limpio para Supabase)
CREATE OR REPLACE FUNCTION public.toggle_challenge_vote(p_submission_id UUID)
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

    -- Recuento exacto por integridad
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
GRANT EXECUTE ON FUNCTION public.toggle_challenge_vote(uuid) TO authenticated;
GRANT EXECUTE ON FUNCTION public.toggle_challenge_vote(uuid) TO anon;

-- 3. REPARAR EL RETO ACTIVO (Asegurar que siempre devuelve datos válidos)
DROP FUNCTION IF EXISTS public.get_or_generate_active_challenge();

CREATE OR REPLACE FUNCTION public.get_or_generate_active_challenge()
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
    SELECT ac.* INTO current_active 
    FROM active_challenges ac
    WHERE ac.status IN ('active', 'voting', 'completed')
    ORDER BY ac.created_at DESC 
    LIMIT 1;

    -- Margen de seguridad para transiciones (3 segundos antes de expirar)
    IF FOUND AND current_active.status = 'active' AND current_active.expires_at <= (NOW() + INTERVAL '3 seconds') THEN
        UPDATE active_challenges 
        SET status = 'voting', 
            voting_expires_at = NOW() + INTERVAL '35 seconds'
        WHERE id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    IF FOUND AND current_active.status = 'voting' AND current_active.voting_expires_at <= (NOW() + INTERVAL '2 seconds') THEN
        UPDATE active_challenges 
        SET status = 'completed',
            expires_at = NOW() + INTERVAL '7 seconds'
        WHERE id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    IF FOUND AND current_active.status = 'completed' AND current_active.expires_at <= (NOW() + INTERVAL '1 second') THEN
        current_active := NULL;
    END IF;

    IF current_active IS NULL THEN
        INSERT INTO active_challenges (challenge_id, expires_at, status)
        SELECT cm.id, NOW() + INTERVAL '5 minutes', 'active'
        FROM challenges_master cm
        ORDER BY RANDOM()
        LIMIT 1
        RETURNING * INTO current_active;
    END IF;

    RETURN QUERY SELECT 
        current_active.id as active_id,
        current_active.challenge_id,
        current_active.expires_at,
        current_active.voting_expires_at,
        current_active.status,
        current_active.winner_ids;
END;
$$ LANGUAGE plpgsql;

GRANT EXECUTE ON FUNCTION get_or_generate_active_challenge() TO authenticated, anon;
