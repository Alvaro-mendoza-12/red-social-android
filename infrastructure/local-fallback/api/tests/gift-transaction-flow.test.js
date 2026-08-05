const assert = require('assert/strict')
const crypto = require('crypto')

process.env.LIVE_GIFTS_ENABLED = 'true'
process.env.LIVE_GIFT_MAX_COINS_PER_GIFT = '5000'
process.env.LIVE_GIFT_MAX_COINS_PER_DAY = '25000'
process.env.LIVE_GIFT_MAX_GIFTS_PER_MINUTE = '20'

global.fetch = async () => ({ ok: true })

const { _test } = require('../src/postgrest-compat-routes')

const senderId = '10000000-0000-4000-8000-000000000001'
const receiverId = '20000000-0000-4000-8000-000000000002'
const liveId = '30000000-0000-4000-8000-000000000003'
const gift = _test.DEFAULT_VIRTUAL_GIFTS[0]

function cloneMap(source) {
  return new Map([...source].map(([key, value]) => [key, structuredClone(value)]))
}

function createMutex() {
  let tail = Promise.resolve()
  return async function acquire() {
    let release
    const mine = new Promise(resolve => { release = resolve })
    const previous = tail
    tail = mine
    await previous
    return release
  }
}

function createPool({ balance = 100, liveActive = true, failOnTable = null } = {}) {
  const compat = new Map()
  const users = [
    {
      id: senderId,
      supabase_user_id: senderId,
      email: 'sender@example.invalid',
      display_name: 'Sender Test',
      avatar_url: null,
      created_at: new Date().toISOString(),
    },
    {
      id: receiverId,
      supabase_user_id: receiverId,
      email: 'receiver@example.invalid',
      display_name: 'Receiver Test',
      avatar_url: null,
      created_at: new Date().toISOString(),
    },
  ]
  const lives = [{
    id: liveId,
    host_user_id: receiverId,
    host_name: 'Receiver Test',
    host_avatar_url: null,
    title: 'Live Test',
    source: 'camera',
    hashtags: [],
    viewer_count: 1,
    peak_viewer_count: 1,
    total_comments: 0,
    total_reactions: 0,
    is_active: liveActive,
    started_at: new Date().toISOString(),
    ended_at: liveActive ? null : new Date().toISOString(),
    created_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  }]
  const now = new Date().toISOString()
  compat.set(`virtual_gifts:${gift.id}`, {
    table_name: 'virtual_gifts', row_id: gift.id,
    row: { ...gift, is_active: true }, created_at: now, updated_at: now,
  })
  compat.set(`wallets:${senderId}`, {
    table_name: 'wallets', row_id: senderId,
    row: { id: senderId, user_id: senderId, balance_diamonds: balance, balance_credits: 0, total_spent: 0 },
    created_at: now, updated_at: now,
  })

  const acquireTransaction = createMutex()
  const state = { compat, users, lives }

  function makeQuery(clientState = null) {
    return async (sql, params = []) => {
      const text = String(sql).trim()
      const active = clientState?.working || compat

      if (text === 'BEGIN') {
        clientState.release = await acquireTransaction()
        clientState.working = cloneMap(compat)
        return { rows: [] }
      }
      if (text === 'COMMIT') {
        compat.clear()
        for (const [key, value] of clientState.working) compat.set(key, value)
        clientState.working = null
        clientState.release?.()
        clientState.release = null
        return { rows: [] }
      }
      if (text === 'ROLLBACK') {
        clientState.working = null
        clientState.release?.()
        clientState.release = null
        return { rows: [] }
      }
      if (text.startsWith('SELECT pg_advisory_xact_lock')) return { rows: [] }
      if (text.startsWith('SELECT to_regclass')) return { rows: [{ oid: null }] }

      if (text.startsWith('SELECT id::text AS local_user_id')) {
        const user = users.find(item => item.id.toLowerCase() === String(params[0] || '').toLowerCase())
        return { rows: user ? [{
          local_user_id: user.id,
          public_user_id: user.supabase_user_id,
          email: user.email,
          display_name: user.display_name,
          avatar_url: user.avatar_url,
        }] : [] }
      }
      if (text.includes('LEFT JOIN fallback_profile_moderation')) {
        return { rows: [{ email: 'sender@example.invalid', display_name: 'Sender Test', is_admin: false, admin_access_level: 'none', admin_is_owner: false }] }
      }
      if (text.includes('FROM fallback_users') && text.includes('ORDER BY created_at DESC')) {
        return { rows: users.map(user => ({ ...user, password_changed_by_user_at: null, local_password_set_at: null })) }
      }
      if (text.includes('FROM fallback_live_streams') && text.includes('ORDER BY created_at DESC')) {
        return { rows: structuredClone(lives) }
      }
      if (text.includes("table_name = 'wallets'") && text.includes('FOR UPDATE')) {
        const requestedUserId = String(params[0] || '').toLowerCase()
        const identityValues = (params[1] || []).map(value => String(value).toLowerCase())
        const rows = [...active.values()]
          .filter(entry => entry.table_name === 'wallets')
          .map(entry => entry.row)
          .filter(row => String(row.id || '').toLowerCase() === requestedUserId || identityValues.includes(String(row.user_id || '').toLowerCase()))
          .map(row => ({ row }))
        return { rows }
      }
      if (text.startsWith('SELECT row') && text.includes('FROM fallback_compat_rows')) {
        const tableName = String(params[0] || '')
        return {
          rows: [...active.values()]
            .filter(entry => entry.table_name === tableName)
            .sort((a, b) => String(b.updated_at).localeCompare(String(a.updated_at)))
            .map(entry => ({ row: structuredClone(entry.row) })),
        }
      }
      if (text.startsWith('INSERT INTO fallback_compat_rows')) {
        const [tableName, rowId, row] = params
        if (tableName === failOnTable) {
          const error = new Error('simulated postgres write failure')
          error.code = '23514'
          error.table = 'fallback_compat_rows'
          error.constraint = `test_${tableName}_constraint`
          throw error
        }
        const stamp = new Date().toISOString()
        const key = `${tableName}:${rowId}`
        const previous = active.get(key)?.row || {}
        active.set(key, {
          table_name: tableName,
          row_id: rowId,
          row: { ...previous, ...structuredClone(row) },
          created_at: active.get(key)?.created_at || stamp,
          updated_at: stamp,
        })
        return { rows: [] }
      }

      throw new Error(`Unhandled gift-flow test SQL: ${text.slice(0, 160)}`)
    }
  }

  return {
    state,
    query: makeQuery(),
    connect: async () => {
      const clientState = { working: null, release: null }
      return { query: makeQuery(clientState), release() { clientState.release?.() } }
    },
  }
}

