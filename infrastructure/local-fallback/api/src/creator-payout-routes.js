const crypto = require('crypto')
const { appAdminHasCapability } = require('./admin-moderation-routes')
const { ADMIN_CAPABILITY } = require('./admin-capabilities')

const PAYOUT_FLAG = 'CREATOR_PAYOUTS_ENABLED'
const PAYOUT_STATES = Object.freeze([
  'PENDING',
  'APPROVED',
  'PROCESSING',
  'PAID',
  'FAILED',
  'REJECTED',
  'CANCELLED',
])
const RESERVED_STATES = new Set(['PENDING', 'APPROVED', 'PROCESSING'])
const TERMINAL_PAYPAL_EVENTS = Object.freeze({
  'PAYMENT.PAYOUTS-ITEM.SUCCEEDED': 'PAID',
  'PAYMENT.PAYOUTS-ITEM.FAILED': 'FAILED',
  'PAYMENT.PAYOUTS-ITEM.DENIED': 'FAILED',
  'PAYMENT.PAYOUTS-ITEM.BLOCKED': 'FAILED',
  'PAYMENT.PAYOUTS-ITEM.RETURNED': 'FAILED',
  'PAYMENT.PAYOUTS-ITEM.REFUNDED': 'FAILED',
  'PAYMENT.PAYOUTS-ITEM.CANCELED': 'CANCELLED',
})

function boolFromEnvironment(value) {
  return /^(1|true|yes|on)$/i.test(String(value || '').trim())
}

function boundedInteger(value, fallback, minimum, maximum) {
  const numeric = Math.trunc(Number(value))
  return Number.isFinite(numeric) ? Math.max(minimum, Math.min(maximum, numeric)) : fallback
}

function payoutConfiguration(environment = process.env) {
  const mode = String(environment.PAYPAL_MODE || 'sandbox').trim().toLowerCase() === 'live'
    ? 'live'
    : 'sandbox'
  const officialBaseUrl = mode === 'live'
    ? 'https://api-m.paypal.com'
    : 'https://api-m.sandbox.paypal.com'
  const requestedBaseUrl = String(environment.PAYPAL_API_BASE_URL || officialBaseUrl).trim().replace(/\/+$/, '')
  const baseUrl = requestedBaseUrl === officialBaseUrl ? requestedBaseUrl : ''
  const clientId = String(environment.PAYPAL_CLIENT_ID || '').trim()
  const clientSecret = String(environment.PAYPAL_CLIENT_SECRET || '').trim()
  const webhookId = String(environment.PAYPAL_WEBHOOK_ID || '').trim()
  const encryptionKey = String(environment.PAYOUT_ACCOUNT_ENCRYPTION_KEY || '').trim()
  const minorPerCoin = boundedInteger(environment.CREATOR_PAYOUT_MINOR_PER_COIN, 0, 0, 100_000)
  const minimumMinor = boundedInteger(environment.CREATOR_PAYOUT_MINIMUM_MINOR, 0, 0, 10_000_000_000)
  const holdDays = boundedInteger(environment.CREATOR_EARNINGS_HOLD_DAYS, 7, 0, 180)
  const currency = /^[A-Z]{3}$/.test(String(environment.CREATOR_PAYOUT_CURRENCY || 'USD').trim().toUpperCase())
    ? String(environment.CREATOR_PAYOUT_CURRENCY || 'USD').trim().toUpperCase()
    : 'USD'
  const credentialsConfigured = Boolean(baseUrl && clientId && clientSecret && encryptionKey.length >= 32)
  const providerConfigured = credentialsConfigured && minorPerCoin > 0 && minimumMinor > 0
  return {
    enabled: boolFromEnvironment(environment[PAYOUT_FLAG]),
    mode,
    baseUrl,
    clientId,
    clientSecret,
    webhookId,
    encryptionKey,
    minorPerCoin,
    minimumMinor,
    holdDays,
    currency,
    providerConfigured,
    credentialsConfigured,
    webhookConfigured: providerConfigured && Boolean(webhookId),
  }
}

function publicError(code, status) {
  const error = new Error(code)
  error.code = code
  error.status = status
  error.expose = true
  return error
}

function normalizeText(value, maximum = 160) {
  return String(value || '').trim().slice(0, maximum)
}

function normalizeId(value) {
  return normalizeText(value, 256).toLowerCase()
}

function identityValues(identity) {
  const values = new Set()
  for (const value of identity?.values || []) {
    const normalized = normalizeId(value)
    if (normalized) values.add(normalized)
  }
  for (const value of [identity?.displayUserId, identity?.preferredUserId, identity?.localUserId]) {
    const normalized = normalizeId(value)
    if (normalized) values.add(normalized)
  }
  return [...values]
}

function preferredCreatorId(identity) {
  return normalizeId(identity?.displayUserId || identity?.preferredUserId || identity?.localUserId || identityValues(identity)[0])
}

function validEmail(value) {
  const email = normalizeText(value, 254).toLowerCase()
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ? email : ''
}

function maskEmail(value) {
  const email = validEmail(value)
  if (!email) return ''
  const [local, domain] = email.split('@')
  const prefix = local.slice(0, Math.min(2, local.length))
  return `${prefix}${'*'.repeat(Math.max(2, Math.min(8, local.length - prefix.length)))}@${domain}`
}

function encryptionKey(secret) {
  if (String(secret || '').length < 32) throw publicError('PAYOUT_ENCRYPTION_NOT_CONFIGURED', 503)
  return crypto.createHash('sha256').update(String(secret)).digest()
}

function encryptPayoutEmail(email, secret) {
  const normalized = validEmail(email)
  if (!normalized) throw publicError('PAYOUT_EMAIL_INVALID', 400)
  const iv = crypto.randomBytes(12)
  const cipher = crypto.createCipheriv('aes-256-gcm', encryptionKey(secret), iv)
  cipher.setAAD(Buffer.from('novvin:creator-payout-email:v1'))
  const ciphertext = Buffer.concat([cipher.update(normalized, 'utf8'), cipher.final()])
  return JSON.stringify({
    version: 1,
    iv: iv.toString('base64'),
    ciphertext: ciphertext.toString('base64'),
    tag: cipher.getAuthTag().toString('base64'),
  })
}

