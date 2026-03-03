-- ========================================================
-- REPARACIÓN NUCLEAR V8: REALTIME Y SINCRONIZACIÓN TOTAL
-- ========================================================

-- 1. HABILITAR REALTIME PARA TABLAS CRÍTICAS
-- Esto activa los listeners en la App para que reciban borrados al instante.
BEGIN;
  -- Asegurar que la publicación existe (solo para Supabase)
  -- Si ya existen, no hay problema, se ignorarán los errores de duplicado si se ejecuta por partes.
  DROP PUBLICATION IF EXISTS supabase_realtime;
  CREATE PUBLICATION supabase_realtime FOR TABLE 
    public.communities, 
    public.community_members, 
    public.community_messages, 
    public.groups, 
    public.group_members, 
    public.group_messages;
COMMIT;

-- 2. REPLICA IDENTITY FULL
-- Esto es CRUCIAL para que el payload de Realtime contenga "is_deleted: true" 
-- y el dispositivo sepa qué comunidad borrar localmente.
ALTER TABLE public.communities REPLICA IDENTITY FULL;
ALTER TABLE public.groups REPLICA IDENTITY FULL;
ALTER TABLE public.community_members REPLICA IDENTITY FULL;
ALTER TABLE public.group_members REPLICA IDENTITY FULL;

-- 3. AJUSTE DE RLS (SELECT V8)
-- Permitimos SELECT a todos los autenticados para que reciban la señal de Realtime.
-- La App filtrará lo que no debe mostrarse, pero la "señal" debe poder llegar.
DROP POLICY IF EXISTS "comm_select_v7" ON public.communities;
CREATE POLICY "comm_select_v8" ON public.communities
    FOR SELECT USING (auth.role() = 'authenticated');

DROP POLICY IF EXISTS "groups_select_v7" ON public.groups;
CREATE POLICY "groups_select_v8" ON public.groups
    FOR SELECT USING (auth.role() = 'authenticated');

-- 4. SEGURIDAD DE MENSAJES (MANTENER BLOQUEO SI ESTÁ BORRADO)
DROP POLICY IF EXISTS "comm_msg_insert_v7" ON public.community_messages;
CREATE POLICY "comm_msg_insert_v8" ON public.community_messages
    FOR INSERT WITH CHECK (
        EXISTS (SELECT 1 FROM communities WHERE id = community_id AND is_deleted = FALSE)
    );

DROP POLICY IF EXISTS "group_msg_insert_v7" ON public.group_messages;
CREATE POLICY "group_msg_insert_v8" ON public.group_messages
    FOR INSERT WITH CHECK (
        EXISTS (SELECT 1 FROM groups WHERE id = group_id AND is_deleted = FALSE)
    );

-- 5. VALORES POR DEFECTO
ALTER TABLE public.communities ALTER COLUMN is_deleted SET DEFAULT false;
ALTER TABLE public.groups ALTER COLUMN is_deleted SET DEFAULT false;
UPDATE public.communities SET is_deleted = false WHERE is_deleted IS NULL;
UPDATE public.groups SET is_deleted = false WHERE is_deleted IS NULL;
