BEGIN;

CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE fallback_game_catalog
  ADD COLUMN IF NOT EXISTS external_provider_id TEXT,
  ADD COLUMN IF NOT EXISTS slug TEXT,
  ADD COLUMN IF NOT EXISTS short_name TEXT,
  ADD COLUMN IF NOT EXISTS cover_url TEXT,
  ADD COLUMN IF NOT EXISTS logo_url TEXT,
  ADD COLUMN IF NOT EXISTS icon_url TEXT,
  ADD COLUMN IF NOT EXISTS genres JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN IF NOT EXISTS online_supported BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS multiplayer_supported BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN IF NOT EXISTS competitive BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS ranked_supported BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS roles_supported BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS release_date DATE,
  ADD COLUMN IF NOT EXISTS provider TEXT NOT NULL DEFAULT 'manual',
  ADD COLUMN IF NOT EXISTS provider_updated_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS catalog_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  ADD COLUMN IF NOT EXISTS support_level TEXT NOT NULL DEFAULT 'CATALOG_SUPPORTED',
  ADD COLUMN IF NOT EXISTS game_user_id_label TEXT NOT NULL DEFAULT 'ID del juego',
  ADD COLUMN IF NOT EXISTS source_url TEXT,
  ADD COLUMN IF NOT EXISTS cover_license TEXT,
  ADD COLUMN IF NOT EXISTS cover_attribution TEXT,
  ADD COLUMN IF NOT EXISTS cover_source_url TEXT,
  ADD COLUMN IF NOT EXISTS manual_override BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE fallback_game_catalog
   SET slug = COALESCE(NULLIF(slug, ''), id),
       short_name = COALESCE(NULLIF(short_name, ''), name),
       cover_url = COALESCE(cover_url, image_url),
       ranked_supported = CASE WHEN jsonb_array_length(ranks) > 0 THEN TRUE ELSE ranked_supported END,
       roles_supported = CASE WHEN jsonb_array_length(roles) > 0 THEN TRUE ELSE roles_supported END,
       competitive = CASE WHEN jsonb_array_length(ranks) > 0 THEN TRUE ELSE competitive END,
       support_level = CASE
         WHEN jsonb_array_length(ranks) > 0 OR jsonb_array_length(roles) > 0 THEN 'FULLY_SUPPORTED'
         ELSE support_level
       END,
       catalog_updated_at = COALESCE(catalog_updated_at, updated_at, NOW())
 WHERE TRUE;

ALTER TABLE fallback_game_catalog
  DROP CONSTRAINT IF EXISTS fallback_game_catalog_support_level_check;
ALTER TABLE fallback_game_catalog
  ADD CONSTRAINT fallback_game_catalog_support_level_check
  CHECK (support_level IN ('FULLY_SUPPORTED', 'CATALOG_SUPPORTED', 'UNSUPPORTED'));

CREATE UNIQUE INDEX IF NOT EXISTS uq_fallback_game_catalog_provider_external
  ON fallback_game_catalog(lower(provider), lower(external_provider_id))
  WHERE external_provider_id IS NOT NULL AND external_provider_id <> '';
