-- ========================================================
-- FIX: PERMISOS DE EDICIÓN DE ROLES (PROMOVER A ADMIN)
-- ========================================================

BEGIN;

-- 1. POLÍTICAS PARA group_members
DROP POLICY IF EXISTS "members_update_policy" ON public.group_members;
CREATE POLICY "members_update_policy" ON public.group_members
    FOR UPDATE USING (
        EXISTS (
            SELECT 1 FROM public.groups 
            WHERE id = group_id AND creator_id = auth.uid() -- El creador cambia roles
        )
        OR (
            SELECT role FROM public.group_members 
            WHERE group_id = public.group_members.group_id AND user_id = auth.uid()
        ) = 'admin' -- Un admin cambia roles
    );

-- 2. POLÍTICAS PARA community_members
DROP POLICY IF EXISTS "communities_members_update_policy" ON public.community_members;
CREATE POLICY "communities_members_update_policy" ON public.community_members
    FOR UPDATE USING (
        EXISTS (
            SELECT 1 FROM public.communities 
            WHERE id = community_id AND creator_id = auth.uid() -- El creador cambia roles
        )
        OR (
            SELECT role FROM public.community_members 
            WHERE community_id = public.community_members.community_id AND user_id = auth.uid()
        ) IN ('admin', 'owner') -- Un admin cambia roles
    );

COMMIT;

SELECT '✅ POLÍTICAS DE ACTUALIZACIÓN DE MIEMBROS APLICADAS' as status;
