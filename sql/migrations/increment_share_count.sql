-- 1. Asegurar que la columna existe en la tabla de videos
-- Esto permite llevar la cuenta global de compartidos directamente en cada video
ALTER TABLE public.videos ADD COLUMN IF NOT EXISTS share_count INTEGER DEFAULT 0;

-- 2. Asegurar que la tabla shares tiene los permisos correctos
-- Si la tabla no existe por alguna razón, este script la crea (failsafe)
CREATE TABLE IF NOT EXISTS public.shares (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE NOT NULL,
    post_id UUID REFERENCES public.posts(id) ON DELETE CASCADE,
    video_id UUID REFERENCES public.videos(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now()) NOT NULL,
    CONSTRAINT check_only_one_target CHECK (
        (post_id IS NOT NULL AND video_id IS NULL) OR
        (post_id IS NULL AND video_id IS NOT NULL)
    ),
    UNIQUE(user_id, post_id),
    UNIQUE(user_id, video_id)
);

-- 3. Habilitar RLS en shares
ALTER TABLE public.shares ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Shares are viewable by everyone" ON public.shares;
CREATE POLICY "Shares are viewable by everyone" ON public.shares FOR SELECT USING (true);

DROP POLICY IF EXISTS "Users can manage their own shares" ON public.shares;
CREATE POLICY "Users can manage their own shares" ON public.shares FOR ALL USING (auth.uid() = user_id);

-- 4. Crear o actualizar la función RPC para incremento atómico
-- Esta función se encarga de aumentar/disminuir el contador global
DROP FUNCTION IF EXISTS public.increment_share_count(UUID, INTEGER);

CREATE OR REPLACE FUNCTION public.increment_share_count(video_id UUID, delta INTEGER)
RETURNS void AS $$
BEGIN
  UPDATE public.videos
  SET share_count = GREATEST(0, COALESCE(share_count, 0) + delta)
  WHERE id = video_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
