-- ======================================================
-- MODERACIÓN DE SALAS: PERMISOS PARA ADMINS
-- ======================================================

-- 1. Marcar a los usuarios como admins reales en la base de datos
UPDATE profiles
SET is_admin = true
WHERE email IN ('alvaromendozamarin004@gmail.com', 'gamergamerff017@gmail.com');

-- 2. Asegurar que la tabla voice_rooms permita borrado por admins
-- Primero borramos la política restrictiva si existe (normalmente solo el creador)
-- DROP POLICY IF EXISTS "Users can delete own voice rooms" ON voice_rooms;

-- 3. Crear (o actualizar) la política de eliminación
-- Esta política permite borrar rsi eres el creador O si eres un admin
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

-- 4. Verificar que los admins tengan is_admin = true
SELECT id, username, email, is_admin 
FROM profiles 
WHERE email IN ('alvaromendozamarin004@gmail.com', 'gamergamerff017@gmail.com');
