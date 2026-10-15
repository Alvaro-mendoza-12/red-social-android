-- Run as the bootstrap administrator against the intended fallback database.
-- This file sets no password. Set it interactively with psql \password novvin_api.
-- It deliberately does not demote or remove the existing bootstrap role.
BEGIN;
DO $$ BEGIN
  IF current_database()<>'conectate_fallback' THEN
    RAISE EXCEPTION 'Runtime role bootstrap requires the dedicated conectate_fallback database';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='novvin_api') THEN
    CREATE ROLE novvin_api LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='novvin_api' AND
      (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)) THEN
    RAISE EXCEPTION 'Existing novvin_api role has unsafe capabilities';
  END IF;
END $$;
GRANT USAGE,CREATE ON SCHEMA public TO novvin_api;
DO $$ DECLARE obj RECORD; BEGIN
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO novvin_api',current_database());
  FOR obj IN SELECT c.relname,c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='public' AND c.relkind IN ('r','p','S','v','m','f')
      AND NOT EXISTS(SELECT 1 FROM pg_depend d WHERE d.classid='pg_class'::regclass AND d.objid=c.oid AND d.deptype='e')
    ORDER BY CASE WHEN c.relkind='S' THEN 1 ELSE 0 END,c.relname LOOP
    EXECUTE format('ALTER %s public.%I OWNER TO novvin_api',
      CASE obj.relkind WHEN 'S' THEN 'SEQUENCE' WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW'
        WHEN 'f' THEN 'FOREIGN TABLE' ELSE 'TABLE' END,obj.relname);
  END LOOP;
  FOR obj IN SELECT p.oid::regprocedure::text AS signature FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
    WHERE n.nspname='public' AND p.prokind='f'
      AND NOT EXISTS(SELECT 1 FROM pg_depend d WHERE d.classid='pg_proc'::regclass AND d.objid=p.oid AND d.deptype='e') LOOP
    EXECUTE format('ALTER FUNCTION %s OWNER TO novvin_api',obj.signature);
  END LOOP;
END $$;
COMMIT;
