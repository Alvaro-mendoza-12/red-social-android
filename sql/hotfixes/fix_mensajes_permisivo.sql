-- ==========================================
-- FIX: POLÍTICAS PERMISIVAS PARA MENSAJES
-- ==========================================

-- Deshabilitar RLS temporalmente
ALTER TABLE public.group_messages DISABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_messages DISABLE ROW LEVEL SECURITY;

-- Eliminar TODAS las políticas existentes
DO $$ 
DECLARE pol RECORD;
BEGIN
    FOR pol IN SELECT policyname FROM pg_policies WHERE tablename = 'group_messages' AND schemaname = 'public'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.group_messages', pol.policyname);
    END LOOP;
    
    FOR pol IN SELECT policyname FROM pg_policies WHERE tablename = 'community_messages' AND schemaname = 'public'
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS %I ON public.community_messages', pol.policyname);
    END LOOP;
END $$;

-- ========== GROUP_MESSAGES (PERMISIVO) ==========

CREATE POLICY "group_messages_permissive_select" 
ON public.group_messages
FOR SELECT 
TO authenticated
USING (true);

CREATE POLICY "group_messages_permissive_insert" 
ON public.group_messages
FOR INSERT 
TO authenticated
WITH CHECK (true);

CREATE POLICY "group_messages_permissive_update" 
ON public.group_messages
FOR UPDATE 
TO authenticated
USING (sender_id = auth.uid());

CREATE POLICY "group_messages_permissive_delete" 
ON public.group_messages
FOR DELETE 
TO authenticated
USING (sender_id = auth.uid());

-- ========== COMMUNITY_MESSAGES (PERMISIVO) ==========

CREATE POLICY "community_messages_permissive_select" 
ON public.community_messages
FOR SELECT 
TO authenticated
USING (true);

CREATE POLICY "community_messages_permissive_insert" 
ON public.community_messages
FOR INSERT 
TO authenticated
WITH CHECK (true);

CREATE POLICY "community_messages_permissive_update" 
ON public.community_messages
FOR UPDATE 
TO authenticated
USING (sender_id = auth.uid());

CREATE POLICY "community_messages_permissive_delete" 
ON public.community_messages
FOR DELETE 
TO authenticated
USING (sender_id = auth.uid());

-- Reactivar RLS
ALTER TABLE public.group_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_messages ENABLE ROW LEVEL SECURITY;

-- Verificación
SELECT '✅ GROUP_MESSAGES POLICIES' as status;
SELECT policyname, cmd FROM pg_policies WHERE tablename = 'group_messages' ORDER BY cmd;

SELECT '✅ COMMUNITY_MESSAGES POLICIES' as status;
SELECT policyname, cmd FROM pg_policies WHERE tablename = 'community_messages' ORDER BY cmd;
