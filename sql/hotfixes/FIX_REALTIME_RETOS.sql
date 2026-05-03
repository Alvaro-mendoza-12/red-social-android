-- PASO 1: Activar Realtime de forma segura (con bloque DO correcto)
DO $$
BEGIN
  BEGIN
    ALTER PUBLICATION supabase_realtime ADD TABLE challenge_submissions;
  EXCEPTION 
    WHEN OTHERS THEN NULL; -- Si ya está activa o da error, ignoramos y seguimos.
  END;
END $$;

-- PASO 2: Asegurar Políticas de Lectura (RLS)
-- Borrar políticas antiguas para evitar conflictos
DROP POLICY IF EXISTS "Enable read access for all users" ON challenge_submissions;
DROP POLICY IF EXISTS "Authenticated users can see all submissions" ON challenge_submissions;
DROP POLICY IF EXISTS "Public submissions are visible" ON challenge_submissions;

-- Crear política permisiva DE LECTURA: Todos los usuarios autenticados ven todo
CREATE POLICY "Authenticated users can see all submissions"
ON challenge_submissions FOR SELECT
TO authenticated
USING (true);

-- Política para VOTAR (UPDATE): Todos pueden actualizar (votar)
DROP POLICY IF EXISTS "Authenticated users can vote" ON challenge_submissions;
CREATE POLICY "Authenticated users can vote"
ON challenge_submissions FOR UPDATE
TO authenticated
USING (true)
WITH CHECK (true);
