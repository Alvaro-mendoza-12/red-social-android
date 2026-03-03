-- ========================================================
-- REPARACIÓN NUCLEAR V15: VISIBILIDAD DE GRUPOS Y SEÑALES
-- ========================================================

-- Objetivo: Que los nuevos miembros vean el grupo al instante.
-- Error previo: Faltaba la política SELECT para la tabla 'groups'.

BEGIN;

-- 1. POLÍTICAS PARA LA TABLA 'groups' (Faltaban en V14)
-- Solo puedes ver el grupo si eres el creador o si eres miembro.
DROP POLICY IF EXISTS "group_select_v15" ON public.groups;
CREATE POLICY "group_select_v15" ON public.groups
    FOR SELECT USING (
        creator_id = auth.uid() 
        OR 
        EXISTS (
            SELECT 1 FROM public.group_members 
            WHERE group_id = public.groups.id 
              AND user_id = auth.uid()
        )
    );

-- 2. REFORZAR POLÍTICA DE MIEMBROS
-- Aseguramos que un usuario pueda ver SIEMPRE su propia membresía 
-- (crucial para recibir el evento INSERT de Realtime)
DROP POLICY IF EXISTS "group_mem_select_v14" ON public.group_members;
DROP POLICY IF EXISTS "group_mem_select_v15" ON public.group_members;
CREATE POLICY "group_mem_select_v15" ON public.group_members
    FOR SELECT USING (
        user_id = auth.uid() -- Puedes ver tu propia fila
        OR 
        EXISTS ( -- O puedes ver a otros si tú estás en el grupo
            SELECT 1 FROM public.group_members AS self
            WHERE self.group_id = public.group_members.group_id 
              AND self.user_id = auth.uid()
        )
    );

-- 3. ASEGURAR REALTIME PARA LA TABLA 'groups'
-- Si se crea un grupo, queremos que los miembros se enteren (aunque es más seguro vía membresías)
ALTER TABLE public.groups REPLICA IDENTITY FULL;

COMMIT;

-- EXPLICACIÓN:
-- 1. Cuando creas un grupo, se inserta en 'groups' y en 'group_members'.
-- 2. El socket del usuario invitado recibe el INSERT de 'group_members' 
--    porque ahora la política 'user_id = auth.uid()' es directa.
-- 3. Al recibir el evento, el código llama a 'syncGroups()'.
-- 4. 'syncGroups()' ahora funcionará porque ya existe la política SELECT para 'groups'.