CREATE INDEX IF NOT EXISTS idx_fallback_game_catalog_name_trgm
  ON fallback_game_catalog USING gin (lower(name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_fallback_game_catalog_slug_trgm
  ON fallback_game_catalog USING gin (lower(slug) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_fallback_game_catalog_active_support
  ON fallback_game_catalog(active, support_level, catalog_updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_fallback_game_catalog_platforms_gin
  ON fallback_game_catalog USING gin (platforms);
CREATE INDEX IF NOT EXISTS idx_fallback_game_catalog_genres_gin
  ON fallback_game_catalog USING gin (genres);

CREATE TABLE IF NOT EXISTS fallback_playmatch_game_rank_schemas (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  game_id TEXT NOT NULL REFERENCES fallback_game_catalog(id) ON DELETE CASCADE,
  schema_key TEXT NOT NULL,
  display_name TEXT NOT NULL,
  schema_version TEXT NOT NULL DEFAULT '1',
  season TEXT NOT NULL DEFAULT '',
  region_code TEXT NOT NULL DEFAULT '',
  platform TEXT NOT NULL DEFAULT '',
  points_label TEXT NOT NULL DEFAULT '',
  official_source_url TEXT,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (game_id, schema_key, schema_version, season, region_code, platform)
);

CREATE TABLE IF NOT EXISTS fallback_playmatch_game_ranks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  schema_id UUID NOT NULL REFERENCES fallback_playmatch_game_rank_schemas(id) ON DELETE CASCADE,
  rank_key TEXT NOT NULL,
  tier_key TEXT NOT NULL DEFAULT '',
  division_key TEXT NOT NULL DEFAULT '',
  display_name TEXT NOT NULL,
  order_index INTEGER NOT NULL,
  icon_url TEXT,
  min_rating INTEGER,
  max_rating INTEGER,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (schema_id, rank_key)
);

CREATE INDEX IF NOT EXISTS idx_fallback_playmatch_game_ranks_order
  ON fallback_playmatch_game_ranks(schema_id, active, order_index);

CREATE TABLE IF NOT EXISTS fallback_playmatch_game_roles (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  game_id TEXT NOT NULL REFERENCES fallback_game_catalog(id) ON DELETE CASCADE,
  role_key TEXT NOT NULL,
  display_name TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  icon_url TEXT,
  order_index INTEGER NOT NULL DEFAULT 0,
  official_source_url TEXT,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (game_id, role_key)
);

CREATE INDEX IF NOT EXISTS idx_fallback_playmatch_game_roles_order
  ON fallback_playmatch_game_roles(game_id, active, order_index);

CREATE TABLE IF NOT EXISTS fallback_playmatch_catalog_sync_runs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  provider TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'RUNNING',
  requested_by TEXT,
  cursor_before TEXT,
  cursor_after TEXT,
  fetched_count INTEGER NOT NULL DEFAULT 0,
  inserted_count INTEGER NOT NULL DEFAULT 0,
  updated_count INTEGER NOT NULL DEFAULT 0,
  skipped_count INTEGER NOT NULL DEFAULT 0,
  error_code TEXT,
  started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  completed_at TIMESTAMPTZ,
  CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE IF NOT EXISTS fallback_playmatch_catalog_provider_state (
  provider TEXT PRIMARY KEY,
  next_cursor TEXT,
  last_success_at TIMESTAMPTZ,
  last_error_code TEXT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO fallback_playmatch_game_rank_schemas(
  game_id, schema_key, display_name, schema_version, active
)
SELECT id, 'official', name || ' competitivo', 'seed-v1', TRUE
  FROM fallback_game_catalog
 WHERE jsonb_array_length(ranks) > 0
ON CONFLICT (game_id, schema_key, schema_version, season, region_code, platform)
DO UPDATE SET active = TRUE, updated_at = NOW();

INSERT INTO fallback_playmatch_game_ranks(
  schema_id, rank_key, display_name, order_index, active
)
SELECT schema.id,
       lower(regexp_replace(rank.value, '[^a-zA-Z0-9]+', '-', 'g')),
       rank.value,
       (rank.ordinality - 1)::INTEGER,
       TRUE
  FROM fallback_game_catalog catalog
  JOIN fallback_playmatch_game_rank_schemas schema
    ON schema.game_id = catalog.id
   AND schema.schema_key = 'official'
   AND schema.schema_version = 'seed-v1'
 CROSS JOIN LATERAL jsonb_array_elements_text(catalog.ranks)
   WITH ORDINALITY AS rank(value, ordinality)
ON CONFLICT (schema_id, rank_key)
DO UPDATE SET display_name = EXCLUDED.display_name,
              order_index = EXCLUDED.order_index,
              active = TRUE,
              updated_at = NOW();

INSERT INTO fallback_playmatch_game_roles(
  game_id, role_key, display_name, order_index, active
)
SELECT catalog.id,
       lower(regexp_replace(role.value, '[^a-zA-Z0-9]+', '-', 'g')),
       role.value,
       (role.ordinality - 1)::INTEGER,
       TRUE
  FROM fallback_game_catalog catalog
 CROSS JOIN LATERAL jsonb_array_elements_text(catalog.roles)
   WITH ORDINALITY AS role(value, ordinality)
ON CONFLICT (game_id, role_key)
DO UPDATE SET display_name = EXCLUDED.display_name,
              order_index = EXCLUDED.order_index,
              active = TRUE,
              updated_at = NOW();

COMMIT;
