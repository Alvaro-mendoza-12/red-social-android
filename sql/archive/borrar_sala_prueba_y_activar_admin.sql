-- ======================================================
-- 🛠️ BORRADO DE SALA DE PRUEBA Y ACTIVACIÓN DE ADMIN
-- ======================================================

-- 1. Borrar la sala de prueba que creamos para el contador
DELETE FROM voice_rooms 
WHERE id = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'::uuid;

-- 2. Asegurar que TUS DOS CUENTAS son Admins en la base de datos
UPDATE profiles
SET is_admin = true
WHERE email IN ('alvaromendozamarin004@gmail.com', 'gamergamerff017@gmail.com');

-- 3. Activar el "Poder Admin" para borrar cualquier sala (RLS Policy)
DROP POLICY IF EXISTS "Admins and creators can delete voice rooms" ON voice_rooms;
CREATE POLICY "Admins and creators can delete voice rooms" 
ON voice_rooms
FOR DELETE
USING (
    auth.uid() = creator_id 
    OR 
    EXISTS (
        SELECT 1 FROM profiles 
        WHERE id = auth.uid() AND is_admin = true
    )
);

-- 4. Verificar resultado final
SELECT id, name, creator_id FROM voice_rooms;
