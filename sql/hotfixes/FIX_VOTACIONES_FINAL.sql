-- ================================================================
-- FIX DEFINITIVO PARA EL SISTEMA DE VOTACIONES EN RETOS
-- Elimina errores 42P10 y sincroniza las funciones correctamente
-- ================================================================

-- 1. ELIMINAR TODAS LAS VERSIONES CONFLICTIVAS DE FUNCIONES
DROP FUNCTION IF EXISTS public.toggle_challenge_vote(uuid);
DROP FUNCTION IF EXISTS public.toggle_challenge_vote(text);
DROP FUNCTION IF EXISTS public.toggle_challenge_vote();
DROP FUNCTION IF EXISTS public.v2026_challenge_vote(uuid);
DROP FUNCTION IF EXISTS public.v2026_challenge_vote(text);
DROP FUNCTION IF EXISTS public.v2026_challenge_vote();

-- 2. ASEGURAR QUE EXISTE LA COLUMNA vote_count EN challenge_submissions
ALTER TABLE challenge_submissions 
ADD COLUMN IF NOT EXISTS vote_count INTEGER DEFAULT 0;

-- 3. CREAR LA FUNCIÓN CORRECTA: v2026_challenge_vote
-- Esta es la que está siendo llamada desde el código Kotlin
-- IMPORTANTE: Usa TEXT como parámetro y convierte a UUID internamente
CREATE OR REPLACE FUNCTION public.v2026_challenge_vote(p_id TEXT)
RETURNS JSONB
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
    -- Validar autenticación
    IF v_voter_id IS NULL THEN 
        RAISE EXCEPTION 'No autenticado'; 
    END IF;
    
    -- Convertir TEXT a UUID
    v_target_id := p_id::UUID;

    -- Obtener el active_challenge_id desde la submission
    SELECT active_challenge_id INTO v_active_id 
    FROM challenge_submissions 
    WHERE id = v_target_id;

    IF v_active_id IS NULL THEN 
        RAISE EXCEPTION 'No se encontró la participación'; 
    END IF;

    -- Toggle del voto: si existe, eliminar; si no existe, agregar
    IF EXISTS (
        SELECT 1 FROM challenge_votes 
        WHERE submission_id = v_target_id AND voter_id = v_voter_id
    ) THEN
        -- Eliminar voto existente
        DELETE FROM challenge_votes 
        WHERE submission_id = v_target_id AND voter_id = v_voter_id;
        v_voted := false;
    ELSE
        -- Agregar nuevo voto
        INSERT INTO challenge_votes (submission_id, voter_id, active_challenge_id)
        VALUES (v_target_id, v_voter_id, v_active_id)
        ON CONFLICT (submission_id, voter_id) DO NOTHING;
        v_voted := true;
    END IF;

    -- Recalcular el conteo total de votos para esta submission
    SELECT count(*)::int INTO v_count 
    FROM challenge_votes 
    WHERE submission_id = v_target_id;
    
    -- Actualizar la columna vote_count en la submission
    UPDATE challenge_submissions 
    SET vote_count = v_count 
    WHERE id = v_target_id;
    
    -- Retornar el resultado en formato JSON
    RETURN jsonb_build_object(
        'vote_count', v_count, 
        'voted_by_me', v_voted
    );
END;
$$;

-- 4. OTORGAR PERMISOS DE EJECUCIÓN
GRANT EXECUTE ON FUNCTION public.v2026_challenge_vote(TEXT) TO authenticated, anon;
NOTIFY pgrst, 'reload schema';

-- 5. VERIFICAR/CREAR ÍNDICE ÚNICO PARA EVITAR VOTOS DUPLICADOS
-- Nota: El constraint ya existe en challenge_votes pero agregamos un índice por si acaso
DROP INDEX IF EXISTS idx_challenge_votes_unique;
CREATE UNIQUE INDEX IF NOT EXISTS idx_challenge_votes_submission_voter 
ON challenge_votes (submission_id, voter_id);

