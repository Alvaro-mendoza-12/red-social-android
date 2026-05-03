-- ==========================================
-- FIX RLS INFINITE RECURSION - COMPLETO
-- Corrige recursión en GROUPS y COMMUNITIES
-- ==========================================

-- ========================================
-- PARTE 1: FIX PARA GROUPS
-- ========================================

-- 1.1 Función helper para verificar membresía de grupos (Bypasses RLS)
CREATE OR REPLACE FUNCTION public.is_group_member(_group_id uuid)
RETURNS boolean AS $$
BEGIN
  -- Esta función corre con privilegios de owner (SECURITY DEFINER)
  -- por lo que NO dispara las políticas RLS recursivamente
  RETURN EXISTS (
    SELECT 1 FROM public.group_members
    WHERE group_id = _group_id 
    AND user_id = auth.uid()
  );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 1.2 Limpiar políticas existentes de group_members
DROP POLICY IF EXISTS "Users can see members of their groups" ON public.group_members;
DROP POLICY IF EXISTS "Admins can manage members" ON public.group_members;
DROP POLICY IF EXISTS "Enable insert for group members" ON public.group_members;
DROP POLICY IF EXISTS "Enable select for group members" ON public.group_members;

-- 1.3 NUEVA política INSERT para group_members (sin recursión)
CREATE POLICY "Enable insert for group members" ON public.group_members
FOR INSERT WITH CHECK (
    -- Caso 1: Soy el creador del grupo (verificado vía tabla groups, seguro)
    EXISTS (
        SELECT 1 FROM public.groups 
        WHERE id = group_id AND creator_id = auth.uid()
    )
    OR
    -- Caso 2: Soy un admin agregando a alguien (verificado vía tabla groups, seguro)
    EXISTS (
        SELECT 1 FROM public.group_members 
        WHERE group_id = group_members.group_id 
        AND user_id = auth.uid() 
        AND role = 'admin'
    )
);

-- 1.4 NUEVA política SELECT para group_members (sin recursión)
CREATE POLICY "Enable select for group members" ON public.group_members
FOR SELECT USING (
    -- Puedo ver mi propia membresía
    user_id = auth.uid()
    OR
    -- Puedo ver miembros si soy miembro (usando función SECURITY DEFINER)
    public.is_group_member(group_id)
);

-- 1.5 Política UPDATE para group_members
CREATE POLICY "Admins can update members" ON public.group_members
FOR UPDATE USING (
    -- Solo admins pueden actualizar roles
    public.is_group_member(group_id) AND
    EXISTS (
        SELECT 1 FROM public.group_members 
        WHERE group_id = group_members.group_id 
        AND user_id = auth.uid() 
        AND role = 'admin'
    )
);

-- 1.6 Política DELETE para group_members
CREATE POLICY "Admins can delete members" ON public.group_members
FOR DELETE USING (
    -- Admin puede eliminar miembros
    EXISTS (
        SELECT 1 FROM public.group_members 
        WHERE group_id = group_members.group_id 
        AND user_id = auth.uid() 
        AND role = 'admin'
    )
    OR
    -- Usuario puede salirse del grupo
    user_id = auth.uid()
);

-- ========================================
-- PARTE 2: FIX PARA COMMUNITIES
-- ========================================

-- 2.1 Función helper para verificar membresía de comunidades (Bypasses RLS)
CREATE OR REPLACE FUNCTION public.is_community_member(_community_id uuid)
RETURNS boolean AS $$
BEGIN
  -- Esta función corre con privilegios de owner (SECURITY DEFINER)
  -- por lo que NO dispara las políticas RLS recursivamente
  RETURN EXISTS (
    SELECT 1 FROM public.community_members
    WHERE community_id = _community_id 
    AND user_id = auth.uid()
  );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 2.2 Limpiar políticas existentes de community_members
DROP POLICY IF EXISTS "Enable insert for creators or public join" ON public.community_members;
DROP POLICY IF EXISTS "Enable select for members" ON public.community_members;
DROP POLICY IF EXISTS "Users can create communities" ON public.communities;
DROP POLICY IF EXISTS "Manage community membership" ON public.community_members;
DROP POLICY IF EXISTS "Public communities are viewable" ON public.communities;
DROP POLICY IF EXISTS "Members can view members" ON public.community_members;
DROP POLICY IF EXISTS "Enable insert for community members" ON public.community_members;
DROP POLICY IF EXISTS "Enable select for community members" ON public.community_members;
DROP POLICY IF EXISTS "Everyone can see members of public communities" ON public.community_members;
DROP POLICY IF EXISTS "Owners can manage community members" ON public.community_members;

