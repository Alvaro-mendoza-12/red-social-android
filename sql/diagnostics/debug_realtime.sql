-- DIAGNOSTICO DE REALTIME
-- Listar tablas en la publicación
SELECT * FROM pg_publication_tables WHERE pubname = 'supabase_realtime';

-- Verificar REPLICA IDENTITY
SELECT CASE relreplident
          WHEN 'd' THEN 'default'
          WHEN 'n' THEN 'nothing'
          WHEN 'f' THEN 'full'
          WHEN 'i' THEN 'index'
       END AS replica_identity
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relname = 'moments';
