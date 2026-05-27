-- MÓDULO 4: PERFIL, AJUSTES Y MEJORAS GENERALES
-- Este script complementa los anteriores para alcanzar el 100% de funcionalidad.

-- 1. Campos de personalización avanzada para Perfil (Estilo Discord)
ALTER TABLE public.profiles 
ADD COLUMN IF NOT EXISTS favorite_games TEXT[] DEFAULT '{}',
ADD COLUMN IF NOT EXISTS favorite_animes TEXT[] DEFAULT '{}',
ADD COLUMN IF NOT EXISTS favorite_music TEXT,
ADD COLUMN IF NOT EXISTS favorite_books TEXT,
ADD COLUMN IF NOT EXISTS favorite_sports TEXT,
ADD COLUMN IF NOT EXISTS career TEXT,
ADD COLUMN IF NOT EXISTS schedule_preference TEXT,
ADD COLUMN IF NOT EXISTS status_message TEXT,
ADD COLUMN IF NOT EXISTS likes_count INTEGER DEFAULT 0,
ADD COLUMN IF NOT EXISTS views_count INTEGER DEFAULT 0;

-- 2. Sistema de Retos (Challenges)
CREATE TABLE IF NOT EXISTS public.challenges (
  id UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
  creator_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
  title TEXT NOT NULL,
  description TEXT,
  target_reps INTEGER DEFAULT 0,
  deadline TIMESTAMP WITH TIME ZONE,
  category TEXT DEFAULT 'general', -- 'fitness', 'gaming', 'social'
  status TEXT DEFAULT 'active', -- 'active', 'completed', 'expired'
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 3. Participación en Retos (Evidencia y Validación)
CREATE TABLE IF NOT EXISTS public.challenge_submissions (
  id UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
  challenge_id UUID REFERENCES public.challenges(id) ON DELETE CASCADE,
  user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
  media_url TEXT NOT NULL,
  comment TEXT,
  is_verified BOOLEAN DEFAULT FALSE,
  likes_count INTEGER DEFAULT 0,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(challenge_id, user_id)
);

-- 4. Vistas de Ranking (Top 100)
-- Ranking de usuarios por seguidores
CREATE OR REPLACE VIEW public.view_ranking_users AS
SELECT 
    id, 
    username, 
    full_name, 
    avatar_url, 
    followers_count,
    ROW_NUMBER() OVER (ORDER BY followers_count DESC) as rank_position
FROM public.profiles
LIMIT 100;

-- Ranking de videos por impacto (vistas + likes)
CREATE OR REPLACE VIEW public.view_ranking_videos AS
SELECT 
    v.id, 
    v.title, 
    v.video_url, 
    v.thumbnail_url,
    p.username as author_name,
    (v.views + v.like_count * 5 + v.share_count * 10) as impact_score,
    ROW_NUMBER() OVER (ORDER BY (v.views + v.like_count * 5 + v.share_count * 10) DESC) as rank_position
FROM public.videos v
JOIN public.profiles p ON v.user_id = p.id
WHERE v.is_public = true
LIMIT 100;

-- Ranking de publicaciones por impacto (likes + compartidos)
CREATE OR REPLACE VIEW public.view_ranking_posts AS
SELECT 
    p.id, 
    p.content, 
    p.media_urls,
    u.username as author_name,
    (p.like_count + p.share_count * 2) as impact_score,
    ROW_NUMBER() OVER (ORDER BY (p.like_count + p.share_count * 2) DESC) as rank_position
FROM public.posts p
JOIN public.profiles u ON p.user_id = u.id
WHERE p.visibility = 'public'
LIMIT 100;

-- 5. Registro de Cuentas Guardadas (Para cambio rápido de cuenta)
CREATE TABLE IF NOT EXISTS public.saved_accounts (
  id UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
  main_user_id UUID REFERENCES auth.users(id) ON DELETE CASCADE,
  linked_user_id UUID REFERENCES public.profiles(id) ON DELETE CASCADE,
  last_login TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(main_user_id, linked_user_id)
);

-- 6. Configuración de Privacidad Extendida
ALTER TABLE public.profiles
ADD COLUMN IF NOT EXISTS privacy_show_online BOOLEAN DEFAULT TRUE,
ADD COLUMN IF NOT EXISTS privacy_show_last_seen BOOLEAN DEFAULT TRUE,
ADD COLUMN IF NOT EXISTS privacy_allow_messages_from TEXT DEFAULT 'everyone'; -- 'everyone', 'friends'

-- 7. Habilitar Realtime para las nuevas tablas
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'challenges') THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.challenges;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_publication_tables WHERE pubname = 'supabase_realtime' AND tablename = 'challenge_submissions') THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.challenge_submissions;
  END IF;
END $$;
