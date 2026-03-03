-- Tablas para el sistema de Retos en Tiempo Real

-- 1. Catálogo Maestro de Retos
CREATE TABLE IF NOT EXISTS challenges_master (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title TEXT NOT NULL,
    description TEXT,
    challenge_type TEXT NOT NULL, -- 'drawing', 'camera', 'text'
    difficulty TEXT DEFAULT 'medium', -- 'easy', 'medium', 'hard'
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 2. Retos Activos (Sincronización comunitaria con Fases)
CREATE TABLE IF NOT EXISTS active_challenges (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    challenge_id UUID REFERENCES challenges_master(id) ON DELETE CASCADE,
    start_time TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL, -- Fin fase de realización
    voting_expires_at TIMESTAMP WITH TIME ZONE, -- Fin fase de votación (expires_at + 1 min)
    status TEXT DEFAULT 'active', -- 'active', 'voting', 'completed'
    winner_ids UUID[] DEFAULT '{}', -- Top 3 user_ids
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 4. Sistema de Votos
CREATE TABLE IF NOT EXISTS challenge_votes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    submission_id UUID REFERENCES challenge_submissions(id) ON DELETE CASCADE,
    active_challenge_id UUID REFERENCES active_challenges(id) ON DELETE CASCADE,
    voter_id UUID REFERENCES profiles(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    UNIQUE(active_challenge_id, voter_id) -- Un voto por usuario por reto
);

-- 3. Participaciones de Usuarios (Logros)
CREATE TABLE IF NOT EXISTS challenge_submissions (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    challenge_id UUID REFERENCES challenges_master(id) ON DELETE CASCADE,
    active_challenge_id UUID REFERENCES active_challenges(id) ON DELETE CASCADE,
    user_id UUID REFERENCES profiles(id) ON DELETE CASCADE,
    content_url TEXT, -- URL de la imagen (dibujo o foto)
    text_content TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Habilitar Realtime de forma segura
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' 
        AND schemaname = 'public' 
        AND tablename = 'active_challenges'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE active_challenges;
    END IF;
END $$;

-- Alterar retos iniciales para incluir texto
INSERT INTO challenges_master (title, description, challenge_type, difficulty)
VALUES 
('Confesión anónima', 'Escribe algo que nadie sepa de ti.', 'text', 'easy'),
('Adivina el acertijo', 'Me tienes hoy y me esperas mañana. ¿Qué soy? (Responde en texto)', 'text', 'medium'),
('Canta tu canción favorita', 'Graba un audio cantando el coro de una canción que te guste.', 'audio', 'medium'),
('Baile improvisado', 'Sube un video de 5 segundos bailando lo más raro que puedas.', 'camera', 'hard'),
('Dibuja un unicornio punk', 'Usa el lienzo para dibujar un unicornio con estilo punk rock.', 'drawing', 'hard'),
('Teléfono Malogrado (Texto)', 'Describe el último sueño que tuviste en una frase.', 'text', 'easy');

-- Función mejorada para manejar fases y ganadores automáticamente
CREATE OR REPLACE FUNCTION get_or_generate_active_challenge()
RETURNS SETOF active_challenges AS $$
DECLARE
    current_active active_challenges;
BEGIN
    -- 1. Buscar reto que esté en fase 'active' o 'voting'
    SELECT * INTO current_active 
    FROM active_challenges 
    WHERE (status = 'active' AND expires_at > NOW()) 
       OR (status = 'voting' AND voting_expires_at > NOW())
    ORDER BY created_at DESC 
    LIMIT 1;

    -- 2. Si hay un reto 'active' que ya expiró, pasarlo a 'voting'
    IF FOUND AND current_active.status = 'active' AND current_active.expires_at <= NOW() THEN
        UPDATE active_challenges 
        SET status = 'voting', 
            voting_expires_at = NOW() + INTERVAL '1 minute'
        WHERE id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    -- 3. Si hay un reto 'voting' que ya expiró, calcular ganadores y cerrarlo
    IF FOUND AND current_active.status = 'voting' AND current_active.voting_expires_at <= NOW() THEN
        -- Calcular Top 3
        WITH top_voted AS (
            SELECT user_id, COUNT(*) as vote_count
            FROM challenge_submissions s
            JOIN challenge_votes v ON v.submission_id = s.id
            WHERE s.active_challenge_id = current_active.id
            GROUP BY user_id
            ORDER BY vote_count DESC
            LIMIT 3
        )
        UPDATE active_challenges 
        SET status = 'completed',
            winner_ids = ARRAY(SELECT user_id FROM top_voted)
        WHERE id = current_active.id;
        
        -- Marcarlo como no encontrado para generar uno nuevo ipso-facto
        current_active := NULL;
    END IF;

    -- 4. Generar uno nuevo si no hay nada activo/voting
    IF current_active IS NULL THEN
        INSERT INTO active_challenges (challenge_id, expires_at)
        SELECT id, NOW() + INTERVAL '10 minutes'
        FROM challenges_master
        ORDER BY RANDOM()
        LIMIT 1
        RETURNING * INTO current_active;
    END IF;

    RETURN NEXT current_active;
END;
$$ LANGUAGE plpgsql;
