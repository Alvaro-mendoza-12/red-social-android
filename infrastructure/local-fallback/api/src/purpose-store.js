'use strict'

const crypto = require('node:crypto')
const { fail } = require('./purpose-domain')
async function ensurePurposeTables(db) {
  await db.query(`CREATE TABLE IF NOT EXISTS experimental_purpose_objects (
    kind TEXT NOT NULL CHECK(kind IN ('social','gaming','guide','community_start')), object_key TEXT NOT NULL,
    target_id TEXT NOT NULL, author_id TEXT NOT NULL, state JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), PRIMARY KEY(kind,object_key))`)
  await db.query(`CREATE INDEX IF NOT EXISTS idx_experimental_purpose_target ON experimental_purpose_objects(kind,target_id,updated_at DESC,object_key)`)
  await db.query(`CREATE INDEX IF NOT EXISTS idx_experimental_purpose_author ON experimental_purpose_objects(author_id,kind,updated_at DESC)`)
  await db.query(`CREATE INDEX IF NOT EXISTS idx_experimental_purpose_players ON experimental_purpose_objects
    USING GIN ((state->'participants') jsonb_path_ops) WHERE kind='gaming'`)
  await db.query(`CREATE TABLE IF NOT EXISTS experimental_purpose_operations (
    actor_id TEXT NOT NULL, operation_id UUID NOT NULL, request_hash TEXT NOT NULL, kind TEXT NOT NULL, object_key TEXT NOT NULL,
    PRIMARY KEY(actor_id,operation_id), FOREIGN KEY(kind,object_key) REFERENCES experimental_purpose_objects(kind,object_key) ON DELETE CASCADE)`)
}
async function readPurpose(db, kind, key) {
  return (await db.query('SELECT state FROM experimental_purpose_objects WHERE kind=$1 AND object_key=$2', [kind,key])).rows[0]?.state || null
}
async function writePurpose(pool, {kind, key, targetId, command, authorize, apply}) {
  const db = await pool.connect()
  try {
    await db.query('BEGIN')
    await db.query("SET LOCAL statement_timeout='5s'")
    await db.query("SET LOCAL lock_timeout='2s'")
    await db.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))', [`purpose:${kind}:${key}`])
    const context = await authorize(db)
    const digest = crypto.createHash('sha256').update(JSON.stringify({kind,key,targetId,command})).digest('hex')
    const receipt = (await db.query('SELECT request_hash FROM experimental_purpose_operations WHERE actor_id=$1 AND operation_id=$2', [context.actorId,command.operationId])).rows[0]
    const previous = await readPurpose(db,kind,key)
    if (receipt) {
      if (receipt.request_hash !== digest) throw fail('PURPOSE_OPERATION_CONFLICT',409)
      if (!previous) throw fail('PURPOSE_NOT_FOUND',404)
      await db.query('COMMIT')
      return {state:previous,replayed:true}
    }
    if ((previous?.revision || 0) !== command.expectedRevision) throw fail('PURPOSE_REVISION_CONFLICT',409)
    const now = new Date().toISOString()
    const state = {...apply(previous,command,context,now),revision:(previous?.revision || 0)+1,updatedAt:now}
    await db.query(`INSERT INTO experimental_purpose_objects(kind,object_key,target_id,author_id,state)
      VALUES($1,$2,$3,$4,$5::jsonb) ON CONFLICT(kind,object_key) DO UPDATE SET state=EXCLUDED.state,updated_at=NOW()`,
    [kind,key,targetId,state.authorId,JSON.stringify(state)])
    await db.query('INSERT INTO experimental_purpose_operations(actor_id,operation_id,request_hash,kind,object_key) VALUES($1,$2,$3,$4,$5)',
      [context.actorId,command.operationId,digest,kind,key])
    await db.query('COMMIT')
    return {state,replayed:false}
  } catch (error) {
    try {await db.query('ROLLBACK')} catch (rollback) {console.warn('[purpose] rollback failed',{code:rollback.code || 'DATABASE_FAILURE'})}
    if (error.code === '23505') throw fail('PURPOSE_OPERATION_CONFLICT',409)
    throw error
  } finally {db.release()}
}
async function purgePurposeForAccounts(db, identities) {
  const ids=[...new Set(identities.map(value=>String(value || '').trim().toLowerCase()).filter(Boolean))]
  if (!ids.length) return {objects:0,operations:0}
  const present=(await db.query("SELECT to_regclass('public.experimental_purpose_objects') AS objects, to_regclass('public.experimental_purpose_operations') AS operations")).rows[0]
  if (!present?.objects) return {objects:0,operations:0}
  const operations=present.operations ? (await db.query('DELETE FROM experimental_purpose_operations WHERE lower(actor_id)=ANY($1::text[])',[ids])).rowCount : 0
  const objects=(await db.query(`DELETE FROM experimental_purpose_objects item WHERE lower(author_id)=ANY($1::text[])
    OR EXISTS (SELECT 1 FROM jsonb_array_elements(COALESCE(item.state->'participants','[]'::jsonb)) member WHERE lower(member->>'id')=ANY($1::text[]))`,[ids])).rowCount
  return {objects:Number(objects)||0,operations:Number(operations)||0}
}
module.exports={ensurePurposeTables,readPurpose,writePurpose,purgePurposeForAccounts}
