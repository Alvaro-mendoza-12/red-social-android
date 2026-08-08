-- Socializar: consentimiento explícito para el backend local/Ubuntu.
--
-- Esta migración es aditiva e idempotente. No habilita descubrimiento,
-- solicitudes, salas, ni chat aleatorio: todos siguen detrás de flags
-- SOCIALIZATION_* apagados por defecto y de consentimiento individual.
-- No almacena mensajes, presencia, ubicación precisa ni atributos sensibles.

CREATE TABLE IF NOT EXISTS fallback_user_socialization_preferences (
    user_id TEXT PRIMARY KEY,
    discovery_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
    connection_requests_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
    community_discovery_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
    icebreakers_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
    social_rooms_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_fallback_socialization_preferences_updated
    ON fallback_user_socialization_preferences(updated_at DESC);
