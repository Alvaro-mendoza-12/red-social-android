BEGIN;

ALTER TABLE fallback_playmatch_catalog_sync_runs
  ADD COLUMN IF NOT EXISTS pages_count INTEGER NOT NULL DEFAULT 0;

COMMIT;
