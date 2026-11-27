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
    -- These two files were installed with CRLF on the original Ubuntu host.
    -- Keep its ledger intact and accept only the two verified byte sequences
    -- for each name. Every other change still requires a new migration.
    IF recorded <> checksum AND NOT EXISTS (
      SELECT 1 FROM (VALUES
        ('002_sync_support.sql',
         '6d672f68193658b3e7b1eef67780bd6ddd7dbd186750a77f2879255c9f06d687',
         '58b63e60e148f0864c4f37c073789bf365c8233ec42720220f1e1e862ca37b3b'),
        ('003_password_migration_notice.sql',
         '34ddb64ab403442a77e05031b675ab24192e762e188925b9ff743af37b219cf4',
         'e71fc58797864f1982e855634b86b1afe60be45294171320cbbcf8f70049d3ea')
      ) historical(filename, lf_digest, crlf_digest)
      WHERE historical.filename = migration_name
        AND recorded IN (lf_digest, crlf_digest) AND checksum IN (lf_digest, crlf_digest)
    ) THEN
      RAISE EXCEPTION 'Checksum de migración ya aplicada no coincide: %', migration_name;
    END IF;
    RETURN 'MIGRATION_ALREADY_APPLIED=' || migration_name;
  END IF;

  IF migration_name = '026_pending_friendship_allows_conversation.sql' THEN
    -- Serialize with the API's schema initializer before checking its trigger.
    PERFORM pg_advisory_xact_lock(hashtext('novvin:api:schema-initialization:v1'));
    IF to_regprocedure('public.fallback_direct_message_request_state(text,text)') IS NOT NULL
      AND EXISTS (
        SELECT 1 FROM pg_catalog.pg_trigger AS trg
        JOIN pg_catalog.pg_proc AS guard ON guard.oid = trg.tgfoid
        WHERE trg.tgrelid = to_regclass('public.fallback_messages')
          AND trg.tgname = 'trg_fallback_guard_introductory_message'
          AND guard.oid = to_regprocedure('public.fallback_guard_introductory_message()')
          AND NOT trg.tgisinternal
          AND guard.prosrc LIKE '%fallback_direct_message_request_state(%'
          AND guard.prosrc LIKE '%MESSAGE_REQUEST_PENDING%'
      ) THEN
      IF checksum NOT IN (
        '00c256d0113129072589ba99fa1bf81fd6827b46c4e59d9398c7a26a1dc9e933',
        '923baf68a02babc4d561b024922b1938a2936b4967251fca81b56dab1d42c320'
      ) THEN
        RAISE EXCEPTION 'Checksum de migración histórica sustituida no coincide: %', migration_name;
      END IF;
      -- The running API already installed the replacement. Do not record SQL we did not execute.
      RETURN 'MIGRATION_SUPERSEDED=' || migration_name;
    END IF;
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
