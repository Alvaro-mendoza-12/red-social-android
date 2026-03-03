-- 1. Habilitar RLS en las tablas maestras y activas
ALTER TABLE challenges_master ENABLE ROW LEVEL SECURITY;
ALTER TABLE active_challenges ENABLE ROW LEVEL SECURITY;

-- 2. Políticas para challenges_master (Lectura pública para ver títulos/descripciones)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE policyname = 'Public Read Master'
    ) THEN
        CREATE POLICY "Public Read Master" ON challenges_master 
        FOR SELECT TO authenticated, anon USING (true);
    END IF;
END $$;

-- 3. Políticas para active_challenges (Lectura pública para ver el reto en curso)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies WHERE policyname = 'Public Read Active'
    ) THEN
        CREATE POLICY "Public Read Active" ON active_challenges 
        FOR SELECT TO authenticated, anon USING (true);
    END IF;
END $$;

-- Nota: Solo funciones administrativas o triggers deben poder modificar estas tablas.
-- Si necesitas crear retos desde la app web de admin, deberás añadir políticas de INSERT/UPDATE.
