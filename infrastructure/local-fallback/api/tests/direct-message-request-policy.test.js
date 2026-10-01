const assert = require('node:assert/strict')
const test = require('node:test')
const { ensureDirectMessageRequestPolicy, introPolicyError } = require('../src/direct-message-request-policy')

test('pending friendship permits subsequent messages while rejection and block remain enforced', async () => {
  const statements = []
  await ensureDirectMessageRequestPolicy({ query: async (sql) => { statements.push(sql); return { rows: [] } } })
  const guard = statements.find(sql => sql.includes('CREATE OR REPLACE FUNCTION'))
  assert.ok(guard)
  assert.ok(!guard.includes("RAISE EXCEPTION 'FRIEND_REQUEST_PENDING'"))
  assert.ok(guard.includes("RAISE EXCEPTION 'FRIEND_REQUEST_REJECTED'"))
  assert.ok(guard.includes("RAISE EXCEPTION 'CHAT_BLOCKED_BY_RECIPIENT'"))
  assert.ok(guard.includes('ON CONFLICT (sender_id, receiver_id) DO NOTHING'))
  assert.ok(guard.includes('WHERE id = NEW.id'))
})

test('legacy pending-policy errors still surface as rejection rather than successful acknowledgement', () => {
  assert.equal(introPolicyError(new Error('FRIEND_REQUEST_PENDING')), 'FRIEND_REQUEST_PENDING')
  assert.equal(introPolicyError(new Error('FRIEND_REQUEST_REJECTED')), 'FRIEND_REQUEST_REJECTED')
  assert.equal(introPolicyError(new Error('unrelated error')), null)
})
