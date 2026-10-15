'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { parseObjectReference, trustedOrigins } = require('./storage-read-access');
const { SOURCES, LOCAL_TABLES, IMPORTED_TABLES } = require('./storage-content-read');

const norm = value => typeof value === 'string' ? value.trim().toLowerCase() : '';
const uuid = value => /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(norm(value));
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const fail = code => Object.assign(new Error(code), { code });
const identifier = value => {
  if (!/^[a-z_][a-z_0-9]*$/.test(value)) throw fail('LEGACY_MIGRATION_IDENTIFIER_INVALID');
  return '"' + value + '"';
};
const PRIVATE_SOURCES = Object.freeze({
  fallback_messages: { key: 'id', owner: ['sender_id'], fields: ['media_url', 'thumbnail_url'] },
  fallback_room_messages: { key: 'id', owner: ['sender_id'], fields: ['media_url', 'thumbnail_url'] },
  fallback_random_chat_messages: { key: 'id', owner: ['sender_id'], fields: ['media_url', 'thumbnail_url'] },
  messages: { key: 'id', owner: ['sender_id', 'user_id'], fields: ['media_url', 'thumbnail_url'] },
  group_messages: { key: 'id', owner: ['sender_id', 'user_id'], fields: ['media_url', 'thumbnail_url'] },
  community_messages: { key: 'id', owner: ['sender_id', 'user_id'], fields: ['media_url', 'thumbnail_url'] },
  proximity_messages: { key: 'id', owner: ['sender_id', 'user_id'], fields: ['media_url', 'thumbnail_url'] },
});

function migrationPolicy({ canonicalBase, historicalOrigins, evidenceDigest }) {
  const canonical = trustedOrigins([canonicalBase])[0];
  if (!Array.isArray(historicalOrigins) || !historicalOrigins.length || historicalOrigins.length > 32) throw fail('LEGACY_MIGRATION_EXACT_ORIGINS_REQUIRED');
  // This offline list is reviewed explicitly. It does not alter storage read
  // trust or allow wildcard/substring/Host-derived admission.
  const historical = [...new Set(historicalOrigins.map(value => {
    const origin=trustedOrigins([value])[0];
    if(new URL(origin).hostname.includes('*'))throw fail('LEGACY_MIGRATION_WILDCARD_ORIGIN_DENIED');
    return origin;
  }))].sort();
  if (historical.includes(canonical)) throw fail('LEGACY_MIGRATION_ORIGIN_LIST_INVALID');
  if (!/^[0-9a-f]{64}$/.test(String(evidenceDigest || ''))) throw fail('LEGACY_MIGRATION_EVIDENCE_DIGEST_REQUIRED');
  return Object.freeze({ canonicalBase: canonical, historicalOrigins: Object.freeze(historical), evidenceDigest });
}

function canonicalOwner(alias, users) {
  const value = norm(alias);
  if (!uuid(value)) return null;
  const candidates = users.filter(user => [norm(user.id), norm(user.supabase_user_id)].includes(value));
  if (candidates.length !== 1 || !uuid(candidates[0].id)) return null;
  return norm(candidates[0].id);
}

function safeObjectFile(storageRoot, object) {
  const root = fs.realpathSync(storageRoot);
  if (!/^[a-zA-Z0-9_-]{1,128}$/.test(object.bucket_id) || typeof object.name !== 'string') throw fail('LEGACY_MIGRATION_OBJECT_PATH_INVALID');
  const segments = [object.bucket_id, ...object.name.split('/')];
  let current = root;
  for (const segment of segments) {
    if (!segment || ['.', '..'].includes(segment) || /[\\/\x00-\x1f\x7f]/.test(segment)) throw fail('LEGACY_MIGRATION_OBJECT_PATH_INVALID');
    current = path.join(current, segment);
    const stat = fs.lstatSync(current);
    if (stat.isSymbolicLink()) throw fail('LEGACY_MIGRATION_SYMLINK_DENIED');
  }
  const resolved = fs.realpathSync(current);
  const relative = path.relative(root, resolved);
  if (!relative || relative.startsWith('..' + path.sep) || path.isAbsolute(relative)) throw fail('LEGACY_MIGRATION_OBJECT_PATH_INVALID');
  const stat = fs.statSync(resolved);
  if (!stat.isFile() || stat.size <= 0 || stat.size > 500 * 1024 * 1024) throw fail('LEGACY_MIGRATION_PHYSICAL_FILE_INVALID');
  if (!Number.isSafeInteger(Number(object.size_bytes)) || Number(object.size_bytes) !== stat.size) throw fail('LEGACY_MIGRATION_SIZE_MISMATCH');
  return { filename: resolved, bytes: stat.size };
}

