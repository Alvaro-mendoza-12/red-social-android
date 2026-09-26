'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { verifyPlayMatchCandidates } = require('../scripts/verify-playmatch-candidates-readonly')

test('real-schema diagnostic stays read-only and does not print account identifiers', async () => {
  const statements = []
  const id = '11111111-1111-4111-8111-111111111111'
  const db = { query: async (sql) => {
    statements.push(sql)
    return { rows: sql.startsWith('SELECT id,supabase_user_id') ? [{ id }] : [] }
  } }
  const result = await verifyPlayMatchCandidates(db)
  assert.equal(result.ok, true)
  assert.equal(result.skipped, false)
  assert.equal(result.checks, 4)
  assert.equal(statements[0], 'BEGIN READ ONLY')
  assert.equal(statements.at(-1), 'ROLLBACK')
  assert.equal(statements.filter((sql) => sql.includes('FROM fallback_gaming_profiles gp')).length, 4)
  assert(!statements.some((sql) => /\b(INSERT|UPDATE|DELETE|CREATE|ALTER)\b/.test(sql)))
  assert(!JSON.stringify(result).includes(id))
})

test('empty real database is explicitly skipped rather than counted as verified', async () => {
  const statements = []
  const result = await verifyPlayMatchCandidates({ query: async (sql) => { statements.push(sql); return { rows: [] } } })
  assert.equal(result.skipped, true)
  assert.equal(result.checks, 0)
  assert.equal(statements.at(-1), 'ROLLBACK')
})

test('diagnostic rolls back when a real candidate query fails', async () => {
  const statements = []
  const db = { query: async (sql) => {
    statements.push(sql)
    if (sql.includes('FROM fallback_gaming_profiles gp')) throw new Error('fixture schema failure')
    return { rows: sql.startsWith('SELECT id,supabase_user_id') ? [{ id: '11111111-1111-4111-8111-111111111111' }] : [] }
  } }
  await assert.rejects(verifyPlayMatchCandidates(db), /fixture schema failure/)
  assert.equal(statements.at(-1), 'ROLLBACK')
})
