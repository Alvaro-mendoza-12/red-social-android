'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { Pool } = require('pg')
const { _test: { insertAuthorizedCompatRow } } = require('../src/postgrest-compat-routes')
const url = process.env.COMPAT_INSERT_TEST_DATABASE_URL
if (url && !new URL(url).pathname.startsWith('/novvin_compat_audit_')) throw new Error('An isolated compatibility test database is required')

// Also callable against a fresh in-memory PostgreSQL engine for local SQL QA.
async function verifyCompatInsertDatabase(db) {
  await db.query('CREATE SCHEMA supabase_import')
  await db.query(`CREATE TABLE fallback_compat_rows(table_name text, row_id text, row jsonb, updated_at timestamptz DEFAULT now(), PRIMARY KEY(table_name,row_id))`)
  await db.query('CREATE TABLE fallback_posts(id text, user_id text, content text)')
  await db.query('CREATE TABLE supabase_import.posts(id text, user_id text, content text)')
  await db.query("INSERT INTO fallback_posts VALUES ('native-foreign','other','Do not change')")
  await db.query("INSERT INTO supabase_import.posts VALUES ('import-foreign','other','Do not change')")
  await db.query(`INSERT INTO fallback_compat_rows(table_name,row_id,row) VALUES
    ('posts','compat-foreign','{"id":"compat-foreign","user_id":"other","content":"Do not change"}'),
    ('posts','owned','{"id":"owned","user_id":"viewer","content":"Old","visibility":"private"}')`)
  const identity = { ids: new Set(['viewer']), values: ['viewer'], localUserId: 'viewer', preferredUserId: 'viewer' }
  for (const id of ['native-foreign', 'import-foreign', 'compat-foreign']) {
    await assert.rejects(insertAuthorizedCompatRow(db, 'posts', identity, { id, user_id: 'viewer', content: 'Forged' }), error => error.status === 403)
  }
  await insertAuthorizedCompatRow(db, 'posts', identity, { id: 'owned', user_id: 'viewer', content: 'Updated' })
  const owned = (await db.query("SELECT row FROM fallback_compat_rows WHERE row_id='owned'")).rows[0].row
  assert.equal(owned.content, 'Updated')
  assert.equal(owned.visibility, 'private')
  assert.equal((await db.query("SELECT row FROM fallback_compat_rows WHERE row_id='compat-foreign'")).rows[0].row.content, 'Do not change')
  assert.equal((await db.query("SELECT count(*)::int AS count FROM fallback_compat_rows WHERE row_id IN ('native-foreign','import-foreign')")).rows[0].count, 0)

  // Native projections sometimes synthesize their IDs or share a physical table.
  await db.query('CREATE TABLE fallback_post_reactions(post_id text,user_id text,reaction_type text)')
  await db.query("INSERT INTO fallback_post_reactions VALUES ('post','other','like')")
  await assert.rejects(insertAuthorizedCompatRow(db, 'post_likes', identity, { id: 'post:other', post_id: 'post', user_id: 'viewer' }), error => error.status === 403)
  await db.query('CREATE TABLE fallback_room_messages(id text,room_id text,sender_id text)')
  await db.query("INSERT INTO fallback_room_messages VALUES ('message','foreign-room','other')")
  // A foreign sender must be rejected before a projection can replace the native row.
  await assert.rejects(insertAuthorizedCompatRow(db, 'group_messages', identity, { id: 'message', room_id: 'own-room', sender_id: 'viewer' }))
  assert.equal((await db.query("SELECT count(*)::int AS count FROM fallback_compat_rows WHERE table_name IN ('post_likes','group_messages')")).rows[0].count, 0)
}

test('PostgreSQL compatibility inserts authorize every source and preserve owned merges', { skip: !url }, async () => {
  const db = new Pool({ connectionString: url })
  try { await verifyCompatInsertDatabase(db) } finally { await db.end() }
})
module.exports = { verifyCompatInsertDatabase }