function decryptPayoutEmail(document, secret) {
  const parsed = typeof document === 'string' ? JSON.parse(document) : document
  if (parsed?.version !== 1) throw new Error('PAYOUT_EMAIL_ENCRYPTION_VERSION_UNSUPPORTED')
  const decipher = crypto.createDecipheriv(
    'aes-256-gcm',
    encryptionKey(secret),
    Buffer.from(parsed.iv, 'base64'),
  )
  decipher.setAAD(Buffer.from('novvin:creator-payout-email:v1'))
  decipher.setAuthTag(Buffer.from(parsed.tag, 'base64'))
  const email = Buffer.concat([
    decipher.update(Buffer.from(parsed.ciphertext, 'base64')),
    decipher.final(),
  ]).toString('utf8')
  if (!validEmail(email)) throw new Error('PAYOUT_EMAIL_DECRYPTION_INVALID')
  return email
}

function sha256(value) {
  return crypto.createHash('sha256').update(String(value || '')).digest('hex')
}

function parsePositiveInteger(value) {
  const numeric = Math.trunc(Number(value))
  return Number.isSafeInteger(numeric) && numeric > 0 ? numeric : 0
}

function requestDecision(input) {
  if (!input.enabled) return { ok: false, code: 'CREATOR_PAYOUTS_DISABLED', status: 503 }
  if (!input.providerConfigured) return { ok: false, code: 'PAYOUT_PROVIDER_NOT_CONFIGURED', status: 503 }
  if (!input.creatorVerified) return { ok: false, code: 'CREATOR_NOT_VERIFIED', status: 403 }
  if (String(input.accountStatus || '').toUpperCase() !== 'VERIFIED') {
    return { ok: false, code: 'PAYOUT_ACCOUNT_NOT_VERIFIED', status: 409 }
  }
  if (!parsePositiveInteger(input.amountMinor)) return { ok: false, code: 'PAYOUT_AMOUNT_INVALID', status: 400 }
  if (input.amountMinor < input.minimumMinor) return { ok: false, code: 'PAYOUT_MINIMUM_NOT_REACHED', status: 409 }
  if (input.existingRequest) return { ok: true, duplicate: true }
  if (input.availableMinor < input.amountMinor) return { ok: false, code: 'PAYOUT_INSUFFICIENT_BALANCE', status: 409 }
  return { ok: true, duplicate: false }
}

function earningValueMinor(earning, configuration) {
  const coins = parsePositiveInteger(
    earning?.withdrawable_amount_diamonds ?? earning?.amount_diamonds,
  )
  return coins * configuration.minorPerCoin
}

function buildBalanceSnapshot({ earnings = [], requests = [], allocations = [], configuration, now = Date.now() }) {
  const requestById = new Map(requests.map((request) => [String(request.id), request]))
  const allocatedByEarning = new Map()
  const paidByEarning = new Map()
  for (const allocation of allocations) {
    const request = requestById.get(String(allocation.request_id))
    const state = String(request?.status || '').toUpperCase()
    if (!request || (!RESERVED_STATES.has(state) && state !== 'PAID')) continue
    const earningId = String(allocation.earning_id)
    const amount = parsePositiveInteger(allocation.allocated_minor)
    allocatedByEarning.set(earningId, (allocatedByEarning.get(earningId) || 0) + amount)
    if (state === 'PAID') paidByEarning.set(earningId, (paidByEarning.get(earningId) || 0) + amount)
  }

  const holdMs = configuration.holdDays * 86_400_000
  let pendingMinor = 0
  let rawAvailableMinor = 0
  let recoveryDebtMinor = 0
  let pendingCoins = 0
  let availableCoins = 0
  let heldCoins = 0
  let paidCoins = 0
  let totalCoins = 0
  const eligible = []
  for (const earning of earnings) {
    const earningId = String(earning.id || earning.row_id || '')
    const status = String(earning.status || '').toLowerCase()
    if (!earningId || ['reversed', 'non_withdrawable', 'cancelled', 'failed'].includes(status)) continue
    const valueMinor = earningValueMinor(earning, configuration)
    const valueCoins = coinsForEarning(earning)
    if (valueCoins <= 0 && valueMinor <= 0) continue
    const paid = paidByEarning.get(earningId) || 0
    const allocated = allocatedByEarning.get(earningId) || 0
    const allocatedCoins = configuration.minorPerCoin > 0
      ? Math.min(valueCoins, Math.ceil(allocated / configuration.minorPerCoin))
      : 0
    const remainingCoins = Math.max(0, valueCoins - allocatedCoins)
    totalCoins += valueCoins
    if (configuration.minorPerCoin > 0 && allocatedCoins > 0) {
      for (const allocation of allocations.filter((item) => String(item.earning_id) === earningId)) {
        const allocationCoins = Math.min(
          valueCoins,
          Math.ceil(parsePositiveInteger(allocation.allocated_minor) / configuration.minorPerCoin),
        )
        const allocationState = String(requestById.get(String(allocation.request_id))?.status || '').toUpperCase()
        if (RESERVED_STATES.has(allocationState)) heldCoins += allocationCoins
        if (allocationState === 'PAID') paidCoins += allocationCoins
      }
    }
    recoveryDebtMinor += Math.max(0, paid - valueMinor)
    const remaining = Math.max(0, valueMinor - allocated)
    const createdAt = Date.parse(String(earning.created_at || earning.wrapper_created_at || ''))
    const availableAt = Number.isFinite(createdAt) ? createdAt + holdMs : Number.POSITIVE_INFINITY
    if (availableAt > now) {
      pendingMinor += remaining
      pendingCoins += remainingCoins
    } else if (remaining > 0) {
      rawAvailableMinor += remaining
      availableCoins += remainingCoins
      eligible.push({ earningId, remainingMinor: remaining, availableAt })
    }
  }

  const heldMinor = requests
    .filter((request) => RESERVED_STATES.has(String(request.status || '').toUpperCase()))
    .reduce((total, request) => total + parsePositiveInteger(request.amount_minor), 0)
  const paidMinor = requests
    .filter((request) => String(request.status || '').toUpperCase() === 'PAID')
    .reduce((total, request) => total + parsePositiveInteger(request.amount_minor), 0)
  const availableMinor = Math.max(0, rawAvailableMinor - recoveryDebtMinor)
  return {
    pendingMinor,
    availableMinor,
    rawAvailableMinor,
    heldMinor,
    paidMinor,
    recoveryDebtMinor,
    pendingCoins,
    availableCoins,
    heldCoins,
    paidCoins,
    totalCoins,
    eligible,
  }
}

