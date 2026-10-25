'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const vm = require('node:vm')
const { createRequire } = require('node:module')
const { MESSAGE_PUSH_CONCURRENCY, createMessagePushLimiter } = require('../src/message-push-limiter')

const success = () => ({ successCount: 1, failureCount: 0, responses: [{ success: true }] })
const turn = () => new Promise(resolve => setImmediate(resolve))
async function until(predicate) {
  const deadline = Date.now() + 3_000
  while (!predicate()) {
    if (Date.now() > deadline) throw Error('PUSH_TEST_DEADLINE')
    await turn()
  }
}

// Execute the real push path, including withCurrentMessage, with fake Firebase
// and PostgreSQL boundaries. No credentials, provider calls or database needed.
function fixture() {
  const pending = [], starts = [], reads = [], rows = new Map()
  let active = 0, peak = 0, connects = 0, rollbacks = 0
  const row = messageId => rows.get(messageId) || {
    id: messageId, chat_id: 'chat', sender_id: 'sender', content: 'Current message', content_type: 'text',
  }
  const query = async (sql, parameters = []) => {
    if (sql.includes('FROM fallback_messages')) { reads.push(parameters[1]); return { rows: [row(parameters[1])] } }
    if (sql.includes('FROM fallback_compat_rows')) return { rows: [] }
    if (sql.includes('SELECT fcm.token')) return { rows: [{ token: 'qa-fake-token' }] }
    if (sql.includes('FROM fallback_users')) return { rows: [{ id: 'sender' }] }
    if (sql === 'ROLLBACK') rollbacks++
    return { rows: [] }
  }
  const pool = {
    query,
    connect: async () => {
      connects++; active++; peak = Math.max(peak, active)
      return { query, release: () => { active-- } }
    },
  }
  const filename = require.resolve('../src/push-notifications')
  const originalRequire = createRequire(filename)
  const module = { exports: {} }
  const limiter = createMessagePushLimiter()
  vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
    module, exports: module.exports,
    process: { env: { FIREBASE_SERVICE_ACCOUNT_FILE: '/qa/synthetic-config.json' } },
    console: { log() {}, error() {} },
    require(name) {
      if (name === 'fs') return { existsSync: () => true, readFileSync: () => '{}' }
      if (name === 'firebase-admin/app') return {
        cert: value => value, getApps: () => [{}], initializeApp: () => ({}),
      }
      if (name === 'firebase-admin/messaging') return { getMessaging: () => ({
        sendEachForMulticast: payload => {
          starts.push(payload.data.message_id || payload.data.type)
          if (payload.data.type === 'call_invite') return Promise.resolve(success())
          return new Promise((resolve, reject) => pending.push({ resolve: () => resolve(success()), reject }))
        },
      }) }
      if (name === './message-push-limiter') return { limitMessagePush: limiter }
      return originalRequire(name)
    },
  }, { filename })
  return {
    pending, starts, reads, rows,
    stats: () => ({ active, peak, connects, rollbacks }),
    send: messageId => module.exports.sendPushForNotification(pool, {
      type: 'message', recipient_id: `recipient-${messageId}`, actor_id: 'sender', target_type: 'chat', target_id: 'chat',
    }, { chat_id: 'chat', message_id: messageId }),
    call: () => module.exports.sendPushForNotification(pool, {
      type: 'call_invite', recipient_id: 'callee', actor_id: 'sender', target_type: 'chat', target_id: 'call',
    }),
  }
}

async function drain(fixture, expectedConnections) {
  const deadline = Date.now() + 3_000
  while (fixture.stats().connects < expectedConnections || fixture.stats().active) {
    if (Date.now() > deadline) throw Error('PUSH_DRAIN_DEADLINE')
    fixture.pending.splice(0).forEach(gate => gate.resolve())
    await turn()
  }
}

