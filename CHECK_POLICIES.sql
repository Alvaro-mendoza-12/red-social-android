-- Verificar qué políticas están actualmente activas en las tablas de grupos
SELECT schemaname, tablename, policyname, permissive, roles, cmd, qual 
FROM pg_policies 
WHERE tablename IN ('groups', 'group_members', 'group_messages')
ORDER BY tablename, policyname;
