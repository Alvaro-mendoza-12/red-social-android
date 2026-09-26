const assert = require('assert/strict')
const {
  PayPalPayoutClient,
  allocatePayout,
  buildBalanceSnapshot,
  decryptPayoutEmail,
  encryptPayoutEmail,
  maskEmail,
  paypalBatchStatus,
  paypalEventStatus,
  payoutConfiguration,
  processApprovedCreatorPayouts,
  publicSummary,
  registerCreatorPayoutRoutes,
  requestDecision,
} = require('../src/creator-payout-routes')

const CONFIG = {
  enabled: true,
  mode: 'sandbox',
  baseUrl: 'https://api-m.sandbox.paypal.com',
  clientId: 'sandbox-client',
  clientSecret: 'sandbox-secret',
  webhookId: 'sandbox-webhook',
  encryptionKey: 'test-encryption-key-with-at-least-32-characters',
  minorPerCoin: 2,
  minimumMinor: 100,
  holdDays: 7,
  currency: 'USD',
  providerConfigured: true,
  webhookConfigured: true,
}

function testConfigFailsClosedAndRejectsCustomHost() {
  const config = payoutConfiguration({})
  assert.equal(config.enabled, false)
  assert.equal(config.providerConfigured, false)
  assert.equal(config.credentialsConfigured, false)
  assert.equal(config.minorPerCoin, 0)
  assert.equal(config.minimumMinor, 0)
  const unsafe = payoutConfiguration({
    CREATOR_PAYOUTS_ENABLED: 'true',
    PAYPAL_MODE: 'sandbox',
    PAYPAL_API_BASE_URL: 'https://attacker.invalid',
    PAYPAL_CLIENT_ID: 'id',
    PAYPAL_CLIENT_SECRET: 'secret',
    PAYOUT_ACCOUNT_ENCRYPTION_KEY: 'x'.repeat(40),
  })
  assert.equal(unsafe.baseUrl, '')
  assert.equal(unsafe.providerConfigured, false)
}

function testEmailIsEncryptedAndMasked() {
  const encrypted = encryptPayoutEmail('Creator.Name@example.com', CONFIG.encryptionKey)
  assert.doesNotMatch(encrypted, /Creator\.Name|example\.com/i)
  assert.equal(decryptPayoutEmail(encrypted, CONFIG.encryptionKey), 'creator.name@example.com')
  assert.equal(maskEmail('creator.name@example.com'), 'cr********@example.com')
  assert.throws(() => decryptPayoutEmail(encrypted, `${CONFIG.encryptionKey}-wrong`))
}

function testValidDuplicateInsufficientAndUnverifiedRequests() {
  assert.deepEqual(requestDecision({
    enabled: true,
    providerConfigured: true,
    creatorVerified: true,
    accountStatus: 'VERIFIED',
    amountMinor: 200,
    minimumMinor: 100,
    availableMinor: 500,
    existingRequest: null,
  }), { ok: true, duplicate: false })
  assert.deepEqual(requestDecision({
    enabled: true,
    providerConfigured: true,
    creatorVerified: true,
    accountStatus: 'VERIFIED',
    amountMinor: 200,
    minimumMinor: 100,
    availableMinor: 0,
    existingRequest: { id: 'same-idempotency-key' },
  }), { ok: true, duplicate: true })
  assert.equal(requestDecision({
    enabled: true,
    providerConfigured: true,
    creatorVerified: true,
    accountStatus: 'VERIFIED',
    amountMinor: 600,
    minimumMinor: 100,
    availableMinor: 500,
  }).code, 'PAYOUT_INSUFFICIENT_BALANCE')
  assert.equal(requestDecision({
    enabled: true,
    providerConfigured: true,
    creatorVerified: false,
    accountStatus: 'VERIFIED',
    amountMinor: 200,
    minimumMinor: 100,
    availableMinor: 500,
  }).code, 'CREATOR_NOT_VERIFIED')
  assert.equal(requestDecision({
    enabled: true,
    providerConfigured: true,
    creatorVerified: true,
    accountStatus: 'PENDING',
    amountMinor: 200,
    minimumMinor: 100,
    availableMinor: 500,
  }).code, 'PAYOUT_ACCOUNT_NOT_VERIFIED')
}

