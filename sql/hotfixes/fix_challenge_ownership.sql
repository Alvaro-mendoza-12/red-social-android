-- 1. Añadir columna creator_id a challenges_master (Idempotente)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'challenges_master' AND COLUMN_NAME = 'creator_id') THEN
        ALTER TABLE challenges_master ADD COLUMN creator_id UUID REFERENCES profiles(id) ON DELETE SET NULL;
    END IF;
END $$;

-- 2. Actualizar políticas RLS para challenges_master
ALTER TABLE challenges_master ENABLE ROW LEVEL SECURITY;

-- Permitir lectura a todos (ya existe, pero aseguramos)
DROP POLICY IF EXISTS "Public Read Master" ON challenges_master;
CREATE POLICY "Public Read Master" ON challenges_master FOR SELECT USING (true);

-- Permitir inserción con el ID del creador automático
DROP POLICY IF EXISTS "Authenticated users can create challenges" ON challenges_master;
CREATE POLICY "Authenticated users can create challenges" ON challenges_master 
FOR INSERT TO authenticated 
WITH CHECK (auth.uid() = creator_id);

-- Permitir eliminación solo al creador
DROP POLICY IF EXISTS "Users can delete their own challenges" ON challenges_master;
CREATE POLICY "Users can delete their own challenges" ON challenges_master 
FOR DELETE TO authenticated 
USING (auth.uid() = creator_id);

-- 3. Actualizar la función de creación de retos para que guarde el creator_id
-- No es necesario cambiar el RPC si se hace el INSERT directo desde la app, 
-- pero es bueno tener la columna ahí.

-- 4. Asegurar que las cascadas funcionen (si se borra el maestro, se borra el activo)
-- La tabla active_challenges ya tiene REFERENCES challenges_master(id) ON DELETE CASCADE

-- 5. OPCIONAL: Reclamar retos antiguos (solo una vez)
-- Esto asignará todos los retos que NO tienen creador al usuario que ejecute este script.
-- Útil para que tus pruebas anteriores aparezcan en el historial.
UPDATE challenges_master 
SET creator_id = auth.uid() 
WHERE creator_id IS NULL;
