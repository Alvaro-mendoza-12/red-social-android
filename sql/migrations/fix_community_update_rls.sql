-- Corrección de política de edición de comunidades
-- Ejecuta este script en el SQL Editor de Supabase.

BEGIN;

DROP POLICY IF EXISTS "Community admins can update communities" ON public.communities;

CREATE POLICY "Community admins can update communities" ON public.communities
FOR UPDATE USING (
    creator_id = auth.uid() OR public.is_community_admin(id)
);

COMMIT;
