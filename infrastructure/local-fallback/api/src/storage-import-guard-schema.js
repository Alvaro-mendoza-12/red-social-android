'use strict';
const SCHEMA='novvin_storage_guard';
const WRITER='novvin_storage_binding_writer';
// The privileged import mutator must never invoke a runtime-owned trigger.
// The protected definer instead drops to this narrower identity before writing
// public binding tables. Runtime-created triggers there cannot gain import DML.
function storageImportGuardBootstrapSql({historyStatements,contentStatements,tableStatements=[]}) {
  const functions=[...historyStatements,...contentStatements].map(sql=>sql
    .replaceAll('public.novvin_','novvin_storage_guard.novvin_')
    .replaceAll('SET search_path=pg_catalog,public','SET search_path=pg_catalog,novvin_storage_guard')
    .replace('RETURNS trigger\n    LANGUAGE plpgsql','RETURNS trigger\n    LANGUAGE plpgsql SECURITY DEFINER')
  );
  const functionNames=[
    'novvin_storage_uri_segment(text)','novvin_storage_object_reference(text)','novvin_record_private_media_history()',
    'novvin_storage_content_urls(text,jsonb)','novvin_record_storage_content_bindings()',
  ];
  return `BEGIN;
DO $$ BEGIN
  IF current_database()<>'conectate_fallback' THEN RAISE EXCEPTION 'STORAGE_IMPORT_GUARD_DATABASE_REQUIRED'; END IF;
  IF current_user='novvin_api' OR NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=current_user AND rolsuper) THEN
    RAISE EXCEPTION 'STORAGE_IMPORT_GUARD_ADMIN_REQUIRED';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='${WRITER}') THEN
    CREATE ROLE ${WRITER} NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
  END IF;
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='${WRITER}' AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls))
    OR EXISTS(SELECT 1 FROM pg_auth_members WHERE roleid='${WRITER}'::regrole OR member='${WRITER}'::regrole) THEN
    RAISE EXCEPTION 'STORAGE_IMPORT_GUARD_ROLE_UNSAFE';
  END IF;
END $$;
${tableStatements.map(statement=>statement+';').join('\n')}
DO $$ DECLARE relation text; BEGIN
  FOREACH relation IN ARRAY ARRAY['fallback_storage_read_origins','fallback_storage_private_history','fallback_storage_private_bindings',
    'fallback_storage_content_history','fallback_storage_content_bindings','fallback_storage_content_authority'] LOOP
    EXECUTE format('ALTER TABLE public.%I OWNER TO novvin_api',relation);
  END LOOP;
END $$;
CREATE SCHEMA IF NOT EXISTS ${SCHEMA};
REVOKE ALL ON SCHEMA ${SCHEMA} FROM PUBLIC,novvin_api;
REVOKE CREATE ON SCHEMA ${SCHEMA},public FROM ${WRITER};
GRANT USAGE ON SCHEMA ${SCHEMA},public TO ${WRITER};
GRANT SELECT ON public.fallback_storage_read_origins,public.fallback_storage_objects TO ${WRITER};
GRANT SELECT,INSERT,UPDATE ON public.fallback_storage_private_history TO ${WRITER};
GRANT SELECT,INSERT,DELETE ON public.fallback_storage_private_bindings TO ${WRITER};
GRANT SELECT,INSERT ON public.fallback_storage_content_history TO ${WRITER};
GRANT SELECT,INSERT,DELETE ON public.fallback_storage_content_bindings TO ${WRITER};
GRANT SELECT,INSERT,UPDATE ON public.fallback_storage_content_authority TO ${WRITER};
${functions.map(sql=>sql+';').join('\n')}
${functionNames.map(name=>`ALTER FUNCTION ${SCHEMA}.${name} OWNER TO ${WRITER};\nREVOKE ALL ON FUNCTION ${SCHEMA}.${name} FROM PUBLIC,novvin_api;`).join('\n')}
DO $$ DECLARE relation text; BEGIN
  FOREACH relation IN ARRAY ARRAY['messages','group_messages','community_messages','proximity_messages'] LOOP
    IF to_regclass('supabase_import.'||relation) IS NOT NULL THEN
      EXECUTE format('CREATE OR REPLACE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON supabase_import.%I FOR EACH ROW EXECUTE FUNCTION ${SCHEMA}.novvin_record_private_media_history()',relation||'_private_media_history',relation);
    END IF;
  END LOOP;
  FOREACH relation IN ARRAY ARRAY['posts','videos','moments','profiles'] LOOP
    IF to_regclass('supabase_import.'||relation) IS NOT NULL THEN
      EXECUTE format('CREATE OR REPLACE TRIGGER %I AFTER INSERT OR UPDATE OR DELETE ON supabase_import.%I FOR EACH ROW EXECUTE FUNCTION ${SCHEMA}.novvin_record_storage_content_bindings()',relation||'_storage_content_bindings',relation);
      EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON supabase_import.%I ((lower(id::text)))','idx_storage_content_'||relation||'_id',relation);
    END IF;
  END LOOP;
END $$;
COMMIT;
`;
}
async function requireProtectedImportTrigger(db,relation,triggerName,functionName) {
  const result=await db.query(`SELECT p.prosecdef,pg_get_userbyid(p.proowner) AS owner,p.proconfig
    FROM pg_trigger t JOIN pg_proc p ON p.oid=t.tgfoid JOIN pg_namespace n ON n.oid=p.pronamespace
    WHERE t.tgrelid=$1::regclass AND t.tgname=$2 AND NOT t.tgisinternal AND t.tgenabled IN ('O','A') AND t.tgtype=29
      AND n.nspname='novvin_storage_guard' AND p.proname=$3 AND p.pronargs=0`,[relation,triggerName,functionName]);
  const row=result.rows[0];
  if(!row||!row.prosecdef||row.owner!==WRITER||!row.proconfig?.some(value=>value==='search_path=pg_catalog, novvin_storage_guard'||value==='search_path=pg_catalog,novvin_storage_guard'))
    throw Object.assign(new Error('STORAGE_IMPORT_PROTECTED_TRIGGER_REQUIRED'),{code:'STORAGE_IMPORT_PROTECTED_TRIGGER_REQUIRED'});
  const safe=await db.query(`SELECT 1 FROM pg_roles r WHERE r.rolname='novvin_storage_binding_writer'
    AND NOT (r.rolcanlogin OR r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls)
    AND NOT EXISTS(SELECT 1 FROM pg_auth_members WHERE roleid=r.oid OR member=r.oid)
    AND NOT has_schema_privilege('novvin_api','novvin_storage_guard','CREATE')
    AND NOT has_schema_privilege(r.oid,'novvin_storage_guard','CREATE')
    AND NOT EXISTS(SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
      WHERE n.nspname='novvin_storage_guard' AND p.proowner<>r.oid)
    AND NOT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='supabase_import'
      AND (has_table_privilege(r.oid,c.oid,'INSERT') OR has_table_privilege(r.oid,c.oid,'UPDATE') OR has_table_privilege(r.oid,c.oid,'DELETE') OR c.relowner=r.oid))`);
  if(!safe.rows.length)throw Object.assign(new Error('STORAGE_IMPORT_GUARD_ROLE_UNSAFE'),{code:'STORAGE_IMPORT_GUARD_ROLE_UNSAFE'});
}
module.exports={SCHEMA,WRITER,storageImportGuardBootstrapSql,requireProtectedImportTrigger};
