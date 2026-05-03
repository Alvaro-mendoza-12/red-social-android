-- ========================================================
-- REPARACIÓN NUCLEAR V6: ELIMINACIÓN TOTAL Y TRANSPARENTE
-- ========================================================

-- 1. ASEGURAR COLUMNAS Y VALORES POR DEFECTO
ALTER TABLE public.communities ALTER COLUMN is_deleted SET DEFAULT false;
ALTER TABLE public.groups ALTER COLUMN is_deleted SET DEFAULT false;

-- Limpiar posibles NULLs que rompen las políticas
UPDATE public.communities SET is_deleted = false WHERE is_deleted IS NULL;
UPDATE public.groups SET is_deleted = false WHERE is_deleted IS NULL;

-- 2. LIMPIAR POLÍTICAS ANTERIORES PARA EVITAR CONFLICTOS
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

-- 3. POLÍTICAS DE COMUNIDADES (V6)
-- SELECT: Nadie ve una comunidad borrada, salvo el creador.
CREATE POLICY "comm_select_v6" ON public.communities
    FOR SELECT USING (
        (is_deleted = FALSE) OR (creator_id = auth.uid())
    );

CREATE POLICY "comm_insert_v6" ON public.communities
    FOR INSERT WITH CHECK (auth.role() = 'authenticated');

CREATE POLICY "comm_update_v6" ON public.communities
    FOR UPDATE USING (creator_id = auth.uid());

-- 4. POLÍTICAS DE GRUPOS (V6)
CREATE POLICY "groups_select_v6" ON public.groups
    FOR SELECT USING (
        (is_deleted = FALSE) OR (creator_id = auth.uid())
    );

CREATE POLICY "groups_insert_v6" ON public.groups
    FOR INSERT WITH CHECK (auth.role() = 'authenticated');

CREATE POLICY "groups_update_v6" ON public.groups
    FOR UPDATE USING (creator_id = auth.uid());

-- 5. POLÍTICAS DE MIEMBROS (V6)
-- Solo se pueden ver miembros si la comunidad/grupo NO está borrada
CREATE POLICY "comm_mem_select_v6" ON public.community_members
    FOR SELECT USING (
        EXISTS (SELECT 1 FROM communities WHERE id = community_id AND is_deleted = FALSE)
        OR EXISTS (SELECT 1 FROM communities WHERE id = community_id AND creator_id = auth.uid())
    );

CREATE POLICY "group_mem_select_v6" ON public.group_members
    FOR SELECT USING (
        EXISTS (SELECT 1 FROM groups WHERE id = group_id AND is_deleted = FALSE)
        OR EXISTS (SELECT 1 FROM groups WHERE id = group_id AND creator_id = auth.uid())
    );

-- Políticas generales de inserción/borrado de miembros
CREATE POLICY "comm_mem_mod_v6" ON public.community_members
    FOR ALL USING (auth.role() = 'authenticated');

CREATE POLICY "group_mem_mod_v6" ON public.group_members
    FOR ALL USING (auth.role() = 'authenticated');

-- 6. POLÍTICAS DE MENSAJES (V6)
-- BLOQUEO TOTAL DE MENSAJES SI ESTÁ BORRADO
CREATE POLICY "comm_msg_select_v6" ON public.community_messages
    FOR SELECT USING (
        EXISTS (SELECT 1 FROM communities WHERE id = community_id AND is_deleted = FALSE)
        OR EXISTS (SELECT 1 FROM communities WHERE id = community_id AND creator_id = auth.uid())
    );

CREATE POLICY "comm_msg_insert_v6" ON public.community_messages
    FOR INSERT WITH CHECK (
        EXISTS (SELECT 1 FROM communities WHERE id = community_id AND is_deleted = FALSE)
    );

CREATE POLICY "group_msg_select_v6" ON public.group_messages
    FOR SELECT USING (
        EXISTS (SELECT 1 FROM groups WHERE id = group_id AND is_deleted = FALSE)
        OR EXISTS (SELECT 1 FROM groups WHERE id = group_id AND creator_id = auth.uid())
    );

CREATE POLICY "group_msg_insert_v6" ON public.group_messages
    FOR INSERT WITH CHECK (
        EXISTS (SELECT 1 FROM groups WHERE id = group_id AND is_deleted = FALSE)
    );

-- 7. ÍNDICES PARA VELOCIDAD
CREATE INDEX IF NOT EXISTS idx_communities_is_deleted ON public.communities(is_deleted);
CREATE INDEX IF NOT EXISTS idx_groups_is_deleted ON public.groups(is_deleted);
