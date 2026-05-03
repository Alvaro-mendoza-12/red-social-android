-- SCRIPT DE NORMALIZACIÓN DEFINITIVA PARA SUPABASE (CORREGIDO PARA UUID)
-- Ejecuta esto en el SQL Editor de Supabase. 
-- El casting ::TEXT permite aplicar LOWER/TRIM a columnas de tipo UUID.

-- 1. Normalizar Grupos
UPDATE public.groups SET id = LOWER(TRIM(id::TEXT))::UUID;

-- 2. Normalizar Comunidades
UPDATE public.communities SET id = LOWER(TRIM(id::TEXT))::UUID;

-- 3. Normalizar Miembros de Grupos
UPDATE public.group_members SET group_id = LOWER(TRIM(group_id::TEXT))::UUID;

-- 4. Normalizar Miembros de Comunidades
UPDATE public.community_members SET community_id = LOWER(TRIM(community_id::TEXT))::UUID;

-- 5. Normalizar Mensajes de Grupos
UPDATE public.group_messages SET group_id = LOWER(TRIM(group_id::TEXT))::UUID;

-- 6. Normalizar Mensajes de Comunidades
UPDATE public.community_messages SET community_id = LOWER(TRIM(community_id::TEXT))::UUID;

-- NOTA: Si recibes un error de "Duplicate Key", significa que ya hay registros duplicados con distinta capitalización.
-- En ese caso, la app ya está preparada para manejarlos, pero es recomendable limpiar los duplicados en Supabase.
