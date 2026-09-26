const crypto = require('crypto')
const { OAuth2Client } = require('google-auth-library')

const RTDN_ROUTE = '/webhooks/google-play/rtdn'
const MAX_MESSAGE_BYTES = 128 * 1024
const PROCESSING_LEASE_MINUTES = 5

function enabledValue(value, fallback = false) {
  const normalized = String(value ?? '').trim().toLowerCase()
  if (!normalized) return fallback
  return ['1', 'true', 'yes', 'on'].includes(normalized)
}

function googlePlayRtdnConfiguration(env = process.env) {
  const enabled = enabledValue(env.GOOGLE_PLAY_RTDN_ENABLED, false)
  const audience = String(env.GOOGLE_PLAY_RTDN_AUDIENCE || '').trim()
  const pushServiceAccountEmail = String(
    env.GOOGLE_PLAY_RTDN_PUSH_SERVICE_ACCOUNT_EMAIL || '',
  ).trim().toLowerCase()
  const verificationToken = String(env.GOOGLE_PLAY_RTDN_VERIFICATION_TOKEN || '').trim()
  const pendingRefundPreference = String(
    env.GOOGLE_PLAY_PENDING_REFUND_PREFERENCE || 'NEUTRAL',
  ).trim().toUpperCase()
  const acceptedPreference = ['APPROVE', 'DECLINE', 'NEUTRAL'].includes(pendingRefundPreference)
    ? pendingRefundPreference
    : 'NEUTRAL'
  return {
    enabled,
    ready: Boolean(enabled && audience && pushServiceAccountEmail),
    audience,
    pushServiceAccountEmail,
    verificationToken,
    pendingRefundPreference: acceptedPreference,
    route: RTDN_ROUTE,
  }
}

function sha256(value) {
  return crypto.createHash('sha256').update(String(value || '')).digest('hex')
}

function safeEqual(left, right) {
  const a = Buffer.from(String(left || ''))
  const b = Buffer.from(String(right || ''))
  return a.length === b.length && crypto.timingSafeEqual(a, b)
}

function rtdnError(code, status) {
  const error = new Error(code)
  error.code = code
  error.status = status
  error.expose = true
  return error
}

function decodedBase64Json(value) {
  const data = String(value || '').trim()
  if (!data || data.length > Math.ceil(MAX_MESSAGE_BYTES * 4 / 3) + 8) {
    throw rtdnError('GOOGLE_PLAY_RTDN_INVALID_DATA', 400)
  }
  let decoded
  try {
    decoded = Buffer.from(data, 'base64')
  } catch (_) {
    throw rtdnError('GOOGLE_PLAY_RTDN_INVALID_DATA', 400)
  }
  if (decoded.length === 0 || decoded.length > MAX_MESSAGE_BYTES) {
    throw rtdnError('GOOGLE_PLAY_RTDN_INVALID_DATA', 400)
  }
  try {
    return JSON.parse(decoded.toString('utf8'))
  } catch (_) {
    throw rtdnError('GOOGLE_PLAY_RTDN_INVALID_JSON', 400)
  }
}

function boundedText(value, max, code) {
  const normalized = String(value || '').trim()
  if (!normalized || normalized.length > max) throw rtdnError(code, 400)
  return normalized
}

