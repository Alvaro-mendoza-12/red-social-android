-- ================================================================
-- CHALLENGES SYSTEM - COMPLETE FIX (MASTER SCRIPT)
-- ================================================================
-- Este script es idempotente y puede ejecutarse múltiples veces
-- Consolida TODOS los fixes anteriores en un solo lugar
-- ================================================================

-- ============================================================
-- 1. SCHEMA: Asegurar que todas las tablas tienen las columnas necesarias
-- ============================================================

-- 1.1 challenges_master: añadir creator_id si no existe
ALTER TABLE challenges_master 
ADD COLUMN IF NOT EXISTS creator_id UUID REFERENCES profiles(id) ON DELETE SET NULL;

-- 1.2 challenge_submissions: añadir vote_count si no existe
ALTER TABLE challenge_submissions 
ADD COLUMN IF NOT EXISTS vote_count INTEGER DEFAULT 0;

-- ============================================================
-- 2. TRIGGERS: Auto-actualizar vote_count cuando cambian votos
-- ============================================================

-- 2.1 Función del trigger
CREATE OR REPLACE FUNCTION update_challenge_vote_count()
RETURNS TRIGGER
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    IF (TG_OP = 'INSERT') THEN
        UPDATE challenge_submissions 
        SET vote_count = vote_count + 1 
        WHERE id = NEW.submission_id;
        RETURN NEW;
    ELSIF (TG_OP = 'DELETE') THEN
        UPDATE challenge_submissions 
        SET vote_count = GREATEST(0, vote_count - 1) 
        WHERE id = OLD.submission_id;
        RETURN OLD;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- 2.2 Aplicar trigger
DROP TRIGGER IF EXISTS tr_update_challenge_vote_count ON challenge_votes;
CREATE TRIGGER tr_update_challenge_vote_count
AFTER INSERT OR DELETE ON challenge_votes
FOR EACH ROW EXECUTE FUNCTION update_challenge_vote_count();

-- 2.3 Sincronizar vote_count con datos actuales
UPDATE challenge_submissions s
SET vote_count = (
    SELECT COUNT(*) 
    FROM challenge_votes v 
    WHERE v.submission_id = s.id
);

-- ============================================================
-- 3. RPCs: Funciones de PostgreSQL
-- ============================================================

-- 3.1 Toggle Vote (CRÍTICO: Debe retornar JSONB válido)
DROP FUNCTION IF EXISTS toggle_challenge_vote(UUID);

CREATE OR REPLACE FUNCTION toggle_challenge_vote(p_submission_id UUID)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_count INTEGER;
    v_voted BOOLEAN;
    v_voter_id UUID := auth.uid();
    v_active_challenge_id UUID;
BEGIN
    -- Verificar autenticación
    IF v_voter_id IS NULL THEN
        RAISE EXCEPTION 'User not authenticated';
    END IF;

    -- Obtener el active_challenge_id de la participación
    SELECT active_challenge_id INTO v_active_challenge_id 
    FROM challenge_submissions 
    WHERE id = p_submission_id;
    
    IF v_active_challenge_id IS NULL THEN
        RAISE EXCEPTION 'Submission not found';
    END IF;

    -- Toggle: si existe el voto, eliminarlo; si no, crearlo
    IF EXISTS (
        SELECT 1 FROM challenge_votes 
        WHERE submission_id = p_submission_id AND voter_id = v_voter_id
    ) THEN
        -- Eliminar voto
        DELETE FROM challenge_votes 
        WHERE submission_id = p_submission_id AND voter_id = v_voter_id;
        v_voted := false;
    ELSE
        -- Insertar voto (el UNIQUE constraint asegura 1 voto por usuario por challenge)
        INSERT INTO challenge_votes (submission_id, voter_id, active_challenge_id)
        VALUES (p_submission_id, v_voter_id, v_active_challenge_id)
        ON CONFLICT (active_challenge_id, voter_id) 
        DO UPDATE SET submission_id = p_submission_id;
        v_voted := true;
    END IF;

    -- Obtener el conteo actualizado
    SELECT vote_count INTO v_count 
    FROM challenge_submissions 
    WHERE id = p_submission_id;
    
    -- IMPORTANTE: Retornar JSONB válido SIEMPRE
    RETURN jsonb_build_object(
        'vote_count', COALESCE(v_count, 0),
        'voted_by_me', v_voted
    );