function testBalanceUsesOnlyPurchasedEarningsAndRefundDebt() {
  const now = Date.parse('2026-08-08T12:00:00.000Z')
  const old = '2026-07-01T00:00:00.000Z'
  const recent = '2026-08-07T00:00:00.000Z'
  const requests = [
    { id: 'paid-request', status: 'PAID', amount_minor: 100 },
    { id: 'held-request', status: 'APPROVED', amount_minor: 40 },
    { id: 'failed-request', status: 'FAILED', amount_minor: 999 },
  ]
  const snapshot = buildBalanceSnapshot({
    configuration: CONFIG,
    now,
    earnings: [
      { id: 'paid-refunded', withdrawable_amount_diamonds: 20, status: 'pending', created_at: old },
      { id: 'available', withdrawable_amount_diamonds: 100, status: 'pending', created_at: old },
      { id: 'recent', withdrawable_amount_diamonds: 50, status: 'pending', created_at: recent },
      { id: 'promo', withdrawable_amount_diamonds: 0, non_withdrawable_amount_diamonds: 999, status: 'non_withdrawable', created_at: old },
    ],
    requests,
    allocations: [
      { request_id: 'paid-request', earning_id: 'paid-refunded', allocated_minor: 100 },
      { request_id: 'held-request', earning_id: 'available', allocated_minor: 40 },
      { request_id: 'failed-request', earning_id: 'available', allocated_minor: 999 },
    ],
  })
  assert.equal(snapshot.pendingMinor, 100)
  assert.equal(snapshot.heldMinor, 40)
  assert.equal(snapshot.paidMinor, 100)
  assert.equal(snapshot.recoveryDebtMinor, 60)
  assert.equal(snapshot.rawAvailableMinor, 160)
  assert.equal(snapshot.availableMinor, 100)
  assert.equal(snapshot.pendingCoins, 50)
  assert.equal(snapshot.availableCoins, 80)
  assert.equal(snapshot.heldCoins, 20)
  assert.equal(snapshot.paidCoins, 20)
  assert.equal(snapshot.totalCoins, 170)
  assert.deepEqual(allocatePayout(snapshot, 100), [{ earningId: 'available', allocatedMinor: 100 }])
}

function testPublicSummaryExposesCoinsWhenFiatConversionIsDisabled() {
  const summary = publicSummary({
    account: null,
    requests: [],
    allocations: [],
    earnings: [{
      id: 'earning-pending',
      withdrawable_amount_diamonds: 15,
      amount_diamonds: 15,
      status: 'pending',
      created_at: '2026-08-10T00:00:00.000Z',
    }],
  }, { ...CONFIG, enabled: false, minorPerCoin: 0, minimumMinor: 0 }, false, Date.parse('2026-08-09T00:00:00.000Z'))
  assert.equal(summary.enabled, false)
  assert.equal(summary.balances.pendingMinor, 0)
  assert.equal(summary.balances.totalCoins, 15)
  assert.equal(summary.balances.pendingCoins, 15)
}

async function testPayPalSandboxSuccessAndFailure() {
  const calls = []
  const successFetch = async (url, init) => {
    calls.push({ url, init })
    if (url.endsWith('/v1/oauth2/token')) {
      return { ok: true, status: 200, json: async () => ({ access_token: 'secret-access-token' }) }
    }
    return {
      ok: true,
      status: 201,
      json: async () => ({ batch_header: { payout_batch_id: 'BATCH-1', batch_status: 'PENDING' } }),
    }
  }
  const client = new PayPalPayoutClient(CONFIG, successFetch)
  const result = await client.createPayout({
    id: 'request-1',
    paypal_sender_batch_id: 'novvin-request-1',
    amount_minor: 250,
    currency: 'USD',
  }, 'sandbox-recipient@example.test')
  assert.deepEqual(result, { batchId: 'BATCH-1', batchStatus: 'PENDING' })
  const payoutBody = JSON.parse(calls[1].init.body)
  assert.equal(payoutBody.items[0].amount.value, '2.50')
  assert.equal(payoutBody.items[0].receiver, 'sandbox-recipient@example.test')
  assert.equal(calls[1].init.headers['PayPal-Request-Id'], 'novvin-request-1')

  const failureClient = new PayPalPayoutClient(CONFIG, async (url) => {
    if (url.endsWith('/v1/oauth2/token')) {
      return { ok: true, status: 200, json: async () => ({ access_token: 'token' }) }
    }
    return { ok: false, status: 422, json: async () => ({ name: 'RECEIVER_UNREGISTERED' }) }
  })
  await assert.rejects(
    () => failureClient.createPayout({
      id: 'request-2', paypal_sender_batch_id: 'novvin-request-2', amount_minor: 250, currency: 'USD',
    }, 'failed@example.test'),
    (error) => error.message === 'PAYPAL_REQUEST_FAILED' && error.retryable === false,
  )
}