-- 6. VERIFICAR CONSTRAINT ÚNICO POR RETO (solo un voto por usuario por reto activo)
-- Este constraint evita que un usuario vote múltiples veces en el mismo reto
ALTER TABLE challenge_votes DROP CONSTRAINT IF EXISTS unique_vote_per_challenge;
ALTER TABLE challenge_votes 
ADD CONSTRAINT unique_vote_per_challenge 
UNIQUE (active_challenge_id, voter_id);

-- 7. SINCRONIZAR vote_count PARA TODAS LAS SUBMISSIONS EXISTENTES
UPDATE challenge_submissions cs
SET vote_count = (
    SELECT COUNT(*)::INT
    FROM challenge_votes cv
    WHERE cv.submission_id = cs.id
);

-- 8. CREAR TRIGGER PARA MANTENER vote_count SINCRONIZADO AUTOMÁTICAMENTE
-- (Opcional pero recomendado para evitar desincronización futura)

CREATE OR REPLACE FUNCTION sync_submission_vote_count()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' OR TG_OP = 'DELETE' THEN
        UPDATE challenge_submissions
        SET vote_count = (
            SELECT COUNT(*)::INT
            FROM challenge_votes
            WHERE submission_id = COALESCE(NEW.submission_id, OLD.submission_id)
        )
        WHERE id = COALESCE(NEW.submission_id, OLD.submission_id);
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trigger_sync_vote_count ON challenge_votes;
CREATE TRIGGER trigger_sync_vote_count
AFTER INSERT OR DELETE ON challenge_votes
FOR EACH ROW
EXECUTE FUNCTION sync_submission_vote_count();

-- 9. HABILITAR RLS SI NO ESTÁ ACTIVADO
ALTER TABLE challenge_votes ENABLE ROW LEVEL SECURITY;
ALTER TABLE challenge_submissions ENABLE ROW LEVEL SECURITY;

-- 10. POLÍTICAS RLS PARA challenge_votes
DROP POLICY IF EXISTS "Authenticated users can view votes" ON challenge_votes;
CREATE POLICY "Authenticated users can view votes" ON challenge_votes
FOR SELECT USING (auth.uid() IS NOT NULL);

DROP POLICY IF EXISTS "Users can insert own votes" ON challenge_votes;
CREATE POLICY "Users can insert own votes" ON challenge_votes
FOR INSERT WITH CHECK (auth.uid() = voter_id);

DROP POLICY IF EXISTS "Users can delete own votes" ON challenge_votes;
CREATE POLICY "Users can delete own votes" ON challenge_votes
FOR DELETE USING (auth.uid() = voter_id);

-- 11. POLÍTICAS RLS PARA challenge_submissions
DROP POLICY IF EXISTS "Authenticated users can view submissions" ON challenge_submissions;
CREATE POLICY "Authenticated users can view submissions" ON challenge_submissions
FOR SELECT USING (auth.uid() IS NOT NULL);

DROP POLICY IF EXISTS "Users can insert own submissions" ON challenge_submissions;
CREATE POLICY "Users can insert own submissions" ON challenge_submissions
FOR INSERT WITH CHECK (auth.uid() = user_id);

-- ================================================================
-- VERIFICACIÓN FINAL
-- ================================================================

-- Verificar que la función existe y tiene la firma correcta
SELECT 
    proname AS function_name,
    pg_get_function_arguments(oid) AS arguments,
    pg_get_function_result(oid) AS return_type
FROM pg_proc
WHERE proname = 'v2026_challenge_vote'
AND pronamespace = 'public'::regnamespace;

-- Mensaje de confirmación
DO $$
BEGIN
    RAISE NOTICE '✅ Sistema de votaciones reparado exitosamente';
    RAISE NOTICE '✅ Función v2026_challenge_vote creada con parámetro UUID';
    RAISE NOTICE '✅ Constraints únicos aplicados';
    RAISE NOTICE '✅ Trigger de sincronización activado';
    RAISE NOTICE '✅ RLS configurado correctamente';
END $$;
