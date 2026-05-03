-- Diagnóstico de emergencia para restricciones de Supabase por storage.
-- Caso típico: `exceed_storage_size_quota` y respuestas HTTP 402.
--
-- Uso recomendado:
-- 1. Abre Supabase Dashboard > SQL Editor.
-- 2. Ejecuta estas consultas una por una.
-- 3. Identifica el bucket y los archivos más pesados.
-- 4. Borra esos objetos desde Storage en el dashboard o con la Storage API.
--
-- Este script es SOLO de diagnóstico. No elimina nada.

-- 1) Resumen por bucket.
select
    bucket_id,
    count(*) as object_count,
    coalesce(sum((metadata->>'size')::bigint), 0) as total_size_bytes,
    round(coalesce(sum((metadata->>'size')::numeric), 0) / 1024 / 1024, 2) as total_size_mb,
    round(coalesce(sum((metadata->>'size')::numeric), 0) / 1024 / 1024 / 1024, 2) as total_size_gb,
    min(created_at) as oldest_object_at,
    max(created_at) as newest_object_at
from storage.objects
group by bucket_id
order by total_size_bytes desc, object_count desc;

-- 2) Archivos más grandes de todo el proyecto.
select
    bucket_id,
    name,
    (metadata->>'size')::bigint as size_bytes,
    round(((metadata->>'size')::numeric) / 1024 / 1024, 2) as size_mb,
    created_at,
    updated_at
from storage.objects
order by size_bytes desc
limit 200;

-- 3) Carpetas/prefijos que más pesan dentro de cada bucket.
select
    bucket_id,
    nullif(split_part(name, '/', 1), '') as top_level_prefix,
    count(*) as object_count,
    coalesce(sum((metadata->>'size')::bigint), 0) as total_size_bytes,
    round(coalesce(sum((metadata->>'size')::numeric), 0) / 1024 / 1024, 2) as total_size_mb
from storage.objects
group by bucket_id, nullif(split_part(name, '/', 1), '')
order by total_size_bytes desc, object_count desc
limit 300;

-- 4) Objetos antiguos y pesados candidatos a limpieza manual.
select
    bucket_id,
    name,
    (metadata->>'size')::bigint as size_bytes,
    round(((metadata->>'size')::numeric) / 1024 / 1024, 2) as size_mb,
    created_at
from storage.objects
where created_at < now() - interval '30 days'
order by size_bytes desc, created_at asc
limit 300;

-- 5) Conteo por día para detectar picos de subida.
select
    bucket_id,
    date_trunc('day', created_at) as day_bucket,
    count(*) as uploaded_objects,
    round(coalesce(sum((metadata->>'size')::numeric), 0) / 1024 / 1024, 2) as uploaded_mb
from storage.objects
group by bucket_id, date_trunc('day', created_at)
order by day_bucket desc, uploaded_mb desc
limit 365;

-- 6) Lista exportable para borrar manualmente desde Storage dashboard/API.
select
    bucket_id,
    name,
    created_at,
    round(((metadata->>'size')::numeric) / 1024 / 1024, 2) as size_mb
from storage.objects
order by (metadata->>'size')::bigint desc
limit 500;
