-- ========================================================
-- REPARACIÓN NUCLEAR V11: FIN DEL ERROR DE DUPLICADOS
-- ========================================================

-- 1. ELIMINAR RESTRICCIÓN RÍGIDA DE DUPLICADOS EN GRUPOS
-- El error "groups_name_creator_id_key" indica que no puedes crear otro grupo con el mismo nombre,
-- incluso si el anterior fue borrado. Vamos a arreglar eso.

ALTER TABLE public.groups DROP CONSTRAINT IF EXISTS groups_name_creator_id_key;
DROP INDEX IF EXISTS idx_groups_name_creator_id;

-- 2. CREAR ÍNDICE ÚNICO PARCIAL
-- Este índice solo prohibirá duplicados si el grupo NO está borrado.
-- Permite crear un grupo nuevo con el mismo nombre si el anterior tiene is_deleted = true.
CREATE UNIQUE INDEX IF NOT EXISTS groups_name_creator_id_active_idx 
ON public.groups (name, creator_id) 
WHERE (is_deleted = false);

-- 3. MISMO PROCEDIMIENTO PARA COMUNIDADES (Por prevención)
ALTER TABLE public.communities DROP CONSTRAINT IF EXISTS communities_name_creator_id_key;
DROP INDEX IF EXISTS idx_communities_name_creator_id;

CREATE UNIQUE INDEX IF NOT EXISTS communities_name_creator_id_active_idx 
ON public.communities (name, creator_id) 
WHERE (is_deleted = false);
