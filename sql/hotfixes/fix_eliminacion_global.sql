-- ADICIÓN DE COLUMNA PARA ELIMINACIÓN LÓGICA (SOFT DELETE)
-- Este script permite eliminar grupos y comunidades de forma global e instantánea.

-- 1. Añadir columna is_deleted a comunidades
ALTER TABLE public.communities ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN DEFAULT FALSE;

-- 2. Añadir columna is_deleted a grupos
ALTER TABLE public.groups ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN DEFAULT FALSE;

-- 3. Crear índices para optimizar el filtrado de elementos eliminados
CREATE INDEX IF NOT EXISTS idx_communities_is_deleted ON public.communities(is_deleted);
CREATE INDEX IF NOT EXISTS idx_groups_is_deleted ON public.groups(is_deleted);

-- 4. Actualizar políticas RLS para que no se puedan ver elementos eliminados
-- (Opcional, pero recomendado para máxima seguridad)
DROP POLICY IF EXISTS "Public communities are visible to everyone" ON public.communities;
CREATE POLICY "Public communities are visible to everyone" ON public.communities
    FOR SELECT USING (is_public = TRUE AND is_deleted = FALSE);

DROP POLICY IF EXISTS "Users can see groups they are members of" ON public.groups;
CREATE POLICY "Users can see groups they are members of" ON public.groups
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.group_members 
            WHERE group_id = public.groups.id AND user_id = auth.uid()
        )
        -- Eliminamos "AND is_deleted = FALSE" para que los miembros reciban el evento de borrado en Realtime
    );

DROP POLICY IF EXISTS "Users can see communities they are members of" ON public.communities;
CREATE POLICY "Users can see communities they are members of" ON public.communities
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.community_members 
            WHERE community_id = public.communities.id AND user_id = auth.uid()
        )
        -- Eliminamos is_deleted check aquí también
    );

NOTIFY pgrst, 'reload schema';

SELECT '✅ Sistema de Soft Delete activado para Grupos y Comunidades.' as resultado;
