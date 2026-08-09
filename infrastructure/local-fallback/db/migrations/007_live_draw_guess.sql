-- Live drawing/guessing game (phase 6).
--
-- Additive and idempotent. The API remains fail-closed unless
-- LIVE_DRAW_GUESS_ENABLED=true. Prompts stay server-side; raw guesses and
-- drawing points are intentionally not persisted.

CREATE TABLE IF NOT EXISTS fallback_live_draw_games (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
    host_user_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'lobby'
        CHECK (status IN ('lobby', 'active', 'completed', 'canceled')),
    max_players SMALLINT NOT NULL DEFAULT 8 CHECK (max_players BETWEEN 2 AND 8),
    total_rounds SMALLINT NOT NULL DEFAULT 3 CHECK (total_rounds BETWEEN 1 AND 12),
    round_seconds SMALLINT NOT NULL DEFAULT 75 CHECK (round_seconds BETWEEN 30 AND 180),
    current_round_number SMALLINT NOT NULL DEFAULT 0 CHECK (current_round_number BETWEEN 0 AND 12),
    drawer_offset SMALLINT NOT NULL DEFAULT 0 CHECK (drawer_offset BETWEEN 0 AND 7),
    request_nonce TEXT NOT NULL CHECK (char_length(request_nonce) BETWEEN 16 AND 160),
    start_nonce TEXT CHECK (start_nonce IS NULL OR char_length(start_nonce) BETWEEN 16 AND 160),
    cancel_nonce TEXT CHECK (cancel_nonce IS NULL OR char_length(cancel_nonce) BETWEEN 16 AND 160),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (live_id, request_nonce)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_live_draw_one_open_game
    ON fallback_live_draw_games(live_id)
    WHERE status IN ('lobby', 'active');

CREATE INDEX IF NOT EXISTS idx_fallback_live_draw_games_due
    ON fallback_live_draw_games(status, updated_at)
    WHERE status = 'active';

CREATE TABLE IF NOT EXISTS fallback_live_draw_players (
    game_id UUID NOT NULL REFERENCES fallback_live_draw_games(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL,
    seat_index SMALLINT NOT NULL CHECK (seat_index BETWEEN 0 AND 7),
    status TEXT NOT NULL DEFAULT 'invited'
        CHECK (status IN ('invited', 'accepted', 'declined', 'left')),
    score BIGINT NOT NULL DEFAULT 0 CHECK (score >= 0),
    acceptance_nonce TEXT
        CHECK (acceptance_nonce IS NULL OR char_length(acceptance_nonce) BETWEEN 16 AND 160),
    accepted_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (game_id, user_id),
    UNIQUE (game_id, seat_index)
);

CREATE INDEX IF NOT EXISTS idx_fallback_live_draw_players_score
    ON fallback_live_draw_players(game_id, score DESC, accepted_at ASC)
    WHERE status = 'accepted';

CREATE TABLE IF NOT EXISTS fallback_live_draw_rounds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id UUID NOT NULL REFERENCES fallback_live_draw_games(id) ON DELETE CASCADE,
    round_number SMALLINT NOT NULL CHECK (round_number BETWEEN 1 AND 12),
    drawer_user_id TEXT NOT NULL,
    prompt_key TEXT NOT NULL CHECK (char_length(prompt_key) BETWEEN 2 AND 64),
    prompt_version SMALLINT NOT NULL DEFAULT 1 CHECK (prompt_version > 0),
    status TEXT NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'completed', 'expired', 'canceled')),
    last_draw_sequence BIGINT NOT NULL DEFAULT -1 CHECK (last_draw_sequence >= -1),
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ends_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (game_id, round_number),
    UNIQUE (game_id, id),
    FOREIGN KEY (game_id, drawer_user_id)
        REFERENCES fallback_live_draw_players(game_id, user_id) ON DELETE CASCADE,
    CHECK (ends_at > started_at)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_live_draw_one_active_round
    ON fallback_live_draw_rounds(game_id)
    WHERE status = 'active';

CREATE INDEX IF NOT EXISTS idx_fallback_live_draw_rounds_due
    ON fallback_live_draw_rounds(status, ends_at)
    WHERE status = 'active';

CREATE TABLE IF NOT EXISTS fallback_live_draw_guesses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id UUID NOT NULL REFERENCES fallback_live_draw_games(id) ON DELETE CASCADE,
    round_id UUID NOT NULL REFERENCES fallback_live_draw_rounds(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL,
    idempotency_key TEXT NOT NULL CHECK (char_length(idempotency_key) BETWEEN 16 AND 160),
    answer_length SMALLINT NOT NULL CHECK (answer_length BETWEEN 1 AND 72),
    correct BOOLEAN NOT NULL,
    awarded_points INTEGER NOT NULL DEFAULT 0 CHECK (awarded_points BETWEEN 0 AND 1000),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (game_id, user_id, idempotency_key),
    FOREIGN KEY (game_id, round_id)
        REFERENCES fallback_live_draw_rounds(game_id, id) ON DELETE CASCADE,
    FOREIGN KEY (game_id, user_id)
        REFERENCES fallback_live_draw_players(game_id, user_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_live_draw_one_correct_guess
    ON fallback_live_draw_guesses(round_id, lower(user_id))
    WHERE correct = TRUE;

CREATE INDEX IF NOT EXISTS idx_fallback_live_draw_guesses_round
    ON fallback_live_draw_guesses(round_id, submitted_at ASC);

CREATE TABLE IF NOT EXISTS fallback_live_draw_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id UUID NOT NULL REFERENCES fallback_live_draw_games(id) ON DELETE CASCADE,
    round_id UUID REFERENCES fallback_live_draw_rounds(id) ON DELETE SET NULL,
    actor_user_id TEXT,
    event_type TEXT NOT NULL CHECK (event_type IN (
        'game_created', 'player_accepted', 'game_started', 'round_started',
        'guess_correct', 'round_completed', 'game_completed', 'game_canceled'
    )),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (NOT (metadata ?| ARRAY['prompt', 'prompt_key', 'guess', 'answer']))
);

CREATE INDEX IF NOT EXISTS idx_fallback_live_draw_audit_game
    ON fallback_live_draw_audit(game_id, created_at ASC);
