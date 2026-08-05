const assert = require('assert/strict')
const crypto = require('crypto')
const fs = require('fs')
const os = require('os')
const path = require('path')

const { privateKey } = crypto.generateKeyPairSync('rsa', {
  modulusLength: 2048,
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
  publicKeyEncoding: { type: 'spki', format: 'pem' },
})

const packages = {
  novvin_coins_70: 70,
  novvin_coins_140: 140,
  novvin_coins_350: 350,
  novvin_coins_700: 700,
  novvin_coins_1400: 1400,
  novvin_coins_3500: 3500,
  novvin_coins_7000: 7000,
  novvin_coins_14000: 14000,
}
const userId = 'user-coin-flow-1'
const accountHash = crypto.createHash('sha256').update(userId).digest('hex')

process.env.LIVE_COIN_PURCHASES_ENABLED = 'true'
process.env.GOOGLE_PLAY_PACKAGE_NAME = 'conectate.com'
process.env.GOOGLE_PLAY_COIN_PACKAGES_JSON = JSON.stringify(packages)
process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON = JSON.stringify({
  client_email: 'billing-test@example.invalid',
  private_key: privateKey,
  token_uri: 'https://oauth2.googleapis.com/token',
})
delete process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_FILE

function createPool() {
  const compat = new Map()
  const state = { compat, googleTransactions: [] }

  const query = async (sql, params = []) => {
    const text = String(sql).trim()
    if (text.startsWith('SELECT id::text AS local_user_id')) {
      return {
        rows: [{
          local_user_id: userId,
          public_user_id: userId,
          email: 'coin-flow@example.invalid',
          display_name: 'Coin Flow Test',
          avatar_url: null,
        }],
      }
    }
    if (text.startsWith('SELECT to_regclass')) return { rows: [{ oid: null }] }
    if (text.startsWith('SELECT pg_advisory_xact_lock')) return { rows: [] }
    if (text === 'BEGIN' || text === 'COMMIT' || text === 'ROLLBACK') return { rows: [] }
    if (text.startsWith('CREATE TABLE') || text.startsWith('CREATE INDEX')) return { rows: [] }

    if (text.includes("table_name = 'wallets'") && text.includes('FOR UPDATE')) {
      const requestedUserId = String(params[0] || '').toLowerCase()
      const rows = [...compat.values()]
        .filter(entry => entry.table_name === 'wallets')
        .map(entry => entry.row)
        .filter(row => String(row.user_id || '').toLowerCase() === requestedUserId)
        .map(row => ({ row }))
      return { rows }
    }

    if (text.startsWith('SELECT row') && text.includes('FROM fallback_compat_rows')) {
      const tableName = String(params[0] || '')
      const rows = [...compat.values()]
        .filter(entry => entry.table_name === tableName)
        .sort((a, b) => String(b.updated_at).localeCompare(String(a.updated_at)))
        .map(entry => ({ row: entry.row }))
      return { rows }
    }

    if (text.startsWith('INSERT INTO fallback_compat_rows')) {
      const [tableName, rowId, row] = params
      const now = new Date().toISOString()
      compat.set(`${tableName}:${rowId}`, {
        table_name: tableName,
        row_id: rowId,
        row,
        updated_at: now,
      })
      return { rows: [] }
    }

    throw new Error(`Unhandled test SQL: ${text.slice(0, 120)}`)
  }

  return {
    state,
    query,
    connect: async () => ({ query, release() {} }),
  }
}

const pool = createPool()
let purchaseMode = { state: 0, googleError: false }
let consumeCalls = 0

global.fetch = async (url) => {
  const target = String(url)
  if (target.includes('oauth2.googleapis.com/token')) {
    return { ok: true, json: async () => ({ access_token: 'test-access-token' }) }
  }
  if (target.includes(':consume')) {
    consumeCalls += 1
    if (purchaseMode.consumeError) throw new Error('consume test failure')
    return { ok: true, json: async () => ({}) }
  }
  if (purchaseMode.googleError) {
    return { ok: false, json: async () => ({ error: {} }) }
  }
  return {
    ok: true,
    json: async () => ({
      purchaseState: purchaseMode.state,
      orderId: `order-${purchaseMode.token || 'test'}`,
      purchaseTimeMillis: String(Date.now()),
      obfuscatedExternalAccountId: accountHash,
    }),
  }
}

const { _test } = require('../src/postgrest-compat-routes')
const verify = (productId, purchaseToken) =>
  _test.verifyGooglePlayPurchaseRpc(pool, { sub: userId }, {
    p_product_id: productId,
    p_purchase_token: purchaseToken,
  })

function assertFileBackedServiceAccount() {
  const filePath = path.join(os.tmpdir(), `novvin-billing-test-${process.pid}.json`)
  const originalInline = process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON
  fs.writeFileSync(filePath, originalInline, { encoding: 'utf8', mode: 0o600 })
  try {
    process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_FILE = filePath
    delete process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON
    assert.equal(_test.googlePlayConfiguration().enabled, true)
  } finally {
    process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON = originalInline
    delete process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_FILE
    fs.rmSync(filePath, { force: true })
  }
}

async function expectCode(action, code) {
  await assert.rejects(action, error => {
    assert.equal(error.message, code)
    return true
  })
}

async function run() {
  assertFileBackedServiceAccount()
  purchaseMode = { state: 0, token: 'valid' }
  const valid = await verify('novvin_coins_70', 'purchase-token-valid')
  assert.equal(valid.status, 'credited')
  assert.equal(valid.credited, true)
  assert.equal(valid.coins, 70)
  assert.equal(valid.balance_diamonds, 70)
  assert.equal(consumeCalls, 1)

  const duplicate = await verify('novvin_coins_70', 'purchase-token-valid')
  assert.equal(duplicate.credited, false)
  assert.equal(duplicate.idempotent_replay, true)
  assert.equal(duplicate.coins, 0)
  assert.equal(consumeCalls, 1)

  purchaseMode = { state: 2, token: 'pending' }
  const pending = await verify('novvin_coins_70', 'purchase-token-pending')
  assert.equal(pending.status, 'pending')
  assert.equal(pending.credited, false)

  purchaseMode = { state: 1, token: 'cancelled' }
  await expectCode(
    () => verify('novvin_coins_70', 'purchase-token-cancelled'),
    'GOOGLE_PLAY_PURCHASE_NOT_COMPLETED',
  )

  purchaseMode = { state: 0, token: 'unknown-product' }
  await expectCode(
    () => verify('novvin_coins_unknown', 'purchase-token-unknown'),
    'GOOGLE_PLAY_PURCHASE_INVALID',
  )

  purchaseMode = { state: 0, token: 'google-error', googleError: true }
  await expectCode(
    () => verify('novvin_coins_70', 'purchase-token-google-error'),
    'GOOGLE_PLAY_PURCHASE_NOT_FOUND',
  )

  const wallet = [...pool.state.compat.values()]
    .map(entry => entry.row)
    .find(row => row.user_id === userId && row.balance_diamonds != null)
  assert.equal(wallet.balance_diamonds, 70)
  const purchases = [...pool.state.compat.values()]
    .filter(entry => entry.table_name === 'billing_purchases')
  assert.equal(purchases.length, 1)
  assert.equal(purchases[0].row.coins, 70)
  console.log('coin purchase flow tests passed: valid, duplicate, pending, cancelled, unknown, google error')
}

run().catch(error => {
  console.error(error)
  process.exitCode = 1
})
