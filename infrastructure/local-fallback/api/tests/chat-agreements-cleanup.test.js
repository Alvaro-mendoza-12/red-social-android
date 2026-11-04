'use strict'

const { test } = require('node:test')
const assert = require('node:assert/strict')
const { purgeChatAgreementsForAccounts } = require('../src/chat-agreements-cleanup')

test('an empty identity set or an uninstalled experiment performs no deletion', async () => {
  const calls = []
  const db = { query: async sql => { calls.push(sql); return { rows: [{ agreements: null, operations: null }] } } }
  assert.deepEqual(await purgeChatAgreementsForAccounts(db, []), { agreements: 0, operations: 0 })
  assert.equal(calls.length, 0)
  assert.deepEqual(await purgeChatAgreementsForAccounts(db, ['account']), { agreements: 0, operations: 0 })
  assert.equal(calls.length, 1)
})
test('cleanup targets both canonical aliases and every agreement in which the deleted account participated', async () => {
  const calls = []
  const db = { query: async (sql, values) => {
    calls.push({ sql, values })
    return sql.includes('to_regclass') ? { rows: [{ agreements: 'table', operations: 'table' }] } : { rowCount: 2 }
  } }
  assert.deepEqual(await purgeChatAgreementsForAccounts(db, [' LOCAL ', 'PUBLIC', 'local']), { agreements: 2, operations: 2 })
  assert.deepEqual(calls[1].values, [['local', 'public']]); assert.deepEqual(calls[2].values, [['local', 'public']])
  assert.match(calls[2].sql, /author_id/); assert.match(calls[2].sql, /jsonb_array_elements/)
  assert.equal(calls.some(call => /DELETE FROM fallback_users/.test(call.sql)), false)
})
test('a deletion failure aborts the owning transaction instead of pretending that data was removed', async () => {
  const db = { query: async sql => {
    if (sql.includes('to_regclass')) return { rows: [{ agreements: 'table', operations: 'table' }] }
    throw Error('disk unavailable')
  } }
  await assert.rejects(purgeChatAgreementsForAccounts(db, ['account']), /disk unavailable/)
})