function decodeGooglePlayRtdnEnvelope(body) {
  const message = body?.message
  if (!message || typeof message !== 'object') {
    throw rtdnError('GOOGLE_PLAY_RTDN_INVALID_ENVELOPE', 400)
  }
  const messageId = boundedText(
    message.messageId || message.message_id,
    256,
    'GOOGLE_PLAY_RTDN_MESSAGE_ID_REQUIRED',
  )
  const notification = decodedBase64Json(message.data)
  const packageName = boundedText(
    notification.packageName,
    255,
    'GOOGLE_PLAY_RTDN_PACKAGE_REQUIRED',
  )
  const eventTimeMillis = Number(notification.eventTimeMillis || 0)
  const eventTime = Number.isFinite(eventTimeMillis) && eventTimeMillis > 0
    ? new Date(eventTimeMillis).toISOString()
    : (message.publishTime || null)
  const candidates = [
    ['one_time_product', notification.oneTimeProductNotification],
    ['voided_purchase', notification.voidedPurchaseNotification],
    ['pending_refund_review', notification.pendingRefundReviewNotification],
    ['subscription', notification.subscriptionNotification],
    ['test', notification.testNotification],
  ].filter(([, value]) => value && typeof value === 'object')
  if (candidates.length !== 1) {
    throw rtdnError('GOOGLE_PLAY_RTDN_NOTIFICATION_TYPE_INVALID', 400)
  }
  const [kind, payload] = candidates[0]
  const notificationType = Number.isFinite(Number(payload.notificationType))
    ? Math.trunc(Number(payload.notificationType))
    : null
  const purchaseToken = String(payload.purchaseToken || '').trim()
  const orderId = String(payload.orderId || '').trim()
  const productId = String(payload.sku || payload.productId || '').trim()
  if (purchaseToken.length > 4096 || orderId.length > 512 || productId.length > 512) {
    throw rtdnError('GOOGLE_PLAY_RTDN_FIELD_TOO_LONG', 400)
  }
  if (['one_time_product', 'voided_purchase'].includes(kind) && !purchaseToken) {
    throw rtdnError('GOOGLE_PLAY_RTDN_PURCHASE_TOKEN_REQUIRED', 400)
  }
  return {
    messageId,
    packageName,
    eventTime,
    eventTimeMillis: Number.isFinite(eventTimeMillis) ? eventTimeMillis : 0,
    kind,
    notificationType,
    purchaseToken,
    purchaseTokenHash: purchaseToken ? sha256(purchaseToken) : null,
    orderId,
    orderIdHash: orderId ? sha256(orderId) : null,
    productId,
    payload,
  }
}

async function ensureGooglePlayRtdnTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS google_play_rtdn_events (
      message_id TEXT PRIMARY KEY,
      package_name TEXT NOT NULL,
      event_time TIMESTAMPTZ,
      notification_kind TEXT NOT NULL,
      notification_type INTEGER,
      purchase_token_hash TEXT,
      order_id_hash TEXT,
      product_id TEXT,
      status TEXT NOT NULL CHECK (status IN ('processing', 'processed', 'failed')),
      attempts INTEGER NOT NULL DEFAULT 1,
      result JSONB NOT NULL DEFAULT '{}'::jsonb,
      last_error TEXT,
      received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      processed_at TIMESTAMPTZ
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_google_play_rtdn_status_updated
    ON google_play_rtdn_events(status, updated_at DESC)
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_google_play_rtdn_purchase_hash
    ON google_play_rtdn_events(purchase_token_hash)
    WHERE purchase_token_hash IS NOT NULL
  `)
}

async function claimGooglePlayRtdnEvent(pool, event) {
  const inserted = await pool.query(
    `INSERT INTO google_play_rtdn_events(
       message_id, package_name, event_time, notification_kind,
       notification_type, purchase_token_hash, order_id_hash, product_id,
       status, attempts
     ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, 'processing', 1)
     ON CONFLICT (message_id) DO NOTHING
     RETURNING message_id`,
    [
      event.messageId,
      event.packageName,
      event.eventTime,
      event.kind,
      event.notificationType,
      event.purchaseTokenHash,
      event.orderIdHash,
      event.productId || null,
    ],
  )
  if (inserted.rows.length > 0) return { claimed: true, duplicate: false }
  const retried = await pool.query(
    `UPDATE google_play_rtdn_events
     SET status = 'processing', attempts = attempts + 1, last_error = NULL, updated_at = NOW()
     WHERE message_id = $1
       AND (
         status = 'failed' OR
         (status = 'processing' AND updated_at < NOW() - ($2 * INTERVAL '1 minute'))
       )
     RETURNING message_id`,
    [event.messageId, PROCESSING_LEASE_MINUTES],
  )
  if (retried.rows.length > 0) return { claimed: true, duplicate: false }
  const existing = await pool.query(
    `SELECT status FROM google_play_rtdn_events WHERE message_id = $1 LIMIT 1`,
    [event.messageId],
  )
  return {
    claimed: false,
    duplicate: existing.rows[0]?.status === 'processed',
    inProgress: existing.rows[0]?.status === 'processing',
  }
}

async function completeGooglePlayRtdnEvent(pool, messageId, result) {
  await pool.query(
    `UPDATE google_play_rtdn_events
     SET status = 'processed', result = $2::jsonb, last_error = NULL,
         processed_at = NOW(), updated_at = NOW()
     WHERE message_id = $1`,
    [messageId, JSON.stringify(result || {})],
  )
}

async function failGooglePlayRtdnEvent(pool, messageId, error) {
  await pool.query(
    `UPDATE google_play_rtdn_events
     SET status = 'failed', last_error = $2, updated_at = NOW()
     WHERE message_id = $1`,
    [messageId, String(error?.code || error?.message || 'UNKNOWN').slice(0, 160)],
  )
}

async function verifyGooglePlayRtdnRequest(req, config, oauthClient = new OAuth2Client()) {
  if (!config.ready) throw rtdnError('GOOGLE_PLAY_RTDN_NOT_CONFIGURED', 503)
  if (config.verificationToken && !safeEqual(req.query?.token, config.verificationToken)) {
    throw rtdnError('GOOGLE_PLAY_RTDN_VERIFICATION_TOKEN_INVALID', 403)
  }
  const authorization = String(req.headers?.authorization || '')
  const match = authorization.match(/^Bearer\s+(.+)$/i)
  if (!match) throw rtdnError('GOOGLE_PLAY_RTDN_AUTH_REQUIRED', 401)
  let payload
  try {
    const ticket = await oauthClient.verifyIdToken({
      idToken: match[1],
      audience: config.audience,
    })
    payload = ticket.getPayload()
  } catch (_) {
    throw rtdnError('GOOGLE_PLAY_RTDN_AUTH_INVALID', 401)
  }
  const issuer = String(payload?.iss || '')
  const email = String(payload?.email || '').trim().toLowerCase()
  if (!['https://accounts.google.com', 'accounts.google.com'].includes(issuer) ||
      payload?.email_verified !== true ||
      !safeEqual(email, config.pushServiceAccountEmail)) {
    throw rtdnError('GOOGLE_PLAY_RTDN_CALLER_FORBIDDEN', 403)
  }
  return { email }
}

function createGooglePlayRtdnHandler({
  pool,
  packageName,
  processNotification,
  configProvider = googlePlayRtdnConfiguration,
  oauthClient,
}) {
  if (!pool || typeof processNotification !== 'function') {
    throw new Error('GOOGLE_PLAY_RTDN_HANDLER_DEPENDENCIES_REQUIRED')
  }
  return async function googlePlayRtdnHandler(req, res, next) {
    let event
    try {
      const config = configProvider()
      if (!config.enabled) throw rtdnError('GOOGLE_PLAY_RTDN_DISABLED', 503)
      await verifyGooglePlayRtdnRequest(req, config, oauthClient)
      event = decodeGooglePlayRtdnEnvelope(req.body)
      if (event.packageName !== packageName) {
        throw rtdnError('GOOGLE_PLAY_RTDN_PACKAGE_MISMATCH', 400)
      }
      const claim = await claimGooglePlayRtdnEvent(pool, event)
      if (claim.duplicate) return res.status(204).end()
      if (!claim.claimed) throw rtdnError('GOOGLE_PLAY_RTDN_ALREADY_PROCESSING', 503)
      const result = await processNotification(event, config)
      await completeGooglePlayRtdnEvent(pool, event.messageId, result)
      return res.status(204).end()
    } catch (error) {
      if (event?.messageId) await failGooglePlayRtdnEvent(pool, event.messageId, error).catch(() => {})
      return next(error)
    }
  }
}

function registerGooglePlayRtdnRoutes(app, dependencies) {
  app.post(RTDN_ROUTE, createGooglePlayRtdnHandler(dependencies))
}

module.exports = {
  RTDN_ROUTE,
  claimGooglePlayRtdnEvent,
  completeGooglePlayRtdnEvent,
  createGooglePlayRtdnHandler,
  decodeGooglePlayRtdnEnvelope,
  ensureGooglePlayRtdnTables,
  failGooglePlayRtdnEvent,
  googlePlayRtdnConfiguration,
  registerGooglePlayRtdnRoutes,
  sha256,
  verifyGooglePlayRtdnRequest,
}
