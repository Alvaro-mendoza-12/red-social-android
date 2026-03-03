-- 1. Habilitar Realtime para participaciones y votos
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'challenge_submissions') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE challenge_submissions;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'challenge_votes') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE challenge_votes;
    END IF;
END $$;

-- 2. Asegurar que los perfiles son legibles para mostrar fotos en los retos
ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE policyname = 'Profiles are viewable by everyone') THEN
        CREATE POLICY "Profiles are viewable by everyone" ON profiles FOR SELECT USING (true);
    END IF;
END $$;

-- 3. Actualizar la función para que el conteo de votos sea exacto en tiempo real
-- (Opcional si usas el select count(*) en el repo, pero mejor tenerlo claro)
GRANT SELECT ON challenge_votes TO authenticated;
GRANT SELECT ON challenge_submissions TO authenticated;
