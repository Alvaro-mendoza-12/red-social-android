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
process.env.GOOGLE_PLAY_REFUND_RECONCILIATION_ENABLED = 'true'
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
  if (target.includes('/purchases/voidedpurchases')) {
    return {
      ok: true,
      json: async () => ({
        voidedPurchases: [{
          purchaseToken: 'purchase-token-valid',
          orderId: 'order-valid',
          voidedTimeMillis: String(Date.now()),
          voidedReason: 1,
          voidedSource: 0,
        }],
      }),
    }
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
  const completeCatalog = process.env.GOOGLE_PLAY_COIN_PACKAGES_JSON
  process.env.GOOGLE_PLAY_COIN_PACKAGES_JSON = JSON.stringify(
    Object.fromEntries(Object.entries(packages).slice(0, 7)),
  )
  assert.equal(_test.googlePlayConfiguration().enabled, false)
  process.env.GOOGLE_PLAY_COIN_PACKAGES_JSON = completeCatalog
  assert.equal(_test.googlePlayConfiguration().enabled, true)

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

  const currentWallet = () => [...pool.state.compat.values()]
    .map(entry => entry.row)
    .find(row => row.user_id === userId && row.balance_diamonds != null)
  const wallet = currentWallet()
  assert.equal(wallet.balance_diamonds, 70)
  const purchases = [...pool.state.compat.values()]
    .filter(entry => entry.table_name === 'billing_purchases')
  assert.equal(purchases.length, 1)
  assert.equal(purchases[0].row.coins, 70)

  const reconciliation = await _test.reconcileGooglePlayVoidedPurchases(pool, {
    startTime: Date.now() - 60_000,
    endTime: Date.now(),
  })
  assert.equal(reconciliation.fetched, 1)
  assert.equal(reconciliation.reversed, 1)
  assert.equal(currentWallet().balance_diamonds, 0)
  assert.equal(currentWallet().balance_debt_diamonds, 0)
  const repeatedRefund = await _test.processGooglePlayVoidedPurchase(pool, {
    purchaseToken: 'purchase-token-valid',
    orderId: 'order-valid',
    voidedTimeMillis: String(Date.now()),
  })
  assert.equal(repeatedRefund.reversed, false)
  assert.equal(repeatedRefund.idempotent_replay, true)
  assert.equal(currentWallet().balance_diamonds, 0)
  await expectCode(
    () => verify('novvin_coins_70', 'purchase-token-valid'),
    'GOOGLE_PLAY_PURCHASE_REFUNDED',
  )

  purchaseMode = { state: 0, token: 'debt' }
  const debtPurchase = await verify('novvin_coins_70', 'purchase-token-debt')
  assert.equal(debtPurchase.balance_diamonds, 70)
  currentWallet().balance_diamonds = 10
  const debtRefund = await _test.processGooglePlayVoidedPurchase(pool, {
    purchaseToken: 'purchase-token-debt',
    orderId: 'order-debt',
    voidedTimeMillis: String(Date.now()),
  })
  assert.equal(debtRefund.removed_coins, 10)
  assert.equal(debtRefund.debt_created, 60)
  assert.equal(currentWallet().balance_diamonds, 0)
  assert.equal(currentWallet().balance_debt_diamonds, 60)

  purchaseMode = { state: 0, token: 'debt-repayment' }
  const debtRepayment = await verify('novvin_coins_70', 'purchase-token-debt-repayment')
  assert.equal(debtRepayment.balance_diamonds, 10)
  assert.equal(currentWallet().balance_debt_diamonds, 0)

  const refundTransactions = [...pool.state.compat.values()]
    .filter(entry => entry.table_name === 'wallet_transactions')
    .filter(entry => entry.row.transaction_type === 'google_play_refund')
  assert.equal(refundTransactions.length, 2)

  const catalogPool = createPool()
  let expectedCatalogBalance = 0
  for (const [productId, coins] of Object.entries(packages)) {
    purchaseMode = { state: 0, token: `catalog-${coins}` }
    const result = await _test.verifyGooglePlayPurchaseRpc(catalogPool, { sub: userId }, {
      p_product_id: productId,
      p_purchase_token: `purchase-token-catalog-${coins}`,
    })
    expectedCatalogBalance += coins
    assert.equal(result.status, 'credited')
    assert.equal(result.credited, true)
    assert.equal(result.coins, coins)
    assert.equal(result.balance_diamonds, expectedCatalogBalance)
  }
  const catalogPurchases = [...catalogPool.state.compat.values()]
    .filter(entry => entry.table_name === 'billing_purchases')
  assert.equal(catalogPurchases.length, 8)
  assert.deepEqual(
    Object.fromEntries(catalogPurchases.map(entry => [entry.row.product_id, entry.row.coins])),
    packages,
  )
  console.log('coin purchase flow tests passed: complete/missing catalog, all 8 credits, duplicate, pending, cancelled, unknown, google error, refund, refund replay, refund debt')
}

run().catch(error => {
  console.error(error)
  process.exitCode = 1
})
