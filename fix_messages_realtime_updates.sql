-- ========================================
-- FIX: Habilitar Realtime UPDATE para Messages
-- ========================================
-- Problema: Los eventos UPDATE no se propagan a otros clientes
-- Causa: La tabla 'messages' no tiene REPLICA IDENTITY FULL
-- Solución: Configurar REPLICA IDENTITY FULL para que Supabase 
--           Realtime envíe todos los campos en eventos UPDATE

-- 1. Configurar REPLICA IDENTITY FULL
ALTER TABLE public.messages REPLICA IDENTITY FULL;

-- 2. Verificar que Realtime esté habilitado para la tabla
-- (Esto se hace desde el Dashboard de Supabase en Database > Replication)
-- Asegúrate de que 'messages' esté en la lista de tablas con Realtime habilitado

-- 3. Verificación (Opcional)
-- Ejecuta esto para confirmar la configuración:
SELECT 
    schemaname,
    tablename,
    CASE relreplident
        WHEN 'd' THEN 'default'
        WHEN 'n' THEN 'nothing'
        WHEN 'f' THEN 'full'
        WHEN 'i' THEN 'index'
    END as replica_identity
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_tables t ON t.tablename = c.relname AND t.schemaname = n.nspname
WHERE t.tablename = 'messages' AND t.schemaname = 'public';

-- Resultado esperado: replica_identity = 'full'