function request(overrides = {}) {
  return {
    p_sender_id: senderId,
    p_receiver_id: receiverId,
    p_gift_id: gift.id,
    p_live_stream_id: liveId,
    p_idempotency_key: crypto.randomUUID(),
    p_is_public: true,
    ...overrides,
  }
}

function rows(pool, tableName) {
  return [...pool.state.compat.values()]
    .filter(entry => entry.table_name === tableName)
    .map(entry => entry.row)
}

function balance(pool) {
  return rows(pool, 'wallets').find(row => row.user_id === senderId)?.balance_diamonds
}

async function expectCode(action, code) {
  await assert.rejects(action, error => {
    assert.equal(error.message, code)
    return true
  })
}

async function run() {
  const validPool = createPool()
  const validRequest = request()
  const valid = await _test.sendVirtualGiftRpc(validPool, { sub: senderId }, validRequest)
  assert.equal(valid.sender_balance_diamonds, 95)
  assert.equal(balance(validPool), 95)
  assert.equal(rows(validPool, 'gift_transactions').length, 1)
  assert.equal(rows(validPool, 'creator_earnings').length, 1)
  assert.equal(rows(validPool, 'wallet_transactions').filter(row => row.transaction_type === 'virtual_gift').length, 1)
  assert.equal(rows(validPool, 'gift_transactions')[0].amount_diamonds, 5)
  assert.equal(rows(validPool, 'creator_earnings')[0].amount_diamonds, 5)

  const duplicate = await _test.sendVirtualGiftRpc(validPool, { sub: senderId }, validRequest)
  assert.equal(duplicate.idempotent_replay, true)
  assert.equal(balance(validPool), 95)
  assert.equal(rows(validPool, 'gift_transactions').length, 1)
  assert.equal(rows(validPool, 'creator_earnings').length, 1)

  const insufficientPool = createPool({ balance: 4 })
  await expectCode(() => _test.sendVirtualGiftRpc(insufficientPool, { sub: senderId }, request()), 'INSUFFICIENT_BALANCE')
  assert.equal(balance(insufficientPool), 4)
  assert.equal(rows(insufficientPool, 'gift_transactions').length, 0)

  await expectCode(
    () => _test.sendVirtualGiftRpc(createPool(), { sub: senderId }, request({ p_gift_id: '7a100099-0000-4000-8000-000000000099' })),
    'GIFT_NOT_FOUND',
  )
  await expectCode(
    () => _test.sendVirtualGiftRpc(createPool({ liveActive: false }), { sub: senderId }, request()),
    'LIVE_NOT_ACTIVE',
  )
  await expectCode(
    () => _test.sendVirtualGiftRpc(createPool(), { sub: senderId }, request({ p_receiver_id: senderId })),
    'SELF_GIFT_NOT_ALLOWED',
  )

  const concurrentPool = createPool({ balance: 5 })
  const concurrent = await Promise.allSettled([
    _test.sendVirtualGiftRpc(concurrentPool, { sub: senderId }, request()),
    _test.sendVirtualGiftRpc(concurrentPool, { sub: senderId }, request()),
  ])
  assert.equal(concurrent.filter(result => result.status === 'fulfilled').length, 1)
  assert.equal(concurrent.filter(result => result.status === 'rejected' && result.reason.message === 'INSUFFICIENT_BALANCE').length, 1)
  assert.equal(balance(concurrentPool), 0)
  assert.equal(rows(concurrentPool, 'gift_transactions').length, 1)
  assert.equal(rows(concurrentPool, 'creator_earnings').length, 1)

  for (const failOnTable of ['creator_earnings', 'notifications']) {
    const failedPool = createPool({ failOnTable })
    await expectCode(
      () => _test.sendVirtualGiftRpc(failedPool, { sub: senderId }, request()),
      'DATABASE_ERROR',
    )
    assert.equal(balance(failedPool), 100)
    assert.equal(rows(failedPool, 'gift_transactions').length, 0)
    assert.equal(rows(failedPool, 'creator_earnings').length, 0)
    assert.equal(rows(failedPool, 'wallet_transactions').filter(row => row.transaction_type === 'virtual_gift').length, 0)
  }

  console.log('gift transaction flow tests passed: valid, insufficient, missing, ended, self, duplicate, concurrent, earning failure, database failure')
}

run().catch(error => {
  console.error(error)
  process.exitCode = 1
})
