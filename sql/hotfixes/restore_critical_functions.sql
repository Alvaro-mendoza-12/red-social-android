-- ==========================================================
-- RESTAURACIÓN DE FUNCIONES CRÍTICAS (BORRADAS POR ACCIDENTE)
-- ==========================================================

-- 1. Función para obtener participaciones (Esencial para ver la lista)
CREATE OR REPLACE FUNCTION public.get_challenge_submissions_voted(p_active_challenge_id UUID)
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

-- 2. Función de Trigger para conteo automático (Seguridad para que los votos se sumen)
CREATE OR REPLACE FUNCTION public.update_challenge_vote_count()
RETURNS TRIGGER AS $$
BEGIN
    IF (TG_OP = 'INSERT') THEN
        UPDATE challenge_submissions 
        SET vote_count = (SELECT count(*) FROM challenge_votes WHERE submission_id = NEW.submission_id)
        WHERE id = NEW.submission_id;
    ELSIF (TG_OP = 'DELETE') THEN
        UPDATE challenge_submissions 
        SET vote_count = (SELECT count(*) FROM challenge_votes WHERE submission_id = OLD.submission_id)
        WHERE id = OLD.submission_id;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 3. Re-vincular el Trigger
DROP TRIGGER IF EXISTS tr_update_challenge_vote_count ON challenge_votes;
CREATE TRIGGER tr_update_challenge_vote_count
AFTER INSERT OR DELETE ON challenge_votes
FOR EACH ROW EXECUTE FUNCTION update_challenge_vote_count();

-- 4. Permisos
GRANT EXECUTE ON FUNCTION public.get_challenge_submissions_voted(UUID) TO authenticated, anon;
GRANT EXECUTE ON FUNCTION public.update_challenge_vote_count() TO authenticated;

-- Forzar recarga de esquema para que la App lo vea todo de nuevo
NOTIFY pgrst, 'reload schema';
