'use strict';
const {trustedOrigins}=require('./storage-read-access');
const {HISTORY_TABLE_STATEMENTS,HISTORY_FUNCTION_STATEMENTS}=require('./storage-read-history-ddl');
const {ensureStorageContentSchema}=require('./storage-content-schema');
const {ensureStorageViewOnceSchema}=require('./storage-view-once');
const {requireProtectedImportTrigger}=require('./storage-import-guard-schema');

// Invoke on the existing connected schema-initializer client AFTER storage
// and data/proximity initialization. The current initializer uses a session
// lock, not BEGIN, so this helper owns its transaction by default. A caller
// already inside BEGIN must explicitly pass transactionActive: true.
async function ensureStorageReadSchema(db, { transactionActive = false, origins } = {}) {
  if (!db || typeof db.query !== 'function') throw new TypeError('Connected PostgreSQL client required');
  const allowedOrigins=trustedOrigins(origins);
  if (!transactionActive) await db.query('BEGIN');
  try {
    await initialize(db,allowedOrigins);
    await ensureStorageContentSchema(db);
    await ensureStorageViewOnceSchema(db);
    if (!transactionActive) await db.query('COMMIT');
  } catch (error) {
    if (!transactionActive) await db.query('ROLLBACK').catch(() => {});
    throw error;
  }
}
async function initialize(db,origins) {
  await db.query("SELECT pg_advisory_xact_lock(hashtextextended('novvin-storage-read-schema',0))");
  for(const statement of HISTORY_TABLE_STATEMENTS)await db.query(statement);
  // Never derive trust from Host or an arbitrary recorded object URL. Retain
  // historical explicitly configured origins for durable classification only;
  // actual read admission always uses the guard's current configured origins.
  await db.query('INSERT INTO public.fallback_storage_read_origins(origin) SELECT unnest($1::text[]) ON CONFLICT DO NOTHING',[origins]);
  const indexes = [
    ['idx_storage_reference_public_url', 'fallback_storage_objects', "split_part(public_url,'?',1)"],
    ['idx_private_message_media_ref', 'fallback_messages', "split_part(COALESCE(media_url,''),'?',1)"],
    ['idx_private_message_thumbnail_ref', 'fallback_messages', "split_part(COALESCE(thumbnail_url,''),'?',1)"],
    ['idx_private_room_media_ref', 'fallback_room_messages', "split_part(COALESCE(media_url,''),'?',1)"],
    ['idx_private_random_media_ref', 'fallback_random_chat_messages', "split_part(COALESCE(media_url,''),'?',1)"],
    ['idx_public_room_avatar_ref', 'fallback_collection_rooms', "split_part(COALESCE(image,''),'?',1)"],
  ];
  for (const [name, table, expression] of indexes) await db.query(`CREATE INDEX IF NOT EXISTS ${name} ON ${table}((${expression}))`);
  for(const table of ['fallback_messages','fallback_room_messages','fallback_random_chat_messages'])await db.query(`CREATE INDEX IF NOT EXISTS idx_private_${table}_id_ref ON ${table}((lower(id::text)))`);
  for(const statement of HISTORY_FUNCTION_STATEMENTS)await db.query(statement);
  for (const table of ['fallback_messages', 'fallback_room_messages', 'fallback_random_chat_messages']) {
    const trigger = `${table}_private_media_history`;
    // CREATE OR REPLACE TRIGGER is supported by deployed PG16 and tested PG18.
    await db.query(`CREATE OR REPLACE TRIGGER ${trigger} AFTER INSERT OR UPDATE OR DELETE ON ${table} FOR EACH ROW EXECUTE FUNCTION public.novvin_record_private_media_history()`);
    await db.query(`INSERT INTO fallback_storage_private_history(bucket_id,object_name)
      SELECT DISTINCT o.bucket_id,o.name FROM ${table} m
      CROSS JOIN LATERAL unnest(ARRAY[to_jsonb(m)->>'media_url',to_jsonb(m)->>'thumbnail_url']) u(reference_url)
      CROSS JOIN LATERAL public.novvin_storage_object_reference(u.reference_url) ref
      JOIN public.fallback_storage_objects o ON o.bucket_id=ref.bucket_name AND o.name=ref.object_name ON CONFLICT DO NOTHING`);
    await backfillBindings(db,'public',table,table==='fallback_messages'?'dm':table==='fallback_random_chat_messages'?'random':null);
  }
  for (const table of ['messages', 'group_messages', 'community_messages', 'proximity_messages']) {
    const exists = await db.query('SELECT to_regclass($1) AS relation', [`supabase_import.${table}`]);
    if (!exists.rows[0]?.relation) continue;
    const access = await db.query(`SELECT has_schema_privilege(current_user,'supabase_import','USAGE') AND has_table_privilege(current_user,$1,'SELECT') AS readable`, [`supabase_import.${table}`]);
    if (!access.rows[0]?.readable) throw Object.assign(new Error('STORAGE_IMPORT_READ_BOOTSTRAP_REQUIRED'), { code: 'STORAGE_IMPORT_READ_BOOTSTRAP_REQUIRED' });
    const columns = await db.query(`SELECT a.attname AS name, a.atttypid IN ('text'::regtype,'varchar'::regtype) AS text_type FROM pg_attribute a WHERE a.attrelid=$1::regclass AND a.attnum>0 AND NOT a.attisdropped AND a.attname=ANY($2::text[])`, [`supabase_import.${table}`, ['media_url', 'thumbnail_url']]);
    for (const column of columns.rows) {
      if (!column.text_type) throw new Error('STORAGE_IMPORT_REFERENCE_SCHEMA_UNSUPPORTED');
      const index = await db.query(`SELECT i.indisvalid AND i.indisready AND i.indnkeyatts=1 AND i.indpred IS NULL AS ready
        FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='supabase_import' AND c.relname=$1 AND i.indrelid=$2::regclass`, [`idx_private_${table}_${column.name}_ref`, `supabase_import.${table}`]);
      if (!index.rows[0]?.ready) throw Object.assign(new Error('STORAGE_IMPORT_INDEX_BOOTSTRAP_REQUIRED'), { code: 'STORAGE_IMPORT_INDEX_BOOTSTRAP_REQUIRED' });
    }
    await requireProtectedImportTrigger(db,`supabase_import.${table}`,`${table}_private_media_history`,'novvin_record_private_media_history');
    await db.query(`INSERT INTO fallback_storage_private_history(bucket_id,object_name)
      SELECT DISTINCT o.bucket_id,o.name FROM supabase_import."${table}" m
      CROSS JOIN LATERAL unnest(ARRAY[to_jsonb(m)->>'media_url',to_jsonb(m)->>'thumbnail_url']) u(reference_url)
      CROSS JOIN LATERAL public.novvin_storage_object_reference(u.reference_url) ref
      JOIN public.fallback_storage_objects o ON o.bucket_id=ref.bucket_name AND o.name=ref.object_name ON CONFLICT DO NOTHING`);
    await backfillBindings(db,'supabase_import',table,({messages:'dm',group_messages:'group',community_messages:'community',proximity_messages:'proximity'})[table]);
  }
}
async function backfillBindings(db,schema,table,kind) {
  // schema/table/kind come only from the constant loops above.
  await db.query('DELETE FROM public.fallback_storage_private_bindings WHERE source_schema=$1 AND source_table=$2',[schema,table]);
  await db.query(`INSERT INTO public.fallback_storage_private_bindings(source_schema,source_table,access_kind,message_id,bucket_id,object_name)
    SELECT DISTINCT $1,$2,${kind?'$3::text':"to_jsonb(m)->>'room_kind'"},lower(trim(to_jsonb(m)->>'id')),o.bucket_id,o.name FROM ${schema}."${table}" m
    CROSS JOIN LATERAL unnest(ARRAY[to_jsonb(m)->>'media_url',to_jsonb(m)->>'thumbnail_url']) u(reference_url)
    CROSS JOIN LATERAL public.novvin_storage_object_reference(u.reference_url) ref
    JOIN public.fallback_storage_objects o ON o.bucket_id=ref.bucket_name AND o.name=ref.object_name
    WHERE length(lower(trim(to_jsonb(m)->>'id'))) BETWEEN 1 AND 128
    ON CONFLICT DO NOTHING`,kind?[schema,table,kind]:[schema,table]);
}
module.exports = { ensureStorageReadSchema };
