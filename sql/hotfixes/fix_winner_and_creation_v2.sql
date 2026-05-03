-- 1. Permitir que usuarios autenticados creen sus propios retos maestros
-- Esto resuelve el error "Failed to create master challenge"
ALTER TABLE challenges_master ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies 
        WHERE tablename = 'challenges_master' AND policyname = 'Authenticated users can create challenges'
    ) THEN
        CREATE POLICY "Authenticated users can create challenges" ON challenges_master
        FOR INSERT TO authenticated
        WITH CHECK (true);
    END IF;
END $$;

-- 2. Asegurar que active_challenges también sea insertable por el sistema/RPC
-- (Generalmente el RPC corre con SECURITY DEFINER, pero por si acaso)
ALTER TABLE active_challenges ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies 
        WHERE tablename = 'active_challenges' AND policyname = 'Authenticated users can insert active_challenges'
    ) THEN
        CREATE POLICY "Authenticated users can insert active_challenges" ON active_challenges
        FOR INSERT TO authenticated
        WITH CHECK (true);
    END IF;
END $$;

-- 3. Refinar la transición de ganadores para asegurar orden de votos exacto
-- (Añadimos DROP FUNCTION porque el tipo de retorno cambió en versiones previas)
DROP FUNCTION IF EXISTS get_or_generate_active_challenge();

CREATE OR REPLACE FUNCTION get_or_generate_active_challenge()
RETURNS SETOF active_challenges AS $$
DECLARE
    current_active active_challenges;
BEGIN
    -- 1. Buscar reto actual
    SELECT ac.* INTO current_active 
    FROM active_challenges ac
    WHERE ac.status IN ('active', 'voting', 'completed')
    ORDER BY ac.created_at DESC 
    LIMIT 1;

    -- 2. Transición: Active -> Voting (Añadimos buffer de 3s para evitar cortes bruscos)
    IF FOUND AND current_active.status = 'active' AND current_active.expires_at <= (NOW() + INTERVAL '3 seconds') THEN
        UPDATE active_challenges 
        SET status = 'voting', 
            voting_expires_at = NOW() + INTERVAL '30 seconds'
        WHERE active_challenges.id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    -- 3. Transición: Voting -> Completed (Podio, 5s)
    IF FOUND AND current_active.status = 'voting' AND current_active.voting_expires_at <= (NOW() + INTERVAL '3 seconds') THEN
        WITH top_voted AS (
            SELECT s.user_id, SUM(s.vote_count) as total_votes -- Usamos la columna vote_count para mayor fidelidad
            FROM challenge_submissions s
            WHERE s.active_challenge_id = current_active.id
            GROUP BY s.user_id
            ORDER BY total_votes DESC
            LIMIT 3
        )
        UPDATE active_challenges 
        SET status = 'completed',
            winner_ids = ARRAY(SELECT user_id FROM top_voted),
            expires_at = NOW() + INTERVAL '5 seconds'
        WHERE active_challenges.id = current_active.id
        RETURNING * INTO current_active;
    END IF;

    -- 4. Transición: Completed -> Nuevo Reto
    IF FOUND AND current_active.status = 'completed' AND current_active.expires_at <= (NOW() + INTERVAL '3 seconds') THEN
        current_active := NULL;
    END IF;

    -- 5. Generar Reto Nuevo
    IF current_active IS NULL THEN
        INSERT INTO active_challenges (challenge_id, expires_at, status)
        SELECT cm.id, NOW() + INTERVAL '5 minutes', 'active'
        FROM challenges_master cm
        ORDER BY RANDOM()
        LIMIT 1
        RETURNING * INTO current_active;
    END IF;

    RETURN NEXT current_active;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
