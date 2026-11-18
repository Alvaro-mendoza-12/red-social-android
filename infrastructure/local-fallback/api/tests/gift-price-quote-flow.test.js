const assert = require('assert/strict')
const crypto = require('crypto')

process.env.LIVE_GIFTS_ENABLED = 'true'
process.env.LIVE_GIFT_MAX_COINS_PER_GIFT = '5000'
process.env.LIVE_GIFT_MAX_COINS_PER_DAY = '25000'
process.env.LIVE_GIFT_MAX_GIFTS_PER_MINUTE = '20'

global.fetch = async () => ({ ok: true })

const { _test, registerPostgrestCompatRoutes } = require('../src/postgrest-compat-routes')
const express = require('express')
const http = require('http')

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

function createPool({ balance = 100, debt = 0, liveActive = true, giftsEnabled = true, failOnTable = null, coinSource = 'PURCHASED' } = {}) {
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
    gifts_enabled: giftsEnabled,
    started_at: new Date().toISOString(),
    ended_at: liveActive ? null : new Date().toISOString(),
    created_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  }]
  const now = new Date().toISOString()
  for (const catalogGift of _test.DEFAULT_VIRTUAL_GIFTS) {
    compat.set(`virtual_gifts:${catalogGift.id}`, {
      table_name: 'virtual_gifts', row_id: catalogGift.id,
      row: { ...catalogGift, is_active: true }, created_at: now, updated_at: now,
    })
  }
  compat.set(`wallets:${senderId}`, {
    table_name: 'wallets', row_id: senderId,
    row: {
      id: senderId,
      user_id: senderId,
      balance_diamonds: balance,
      balance_debt_diamonds: debt,
      balance_credits: 0,
      total_spent: 0,
    },
    created_at: now, updated_at: now,
  })
  if (balance > 0) {
    compat.set(`wallet_coin_lots:test-lot-${coinSource.toLowerCase()}`, {
      table_name: 'wallet_coin_lots', row_id: `test-lot-${coinSource.toLowerCase()}`,
      row: {
        id: `test-lot-${coinSource.toLowerCase()}`,
        user_id: senderId,
        source_type: coinSource,
        source_reference: `test:${coinSource.toLowerCase()}`,
        original_amount_diamonds: balance,
        available_amount_diamonds: balance,
        spent_amount_diamonds: 0,
        reversed_amount_diamonds: 0,
        status: 'active',
        created_at: now,
      },
      created_at: now, updated_at: now,
    })
  }

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
      if (text.includes('INNER JOIN fallback_profile_moderation m')) {
        return { rows: state.suspended ? [{ is_suspended: true }] : [] }
      }

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
      if (text.includes('FROM fallback_live_battles') && text.includes("status = 'active'")) {
        return { rows: [] }
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


const results = []
async function check(name, test) { try { await test(); results.push({name,passed:true}) } catch(error) { results.push({name,passed:false,error:error.stack}) } }
const send = (pool, body = {}, auth = {sub:senderId}) => _test.sendVirtualGiftV2Rpc(pool, auth, request({p_expected_price_diamonds:5,...body}))
async function wireRequest(pool, body, { authorized = true, operation = 'send_virtual_gift_v2' } = {}) {
  const app = express()
  app.use(express.json())
  registerPostgrestCompatRoutes(app, { pool, authOptional: (req, _res, next) => {
    // Session verification itself has separate tests. These tests exercise the
    // registered HTTP route after its authentication middleware supplies a visit.
    if (req.headers.authorization === 'Bearer fixture-session') req.auth = { sub: senderId }
    next()
  } })
  app.use((error, _req, res, _next) => res.status(error.status || 500).json({ message: error.message }))
  const server = await new Promise(resolve => {
    const listening = app.listen(0, '127.0.0.1', () => resolve(listening))
  })
  try {
    return await new Promise((resolve, reject) => {
      const data = JSON.stringify(body)
      const req = http.request({ hostname: '127.0.0.1', port: server.address().port,
        path: `/rest/v1/rpc/${operation}`, method: 'POST', headers: {
          'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data),
          ...(authorized ? { Authorization: 'Bearer fixture-session' } : {}),
        } }, res => {
        let response = ''
        res.setEncoding('utf8');res.on('data', chunk => { response += chunk })
        res.on('end', () => { try { resolve({ status: res.statusCode, body: JSON.parse(response) }) } catch (error) { reject(error) } })
      })
      req.on('error', reject);req.setTimeout(5000, () => req.destroy(new Error('HTTP fixture timeout')))
      req.end(data)
    })
  } finally { await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve())) }
}
async function run() {
 await check('the real HTTP route accepts a matching versioned quote',async()=>{
  const pool=createPool();const response=await wireRequest(pool,request({p_expected_price_diamonds:5}))
  assert.equal(response.status,200);assert.equal(response.body.amount_diamonds,5);assert.equal(balance(pool),95)
 })
 await check('the real HTTP route rejects an absent quote before charging',async()=>{
  const pool=createPool();const response=await wireRequest(pool,request())
  assert.equal(response.status,400);assert.equal(response.body.message,'GIFT_EXPECTED_PRICE_REQUIRED');assert.equal(balance(pool),100)
 })
 await check('the real HTTP route rejects a changed price before charging',async()=>{
  const pool=createPool();pool.state.compat.get(`virtual_gifts:${gift.id}`).row.price_diamonds=15
  const response=await wireRequest(pool,request({p_expected_price_diamonds:5}))
  assert.equal(response.status,409);assert.equal(response.body.message,'GIFT_PRICE_CHANGED');assert.equal(balance(pool),100)
 })
 await check('the real HTTP route requires an authenticated session',async()=>{
  const pool=createPool();const response=await wireRequest(pool,request({p_expected_price_diamonds:5}),{authorized:false})
  assert.equal(response.status,401);assert.equal(balance(pool),100)
 })
 await check('the real HTTP route rejects a suspended sender',async()=>{
  const pool=createPool();pool.state.suspended=true
  const response=await wireRequest(pool,request({p_expected_price_diamonds:5}))
  assert.equal(response.status,403);assert.equal(balance(pool),100)
 })
 await check('the legacy HTTP contract remains registered for existing clients',async()=>{
  const pool=createPool();const response=await wireRequest(pool,request(),{operation:'send_virtual_gift'})
  assert.equal(response.status,200);assert.equal(response.body.amount_diamonds,5);assert.equal(balance(pool),95)
 })
 await check('a matching quote charges once and acknowledges the actual amount', async()=>{
  const pool=createPool();const receipt=await send(pool)
  assert.equal(balance(pool),95);assert.equal(receipt.amount_diamonds,5);assert.equal(receipt.idempotent_replay,false)
 })
 for(const quote of [undefined,null,0,-1,1.5,'5',true,Number.MAX_SAFE_INTEGER+1]) {
  await check(`invalid quote ${String(quote)} fails before any charge`,async()=>{
   const pool=createPool();await expectCode(()=>send(pool,{p_expected_price_diamonds:quote}),'GIFT_EXPECTED_PRICE_REQUIRED')
   assert.equal(balance(pool),100);assert.equal(rows(pool,'gift_transactions').length,0)
  })
 }
 for(const price of [15,3]) {
  await check(`a changed stored price ${price} cannot charge the shown five`,async()=>{
   const pool=createPool();pool.state.compat.get(`virtual_gifts:${gift.id}`).row.price_diamonds=price
   await expectCode(()=>send(pool),'GIFT_PRICE_CHANGED');assert.equal(balance(pool),100)
   assert.equal(rows(pool,'gift_transactions').length,0);assert.equal(rows(pool,'creator_earnings').length,0)
  })
 }
 await check('a price changed while acquiring the transaction is re-read before charging',async()=>{
  const pool=createPool();const connect=pool.connect
  pool.connect=async()=>{pool.state.compat.get(`virtual_gifts:${gift.id}`).row.price_diamonds=15;return connect()}
  await expectCode(()=>send(pool),'GIFT_PRICE_CHANGED');assert.equal(balance(pool),100)
 })
 await check('a newly confirmed price uses the server value and original uncharged key',async()=>{
  const pool=createPool();const key=crypto.randomUUID();pool.state.compat.get(`virtual_gifts:${gift.id}`).row.price_diamonds=15
  await expectCode(()=>send(pool,{p_idempotency_key:key}),'GIFT_PRICE_CHANGED')
  const receipt=await send(pool,{p_idempotency_key:key,p_expected_price_diamonds:15})
  assert.equal(receipt.amount_diamonds,15);assert.equal(balance(pool),85);assert.equal(rows(pool,'gift_transactions').length,1)
 })
 await check('a lost receipt is recovered at its original amount despite a newer catalog',async()=>{
  const pool=createPool();const key=crypto.randomUUID();await send(pool,{p_idempotency_key:key})
  pool.state.compat.get(`virtual_gifts:${gift.id}`).row.price_diamonds=15
  const receipt=await send(pool,{p_idempotency_key:key,p_expected_price_diamonds:15})
  assert.equal(receipt.amount_diamonds,5);assert.equal(receipt.idempotent_replay,true);assert.equal(balance(pool),95)
  assert.equal(rows(pool,'gift_transactions').length,1)
 })
 await check('an older receipt returns the current balance after another gift',async()=>{
  const pool=createPool();const key=crypto.randomUUID();await send(pool,{p_idempotency_key:key});await send(pool)
  const receipt=await send(pool,{p_idempotency_key:key});assert.equal(receipt.sender_balance_diamonds,90)
  assert.equal(balance(pool),90);assert.equal(rows(pool,'gift_transactions').length,2)
 })
 await check('the legacy replay also returns the current balance',async()=>{
  const pool=createPool();const original=request();await _test.sendVirtualGiftRpc(pool,{sub:senderId},original)
  await send(pool);const receipt=await _test.sendVirtualGiftRpc(pool,{sub:senderId},original)
  assert.equal(receipt.sender_balance_diamonds,90);assert.equal(receipt.amount_diamonds,5)
 })
 await check('a zero current balance remains zero when replaying an older receipt',async()=>{
  const pool=createPool();const key=crypto.randomUUID();await send(pool,{p_idempotency_key:key})
  const wallet=rows(pool,'wallets').find(row=>row.user_id===senderId);wallet.balance_diamonds=0
  const receipt=await send(pool,{p_idempotency_key:key});assert.equal(receipt.sender_balance_diamonds,0)
 })
 await check('simultaneous retries have one charge and one notification',async()=>{
  const pool=createPool();const key=crypto.randomUUID()
  const receipts=await Promise.all([send(pool,{p_idempotency_key:key}),send(pool,{p_idempotency_key:key})])
  assert.equal(balance(pool),95);assert.equal(rows(pool,'gift_transactions').length,1)
  assert.equal(rows(pool,'notifications').length,1);assert.equal(receipts.filter(x=>x.idempotent_replay).length,1)
  assert.ok(receipts.every(x=>x.sender_balance_diamonds===95&&x.amount_diamonds===5))
 })
 await check('a matching quote cannot authorize a different sender',async()=>{
  const pool=createPool();await expectCode(()=>send(pool,{p_sender_id:receiverId}),'ACTOR_ID_MISMATCH')
  assert.equal(balance(pool),100)
 })
 await check('a quote cannot change a previously charged receiver',async()=>{
  const pool=createPool();const key=crypto.randomUUID();await send(pool,{p_idempotency_key:key})
  await expectCode(()=>send(pool,{p_idempotency_key:key,p_receiver_id:'40000000-0000-4000-8000-000000000004'}),'GIFT_IDEMPOTENCY_CONFLICT')
  assert.equal(balance(pool),95)
 })
 await check('fractional stored prices cannot be truncated into a charge',async()=>{
  const pool=createPool();pool.state.compat.get(`virtual_gifts:${gift.id}`).row.price_diamonds=5.8
  await expectCode(()=>send(pool),'GIFT_PRICE_INVALID');assert.equal(balance(pool),100)
 })
 await check('an inactive gift cannot be charged with a stale quote',async()=>{
  const pool=createPool();pool.state.compat.get(`virtual_gifts:${gift.id}`).row.is_active=false
  await expectCode(()=>send(pool),'GIFT_NOT_FOUND');assert.equal(balance(pool),100)
 })
 console.log(JSON.stringify({tests:results.length,failures:results.filter(x=>!x.passed).length,results},null,2));process.exitCode=results.some(x=>!x.passed)?1:0
}
run().catch(error=>{console.error(error);process.exitCode=2})
