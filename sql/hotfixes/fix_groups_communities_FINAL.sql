-- ========================================================
-- FIX FINAL: EDICIÓN, BORRADO Y TIEMPO REAL (GRUPOS Y COMUNIDADES)
-- ========================================================

-- 1. AGREGAR COLUMNAS FALTANTES
-- Estas columnas son necesarias para que Supabase guarde los estados de edición y borrado
ALTER TABLE public.group_messages 
ADD COLUMN IF NOT EXISTS is_edited BOOLEAN DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS deleted_for_all BOOLEAN DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS deleted_by TEXT[] DEFAULT '{}',
ADD COLUMN IF NOT EXISTS reply_to_id UUID;

ALTER TABLE public.community_messages 
ADD COLUMN IF NOT EXISTS is_edited BOOLEAN DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS deleted_for_all BOOLEAN DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS deleted_by TEXT[] DEFAULT '{}',
ADD COLUMN IF NOT EXISTS reply_to_id UUID;

-- 2. CONFIGURAR REPLICA IDENTITY (Necesario para Realtime UPDATE/DELETE)
ALTER TABLE public.group_messages REPLICA IDENTITY FULL;
ALTER TABLE public.community_messages REPLICA IDENTITY FULL;

-- 3. POLÍTICAS DE RLS ROBUSTAS (Permitir a Admin y Dueño borrar mensajes ajenos)

-- --- GROUP MESSAGES ---
DROP POLICY IF EXISTS "Users can update their own group messages" ON public.group_messages;
DROP POLICY IF EXISTS "Users can update/delete group messages" ON public.group_messages;
CREATE POLICY "Users can update/delete group messages" ON public.group_messages
FOR UPDATE USING (
    auth.uid() = sender_id OR 
    EXISTS (
        SELECT 1 FROM public.group_members 
        WHERE group_id = group_messages.group_id 
        AND user_id = auth.uid() 
        AND role = 'admin'
    ) OR
    EXISTS (
        SELECT 1 FROM public.groups
        WHERE id = group_messages.group_id
        AND creator_id = auth.uid()
    )
);

DROP POLICY IF EXISTS "Users can delete their own group messages" ON public.group_messages;
DROP POLICY IF EXISTS "Users can delete group messages" ON public.group_messages;
CREATE POLICY "Users can delete group messages" ON public.group_messages
FOR DELETE USING (
    auth.uid() = sender_id OR 
    EXISTS (
        SELECT 1 FROM public.group_members 
        WHERE group_id = group_messages.group_id 
        AND user_id = auth.uid() 
        AND role = 'admin'
    ) OR
    EXISTS (
        SELECT 1 FROM public.groups
        WHERE id = group_messages.group_id
        AND creator_id = auth.uid()
    )
);

-- --- COMMUNITY MESSAGES ---
DROP POLICY IF EXISTS "Users can update their own community messages" ON public.community_messages;
DROP POLICY IF EXISTS "Users can update/delete community messages" ON public.community_messages;
CREATE POLICY "Users can update/delete community messages" ON public.community_messages
FOR UPDATE USING (
    auth.uid() = sender_id OR 
    EXISTS (
        SELECT 1 FROM public.community_members 
        WHERE community_id = community_messages.community_id 
        AND user_id = auth.uid() 
        AND role IN ('owner', 'admin')
    ) OR
    EXISTS (
        SELECT 1 FROM public.communities
        WHERE id = community_messages.community_id
        AND creator_id = auth.uid()
    )
);

DROP POLICY IF EXISTS "Users can delete their own community messages" ON public.community_messages;
DROP POLICY IF EXISTS "Users can delete community messages" ON public.community_messages;
CREATE POLICY "Users can delete community messages" ON public.community_messages
FOR DELETE USING (
    auth.uid() = sender_id OR 
    EXISTS (
        SELECT 1 FROM public.community_members 
        WHERE community_id = community_messages.community_id 
        AND user_id = auth.uid() 
        AND role IN ('owner', 'admin')
    ) OR
    EXISTS (
        SELECT 1 FROM public.communities
        WHERE id = community_messages.community_id
        AND creator_id = auth.uid()
    )
);

-- 4. ASEGURAR PUBLICACIÓN REALTIME
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime') THEN
        CREATE PUBLICATION supabase_realtime;
    END IF;
    
    -- Agregar tablas si no están
    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'group_messages') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.group_messages;
    END IF;
    
    IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'community_messages') THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.community_messages;
    END IF;
END $$;

SELECT '✅ FIX GRUPOS/COMUNIDADES APLICADO EXITOSAMENTE' as status;