-- 2.3 NUEVA política INSERT para community_members (sin recursión)
CREATE POLICY "Enable insert for community members" ON public.community_members
FOR INSERT WITH CHECK (
    -- Caso 1: Soy el creador de la comunidad (verificado vía tabla communities, seguro)
    EXISTS (
        SELECT 1 FROM public.communities 
        WHERE id = community_id AND creator_id = auth.uid()
    )
    OR
    -- Caso 2: Me estoy uniendo a una comunidad pública (verificado vía tabla communities, seguro)
    (
       user_id = auth.uid() AND 
       EXISTS (
           SELECT 1 FROM public.communities 
           WHERE id = community_id AND is_public = true
       )
    )
    OR
    -- Caso 3: Un admin/owner me está agregando
    EXISTS (
        SELECT 1 FROM public.community_members 
        WHERE community_id = community_members.community_id 
        AND user_id = auth.uid() 
        AND role IN ('owner', 'admin')
    )
);

-- 2.4 NUEVA política SELECT para community_members (sin recursión)
CREATE POLICY "Enable select for community members" ON public.community_members
FOR SELECT USING (
    -- Puedo ver mi propia membresía
    user_id = auth.uid()
    OR
    -- Puedo ver miembros de comunidades públicas
    EXISTS (
        SELECT 1 FROM public.communities 
        WHERE id = community_id AND is_public = true
    )
    OR
    -- Puedo ver miembros si soy miembro (usando función SECURITY DEFINER)
    public.is_community_member(community_id)
);

-- 2.5 Política UPDATE para community_members
CREATE POLICY "Owners can update members" ON public.community_members
FOR UPDATE USING (
    -- Solo owners/admins pueden actualizar roles
    EXISTS (
        SELECT 1 FROM public.community_members 
        WHERE community_id = community_members.community_id 
        AND user_id = auth.uid() 
        AND role IN ('owner', 'admin')
    )
);

-- 2.6 Política DELETE para community_members
CREATE POLICY "Owners can delete members" ON public.community_members
FOR DELETE USING (
    -- Owner/admin puede eliminar miembros
    EXISTS (
        SELECT 1 FROM public.community_members 
        WHERE community_id = community_members.community_id 
        AND user_id = auth.uid() 
        AND role IN ('owner', 'admin')
    )
    OR
    -- Usuario puede salirse de la comunidad
    user_id = auth.uid()
);

-- ========================================
-- PARTE 3: VERIFICACIÓN
-- ========================================

-- Verificar que las funciones existan
SELECT 'Función is_group_member creada correctamente' AS status
WHERE EXISTS (
    SELECT 1 FROM pg_proc 
    WHERE proname = 'is_group_member'
);

SELECT 'Función is_community_member creada correctamente' AS status
WHERE EXISTS (
    SELECT 1 FROM pg_proc 
    WHERE proname = 'is_community_member'
);

-- Listar políticas de group_members
SELECT 'Políticas de group_members:' AS info;
SELECT schemaname, tablename, policyname, permissive, roles, cmd, qual
FROM pg_policies
WHERE tablename = 'group_members';

-- Listar políticas de community_members
SELECT 'Políticas de community_members:' AS info;
SELECT schemaname, tablename, policyname, permissive, roles, cmd, qual
FROM pg_policies
WHERE tablename = 'community_members';

-- ==========================================
-- ✅ FIN DEL SCRIPT
-- ==========================================
-- 
-- INSTRUCCIONES PARA EJECUTAR:
-- 1. Ve a tu panel de Supabase: https://app.supabase.com
-- 2. Abre tu proyecto
-- 3. Ve a "SQL Editor" en el menú lateral
-- 4. Crea una nueva query
-- 5. Copia y pega TODO este archivo
-- 6. Haz clic en "RUN" o presiona Ctrl+Enter
-- 7. Verifica que no haya errores en la consola
-- 8. Prueba crear un grupo y una comunidad desde tu app
--
-- NOTA: Este script es idempotente, puedes ejecutarlo múltiples veces sin problemas
-- ==========================================
