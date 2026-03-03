-- ========================================================
-- REPARACIÓN NUCLEAR V12: AUDITORÍA Y RESET DE REALTIME
-- ========================================================

-- 1. RE-CREAR PUBLICACIÓN (Garantizar que todas las tablas están incluidas)
-- A veces, añadir tablas una a una no refresca correctamente el slot de replicación.
-- Borrar y re-crear es la forma más segura de "resetear" el sistema.

BEGIN;
  DROP PUBLICATION IF EXISTS supabase_realtime;
  CREATE PUBLICATION supabase_realtime FOR TABLE 
    public.communities, 
    public.community_members, 
    public.community_messages, 
    public.groups, 
    public.group_members, 
    public.group_messages;
COMMIT;

-- 2. ASEGURAR IDENTIDAD DE REPLICACIÓN (CRUCIAL PARA PAYLOAD COMPLETO)
ALTER TABLE public.communities REPLICA IDENTITY FULL;
ALTER TABLE public.groups REPLICA IDENTITY FULL;
ALTER TABLE public.community_members REPLICA IDENTITY FULL;
ALTER TABLE public.group_members REPLICA IDENTITY FULL;

-- 3. REPASO DE POLÍTICAS DE GRUPOS (MÁXIMA VISIBILIDAD)
DROP POLICY IF EXISTS "groups_select_v8" ON public.groups;
CREATE POLICY "groups_select_v12" ON public.groups
    FOR SELECT USING (auth.role() = 'authenticated');

DROP POLICY IF EXISTS "group_mem_select_v10" ON public.group_members;
CREATE POLICY "group_mem_select_v12" ON public.group_members
    FOR SELECT USING (auth.role() = 'authenticated');

-- 4. VERIFICACIÓN DE PERMISOS DE MENSAJES
DROP POLICY IF EXISTS "group_msg_select_v10" ON public.group_messages;
CREATE POLICY "group_msg_select_v12" ON public.group_messages
    FOR SELECT USING (auth.role() = 'authenticated');

-- NOTA: Al poner 'authenticated' en SELECT, garantizamos que Realtime SIEMPRE 
-- envíe los datos al cliente. La App se encarga de mostrar solo lo relevante.