test('room fanout acquires at most three DB connections before delayed FCM, preserving every queued push', async () => {
  const f = fixture()
  const count = 40
  const results = Promise.allSettled(Array.from({ length: count }, (_, index) => f.send(`message-${index}`)))
  await until(() => f.pending.length === MESSAGE_PUSH_CONCURRENCY)
  assert.equal(f.stats().connects, MESSAGE_PUSH_CONCURRENCY, 'Queued recipients acquired DB connections')
  assert.equal(f.stats().active, MESSAGE_PUSH_CONCURRENCY)
  assert.equal(f.starts.length, MESSAGE_PUSH_CONCURRENCY)
  await drain(f, count)
  const delivered = await results
  assert.equal(f.stats().peak, MESSAGE_PUSH_CONCURRENCY)
  assert.equal(f.stats().active, 0)
  assert.equal(delivered.filter(result => result.status === 'fulfilled' && result.value.sent === 1).length, count)
  assert.deepEqual(f.starts, Array.from({ length: count }, (_, index) => `message-${index}`), 'Queue must remain FIFO')
})

test('provider rejection rolls back/releases its connection and the next queued recipients still run', async () => {
  const f = fixture()
  const results = Promise.allSettled(Array.from({ length: 8 }, (_, index) => f.send(`message-${index}`)))
  await until(() => f.pending.length === MESSAGE_PUSH_CONCURRENCY)
  f.pending.shift().reject(Error('QA_PROVIDER_FAILURE'))
  await until(() => f.stats().connects === MESSAGE_PUSH_CONCURRENCY + 1)
  await drain(f, 8)
  const delivered = await results
  assert.equal(delivered[0].status, 'rejected')
  assert.match(delivered[0].reason.message, /QA_PROVIDER_FAILURE/)
  assert.equal(delivered.filter(result => result.status === 'fulfilled').length, 7)
  assert.equal(f.stats().rollbacks, 1)
  assert.equal(f.stats().active, 0)
  assert.equal(f.stats().peak, MESSAGE_PUSH_CONCURRENCY)
})

test('a queued message deleted before its slot is re-read and suppressed without reaching FCM', async () => {
  const f = fixture()
  const count = MESSAGE_PUSH_CONCURRENCY + 1
  const results = Promise.allSettled(Array.from({ length: count }, (_, index) => f.send(`message-${index}`)))
  await until(() => f.pending.length === MESSAGE_PUSH_CONCURRENCY)
  const queuedId = `message-${MESSAGE_PUSH_CONCURRENCY}`
  assert.equal(f.reads.includes(queuedId), false, 'Authorization was checked before entering the queue')
  f.rows.set(queuedId, { id: queuedId, chat_id: 'chat', sender_id: 'sender', deleted_for_all: true })
  await drain(f, count)
  const delivered = await results
  assert.equal(delivered.at(-1).status, 'fulfilled')
  assert.equal(delivered.at(-1).value.suppressed, true)
  assert.equal(f.starts.includes(queuedId), false)
  assert.equal(f.stats().active, 0)
})

test('call invites remain independent of a busy message fanout', async () => {
  const f = fixture()
  const messages = Promise.allSettled(Array.from({ length: MESSAGE_PUSH_CONCURRENCY }, (_, index) => f.send(`message-${index}`)))
  await until(() => f.pending.length === MESSAGE_PUSH_CONCURRENCY)
  const call = await f.call()
  assert.equal(call.sent, 1)
  assert.ok(f.starts.includes('call_invite'))
  assert.equal(f.stats().connects, MESSAGE_PUSH_CONCURRENCY)
  await drain(f, MESSAGE_PUSH_CONCURRENCY)
  await messages
})

test('synchronous task errors also free the slot and a large suppressed batch yields to the event loop', async () => {
  const limit = createMessagePushLimiter(1)
  const starts = []
  const results = Promise.allSettled([
    limit(() => { throw Error('SYNC_FAILURE') }),
    ...Array.from({ length: 100 }, (_, index) => limit(() => { starts.push(index); return index })),
  ])
  let immediateRan = false
  setImmediate(() => { immediateRan = true; assert.ok(starts.length < 100) })
  const delivered = await results
  assert.equal(immediateRan, true)
  assert.equal(delivered[0].status, 'rejected')
  assert.equal(delivered.at(-1).value, 99)
  assert.deepEqual(starts, Array.from({ length: 100 }, (_, index) => index))
})
