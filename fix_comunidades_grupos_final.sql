-- REMAURACIÓN Y LIMPIEZA DE COMUNIDADES Y GRUPOS
-- Ejecutar este script en el SQL Editor de Supabase

-- 1. Asegurar que las tablas existan con la estructura correcta
CREATE TABLE IF NOT EXISTS public.community_messages (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    community_id UUID REFERENCES public.communities(id) ON DELETE CASCADE,
    sender_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    content TEXT NOT NULL,
    content_type TEXT DEFAULT 'text',
    media_url TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    metadata JSONB DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS public.group_messages (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    group_id UUID REFERENCES public.groups(id) ON DELETE CASCADE,
    sender_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    content TEXT NOT NULL,
    content_type TEXT DEFAULT 'text',
    media_url TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    status TEXT DEFAULT 'sent',
    metadata JSONB DEFAULT '{}'::jsonb
);

-- ADICIÓN EXPLÍCITA DE COLUMNAS (Para tablas que ya existían)
ALTER TABLE public.community_messages ADD COLUMN IF NOT EXISTS media_url TEXT;
ALTER TABLE public.group_messages ADD COLUMN IF NOT EXISTS media_url TEXT;

-- RECARGAR CACHÉ DE ESQUEMA
NOTIFY pgrst, 'reload schema';

-- 2. Habilitar RLS
ALTER TABLE public.communities ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.community_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.groups ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.group_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.group_messages ENABLE ROW LEVEL SECURITY;

-- 3. Limpiar y Re-crear Políticas (Evita duplicados y ambigüedades)
DROP POLICY IF EXISTS "Users can send messages to communities they are members of" ON public.community_messages;
CREATE POLICY "Users can send messages to communities they are members of" ON public.community_messages
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.community_members cm
            WHERE cm.community_id = community_messages.community_id 
            AND cm.user_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Users can send messages to their groups" ON public.group_messages;
CREATE POLICY "Users can send messages to their groups" ON public.group_messages
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.group_members gm
            WHERE gm.group_id = group_messages.group_id 
            AND gm.user_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "Users can see messages in communities they can see" ON public.community_messages;
CREATE POLICY "Users can see messages in communities they can see" ON public.community_messages
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.communities c
            WHERE c.id = community_messages.community_id 
            AND (c.is_public = TRUE OR EXISTS (
                SELECT 1 FROM public.community_members cm 
                WHERE cm.community_id = c.id AND cm.user_id = auth.uid()
            ))
        )
    );

DROP POLICY IF EXISTS "Users can see messages in their groups" ON public.group_messages;
CREATE POLICY "Users can see messages in their groups" ON public.group_messages
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM public.group_members gm
            WHERE gm.group_id = group_messages.group_id 
            AND gm.user_id = auth.uid()
        )
    );

-- 4. Limpieza de Duplicados en el Servidor (Opcional pero recomendado)
-- Mantiene solo el registro más antiguo de comunidades con el mismo nombre y creador
DELETE FROM public.communities
WHERE id IN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (PARTITION BY TRIM(LOWER(name)), creator_id ORDER BY created_at ASC) as row_num
        FROM public.communities
    ) t
    WHERE t.row_num > 1
);