function canonicalReference(value, { policy, ownerId, users, registry, storageRoot, fileCache = new Map() }) {
  if (typeof value !== 'string' || !value) return { value, changed: false };
  let origin;
  try { origin = new URL(value).origin; } catch { return { value, changed: false }; }
  if (origin === policy.canonicalBase) return { value, changed: false };
  if (!policy.historicalOrigins.includes(origin)) return { value, changed: false, blocked: /\/storage\/v1\/object\//.test(value) ? 'UNREVIEWED_ORIGIN' : undefined };
  const reference = parseObjectReference(value, [origin]);
  if (!reference) return { value, changed: false, blocked: 'INVALID_REFERENCE_SYNTAX' };
  const candidates = registry.filter(object => object.bucket_id === reference.bucket && object.name === reference.name);
  if (candidates.length !== 1) return { value, changed: false, blocked: candidates.length ? 'AMBIGUOUS_REGISTRY_OBJECT' : 'NO_REGISTRY_OBJECT' };
  const object = candidates[0];
  const entityOwner = canonicalOwner(ownerId, users), objectOwner = canonicalOwner(object.owner_id, users);
  if (!entityOwner || !objectOwner || entityOwner !== objectOwner) return { value, changed: false, blocked: 'OWNER_PROVENANCE_MISMATCH' };
  const registered = parseObjectReference(object.public_url, [policy.canonicalBase, ...policy.historicalOrigins]);
  if (!registered || registered.bucket !== reference.bucket || registered.name !== reference.name) return { value, changed: false, blocked: 'REGISTRY_REFERENCE_PROVENANCE_MISMATCH' };
  const key = object.bucket_id + '\0' + object.name;
  let physical = fileCache.get(key);
  if (!physical) {
    try { physical = safeObjectFile(storageRoot, object); }
    catch (error) { return { value, changed: false, blocked: error.code || 'PHYSICAL_FILE_MISSING' }; }
    fileCache.set(key, physical);
  }
  // Historical query parameters and signed capabilities are never migrated as
  // grants. References become canonical object URLs and are
  // still admitted by the entity read guard, membership/privacy and leases.
  const encoded = object.name.split('/').map(encodeURIComponent).join('/');
  return { value: policy.canonicalBase + '/storage/v1/object/public/' + encodeURIComponent(object.bucket_id) + '/' + encoded,
    changed: true, proof: { ownerId: objectOwner, bucket: object.bucket_id, objectName: object.name, physicalBytes: physical.bytes } };
}

function sourceSpecification(table) {
  if (Object.hasOwn(PRIVATE_SOURCES, table)) return PRIVATE_SOURCES[table];
  const source = SOURCES[table];
  if (!source) throw fail('LEGACY_MIGRATION_SOURCE_INVALID');
  return { ...source, owner: source.kind === 'profile' ? [table === 'fallback_profile_overrides' ? 'user_id' : 'id', 'user_id', 'profile_id'] : ['user_id', 'userId', 'owner_id', 'author_id', 'creator_id', 'profile_id'] };
}

function transformSourceRow(table, row, context) {
  const specification = sourceSpecification(table);
  const changed = {}, blocked = {}, proofs = [];
  const ownerId = specification.owner.map(field => row[field]).find(value => typeof value === 'string' && value.trim());
  const source = specification.nested ? row[specification.nested] : row;
  if (!source || typeof source !== 'object' || Array.isArray(source)) return { changed, blocked, proofs };
  const visit = value => {
    if (Array.isArray(value)) return value.map(visit);
    if (typeof value !== 'string') return value;
    if (value.trim().startsWith('[')) {
      try { const parsed = JSON.parse(value); if (Array.isArray(parsed)) {
        const updated = parsed.map(visit); return JSON.stringify(parsed) === JSON.stringify(updated) ? value : JSON.stringify(updated);
      } } catch {}
    }
    const result = canonicalReference(value, { ...context, ownerId });
    if (result.blocked) blocked[result.blocked] = (blocked[result.blocked] || 0) + 1;
    if (result.changed) proofs.push(result.proof);
    return result.value;
  };
  const updated = { ...source };
  for (const field of specification.fields) {
    if (!Object.hasOwn(source, field)) continue;
    const value = visit(source[field]);
    if (JSON.stringify(value) !== JSON.stringify(source[field])) { updated[field] = value; if (!specification.nested) changed[field] = value; }
  }
  if (specification.nested && JSON.stringify(updated) !== JSON.stringify(source)) changed[specification.nested] = updated;
  return { changed, blocked, proofs };
}

async function planLegacyReferenceMigration(db, { policy, storageRoot }) {
  if (!db || typeof db.query !== 'function') throw fail('LEGACY_MIGRATION_DB_REQUIRED');
  const users = (await db.query('SELECT id::text,supabase_user_id::text FROM public.fallback_users')).rows;
  const registry = (await db.query('SELECT bucket_id,name,owner_id,public_url,size_bytes FROM public.fallback_storage_objects')).rows;
  const privateLocal = Object.keys(PRIVATE_SOURCES).filter(table => table.startsWith('fallback_'));
  const privateImported = Object.keys(PRIVATE_SOURCES).filter(table => !table.startsWith('fallback_'));
  const sources = [...LOCAL_TABLES, ...privateLocal].map(table => ({ schema: 'public', table }));
  sources.push(...[...IMPORTED_TABLES, ...privateImported].map(table => ({ schema: 'supabase_import', table })));
  const plans = [], summary = { reviewedExactOrigins: policy.historicalOrigins.length, changedRows: 0, changedReferences: 0, blocked: {}, tables: [] };
  const fileCache = new Map();
  for (const { schema, table } of sources) {
    if (!(await db.query('SELECT to_regclass($1) IS NOT NULL AS present', [schema + '.' + table])).rows[0]?.present) continue;
    const columns = (await db.query('SELECT column_name,udt_name FROM information_schema.columns WHERE table_schema=$1 AND table_name=$2', [schema, table])).rows;
    const rows = (await db.query('SELECT to_jsonb(source) AS row FROM ' + identifier(schema) + '.' + identifier(table) + ' source')).rows;
    const specification = sourceSpecification(table), seen = new Map();
    for (const { row } of rows) { const key = norm(row[specification.key]); seen.set(key, (seen.get(key) || 0) + 1); }
    let changedRows = 0;
    for (const { row } of rows) {
      const result = transformSourceRow(table, row, { policy, users, registry, storageRoot, fileCache });
      for (const [code, count] of Object.entries(result.blocked)) summary.blocked[code] = (summary.blocked[code] || 0) + count;
      if (!Object.keys(result.changed).length) continue;
      const key = norm(row[specification.key]);
      if (!key || seen.get(key) !== 1) { summary.blocked.AMBIGUOUS_ENTITY_ID = (summary.blocked.AMBIGUOUS_ENTITY_ID || 0) + 1; continue; }
      for (const field of Object.keys(result.changed)) {
        const column = columns.find(c => c.column_name === field);
        if (!column || !['text','varchar','json','jsonb','_text'].includes(column.udt_name)) throw fail('LEGACY_MIGRATION_FIELD_TYPE_UNSUPPORTED');
      }
      plans.push({ schema, table, keyField: specification.key, key, original: row, changed: result.changed, columns, proofs: result.proofs });
      changedRows++;summary.changedRows++;summary.changedReferences += result.proofs.length;
    }
    summary.tables.push({ schema, table, rows: rows.length, changedRows });
  }
  // Exact source values/proofs remain private at the operator. Only this digest
  // and summary are suitable for an audit report or external review.
  const digest = hash(JSON.stringify({ policy, plans }));
  return { version: 1, policy, plans, digest, summary };
}

async function applyLegacyReferenceMigration(db, plan, { reviewedPlanDigest, storageRoot }) {
  if (!plan || plan.version !== 1 || !/^[0-9a-f]{64}$/.test(String(reviewedPlanDigest || '')) || reviewedPlanDigest !== plan.digest ||
      hash(JSON.stringify({ policy: plan.policy, plans: plan.plans })) !== plan.digest) throw fail('LEGACY_MIGRATION_REVIEWED_PLAN_REQUIRED');
  await db.query('BEGIN');
  try {
    await db.query("SET LOCAL lock_timeout='5s'");
    await db.query("SET LOCAL statement_timeout='30s'");
    const users=(await db.query('SELECT id::text,supabase_user_id::text FROM public.fallback_users FOR SHARE')).rows;
    const registry=(await db.query('SELECT bucket_id,name,owner_id,public_url,size_bytes FROM public.fallback_storage_objects FOR SHARE')).rows;
    const fileCache=new Map();
    let updatedRows = 0;
    for (const item of plan.plans) {
      const specification=sourceSpecification(item.table);
      if(!['public','supabase_import'].includes(item.schema)||(item.schema==='public')!==item.table.startsWith('fallback_')||item.keyField!==specification.key)
        throw fail('LEGACY_MIGRATION_SOURCE_INVALID');
      const rechecked=transformSourceRow(item.table,item.original,{policy:plan.policy,users,registry,storageRoot,fileCache});
      if(JSON.stringify(rechecked.changed)!==JSON.stringify(item.changed)||JSON.stringify(rechecked.proofs)!==JSON.stringify(item.proofs))
        throw fail('LEGACY_MIGRATION_PROVENANCE_CHANGED');
      const parameters = [], assignments = [];
      for (const [field, value] of Object.entries(item.changed)) {
        const column = item.columns.find(c => c.column_name === field);
        if (!column || !['text','varchar','json','jsonb','_text'].includes(column.udt_name)) throw fail('LEGACY_MIGRATION_FIELD_TYPE_UNSUPPORTED');
        parameters.push(['json','jsonb'].includes(column.udt_name) ? JSON.stringify(value) : value);
        const cast = ({ text:'text', varchar:'text', json:'json', jsonb:'jsonb', _text:'text[]' })[column.udt_name];
        assignments.push(identifier(field) + '=$' + parameters.length + '::' + cast);
      }
      parameters.push(item.key, JSON.stringify(item.original));
      const result = await db.query('UPDATE ' + identifier(item.schema) + '.' + identifier(item.table) + ' AS source SET ' + assignments.join(',') +
        ' WHERE lower(trim(source.' + identifier(item.keyField) + '::text))=$' + (parameters.length - 1) + ' AND to_jsonb(source)=$' + parameters.length + '::jsonb', parameters);
      if (result.rowCount !== 1) throw fail('LEGACY_MIGRATION_STALE_OR_DUPLICATE_ENTITY');
      updatedRows++;
    }
    await db.query('COMMIT');return { updatedRows, changedReferences: plan.summary.changedReferences, planDigest: plan.digest };
  } catch (error) { await db.query('ROLLBACK').catch(() => {});throw error; }
}

module.exports = { migrationPolicy, canonicalOwner, safeObjectFile, canonicalReference, transformSourceRow,
  planLegacyReferenceMigration, applyLegacyReferenceMigration };
