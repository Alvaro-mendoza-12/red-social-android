-- ==========================================================
-- DIAGNÓSTICO Y LIMPIEZA TOTAL (MODO DIOS)
-- ==========================================================

-- 1. Identificar TODAS las funciones que causan el conflicto 42P10
-- Este bloque borra CUALQUIER función que tenga "vote" y "challenge" en el nombre
DO $$ 
DECLARE 
    r RECORD;
BEGIN
    FOR r IN (
        SELECT n.nspname as schema, p.proname as name, pg_get_function_identity_arguments(p.oid) as args
        FROM pg_proc p 
        JOIN pg_namespace n ON p.pronamespace = n.oid
        WHERE p.proname ILIKE '%vote%' 
          AND (p.proname ILIKE '%challenge%' OR p.proname ILIKE '%submission%' OR p.proname ILIKE '%v1%' OR p.proname ILIKE '%v9%' OR p.proname ILIKE '%radical%')
    ) LOOP
        BEGIN
            EXECUTE 'DROP FUNCTION ' || r.schema || '.' || r.name || '(' || r.args || ')';
            RAISE NOTICE 'Borrada: %.%(%)', r.schema, r.name, r.args;
        EXCEPTION WHEN OTHERS THEN 
            RAISE NOTICE 'No se pudo borrar %.%(%): %', r.schema, r.name, r.args, SQLERRM;
        END;
    END LOOP;
END $$;

-- 2. UNA ÚNICA FUNCIÓN, NOMBRE ÚNICO, SIN OVERLOADS
-- Usamos un nombre que NUNCA se haya usado: "app_rpc_toggle_vote"
-- Solo un parámetro, tipo TEXT para máxima compatibilidad
CREATE OR REPLACE FUNCTION public.app_rpc_toggle_vote(input_id TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_count INTEGER;
    v_voted BOOLEAN;
    v_voter_id UUID := auth.uid();
    v_sub_id UUID;
    v_active_id UUID;
BEGIN
    -- Validar auth
    IF v_voter_id IS NULL THEN
        RAISE EXCEPTION 'Usuario no autenticado';
    END IF;

    -- Validar y convertir UUID
    BEGIN
        v_sub_id := input_id::UUID;
    EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION 'ID inválido: %', input_id;
    END;

    -- Obtener datos de la participación
    SELECT active_challenge_id INTO v_active_id 
    FROM challenge_submissions 
    WHERE id = v_sub_id;

    IF v_active_id IS NULL THEN
        RAISE EXCEPTION 'No se encontró la participación con ID %', v_sub_id;
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

    -- Recuento
    SELECT count(*)::int INTO v_count FROM challenge_votes WHERE submission_id = v_sub_id;
    
    -- Sync denormalized count
    UPDATE challenge_submissions SET vote_count = v_count WHERE id = v_sub_id;
    
    RETURN jsonb_build_object(
        'vote_count', v_count,
        'voted_by_me', v_voted,
        'status', 'success'
    );
END;
$$;

-- 3. PERMISOS Y RESET DE CACHE
GRANT EXECUTE ON FUNCTION public.app_rpc_toggle_vote(TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION public.app_rpc_toggle_vote(TEXT) TO anon;

-- Forzar a PostgREST a recargar todo
NOTIFY pgrst, 'reload schema';
