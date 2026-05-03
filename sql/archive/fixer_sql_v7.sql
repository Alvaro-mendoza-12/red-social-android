-- ========================================================
-- REPARACIÓN NUCLEAR V7: SINCRONIZACIÓN REALTIME GARANTIZADA
-- ========================================================

-- 1. VALORES POR DEFECTO Y LIMPIEZA
ALTER TABLE public.communities ALTER COLUMN is_deleted SET DEFAULT false;
ALTER TABLE public.groups ALTER COLUMN is_deleted SET DEFAULT false;
UPDATE public.communities SET is_deleted = false WHERE is_deleted IS NULL;
UPDATE public.groups SET is_deleted = false WHERE is_deleted IS NULL;

-- 2. LIMPIAR TODAS LAS POLÍTICAS PARA EVITAR CONFLICTOS
DO $$ 
DECLARE 
    r RECORD;
BEGIN
    FOR r IN (
        SELECT policyname, tablename 
        FROM pg_policies 
        WHERE schemaname = 'public' 
          AND tablename IN ('communities', 'community_members', 'groups', 'group_members', 'community_messages', 'group_messages')
    ) 
    LOOP
        EXECUTE 'DROP POLICY IF EXISTS ' || quote_ident(r.policyname) || ' ON public.' || quote_ident(r.tablename);
    END LOOP;
END $$;

-- 3. POLÍTICAS DE COMUNIDADES (V7)
-- SELECT: Permitimos verla si NO está borrada O si eres el creador O si eres miembro.
-- IMPORTANTE: Ser miembro permite recibir el Realtime de borrado. El filtrado de "no mostrar borradas" se hace en la App (Queries).
CREATE POLICY "comm_select_v7" ON public.communities
    FOR SELECT USING (
        (is_deleted = FALSE) OR 
        (creator_id = auth.uid()) OR 
        (EXISTS (SELECT 1 FROM community_members WHERE community_id = id AND user_id = auth.uid()))
    );

CREATE POLICY "comm_insert_v7" ON public.communities
    FOR INSERT WITH CHECK (auth.role() = 'authenticated');

CREATE POLICY "comm_update_v7" ON public.communities
    FOR UPDATE USING (creator_id = auth.uid());

-- 4. POLÍTICAS DE GRUPOS (V7)
CREATE POLICY "groups_select_v7" ON public.groups
    FOR SELECT USING (
        (is_deleted = FALSE) OR 
        (creator_id = auth.uid()) OR 
        (EXISTS (SELECT 1 FROM group_members WHERE group_id = id AND user_id = auth.uid()))
    );

CREATE POLICY "groups_insert_v7" ON public.groups
    FOR INSERT WITH CHECK (auth.role() = 'authenticated');

CREATE POLICY "groups_update_v7" ON public.groups
    FOR UPDATE USING (creator_id = auth.uid());

-- 5. POLÍTICAS DE MIEMBROS (V7)
-- Permitimos SELECT siempre (simplificado para asegurar sincronización de expulsiones/borrados)
CREATE POLICY "comm_mem_select_v7" ON public.community_members FOR SELECT USING (true);
CREATE POLICY "group_mem_select_v7" ON public.group_members FOR SELECT USING (true);

-- Modificaciones de miembros (Unirse/Salir)
CREATE POLICY "comm_mem_mod_v7" ON public.community_members FOR ALL USING (auth.role() = 'authenticated');
CREATE POLICY "group_mem_mod_v7" ON public.group_members FOR ALL USING (auth.role() = 'authenticated');

-- 6. POLÍTICAS DE MENSAJES (BLOQUEO CRÍTICO SI ESTÁ BORRADO)
-- SELECT de mensajes: Solo si la comunidad NO está borrada O eres el creador.
CREATE POLICY "comm_msg_select_v7" ON public.community_messages
    FOR SELECT USING (
        EXISTS (SELECT 1 FROM communities WHERE id = community_id AND is_deleted = FALSE) OR
        EXISTS (SELECT 1 FROM communities WHERE id = community_id AND creator_id = auth.uid())
    );

-- INSERT de mensajes: PROHIBIDO si is_deleted es TRUE
CREATE POLICY "comm_msg_insert_v7" ON public.community_messages
    FOR INSERT WITH CHECK (
        EXISTS (SELECT 1 FROM communities WHERE id = community_id AND is_deleted = FALSE)
    );

CREATE POLICY "group_msg_select_v7" ON public.group_messages
    FOR SELECT USING (
        EXISTS (SELECT 1 FROM groups WHERE id = group_id AND is_deleted = FALSE) OR
        EXISTS (SELECT 1 FROM groups WHERE id = group_id AND creator_id = auth.uid())
    );

CREATE POLICY "group_msg_insert_v7" ON public.group_messages
    FOR INSERT WITH CHECK (
        EXISTS (SELECT 1 FROM groups WHERE id = group_id AND is_deleted = FALSE)
    );

-- 7. ÍNDICES
CREATE INDEX IF NOT EXISTS idx_communities_deleted ON public.communities(is_deleted);
CREATE INDEX IF NOT EXISTS idx_groups_deleted ON public.groups(is_deleted);
