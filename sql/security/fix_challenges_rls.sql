-- 1. Habilitar RLS en las tablas si no lo están
ALTER TABLE challenge_submissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE challenge_votes ENABLE ROW LEVEL SECURITY;

-- 2. Políticas para challenge_submissions

-- Permitir lectura a todos los usuarios autenticados
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE policyname = 'Allow Select Submissions for Authenticated'
    ) THEN
        CREATE POLICY "Allow Select Submissions for Authenticated" ON challenge_submissions 
        FOR SELECT TO authenticated USING (true);
    END IF;
END $$;

-- Permitir insertar sus propias respuestas
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE policyname = 'Allow Insert Own Submission'
    ) THEN
        CREATE POLICY "Allow Insert Own Submission" ON challenge_submissions 
        FOR INSERT TO authenticated WITH CHECK (auth.uid() = user_id);
    END IF;
END $$;

-- 3. Políticas para challenge_votes

-- Permitir lectura de votos para contar
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE policyname = 'Allow Select Votes for Authenticated'
    ) THEN
        CREATE POLICY "Allow Select Votes for Authenticated" ON challenge_votes 
        FOR SELECT TO authenticated USING (true);
    END IF;
END $$;

-- Permitir votar solo una vez por reto (la restricción UNIQUE se encarga de la lógica, aquí solo el permiso)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE policyname = 'Allow Insert Own Vote'
    ) THEN
        CREATE POLICY "Allow Insert Own Vote" ON challenge_votes 
        FOR INSERT TO authenticated WITH CHECK (auth.uid() = voter_id);
    END IF;
END $$;
