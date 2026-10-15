'use strict';
const {SOURCES,LOCAL_TABLES,IMPORTED_TABLES}=require('./storage-content-read');
const {HISTORY_TABLE_STATEMENTS,HISTORY_FUNCTION_STATEMENTS}=require('./storage-read-history-ddl');
const {storageImportGuardBootstrapSql,requireProtectedImportTrigger}=require('./storage-import-guard-schema');
const CONTENT_TABLE_STATEMENTS=Object.freeze([
  `CREATE TABLE IF NOT EXISTS public.fallback_storage_content_history (
    bucket_id text NOT NULL, object_name text NOT NULL, first_bound_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(bucket_id,object_name)
  )`,
  `CREATE TABLE IF NOT EXISTS public.fallback_storage_content_authority (
    access_kind text NOT NULL CHECK(access_kind IN ('post','video','moment')),record_id text NOT NULL,
    deleted boolean NOT NULL DEFAULT false,updated_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(access_kind,record_id)
  )`,
  `CREATE TABLE IF NOT EXISTS public.fallback_storage_content_bindings (
    source_schema text NOT NULL CHECK(source_schema IN ('public','supabase_import')),source_table text NOT NULL,
    access_kind text NOT NULL CHECK(access_kind IN ('post','video','moment','profile')),record_id text NOT NULL CHECK(length(record_id) BETWEEN 1 AND 180),
    bucket_id text NOT NULL,object_name text NOT NULL,
    PRIMARY KEY(source_schema,source_table,record_id,bucket_id,object_name),
    FOREIGN KEY(bucket_id,object_name) REFERENCES public.fallback_storage_objects(bucket_id,name) ON DELETE CASCADE
  )`,
  `CREATE INDEX IF NOT EXISTS idx_storage_content_bindings_object ON public.fallback_storage_content_bindings(bucket_id,object_name)`,
]);
function contentSourceRowsSql() {
  return Object.entries(SOURCES).map(([table,spec])=>`('${table}','${spec.kind}','${spec.key}',${spec.nested?`'${spec.nested}'`:'NULL'},ARRAY[${spec.fields.map(field=>`'${field}'`).join(',')}]::text[])`).join(',\n');
}
const CONTENT_FUNCTION_STATEMENTS=Object.freeze([
  `CREATE OR REPLACE FUNCTION public.novvin_storage_content_urls(source_table text,content_row jsonb)
    RETURNS TABLE(reference_url text) LANGUAGE plpgsql IMMUTABLE STRICT SET search_path=pg_catalog,public AS $$
    DECLARE fields text[];nested text;field text;value jsonb;source jsonb;
    BEGIN
      SELECT specification.fields,specification.nested INTO fields,nested FROM (VALUES ${contentSourceRowsSql()})
        AS specification(table_name,kind,id_field,nested,fields) WHERE specification.table_name=source_table;
      IF fields IS NULL THEN RETURN; END IF;
      source:=CASE WHEN nested IS NULL THEN content_row ELSE content_row->nested END;
      IF jsonb_typeof(source)<>'object' THEN RETURN; END IF;
      FOREACH field IN ARRAY fields LOOP
        value:=source->field;
        IF jsonb_typeof(value)='string' THEN
          reference_url:=source->>field;
          -- Imports store media_urls as text. Decode only a complete JSON array;
          -- malformed array-like values cannot become object references.
          IF left(ltrim(reference_url),1)='[' THEN
            BEGIN
              value:=reference_url::jsonb;
            EXCEPTION WHEN data_exception THEN
              CONTINUE;
            END;
            IF jsonb_typeof(value)<>'array' THEN CONTINUE; END IF;
            RETURN QUERY SELECT picked.url FROM (SELECT CASE
              WHEN jsonb_typeof(entry)='string' THEN entry#>>'{}'
              WHEN field='media_urls' AND jsonb_typeof(entry)='object' THEN CASE
                WHEN jsonb_typeof(entry->'url')='string' AND length(btrim(entry->>'url'))>0 THEN entry->>'url'
                WHEN jsonb_typeof(entry->'media_url')='string' AND length(btrim(entry->>'media_url'))>0 THEN entry->>'media_url'
              END END AS url FROM jsonb_array_elements(value) entry) picked WHERE picked.url IS NOT NULL;
          ELSE
            RETURN NEXT;
          END IF;
        ELSIF jsonb_typeof(value)='array' THEN
          RETURN QUERY SELECT picked.url FROM (SELECT CASE
            WHEN jsonb_typeof(entry)='string' THEN entry#>>'{}'
            WHEN field='media_urls' AND jsonb_typeof(entry)='object' THEN CASE
              WHEN jsonb_typeof(entry->'url')='string' AND length(btrim(entry->>'url'))>0 THEN entry->>'url'
              WHEN jsonb_typeof(entry->'media_url')='string' AND length(btrim(entry->>'media_url'))>0 THEN entry->>'media_url'
            END END AS url FROM jsonb_array_elements(value) entry) picked WHERE picked.url IS NOT NULL;
        END IF;
      END LOOP;
    END $$`,
  `CREATE OR REPLACE FUNCTION public.novvin_record_storage_content_bindings() RETURNS trigger
    LANGUAGE plpgsql SET search_path=pg_catalog,public AS $$
    DECLARE previous jsonb;current_row jsonb;id_field text;kind text;record_id_value text;
    BEGIN
      SELECT specification.kind,specification.id_field INTO kind,id_field FROM (VALUES ${contentSourceRowsSql()})
        AS specification(table_name,kind,id_field,nested,fields) WHERE specification.table_name=TG_TABLE_NAME;
      IF id_field IS NULL THEN RAISE EXCEPTION 'STORAGE_CONTENT_SOURCE_INVALID'; END IF;
      IF TG_OP<>'INSERT' THEN previous:=to_jsonb(OLD);END IF;
      IF TG_OP<>'DELETE' THEN current_row:=to_jsonb(NEW);END IF;
      INSERT INTO public.fallback_storage_content_history(bucket_id,object_name)
        SELECT DISTINCT object.bucket_id,object.name FROM (
          SELECT reference_url FROM public.novvin_storage_content_urls(TG_TABLE_NAME,previous)
          UNION SELECT reference_url FROM public.novvin_storage_content_urls(TG_TABLE_NAME,current_row)
        ) urls CROSS JOIN LATERAL public.novvin_storage_object_reference(urls.reference_url) reference
        JOIN public.fallback_storage_objects object ON object.bucket_id=reference.bucket_name AND object.name=reference.object_name
        ON CONFLICT DO NOTHING;
      IF TG_OP<>'INSERT' THEN
        DELETE FROM public.fallback_storage_content_bindings WHERE source_schema=TG_TABLE_SCHEMA AND source_table=TG_TABLE_NAME
          AND record_id=lower(trim(previous->>id_field));
      END IF;
      record_id_value:=lower(trim(COALESCE(current_row,previous)->>id_field));
      IF TG_TABLE_SCHEMA='public' AND kind IN ('post','video','moment') AND record_id_value IS NOT NULL THEN
        INSERT INTO public.fallback_storage_content_authority(access_kind,record_id,deleted)
          VALUES(kind,record_id_value,TG_OP='DELETE') ON CONFLICT(access_kind,record_id)
          DO UPDATE SET deleted=EXCLUDED.deleted,updated_at=now();
      END IF;
      IF TG_OP='DELETE' THEN RETURN NULL;END IF;
      IF record_id_value IS NULL OR length(record_id_value) NOT BETWEEN 1 AND 180 THEN RETURN NULL;END IF;
      INSERT INTO public.fallback_storage_content_bindings(source_schema,source_table,access_kind,record_id,bucket_id,object_name)
        SELECT DISTINCT TG_TABLE_SCHEMA,TG_TABLE_NAME,kind,record_id_value,object.bucket_id,object.name
        FROM public.novvin_storage_content_urls(TG_TABLE_NAME,current_row) urls
        CROSS JOIN LATERAL public.novvin_storage_object_reference(urls.reference_url) reference
        JOIN public.fallback_storage_objects object ON object.bucket_id=reference.bucket_name AND object.name=reference.object_name
        ON CONFLICT DO NOTHING;
      RETURN NULL;
    END $$`,
]);
async function backfill(db,schema,table) {
  const spec=SOURCES[table];
  await db.query('DELETE FROM public.fallback_storage_content_bindings WHERE source_schema=$1 AND source_table=$2',[schema,table]);
  await db.query(`INSERT INTO public.fallback_storage_content_bindings(source_schema,source_table,access_kind,record_id,bucket_id,object_name)
    SELECT DISTINCT $1,$2,$3,lower(trim(to_jsonb(source)->>'${spec.key}')),object.bucket_id,object.name FROM ${schema}."${table}" source
    CROSS JOIN LATERAL public.novvin_storage_content_urls($2,to_jsonb(source)) urls
    CROSS JOIN LATERAL public.novvin_storage_object_reference(urls.reference_url) reference
    JOIN public.fallback_storage_objects object ON object.bucket_id=reference.bucket_name AND object.name=reference.object_name
    WHERE length(lower(trim(to_jsonb(source)->>'${spec.key}'))) BETWEEN 1 AND 180 ON CONFLICT DO NOTHING`,[schema,table,spec.kind]);
  await db.query(`INSERT INTO public.fallback_storage_content_history(bucket_id,object_name)
    SELECT bucket_id,object_name FROM public.fallback_storage_content_bindings WHERE source_schema=$1 AND source_table=$2 ON CONFLICT DO NOTHING`,[schema,table]);
  if(schema==='public'&&['post','video','moment'].includes(spec.kind))await db.query(`INSERT INTO public.fallback_storage_content_authority(access_kind,record_id,deleted)
    SELECT $1,lower(id::text),false FROM public."${table}" ON CONFLICT DO NOTHING`,[spec.kind]);
}
async function ensureStorageContentSchema(db) {
  for(const sql of CONTENT_TABLE_STATEMENTS)await db.query(sql);
  for(const sql of CONTENT_FUNCTION_STATEMENTS)await db.query(sql);
  for(const table of LOCAL_TABLES) {
    await db.query(`CREATE OR REPLACE TRIGGER ${table}_storage_content_bindings AFTER INSERT OR UPDATE OR DELETE ON public."${table}"
      FOR EACH ROW EXECUTE FUNCTION public.novvin_record_storage_content_bindings()`);
    await backfill(db,'public',table);
  }
  for(const table of IMPORTED_TABLES) {
    const exists=await db.query('SELECT to_regclass($1) AS relation',[`supabase_import.${table}`]);if(!exists.rows[0]?.relation)continue;
    await requireProtectedImportTrigger(db,`supabase_import.${table}`,`${table}_storage_content_bindings`,'novvin_record_storage_content_bindings');
    await backfill(db,'supabase_import',table);
  }
}
// Administrator runs this installation; runtime only verifies and reads imports.
function importedContentBootstrapSql() {
  return storageImportGuardBootstrapSql({historyStatements:HISTORY_FUNCTION_STATEMENTS,contentStatements:CONTENT_FUNCTION_STATEMENTS,
    tableStatements:[...HISTORY_TABLE_STATEMENTS,...CONTENT_TABLE_STATEMENTS]});
}
module.exports={CONTENT_TABLE_STATEMENTS,CONTENT_FUNCTION_STATEMENTS,ensureStorageContentSchema,importedContentBootstrapSql};