function createResponse() {
  const state = { statusCode: 200, body: null }
  return {
    state,
    status(code) { state.statusCode = code; return this },
    json(body) { state.body = body; return this },
    setHeader() {},
  }
}

async function testDuplicateSignedWebhookIsIdempotent() {
  const routes = []
  const app = {
    get(path, ...handlers) { routes.push({ method: 'GET', path, handlers }) },
    put(path, ...handlers) { routes.push({ method: 'PUT', path, handlers }) },
    post(path, ...handlers) { routes.push({ method: 'POST', path, handlers }) },
  }
  const statements = []
  const client = {
    async query(statement) {
      statements.push(String(statement))
      if (/INSERT INTO fallback_paypal_webhook_events/.test(statement)) return { rows: [] }
      return { rows: [] }
    },
    release() {},
  }
  registerCreatorPayoutRoutes(app, {
    pool: { connect: async () => client, query: async () => ({ rows: [] }) },
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => ({ id: 'creator-1' }),
    userIdentity: () => ({ displayUserId: 'creator-1', values: ['creator-1'] }),
    config: () => CONFIG,
    providerFactory: () => ({ verifyWebhook: async () => true }),
  })
  const route = routes.find((entry) => entry.path === '/v1/webhooks/paypal/payouts')
  assert.ok(route)
  const response = createResponse()
  let forwarded
  await route.handlers[0]({
    headers: {},
    body: { id: 'WEBHOOK-1', event_type: 'PAYMENT.PAYOUTS-ITEM.SUCCEEDED', resource: {} },
  }, response, (error) => { forwarded = error })
  assert.equal(forwarded, undefined)
  assert.deepEqual(response.state.body, { accepted: true, duplicate: true })
  assert.equal(statements.filter((statement) => /INSERT INTO fallback_paypal_webhook_events/.test(statement)).length, 1)
  assert.equal(statements.some((statement) => /UPDATE fallback_creator_payout_requests/.test(statement)), false)
}

async function testAccountRoutePreservesCanonicalCreatorAndDoesNotReferenceUndefinedState() {
  const routes = []
  const app = {
    get(path, ...handlers) { routes.push({ method: 'GET', path, handlers }) },
    put(path, ...handlers) { routes.push({ method: 'PUT', path, handlers }) },
    post(path, ...handlers) { routes.push({ method: 'POST', path, handlers }) },
  }
  const statements = []
  const parameters = []
  const existingAccount = {
    creator_user_id: 'creator-canonical',
    recipient_email_hash: 'previous-hash',
    currency: 'USD',
    status: 'VERIFIED',
  }
  const client = {
    async query(statement, values = []) {
      const sql = String(statement)
      statements.push(sql)
      parameters.push(values)
      if (/SELECT \* FROM fallback_creator_payout_accounts/.test(sql)) {
        return { rows: [existingAccount] }
      }
      if (/INSERT INTO fallback_creator_payout_accounts/.test(sql)) {
        return {
          rows: [{
            creator_user_id: values[0],
            recipient_email_ciphertext: values[1],
            recipient_email_hash: values[2],
            recipient_email_masked: values[3],
            currency: values[4],
            status: values[5],
          }],
        }
      }
      return { rows: [] }
    },
    release() {},
  }
  registerCreatorPayoutRoutes(app, {
    pool: { connect: async () => client, query: async () => ({ rows: [] }) },
    authRequired: (_req, _res, next) => next(),
    getCurrentUser: async () => ({ id: 'creator-alias' }),
    userIdentity: () => ({
      displayUserId: 'creator-alias',
      preferredUserId: 'creator-alias',
      values: ['creator-alias', 'creator-canonical'],
    }),
    config: () => CONFIG,
  })
  const route = routes.find((entry) =>
    entry.method === 'PUT' && entry.path === '/v1/data/creator/payouts/account',
  )
  assert.ok(route)
  const response = createResponse()
  let forwarded
  await route.handlers.at(-1)({
    body: { email: 'new-recipient@example.test', currency: 'USD' },
    requestId: 'request-account-1',
  }, response, (error) => { forwarded = error })

  assert.equal(forwarded, undefined)
  assert.equal(response.state.statusCode, 200)
  assert.equal(response.state.body.account.emailMasked, 'ne********@example.test')
  const insertIndex = statements.findIndex((statement) => /INSERT INTO fallback_creator_payout_accounts/.test(statement))
  assert.ok(insertIndex >= 0)
  assert.equal(parameters[insertIndex][0], 'creator-canonical')
  assert.equal(parameters[insertIndex][5], 'PENDING')
  assert.equal(statements.some((statement) => /INSERT INTO fallback_creator_payout_audit/.test(statement)), true)
}

