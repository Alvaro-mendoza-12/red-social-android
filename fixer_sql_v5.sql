-- ========================================================
-- REPARACIÓN DEFINITIVA V5: TRASPASO DE RLS Y VISIBILIDAD
-- ========================================================

-- 1. LIMPIAR POLÍTICAS ANTERIORES
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

-- 2. POLÍTICAS DE COMUNIDADES (V5)
-- Permitimos SELECT si: No está borrada O eres el creador O eres miembro (para recibir el update de borrado)
CREATE POLICY "comm_select_v5" ON public.communities
    FOR SELECT USING (
        (is_deleted = FALSE) OR 
        (creator_id = auth.uid()) OR 
        (EXISTS (SELECT 1 FROM community_members WHERE community_id = id AND user_id = auth.uid()))
    );

CREATE POLICY "comm_insert_v5" ON public.communities
    FOR INSERT WITH CHECK (auth.role() = 'authenticated');

CREATE POLICY "comm_update_v5" ON public.communities
    FOR UPDATE USING (creator_id = auth.uid());

-- 3. POLÍTICAS DE GRUPOS (V5)
CREATE POLICY "groups_select_v5" ON public.groups
    FOR SELECT USING (
        (is_deleted = FALSE) OR 
        (creator_id = auth.uid()) OR 
        (EXISTS (SELECT 1 FROM group_members WHERE group_id = id AND user_id = auth.uid()))
    );

CREATE POLICY "groups_insert_v5" ON public.groups
    FOR INSERT WITH CHECK (auth.role() = 'authenticated');

CREATE POLICY "groups_update_v5" ON public.groups
    FOR UPDATE USING (creator_id = auth.uid());

-- 4. POLÍTICAS DE MENSAJES (BLOQUEO SI ESTÁ BORRADO)
CREATE POLICY "comm_msg_insert_v5" ON public.community_messages
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1 FROM communities c 
            WHERE c.id = community_id 
              AND c.is_deleted = FALSE -- NO ENVIAR SI ESTÁ BORRADA
              AND (c.is_public = TRUE OR EXISTS (
                  SELECT 1 FROM community_members WHERE community_id = c.id AND user_id = auth.uid()
              ))
        )
    );

CREATE POLICY "groups_msg_insert_v5" ON public.group_messages
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1 FROM groups g 
            WHERE g.id = group_id 
              AND g.is_deleted = FALSE -- NO ENVIAR SI ESTÁ BORRADO
              AND EXISTS (
                  SELECT 1 FROM group_members WHERE group_id = g.id AND user_id = auth.uid()
              )
        )
    );

-- 5. POLÍTICAS DE MIEMBROS (SIMPLIFICADAS)
CREATE POLICY "comm_mem_select_v5" ON public.community_members FOR SELECT USING (TRUE);
CREATE POLICY "comm_mem_insert_v5" ON public.community_members FOR INSERT WITH CHECK (auth.uid() = user_id OR EXISTS (SELECT 1 FROM communities WHERE id = community_id AND creator_id = auth.uid()));
CREATE POLICY "comm_mem_delete_v5" ON public.community_members FOR DELETE USING (auth.uid() = user_id OR EXISTS (SELECT 1 FROM communities WHERE id = community_id AND creator_id = auth.uid()));

CREATE POLICY "group_mem_select_v5" ON public.group_members FOR SELECT USING (TRUE);
CREATE POLICY "group_mem_insert_v5" ON public.group_members FOR INSERT WITH CHECK (auth.role() = 'authenticated');
CREATE POLICY "group_mem_delete_v5" ON public.group_members FOR DELETE USING (auth.uid() = user_id OR EXISTS (SELECT 1 FROM groups WHERE id = group_id AND creator_id = auth.uid()));

-- 6. POLÍTICAS DE LECTURA DE MENSAJES
CREATE POLICY "comm_msg_select_v5" ON public.community_messages FOR SELECT USING (TRUE);
CREATE POLICY "group_msg_select_v5" ON public.group_messages FOR SELECT USING (TRUE);
