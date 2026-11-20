-- This session also serves deployers using another host's Compose lock.
BEGIN;
SELECT pg_advisory_xact_lock(hashtextextended('public.fallback_schema_migrations', 0));
CREATE TABLE IF NOT EXISTS public.fallback_schema_migrations (
  filename TEXT PRIMARY KEY,
  checksum_sha256 TEXT NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- Older role bootstraps transferred every public table to the API role.
DO $ledger$
BEGIN
  IF (SELECT tableowner FROM pg_catalog.pg_tables
      WHERE schemaname = 'public' AND tablename = 'fallback_schema_migrations') = 'novvin_api' THEN
    ALTER TABLE public.fallback_schema_migrations OWNER TO CURRENT_USER;
  END IF;
  IF to_regrole('novvin_api') IS NOT NULL THEN
    REVOKE ALL ON public.fallback_schema_migrations FROM novvin_api;
  END IF;
END;
$ledger$;
REVOKE ALL ON public.fallback_schema_migrations FROM PUBLIC;
COMMIT;

CREATE FUNCTION pg_temp.apply_schema_migration(migration_name text, source_bytes bytea)
RETURNS text LANGUAGE plpgsql AS $function$
DECLARE
  checksum text := encode(sha256(source_bytes), 'hex');
  recorded text;
  body text;
BEGIN
  LOCK TABLE public.fallback_schema_migrations IN SHARE ROW EXCLUSIVE MODE;
  SELECT checksum_sha256 INTO recorded FROM public.fallback_schema_migrations
    WHERE filename = migration_name;
  IF FOUND THEN
    IF recorded <> checksum THEN
      RAISE EXCEPTION 'Checksum de migración ya aplicada no coincide: %', migration_name;
    END IF;
    RETURN 'MIGRATION_ALREADY_APPLIED=' || migration_name;
  END IF;

  body := convert_from(source_bytes, 'UTF8');
  -- These immutable files have one outer BEGIN/COMMIT pair. Keep hashing
  -- their original bytes while the caller's statement owns the transaction.
  IF migration_name IN ('001_runtime_prerequisites.sql', '011_live_channel_points.sql',
                        '012_playmatch_games_v1.sql', '013_playmatch_dynamic_catalog_v2.sql',
                        '014_playmatch_catalog_sync_metrics.sql') THEN
    body := regexp_replace(body, '^BEGIN;[[:blank:]]*\r?$', '', 'n');
    body := regexp_replace(body, '(^|\n)COMMIT;[[:space:]]*$', '');
  END IF;

  EXECUTE body;
  INSERT INTO public.fallback_schema_migrations(filename, checksum_sha256)
    VALUES (migration_name, checksum);
  RETURN 'MIGRATION_APPLIED=' || migration_name;
END;
$function$;
