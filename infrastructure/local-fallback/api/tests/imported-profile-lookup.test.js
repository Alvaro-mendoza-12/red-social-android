'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { importedRowsByFieldValues } = require('../src/data-routes')

test('profile identity aliases share one row conversion and retain parameterized filtering', async () => {
  const calls = []
  const pool = { query: async (sql, params) => {
    calls.push({ sql, params })
    return { rows: sql.includes('information_schema.tables') ? [{ exists: 1 }] : [] }
  } }
  await importedRowsByFieldValues(pool, 'profiles', ['id', 'email', 'missing_legacy_id', "id' OR TRUE--"], [' USER-1 ', 'USER-1'], 25)
  const read = calls.find(call => call.sql.includes('supabase_import."profiles"'))
  assert.match(read.sql, /AS MATERIALIZED/)
  assert.equal((read.sql.match(/to_jsonb\(t\)/g) || []).length, 1)
  assert.match(read.sql, /imported\.row->>'missing_legacy_id'/)
  assert.ok(!read.sql.includes('OR TRUE--'))
  assert.deepEqual(read.params, [['user-1'], 25])
})

// This fixture requires a disposable database with this exact name. It creates
// only synthetic rows inside a rolled-back transaction, never production data.
const databaseUrl = process.env.IMPORTED_PROFILE_TEST_DATABASE_URL
test('PostgreSQL preserves alias matching, null/missing columns, complete rows and limits',
  { skip: !databaseUrl }, async () => {
    assert.equal(new URL(databaseUrl).pathname, '/novvin_profile_lookup_test')
    const { Client } = require('pg')
    const db = new Client({ connectionString: databaseUrl })
    await db.connect()
    try {
      await db.query('BEGIN')
      await db.query('CREATE SCHEMA supabase_import')
      await db.query(`CREATE TABLE supabase_import.profiles (
        id text, user_id text, email text, payload text, settings jsonb
      )`)
      await db.query(`INSERT INTO supabase_import.profiles VALUES
        ('user-1', 'alias-1', 'one@example.invalid', repeat('large-field-', 500), '{"private":true}'),
        ('USER-2', NULL, 'two@example.invalid', repeat('large-field-', 500), '{"private":false}'),
        ('user-3', '', NULL, 'other', NULL)`)
      const fields = ['id', 'user_id', 'email', 'profile_id', 'supabase_user_id', 'legacy_id', 'owner_id', 'username', 'missing']
      let query
      const pool = { query: async (sql, params) => {
        if (!sql.includes('information_schema.tables')) query = { sql, params }
        return db.query(sql, params)
      } }
      const legacyPool = { query: async (sql, params) => {
        if (sql.includes('information_schema.tables')) return db.query(sql, params)
        return db.query(`SELECT to_jsonb(t) AS row FROM supabase_import.profiles t
          WHERE ${fields.map(field => `lower(COALESCE(to_jsonb(t)->>'${field}', '')) = ANY($1::text[])`).join(' OR ')} LIMIT $2`, params)
      } }
      for (const values of [['USER-1'], ['alias-1'], ['two@example.invalid'], ['unknown'], ['user-1', 'user-2']]) {
        for (const limit of [1, 10]) {
          const actual = await importedRowsByFieldValues(pool, 'profiles', fields, values, limit)
          const legacy = await importedRowsByFieldValues(legacyPool, 'profiles', fields, values, limit)
          assert.deepEqual(actual, legacy)
        }
      }
      const plan = await db.query(`EXPLAIN (FORMAT JSON) ${query.sql}`, query.params)
      assert.match(JSON.stringify(plan.rows), /CTE Scan/)
    } finally {
      await db.query('ROLLBACK').catch(() => {})
      await db.end()
    }
  })