END;
$$;

-- 3.2 Get Submissions with Vote Status
DROP FUNCTION IF EXISTS get_challenge_submissions_voted(UUID);

CREATE OR REPLACE FUNCTION get_challenge_submissions_voted(p_active_challenge_id UUID)
RETURNS TABLE (
    id UUID,
    challenge_id UUID,
    active_challenge_id UUID,
    user_id UUID,
    content_url TEXT,
    text_content TEXT,
    vote_count INTEGER,
    created_at TIMESTAMP WITH TIME ZONE,
    voted_by_me BOOLEAN,
    user_data JSONB
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    RETURN QUERY
    SELECT 
        s.id,
        s.challenge_id,
        s.active_challenge_id,
        s.user_id,
        s.content_url,
        s.text_content,
        s.vote_count,
        s.created_at,
        EXISTS (
            SELECT 1 FROM challenge_votes v 
            WHERE v.submission_id = s.id AND v.voter_id = auth.uid()
        ) as voted_by_me,
        to_jsonb(p.*) as user_data
    FROM challenge_submissions s
    LEFT JOIN profiles p ON p.id = s.user_id
    WHERE s.active_challenge_id = p_active_challenge_id
    ORDER BY s.vote_count DESC, s.created_at DESC;
END;
$$;

-- 3.3 Get or Generate Active Challenge
DROP FUNCTION IF EXISTS get_or_generate_active_challenge();

CREATE OR REPLACE FUNCTION get_or_generate_active_challenge()
RETURNS SETOF active_challenges
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    current_active active_challenges;
BEGIN
    -- 1. Buscar reto actual
    SELECT ac.* INTO current_active 
    FROM active_challenges ac
    WHERE ac.status IN ('active', 'voting', 'completed')
    ORDER BY ac.created_at DESC 
    LIMIT 1;

    -- 2. Transición: Active -> Voting (30s)
    IF FOUND AND current_active.status = 'active' AND current_active.expires_at <= (NOW() + INTERVAL '3 seconds') THEN
        UPDATE active_challenges 
        SET status = 'voting', 
            voting_expires_at = NOW() + INTERVAL '30 seconds'
        WHERE active_challenges.id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    -- 3. Transición: Voting -> Completed (Podio, 5s)
    IF FOUND AND current_active.status = 'voting' AND current_active.voting_expires_at <= (NOW() + INTERVAL '3 seconds') THEN
        WITH top_voted AS (
            SELECT s.user_id, SUM(s.vote_count) as total_votes
            FROM challenge_submissions s
            WHERE s.active_challenge_id = current_active.id
            GROUP BY s.user_id
            ORDER BY total_votes DESC
            LIMIT 3
        )
        UPDATE active_challenges 
        SET status = 'completed',
            winner_ids = ARRAY(SELECT user_id FROM top_voted),
            expires_at = NOW() + INTERVAL '5 seconds'
        WHERE active_challenges.id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    -- 4. Transición: Completed -> Nuevo Reto
    IF FOUND AND current_active.status = 'completed' AND current_active.expires_at <= (NOW() + INTERVAL '3 seconds') THEN
        current_active := NULL;
    END IF;

    -- 5. Generar Reto Nuevo (10 min)
    IF current_active IS NULL THEN
        INSERT INTO active_challenges (challenge_id, expires_at, status)
        SELECT cm.id, NOW() + INTERVAL '10 minutes', 'active'
        FROM challenges_master cm
        ORDER BY RANDOM()
        LIMIT 1
        RETURNING * INTO current_active;
    END IF;

    RETURN NEXT current_active;
END;
$$;

-- ============================================================
-- 4. RLS POLICIES
-- ============================================================

-- 4.1 challenges_master
ALTER TABLE challenges_master ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Everyone can view challenges" ON challenges_master;
CREATE POLICY "Everyone can view challenges" ON challenges_master
FOR SELECT USING (true);

DROP POLICY IF EXISTS "Authenticated users can create challenges" ON challenges_master;
CREATE POLICY "Authenticated users can create challenges" ON challenges_master
FOR INSERT TO authenticated
WITH CHECK (auth.uid() IS NOT NULL);

-- 4.2 active_challenges
ALTER TABLE active_challenges ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Everyone can view active challenges" ON active_challenges;
CREATE POLICY "Everyone can view active challenges" ON active_challenges
FOR SELECT USING (true);

DROP POLICY IF EXISTS "System can manage active challenges" ON active_challenges;
CREATE POLICY "System can manage active challenges" ON active_challenges
FOR ALL USING (true) WITH CHECK (true);

-- 4.3 challenge_submissions
ALTER TABLE challenge_submissions ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Everyone can view submissions" ON challenge_submissions;
CREATE POLICY "Everyone can view submissions" ON challenge_submissions
FOR SELECT USING (true);

DROP POLICY IF EXISTS "Users can create their submissions" ON challenge_submissions;
CREATE POLICY "Users can create their submissions" ON challenge_submissions
FOR INSERT TO authenticated
WITH CHECK (auth.uid() = user_id);

-- 4.4 challenge_votes
ALTER TABLE challenge_votes ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Everyone can view votes" ON challenge_votes;
CREATE POLICY "Everyone can view votes" ON challenge_votes
FOR SELECT USING (true);

DROP POLICY IF EXISTS "Users can insert their votes" ON challenge_votes;
CREATE POLICY "Users can insert their votes" ON challenge_votes
FOR INSERT TO authenticated
WITH CHECK (auth.uid() = voter_id);

DROP POLICY IF EXISTS "Users can delete their votes" ON challenge_votes;
CREATE POLICY "Users can delete their votes" ON challenge_votes
FOR DELETE TO authenticated
USING (auth.uid() = voter_id);

-- ============================================================
-- 5. GRANTS (Permisos de ejecución)
-- ============================================================

GRANT EXECUTE ON FUNCTION toggle_challenge_vote(UUID) TO authenticated, anon;
GRANT EXECUTE ON FUNCTION get_challenge_submissions_voted(UUID) TO authenticated, anon;
GRANT EXECUTE ON FUNCTION get_or_generate_active_challenge() TO authenticated, anon;

-- ============================================================
-- 6. REALTIME
-- ============================================================

-- Asegurar que todas las tablas están en la publicación de Realtime
DO $$
BEGIN
    -- active_challenges
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' 
        AND tablename = 'active_challenges'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE active_challenges;
    END IF;
    
    -- challenge_submissions
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' 
        AND tablename = 'challenge_submissions'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE challenge_submissions;
    END IF;
    
    -- challenge_votes
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' 
        AND tablename = 'challenge_votes'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE challenge_votes;
    END IF;
END $$;

-- ============================================================
-- 7. VERIFICACIÓN (Comentado - descomentar para debug)
-- ============================================================

-- SELECT 'Schema verification' as step;
-- SELECT column_name, data_type FROM information_schema.columns 
-- WHERE table_name = 'challenge_submissions' AND column_name = 'vote_count';

-- SELECT 'RPC verification' as step;
-- SELECT routine_name, routine_type FROM information_schema.routines 
-- WHERE routine_name LIKE '%challenge%';

-- SELECT 'Realtime verification' as step;
-- SELECT tablename FROM pg_publication_tables WHERE pubname = 'supabase_realtime';
