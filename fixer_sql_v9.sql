-- ========================================================
-- REPARACIÓN NUCLEAR V9: PODER ADMIN Y LIMPIEZA DE FANTASMAS
-- ========================================================

-- 1. EMPOWER ADMINS: Permitir que los administradores también borren
-- Actualizamos la política de UPDATE para comunidades (Creador, Admin de Comunidad o Admin Global)
DROP POLICY IF EXISTS "comm_update_v6" ON public.communities;
DROP POLICY IF EXISTS "comm_update_v9" ON public.communities;
CREATE POLICY "comm_update_v9" ON public.communities
    FOR UPDATE USING (
        (creator_id = auth.uid()) OR 
        (EXISTS (SELECT 1 FROM community_members WHERE community_id = id AND user_id = auth.uid() AND role = 'admin')) OR
        (EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND is_admin = true))
    );

-- Actualizamos la política de UPDATE para grupos (Creador, Admin de Grupo o Admin Global)
DROP POLICY IF EXISTS "groups_update_v6" ON public.groups;
DROP POLICY IF EXISTS "groups_update_v9" ON public.groups;
CREATE POLICY "groups_update_v9" ON public.groups
    FOR UPDATE USING (
        (creator_id = auth.uid()) OR 
        (EXISTS (SELECT 1 FROM group_members WHERE group_id = id AND user_id = auth.uid() AND role = 'admin')) OR
        (EXISTS (SELECT 1 FROM profiles WHERE id = auth.uid() AND is_admin = true))
    );

-- Este comando marca como borradas comunidades donde el creador ya no es miembro,
-- lo cual suele suceder en los "fantasmas" que mencionas.
-- ÚSALO CON PRECAUCIÓN: 
-- UPDATE public.communities SET is_deleted = true WHERE id IN (
--    SELECT c.id FROM public.communities c 
--    LEFT JOIN public.community_members m ON c.id = m.community_id AND c.creator_id = m.user_id
--    WHERE m.user_id IS NULL AND c.is_deleted = false
-- );

-- Mismo comando para grupos:
-- UPDATE public.groups SET is_deleted = true WHERE id IN (
--    SELECT g.id FROM public.groups g 
--    LEFT JOIN public.group_members m ON g.id = m.group_id AND g.creator_id = m.user_id
--    WHERE m.user_id IS NULL AND g.is_deleted = false
-- );

-- 3. BUSCAR TUS COMUNIDADES ACTIVAS PARA BORRADO MANUAL
-- Si quieres ver los IDs de lo que todavía está "vivo" para borrarlo a mano:
-- SELECT id, name FROM communities WHERE creator_id = 'TU_USER_ID' AND is_deleted = false;