async function testApprovedWorkerClaimsAndCallsProvider() {
  const encryptedEmail = encryptPayoutEmail('worker-recipient@example.test', CONFIG.encryptionKey)
  const approved = {
    id: '00000000-0000-4000-8000-000000000001',
    creator_user_id: 'creator-worker',
    amount_minor: 250,
    currency: 'USD',
    status: 'APPROVED',
    recipient_email_ciphertext: encryptedEmail,
    paypal_sender_batch_id: 'novvin-worker-request-1',
  }
  const statements = []
  let claimed = false
  const client = {
    async query(statement) {
      const sql = String(statement)
      statements.push(sql)
      if (/WHERE status = 'APPROVED'/.test(sql) && /FOR UPDATE SKIP LOCKED/.test(sql)) {
        return { rows: [approved] }
      }
      if (/SET status = 'PROCESSING'/.test(sql)) {
        claimed = true
        return { rows: [{ ...approved, status: 'PROCESSING' }] }
      }
      return { rows: [] }
    },
    release() {},
  }
  const providerCalls = []
  const provider = {
    async createPayout(request, email) {
      providerCalls.push({ request, email })
      return { batchId: 'BATCH-WORKER-1', batchStatus: 'PENDING' }
    },
  }
  const poolQueries = []
  const result = await processApprovedCreatorPayouts({
    connect: async () => client,
    async query(statement, values) {
      poolQueries.push({ statement: String(statement), values })
      return { rows: [] }
    },
  }, { configuration: CONFIG, provider, limit: 1 })

  assert.deepEqual(result, { processed: 1, disabled: false })
  assert.equal(claimed, true)
  assert.equal(providerCalls.length, 1)
  assert.equal(providerCalls[0].request.status, 'PROCESSING')
  assert.equal(providerCalls[0].email, 'worker-recipient@example.test')
  assert.equal(statements.some((statement) => /INSERT INTO fallback_creator_payout_ledger/.test(statement)), true)
  assert.equal(poolQueries.some(({ statement, values }) =>
    /paypal_payout_batch_id/.test(statement) && values?.[1] === 'BATCH-WORKER-1',
  ), true)
}

function testPayPalStatusMapping() {
  assert.equal(paypalEventStatus('PAYMENT.PAYOUTS-ITEM.SUCCEEDED'), 'PAID')
  assert.equal(paypalEventStatus('PAYMENT.PAYOUTS-ITEM.FAILED'), 'FAILED')
  assert.equal(paypalEventStatus('UNKNOWN'), null)
  assert.equal(paypalBatchStatus({ items: [{ transaction_status: 'SUCCESS' }] }), 'PAID')
  assert.equal(paypalBatchStatus({ items: [{ transaction_status: 'FAILED' }] }), 'FAILED')
}

async function run() {
  testConfigFailsClosedAndRejectsCustomHost()
  testEmailIsEncryptedAndMasked()
  testValidDuplicateInsufficientAndUnverifiedRequests()
  testBalanceUsesOnlyPurchasedEarningsAndRefundDebt()
  testPublicSummaryExposesCoinsWhenFiatConversionIsDisabled()
  await testPayPalSandboxSuccessAndFailure()
  await testDuplicateSignedWebhookIsIdempotent()
  await testAccountRoutePreservesCanonicalCreatorAndDoesNotReferenceUndefinedState()
  await testApprovedWorkerClaimsAndCallsProvider()
  testPayPalStatusMapping()
  console.log('creator payout route tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exit(1)
})
