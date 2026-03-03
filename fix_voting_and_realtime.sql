-- ==========================================================
-- SCRIPT DEFINITIVO DE VOTACIÓN Y REALTIME (v5 FINAL)
-- ==========================================================

-- 1. Asegurar columna de conteo
ALTER TABLE challenge_submissions ADD COLUMN IF NOT EXISTS vote_count INTEGER DEFAULT 0;

-- 2. Función de Trigger con SECURITY DEFINER (Crucial para que funcione el conteo)
CREATE OR REPLACE FUNCTION update_challenge_vote_count()
RETURNS TRIGGER AS $$
BEGIN
    IF (TG_OP = 'INSERT') THEN
        UPDATE challenge_submissions 
        SET vote_count = vote_count + 1 
        WHERE id = NEW.submission_id;
    ELSIF (TG_OP = 'DELETE') THEN
        UPDATE challenge_submissions 
        SET vote_count = GREATEST(0, vote_count - 1) 
        WHERE id = OLD.submission_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 3. Trigger (Idempotente)
DROP TRIGGER IF EXISTS tr_update_challenge_vote_count ON challenge_votes;
CREATE TRIGGER tr_update_challenge_vote_count
AFTER INSERT OR DELETE ON challenge_votes
FOR EACH ROW EXECUTE FUNCTION update_challenge_vote_count();

-- 4. Sincronizar datos actuales
UPDATE challenge_submissions s
SET vote_count = (
    SELECT count(*) 
    FROM challenge_votes v 
    WHERE v.submission_id = s.id
);

-- 5. RPC Mejorado de Votación (Toggle)
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
        VALUES (p_submission_id, v_voter_id, v_active_challenge_id);
        v_voted := true;
    END IF;

    SELECT vote_count INTO v_count FROM challenge_submissions WHERE id = p_submission_id;
    
    RETURN jsonb_build_object(
        'vote_count', v_count,
        'voted_by_me', v_voted
    );
END;
$$;

-- 6. RPC para obtener participaciones (MUY IMPORTANTE - Estaba faltando)
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
        to_jsonb(p) as user_data
    FROM challenge_submissions s
    LEFT JOIN profiles p ON p.id = s.user_id
    WHERE s.active_challenge_id = p_active_challenge_id
    ORDER BY s.vote_count DESC, s.created_at DESC;
END;
$$;

-- 7. RLS Y PERMISOS
ALTER TABLE challenge_submissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE challenge_votes ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Ver participaciones" ON challenge_submissions;
CREATE POLICY "Ver participaciones" ON challenge_submissions FOR SELECT USING (true);

DROP POLICY IF EXISTS "Ver votos" ON challenge_votes;
CREATE POLICY "Ver votos" ON challenge_votes FOR SELECT USING (true);

DROP POLICY IF EXISTS "Insertar votos" ON challenge_votes;
CREATE POLICY "Insertar votos" ON challenge_votes FOR INSERT WITH CHECK (auth.uid() = voter_id);

DROP POLICY IF EXISTS "Eliminar propio voto" ON challenge_votes;
CREATE POLICY "Eliminar propio voto" ON challenge_votes FOR DELETE USING (auth.uid() = voter_id);

GRANT EXECUTE ON FUNCTION toggle_challenge_vote(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION get_challenge_submissions_voted(UUID) TO authenticated;

-- 8. REALTIME (Asegurar que las tablas están en la publicación)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'challenge_submissions') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE challenge_submissions;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'challenge_votes') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE challenge_votes;
    END IF;
END $$;