function coinsForEarning(earning) {
  return parsePositiveInteger(
    earning?.withdrawable_amount_diamonds ?? earning?.amount_diamonds,
  )
}

function allocatePayout(snapshot, amountMinor) {
  let debtToAbsorb = snapshot.recoveryDebtMinor
  let remaining = amountMinor
  const allocations = []
  for (const earning of snapshot.eligible) {
    let available = earning.remainingMinor
    const absorbed = Math.min(debtToAbsorb, available)
    debtToAbsorb -= absorbed
    available -= absorbed
    if (available <= 0) continue
    const amount = Math.min(remaining, available)
    if (amount > 0) allocations.push({ earningId: earning.earningId, allocatedMinor: amount })
    remaining -= amount
    if (remaining === 0) break
  }
  if (remaining !== 0) throw publicError('PAYOUT_INSUFFICIENT_BALANCE', 409)
  return allocations
}

function publicRequest(row) {
  return {
    id: row.id,
    amountMinor: Number(row.amount_minor || 0),
    currency: row.currency,
    status: row.status,
    account: row.recipient_email_masked || null,
    failureCode: row.failure_code || null,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
    approvedAt: row.approved_at,
    paidAt: row.paid_at,
  }
}

async function ensureCreatorPayoutTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_creator_payout_accounts (
      creator_user_id TEXT PRIMARY KEY,
      provider TEXT NOT NULL DEFAULT 'paypal' CHECK (provider = 'paypal'),
      recipient_email_ciphertext TEXT NOT NULL,
      recipient_email_hash TEXT NOT NULL UNIQUE,
      recipient_email_masked TEXT NOT NULL,
      currency TEXT NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
      status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','VERIFIED','DISABLED')),
      verified_by TEXT,
      verified_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
    CREATE TABLE IF NOT EXISTS fallback_creator_payout_requests (
      id UUID PRIMARY KEY,
      creator_user_id TEXT NOT NULL,
      idempotency_key TEXT NOT NULL,
      amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
      currency TEXT NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
      status TEXT NOT NULL CHECK (status IN ('PENDING','APPROVED','PROCESSING','PAID','FAILED','REJECTED','CANCELLED')),
      recipient_email_ciphertext TEXT NOT NULL,
      recipient_email_hash TEXT NOT NULL,
      recipient_email_masked TEXT NOT NULL,
      paypal_sender_batch_id TEXT NOT NULL UNIQUE,
      paypal_payout_batch_id TEXT UNIQUE,
      paypal_payout_item_id TEXT UNIQUE,
      failure_code TEXT,
      approved_by TEXT,
      approved_at TIMESTAMPTZ,
      processing_at TIMESTAMPTZ,
      paid_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE (creator_user_id, idempotency_key)
    );
    CREATE INDEX IF NOT EXISTS idx_creator_payout_requests_worker
      ON fallback_creator_payout_requests(status, updated_at);
    CREATE TABLE IF NOT EXISTS fallback_creator_payout_allocations (
      request_id UUID NOT NULL REFERENCES fallback_creator_payout_requests(id) ON DELETE CASCADE,
      earning_id TEXT NOT NULL,
      allocated_minor BIGINT NOT NULL CHECK (allocated_minor > 0),
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (request_id, earning_id)
    );
    CREATE INDEX IF NOT EXISTS idx_creator_payout_allocations_earning
      ON fallback_creator_payout_allocations(earning_id);
    CREATE TABLE IF NOT EXISTS fallback_creator_payout_ledger (
      id UUID PRIMARY KEY,
      creator_user_id TEXT NOT NULL,
      request_id UUID REFERENCES fallback_creator_payout_requests(id) ON DELETE SET NULL,
      event_key TEXT NOT NULL UNIQUE,
      event_type TEXT NOT NULL,
      amount_minor BIGINT NOT NULL DEFAULT 0,
      currency TEXT NOT NULL,
      metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
    CREATE TABLE IF NOT EXISTS fallback_paypal_webhook_events (
      event_id TEXT PRIMARY KEY,
      event_type TEXT NOT NULL,
      payload_hash TEXT NOT NULL,
      payout_request_id UUID REFERENCES fallback_creator_payout_requests(id) ON DELETE SET NULL,
      result_status TEXT,
      received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      processed_at TIMESTAMPTZ
    );
    CREATE TABLE IF NOT EXISTS fallback_creator_payout_audit (
      id UUID PRIMARY KEY,
      actor_user_id TEXT,
      creator_user_id TEXT NOT NULL,
      request_id UUID REFERENCES fallback_creator_payout_requests(id) ON DELETE SET NULL,
      event_type TEXT NOT NULL,
      reason_code TEXT,
      request_id_header TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
  `)
}

async function loadPayoutData(client, identity) {
  const aliases = identityValues(identity)
  if (aliases.length === 0) throw publicError('AUTH_REQUIRED', 401)
  const [accountResult, earningResult, requestResult, allocationResult] = await Promise.all([
    client.query(
      `SELECT * FROM fallback_creator_payout_accounts
       WHERE lower(creator_user_id) = ANY($1::text[])
       ORDER BY updated_at DESC LIMIT 1`,
      [aliases],
    ),
    client.query(
      `SELECT row_id AS id, row, created_at AS wrapper_created_at
       FROM fallback_compat_rows
       WHERE table_name = 'creator_earnings'
         AND lower(COALESCE(row->>'creator_id', row->>'user_id', '')) = ANY($1::text[])`,
      [aliases],
    ),
    client.query(
      `SELECT * FROM fallback_creator_payout_requests
       WHERE lower(creator_user_id) = ANY($1::text[])
       ORDER BY created_at DESC`,
      [aliases],
    ),
    client.query(
      `SELECT allocation.*
       FROM fallback_creator_payout_allocations allocation
       JOIN fallback_creator_payout_requests request ON request.id = allocation.request_id
       WHERE lower(request.creator_user_id) = ANY($1::text[])`,
      [aliases],
    ),
  ])
  const earnings = earningResult.rows.map((record) => ({
    id: record.id,
    ...(record.row || {}),
    wrapper_created_at: record.wrapper_created_at,
  }))
  return {
    account: accountResult.rows[0] || null,
    earnings,
    requests: requestResult.rows,
    allocations: allocationResult.rows,
  }
}

async function creatorIsVerified(client, identity) {
  const aliases = identityValues(identity)
  if (aliases.length === 0) return false
  const { rows } = await client.query(
    `SELECT EXISTS (
       SELECT 1 FROM fallback_profile_overrides override
       WHERE lower(override.user_id) = ANY($1::text[])
         AND lower(COALESCE(override.profile->>'is_verified', 'false')) = 'true'
       UNION ALL
       SELECT 1 FROM fallback_compat_rows profile
       WHERE profile.table_name = 'profiles'
         AND lower(COALESCE(profile.row->>'id', profile.row->>'user_id', '')) = ANY($1::text[])
         AND lower(COALESCE(profile.row->>'is_verified', 'false')) = 'true'
     ) AS verified`,
    [aliases],
  )
  return rows[0]?.verified === true
}

async function actorIsPayoutAdmin(client, identity) {
  return appAdminHasCapability(client, identity, ADMIN_CAPABILITY.FINANCE_MANAGE)
}

async function insertLedger(client, { creatorUserId, requestId = null, eventKey, eventType, amountMinor = 0, currency, metadata = {} }) {
  await client.query(
    `INSERT INTO fallback_creator_payout_ledger(
       id, creator_user_id, request_id, event_key, event_type, amount_minor, currency, metadata
     ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8::jsonb)
     ON CONFLICT (event_key) DO NOTHING`,
    [crypto.randomUUID(), creatorUserId, requestId, eventKey, eventType, amountMinor, currency, JSON.stringify(metadata)],
  )
}

async function insertAudit(client, req, event) {
  await client.query(
    `INSERT INTO fallback_creator_payout_audit(
       id, actor_user_id, creator_user_id, request_id, event_type, reason_code, request_id_header
     ) VALUES ($1,$2,$3,$4,$5,$6,$7)`,
    [
      crypto.randomUUID(),
      event.actorUserId || null,
      event.creatorUserId,
      event.requestId || null,
      event.eventType,
      event.reasonCode || null,
      normalizeText(req?.requestId, 80) || null,
    ],
  )
}

function publicSummary(data, configuration, verified, now = Date.now()) {
  const balance = buildBalanceSnapshot({ ...data, configuration, now })
  return {
    enabled: configuration.enabled,
    configured: configuration.providerConfigured,
    mode: configuration.mode,
    creatorVerified: verified,
    currency: configuration.currency,
    minimumMinor: configuration.minimumMinor,
    holdDays: configuration.holdDays,
    account: data.account ? {
      provider: 'paypal',
      emailMasked: data.account.recipient_email_masked,
      status: data.account.status,
      currency: data.account.currency,
      verifiedAt: data.account.verified_at,
    } : null,
    balances: {
      pendingMinor: balance.pendingMinor,
      availableMinor: balance.availableMinor,
      heldMinor: balance.heldMinor,
      paidMinor: balance.paidMinor,
      recoveryDebtMinor: balance.recoveryDebtMinor,
      pendingCoins: balance.pendingCoins,
      availableCoins: balance.availableCoins,
      heldCoins: balance.heldCoins,
      paidCoins: balance.paidCoins,
      totalCoins: balance.totalCoins,
    },
    requests: data.requests.slice(0, 50).map(publicRequest),
  }
}

class PayPalPayoutClient {
  constructor(configuration, fetchImpl = global.fetch) {
    this.configuration = configuration
    this.fetch = fetchImpl
  }

  async accessToken() {
    const response = await this.fetch(`${this.configuration.baseUrl}/v1/oauth2/token`, {
      method: 'POST',
      headers: {
        Authorization: `Basic ${Buffer.from(`${this.configuration.clientId}:${this.configuration.clientSecret}`).toString('base64')}`,
        'Content-Type': 'application/x-www-form-urlencoded',
        Accept: 'application/json',
      },
      body: 'grant_type=client_credentials',
    })
    const body = await response.json().catch(() => ({}))
    if (!response.ok || !body.access_token) {
      const error = new Error('PAYPAL_AUTH_FAILED')
      error.retryable = response.status >= 500
      throw error
    }
    return body.access_token
  }

  async request(path, init = {}) {
    const token = await this.accessToken()
    const response = await this.fetch(`${this.configuration.baseUrl}${path}`, {
      ...init,
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
        ...(init.headers || {}),
      },
    })
    const body = await response.json().catch(() => ({}))
    if (!response.ok) {
      const error = new Error('PAYPAL_REQUEST_FAILED')
      error.retryable = response.status >= 500 || response.status === 429
      error.status = response.status
      error.providerCode = normalizeText(body?.name || body?.details?.[0]?.issue, 80) || 'PAYPAL_REQUEST_FAILED'
      throw error
    }
    return body
  }

  async createPayout(request, recipientEmail) {
    const body = await this.request('/v1/payments/payouts', {
      method: 'POST',
      headers: { 'PayPal-Request-Id': request.paypal_sender_batch_id },
      body: JSON.stringify({
        sender_batch_header: {
          sender_batch_id: request.paypal_sender_batch_id,
          email_subject: 'Tu retiro de ganancias Novvin',
          email_message: 'PayPal está procesando tu retiro de ganancias de creador.',
        },
        items: [{
          recipient_type: 'EMAIL',
          amount: {
            value: (Number(request.amount_minor) / 100).toFixed(2),
            currency: request.currency,
          },
          receiver: recipientEmail,
          note: 'Retiro de ganancias de creador Novvin',
          sender_item_id: request.id,
        }],
      }),
    })
    return {
      batchId: normalizeText(body?.batch_header?.payout_batch_id, 128) || null,
      batchStatus: normalizeText(body?.batch_header?.batch_status, 40).toUpperCase() || 'PENDING',
    }
  }

  async payout(batchId) {
    return this.request(`/v1/payments/payouts/${encodeURIComponent(batchId)}`, { method: 'GET' })
  }

  async verifyWebhook(headers, event) {
    const transmissionId = normalizeText(headers['paypal-transmission-id'], 256)
    const transmissionTime = normalizeText(headers['paypal-transmission-time'], 128)
    const transmissionSignature = normalizeText(headers['paypal-transmission-sig'], 2048)
    const certUrl = normalizeText(headers['paypal-cert-url'], 2048)
    const authAlgo = normalizeText(headers['paypal-auth-algo'], 128)
    if (!transmissionId || !transmissionTime || !transmissionSignature || !certUrl || !authAlgo) return false
    const body = await this.request('/v1/notifications/verify-webhook-signature', {
      method: 'POST',
      body: JSON.stringify({
        transmission_id: transmissionId,
        transmission_time: transmissionTime,
        cert_url: certUrl,
        auth_algo: authAlgo,
        transmission_sig: transmissionSignature,
        webhook_id: this.configuration.webhookId,
        webhook_event: event,
      }),
    })
    return String(body?.verification_status || '').toUpperCase() === 'SUCCESS'
  }
}

function paypalEventStatus(eventType) {
  return TERMINAL_PAYPAL_EVENTS[String(eventType || '').toUpperCase()] || null
}

function paypalBatchStatus(payload) {
  const itemStatus = String(payload?.items?.[0]?.transaction_status || '').toUpperCase()
  const batchStatus = String(payload?.batch_header?.batch_status || '').toUpperCase()
  if (itemStatus === 'SUCCESS') return 'PAID'
  if (['FAILED', 'DENIED', 'BLOCKED', 'RETURNED', 'REFUNDED'].includes(itemStatus)) return 'FAILED'
  if (['CANCELED', 'CANCELLED'].includes(itemStatus)) return 'CANCELLED'
  if (batchStatus === 'SUCCESS') return 'PAID'
  if (['DENIED', 'CANCELED', 'CANCELLED'].includes(batchStatus)) return 'FAILED'
  return null
}

async function transitionPayoutRequest(client, request, status, patch = {}, eventKey = null) {
  if (!PAYOUT_STATES.includes(status)) throw new Error('PAYOUT_STATUS_INVALID')
  const paidAt = status === 'PAID' ? new Date().toISOString() : request.paid_at
  const { rows } = await client.query(
    `UPDATE fallback_creator_payout_requests SET
       status = $2,
       paypal_payout_batch_id = COALESCE($3, paypal_payout_batch_id),
       paypal_payout_item_id = COALESCE($4, paypal_payout_item_id),
       failure_code = $5,
       paid_at = $6,
       updated_at = NOW()
     WHERE id = $1
     RETURNING *`,
    [request.id, status, patch.batchId || null, patch.itemId || null, patch.failureCode || null, paidAt || null],
  )
  const updated = rows[0]
  if (updated && eventKey) {
    await insertLedger(client, {
      creatorUserId: updated.creator_user_id,
      requestId: updated.id,
      eventKey,
      eventType: `PAYOUT_${status}`,
      amountMinor: Number(updated.amount_minor),
      currency: updated.currency,
      metadata: { status },
    })
  }
  return updated
}

async function processApprovedCreatorPayouts(pool, options = {}) {
  const configuration = options.configuration || payoutConfiguration()
  if (!configuration.enabled || !configuration.providerConfigured) return { processed: 0, disabled: true }
  const provider = options.provider || new PayPalPayoutClient(configuration, options.fetchImpl)
  const limit = boundedInteger(options.limit, 5, 1, 20)
  let processed = 0
  for (let index = 0; index < limit; index += 1) {
    const client = await pool.connect()
    let request
    try {
      await client.query('BEGIN')
      const { rows } = await client.query(
        `SELECT * FROM fallback_creator_payout_requests
         WHERE status = 'APPROVED'
         ORDER BY approved_at ASC NULLS LAST, created_at ASC
         FOR UPDATE SKIP LOCKED LIMIT 1`,
      )
      request = rows[0]
      if (!request) {
        await client.query('COMMIT')
        break
      }
      const claimed = await client.query(
        `UPDATE fallback_creator_payout_requests
         SET status = 'PROCESSING', processing_at = NOW(), updated_at = NOW()
         WHERE id = $1 AND status = 'APPROVED'
         RETURNING *`,
        [request.id],
      )
      request = claimed.rows[0]
      await insertLedger(client, {
        creatorUserId: request.creator_user_id,
        requestId: request.id,
        eventKey: `payout:${request.id}:processing`,
        eventType: 'PAYOUT_PROCESSING',
        amountMinor: Number(request.amount_minor),
        currency: request.currency,
      })
      await client.query('COMMIT')
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      throw error
    } finally {
      client.release()
    }

    try {
      const email = decryptPayoutEmail(request.recipient_email_ciphertext, configuration.encryptionKey)
      const result = await provider.createPayout(request, email)
      await pool.query(
        `UPDATE fallback_creator_payout_requests
         SET paypal_payout_batch_id = COALESCE($2, paypal_payout_batch_id), updated_at = NOW()
         WHERE id = $1 AND status = 'PROCESSING'`,
        [request.id, result.batchId],
      )
    } catch (error) {
      const retryable = error?.retryable !== false
      if (!retryable) {
        const failureCode = normalizeText(error?.providerCode || error?.message, 80) || 'PAYPAL_REQUEST_FAILED'
        const failureClient = await pool.connect()
        try {
          await failureClient.query('BEGIN')
          const current = (await failureClient.query(
            `SELECT * FROM fallback_creator_payout_requests WHERE id = $1 FOR UPDATE`,
            [request.id],
          )).rows[0]
          if (current?.status === 'PROCESSING') {
            await transitionPayoutRequest(
              failureClient,
              current,
              'FAILED',
              { failureCode },
              `payout:${request.id}:provider-failed`,
            )
          }
          await failureClient.query('COMMIT')
        } catch (transitionError) {
          await failureClient.query('ROLLBACK').catch(() => {})
          throw transitionError
        } finally {
          failureClient.release()
        }
      }
    }
    processed += 1
  }
  return { processed, disabled: false }
}

async function reconcileCreatorPayouts(pool, options = {}) {
  const configuration = options.configuration || payoutConfiguration()
  if (!configuration.enabled || !configuration.providerConfigured) return { reconciled: 0, disabled: true }
  const provider = options.provider || new PayPalPayoutClient(configuration, options.fetchImpl)
  const { rows } = await pool.query(
    `SELECT * FROM fallback_creator_payout_requests
     WHERE status = 'PROCESSING' AND paypal_payout_batch_id IS NOT NULL
     ORDER BY updated_at ASC LIMIT 20`,
  )
  let reconciled = 0
  for (const request of rows) {
    let payload
    try {
      payload = await provider.payout(request.paypal_payout_batch_id)
    } catch {
      continue
    }
    const status = paypalBatchStatus(payload)
    if (!status) continue
    const client = await pool.connect()
    try {
      await client.query('BEGIN')
      const current = (await client.query(
        `SELECT * FROM fallback_creator_payout_requests WHERE id = $1 FOR UPDATE`,
        [request.id],
      )).rows[0]
      if (current?.status === 'PROCESSING') {
        const itemId = normalizeText(payload?.items?.[0]?.payout_item_id, 128) || null
        await transitionPayoutRequest(
          client,
          current,
          status,
          { itemId, failureCode: status === 'PAID' ? null : 'PAYPAL_RECONCILED_FAILURE' },
          `payout:${request.id}:reconcile:${status.toLowerCase()}`,
        )
        reconciled += 1
      }
      await client.query('COMMIT')
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      throw error
    } finally {
      client.release()
    }
  }
  return { reconciled, disabled: false }
}

function registerCreatorPayoutRoutes(app, dependencies) {
  const {
    pool,
    authRequired,
    getCurrentUser,
    userIdentity,
    config = payoutConfiguration,
    providerFactory = (configuration) => new PayPalPayoutClient(configuration),
    now = () => Date.now(),
  } = dependencies

  async function actor(req) {
    const user = await getCurrentUser(pool, req)
    if (!user) throw publicError('USER_NOT_FOUND', 404)
    const identity = userIdentity(user)
    return { user, identity, creatorId: preferredCreatorId(identity) }
  }

  app.get('/v1/data/creator/payouts/summary', authRequired, async (req, res, next) => {
    try {
      const { identity } = await actor(req)
      const [data, verified] = await Promise.all([
        loadPayoutData(pool, identity),
        creatorIsVerified(pool, identity),
      ])
      res.setHeader('Cache-Control', 'private, no-store')
      res.json(publicSummary(data, config(), verified, now()))
    } catch (error) {
      next(error)
    }
  })

  app.put('/v1/data/creator/payouts/account', authRequired, async (req, res, next) => {
    let client
    try {
      const { identity, creatorId } = await actor(req)
      const configuration = config()
      if (!configuration.providerConfigured) throw publicError('PAYOUT_PROVIDER_NOT_CONFIGURED', 503)
      const email = validEmail(req.body?.email)
      const currency = normalizeText(req.body?.currency || configuration.currency, 3).toUpperCase()
      if (!email) throw publicError('PAYOUT_EMAIL_INVALID', 400)
      if (!/^[A-Z]{3}$/.test(currency)) throw publicError('PAYOUT_CURRENCY_INVALID', 400)
      const ciphertext = encryptPayoutEmail(email, configuration.encryptionKey)
      client = await pool.connect()
      await client.query('BEGIN')
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [`payout-account:${creatorId}`])
      const existing = (await client.query(
        `SELECT * FROM fallback_creator_payout_accounts
         WHERE lower(creator_user_id) = ANY($1::text[]) FOR UPDATE`,
        [identityValues(identity)],
      )).rows[0]
      const canonicalCreatorId = normalizeId(existing?.creator_user_id) || creatorId
      const emailHash = sha256(email)
      const status = existing?.recipient_email_hash === emailHash && existing?.currency === currency
        ? existing.status
        : 'PENDING'
      const { rows } = await client.query(
        `INSERT INTO fallback_creator_payout_accounts(
           creator_user_id, recipient_email_ciphertext, recipient_email_hash,
           recipient_email_masked, currency, status
         ) VALUES ($1,$2,$3,$4,$5,$6)
         ON CONFLICT (creator_user_id) DO UPDATE SET
           recipient_email_ciphertext = EXCLUDED.recipient_email_ciphertext,
           recipient_email_hash = EXCLUDED.recipient_email_hash,
           recipient_email_masked = EXCLUDED.recipient_email_masked,
           currency = EXCLUDED.currency,
           status = EXCLUDED.status,
           verified_by = CASE WHEN EXCLUDED.status = 'VERIFIED' THEN fallback_creator_payout_accounts.verified_by ELSE NULL END,
           verified_at = CASE WHEN EXCLUDED.status = 'VERIFIED' THEN fallback_creator_payout_accounts.verified_at ELSE NULL END,
           updated_at = NOW()
         RETURNING *`,
        [canonicalCreatorId, ciphertext, emailHash, maskEmail(email), currency, status],
      )
      await insertAudit(client, req, {
        actorUserId: creatorId,
        creatorUserId: canonicalCreatorId,
        eventType: 'PAYOUT_ACCOUNT_UPDATED',
      })
      await client.query('COMMIT')
      client.release()
      client = null
      res.json({ account: publicSummary({ account: rows[0], earnings: [], requests: [], allocations: [] }, configuration, false).account })
    } catch (error) {
      if (client) {
        await client.query('ROLLBACK').catch(() => {})
        client.release()
      }
      if (error?.code === '23505') return next(publicError('PAYOUT_ACCOUNT_ALREADY_IN_USE', 409))
      next(error)
    }
  })

  app.post('/v1/data/creator/payouts/requests', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      const { identity, creatorId } = await actor(req)
      const configuration = config()
      const amountMinor = parsePositiveInteger(req.body?.amountMinor)
      const idempotencyKey = normalizeText(req.body?.idempotencyKey, 128)
      if (!/^[a-zA-Z0-9._:-]{8,128}$/.test(idempotencyKey)) throw publicError('PAYOUT_IDEMPOTENCY_KEY_INVALID', 400)
      await client.query('BEGIN')
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [`payout-request:${creatorId}`])
      const data = await loadPayoutData(client, identity)
      const verified = await creatorIsVerified(client, identity)
      const existing = data.requests.find((item) => item.idempotency_key === idempotencyKey)
      if (existing && (Number(existing.amount_minor) !== amountMinor || existing.currency !== configuration.currency)) {
        throw publicError('PAYOUT_IDEMPOTENCY_CONFLICT', 409)
      }
      const snapshot = buildBalanceSnapshot({ ...data, configuration, now: now() })
      const decision = requestDecision({
        enabled: configuration.enabled,
        providerConfigured: configuration.providerConfigured,
        creatorVerified: verified,
        accountStatus: data.account?.status,
        amountMinor,
        minimumMinor: configuration.minimumMinor,
        existingRequest: existing,
        availableMinor: snapshot.availableMinor,
      })
      if (!decision.ok) throw publicError(decision.code, decision.status)
      if (decision.duplicate) {
        await client.query('COMMIT')
        res.json({ request: publicRequest(existing), duplicate: true })
        return
      }
      if (data.account.currency !== configuration.currency) throw publicError('PAYOUT_CURRENCY_MISMATCH', 409)
      const allocations = allocatePayout(snapshot, amountMinor)
      const requestId = crypto.randomUUID()
      const senderBatchId = `novvin-${requestId}`
      const inserted = (await client.query(
        `INSERT INTO fallback_creator_payout_requests(
           id, creator_user_id, idempotency_key, amount_minor, currency, status,
           recipient_email_ciphertext, recipient_email_hash, recipient_email_masked,
           paypal_sender_batch_id
         ) VALUES ($1,$2,$3,$4,$5,'PENDING',$6,$7,$8,$9)
         RETURNING *`,
        [
          requestId,
          creatorId,
          idempotencyKey,
          amountMinor,
          configuration.currency,
          data.account.recipient_email_ciphertext,
          data.account.recipient_email_hash,
          data.account.recipient_email_masked,
          senderBatchId,
        ],
      )).rows[0]
      for (const allocation of allocations) {
        await client.query(
          `INSERT INTO fallback_creator_payout_allocations(request_id, earning_id, allocated_minor)
           VALUES ($1,$2,$3)`,
          [requestId, allocation.earningId, allocation.allocatedMinor],
        )
      }
      await insertLedger(client, {
        creatorUserId: creatorId,
        requestId,
        eventKey: `payout:${requestId}:requested`,
        eventType: 'PAYOUT_REQUESTED',
        amountMinor,
        currency: configuration.currency,
      })
      await insertAudit(client, req, {
        actorUserId: creatorId,
        creatorUserId: creatorId,
        requestId,
        eventType: 'PAYOUT_REQUESTED',
      })
      await client.query('COMMIT')
      res.status(201).json({ request: publicRequest(inserted), duplicate: false })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })

  app.post('/v1/data/creator/payouts/requests/:requestId/cancel', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      const { identity, creatorId } = await actor(req)
      await client.query('BEGIN')
      const request = (await client.query(
        `SELECT * FROM fallback_creator_payout_requests
         WHERE id::text = $1 AND lower(creator_user_id) = ANY($2::text[]) FOR UPDATE`,
        [normalizeId(req.params.requestId), identityValues(identity)],
      )).rows[0]
      if (!request) throw publicError('PAYOUT_REQUEST_NOT_FOUND', 404)
      if (request.status !== 'PENDING') throw publicError('PAYOUT_REQUEST_NOT_CANCELLABLE', 409)
      const updated = await transitionPayoutRequest(
        client,
        request,
        'CANCELLED',
        {},
        `payout:${request.id}:cancelled`,
      )
      await insertAudit(client, req, {
        actorUserId: creatorId,
        creatorUserId: request.creator_user_id,
        requestId: request.id,
        eventType: 'PAYOUT_CANCELLED',
      })
      await client.query('COMMIT')
      res.json({ request: publicRequest(updated) })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })

  app.post('/v1/data/admin/creator-payouts/accounts/:creatorId/verify', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      const admin = await actor(req)
      await client.query('BEGIN')
      if (!(await actorIsPayoutAdmin(client, admin.identity))) throw publicError('ADMIN_REQUIRED', 403)
      const creatorId = normalizeId(req.params.creatorId)
      const account = (await client.query(
        `UPDATE fallback_creator_payout_accounts
         SET status = 'VERIFIED', verified_by = $2, verified_at = NOW(), updated_at = NOW()
         WHERE lower(creator_user_id) = $1 AND status <> 'DISABLED'
         RETURNING *`,
        [creatorId, admin.creatorId],
      )).rows[0]
      if (!account) throw publicError('PAYOUT_ACCOUNT_NOT_FOUND', 404)
      await insertAudit(client, req, {
        actorUserId: admin.creatorId,
        creatorUserId: account.creator_user_id,
        eventType: 'PAYOUT_ACCOUNT_VERIFIED',
      })
      await client.query('COMMIT')
      res.json({ account: { emailMasked: account.recipient_email_masked, status: account.status, currency: account.currency } })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })

  app.post('/v1/data/admin/creator-payouts/requests/:requestId/decision', authRequired, async (req, res, next) => {
    const client = await pool.connect()
    try {
      const admin = await actor(req)
      const decision = normalizeText(req.body?.decision, 16).toUpperCase()
      const reasonCode = normalizeText(req.body?.reasonCode, 80).toUpperCase() || null
      if (!['APPROVE', 'REJECT'].includes(decision)) throw publicError('PAYOUT_DECISION_INVALID', 400)
      await client.query('BEGIN')
      if (!(await actorIsPayoutAdmin(client, admin.identity))) throw publicError('ADMIN_REQUIRED', 403)
      const request = (await client.query(
        `SELECT * FROM fallback_creator_payout_requests WHERE id::text = $1 FOR UPDATE`,
        [normalizeId(req.params.requestId)],
      )).rows[0]
      if (!request) throw publicError('PAYOUT_REQUEST_NOT_FOUND', 404)
      if (request.status !== 'PENDING') throw publicError('PAYOUT_REQUEST_ALREADY_DECIDED', 409)
      const status = decision === 'APPROVE' ? 'APPROVED' : 'REJECTED'
      const { rows } = await client.query(
        `UPDATE fallback_creator_payout_requests SET
           status = $2,
           approved_by = $3,
           approved_at = CASE WHEN $2 = 'APPROVED' THEN NOW() ELSE approved_at END,
           failure_code = CASE WHEN $2 = 'REJECTED' THEN $4 ELSE NULL END,
           updated_at = NOW()
         WHERE id = $1 RETURNING *`,
        [request.id, status, admin.creatorId, reasonCode],
      )
      await insertLedger(client, {
        creatorUserId: request.creator_user_id,
        requestId: request.id,
        eventKey: `payout:${request.id}:${status.toLowerCase()}`,
        eventType: `PAYOUT_${status}`,
        amountMinor: Number(request.amount_minor),
        currency: request.currency,
        metadata: reasonCode ? { reasonCode } : {},
      })
      await insertAudit(client, req, {
        actorUserId: admin.creatorId,
        creatorUserId: request.creator_user_id,
        requestId: request.id,
        eventType: `PAYOUT_${status}`,
        reasonCode,
      })
      await client.query('COMMIT')
      res.json({ request: publicRequest(rows[0]) })
    } catch (error) {
      await client.query('ROLLBACK').catch(() => {})
      next(error)
    } finally {
      client.release()
    }
  })

  app.post('/v1/webhooks/paypal/payouts', async (req, res, next) => {
    const configuration = config()
    if (!configuration.webhookConfigured) return next(publicError('PAYPAL_WEBHOOK_NOT_CONFIGURED', 503))
    try {
      const provider = providerFactory(configuration)
      if (!(await provider.verifyWebhook(req.headers, req.body))) throw publicError('PAYPAL_WEBHOOK_SIGNATURE_INVALID', 401)
      const eventId = normalizeText(req.body?.id, 128)
      const eventType = normalizeText(req.body?.event_type, 100).toUpperCase()
      if (!eventId || !eventType) throw publicError('PAYPAL_WEBHOOK_INVALID', 400)
      const status = paypalEventStatus(eventType)
      const resource = req.body?.resource || {}
      const itemId = normalizeText(resource.payout_item_id || resource.payout_item?.payout_item_id, 128)
      const batchId = normalizeText(resource.payout_batch_id || resource.batch_header?.payout_batch_id, 128)
      const senderItemId = normalizeText(resource.payout_item?.sender_item_id || resource.sender_item_id, 128)
      const payloadHash = sha256(JSON.stringify(req.body))
      const client = await pool.connect()
      try {
        await client.query('BEGIN')
        const inserted = await client.query(
          `INSERT INTO fallback_paypal_webhook_events(event_id, event_type, payload_hash)
           VALUES ($1,$2,$3) ON CONFLICT (event_id) DO NOTHING RETURNING event_id`,
          [eventId, eventType, payloadHash],
        )
        if (!inserted.rows[0]) {
          await client.query('COMMIT')
          return res.json({ accepted: true, duplicate: true })
        }
        const request = (await client.query(
          `SELECT * FROM fallback_creator_payout_requests
           WHERE ($1 <> '' AND paypal_payout_item_id = $1)
              OR ($2 <> '' AND paypal_payout_batch_id = $2)
              OR ($3 <> '' AND id::text = $3)
           ORDER BY updated_at DESC LIMIT 1 FOR UPDATE`,
          [itemId, batchId, senderItemId],
        )).rows[0]
        let updated = request
        if (request && status && !['PAID', 'FAILED', 'REJECTED', 'CANCELLED'].includes(request.status)) {
          updated = await transitionPayoutRequest(
            client,
            request,
            status,
            {
              batchId: batchId || null,
              itemId: itemId || null,
              failureCode: status === 'PAID' ? null : `PAYPAL_${eventType.split('.').pop()}`,
            },
            `paypal-webhook:${eventId}`,
          )
        }
        await client.query(
          `UPDATE fallback_paypal_webhook_events
           SET payout_request_id = $2, result_status = $3, processed_at = NOW()
           WHERE event_id = $1`,
          [eventId, request?.id || null, updated?.status || null],
        )
        await client.query('COMMIT')
        res.json({ accepted: true, duplicate: false })
      } catch (error) {
        await client.query('ROLLBACK').catch(() => {})
        throw error
      } finally {
        client.release()
      }
    } catch (error) {
      next(error)
    }
  })
}

module.exports = {
  PAYOUT_FLAG,
  PAYOUT_STATES,
  PayPalPayoutClient,
  allocatePayout,
  buildBalanceSnapshot,
  decryptPayoutEmail,
  encryptPayoutEmail,
  ensureCreatorPayoutTables,
  maskEmail,
  paypalBatchStatus,
  paypalEventStatus,
  payoutConfiguration,
  processApprovedCreatorPayouts,
  publicSummary,
  reconcileCreatorPayouts,
  registerCreatorPayoutRoutes,
  requestDecision,
}
