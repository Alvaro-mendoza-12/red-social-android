const bcrypt = require('bcryptjs')
const cors = require('cors')
const crypto = require('crypto')
const express = require('express')
const helmet = require('helmet')
const jwt = require('jsonwebtoken')
const { Pool } = require('pg')
const {
  ensureLocalDataTables,
  processBirthdayNotifications,
  processDueCommunityEventReminders,
  registerDataRoutes,
} = require('./data-routes')
const {
  ensurePostgrestCompatTables,
  googlePlayConfiguration,
  processGooglePlayRtdnNotification,
  reconcileGooglePlayVoidedPurchases,
  registerPostgrestCompatRoutes,
} = require('./postgrest-compat-routes')
const {
  ensureGooglePlayRtdnTables,
  registerGooglePlayRtdnRoutes,
} = require('./google-play-rtdn')
const {
  ensureLocalStorageTables,
  cleanupExpiredResumableUploads,
  registerStorageRoutes,
  signStoredObjectReference,
} = require('./storage-routes')
const { registerAiRoutes } = require('./ai-routes')
const { pushNotificationsStatus } = require('./push-notifications')
const { accountSuspendedPayload, findActiveSuspension } = require('./account-status')
const { appAdminHasCapability } = require('./admin-moderation-routes')
const { ADMIN_CAPABILITY } = require('./admin-capabilities')
const { canonicalStorageResponseMiddleware } = require('./storage-url-canonicalization')
const { collectSystemHealth, loadReleaseMetadata } = require('./system-health')
const {
  boundedInteger,
  constantTimeSecretEqual,
  isTrustedProxyAddress,
  parseCorsOrigins,
  requestIpAddress,
  stableKey,
} = require('./request-security')
const {
  createDistributedRateLimitMiddleware,
  pruneExpiredRateLimitWindows,
} = require('./distributed-rate-limit')
const {
  ensureScheduledMessageTables,
  processDueScheduledMessageJobs,
} = require('./scheduled-message-jobs')
const { processDueNotificationDigests } = require('./notification-digest-routes')
const { createRequestObservabilityMiddleware, safeErrorLogDetails } = require('./request-observability')
const { createPasswordResetMailer } = require('./password-reset-mailer')
const {
  hashPasswordRecoveryCode,
  issuePasswordRecoveryCodes,
} = require('./password-recovery-codes')
const { authenticateClientSession, isPublicAuthError } = require('./client-auth-session')
const {
  isTrustedWebSessionRequest,
  readWebSessionCookie,
  serializeWebSessionCookie,
} = require('./web-session-security')
const { withSchemaInitializationLock } = require('./schema-initializer')
const { processPendingLiveFollowerNotifications } = require('./live-follower-notifications')
const { startLiveDrawGuessReconciler } = require('./live-draw-guess-routes')
const {
  createRecoveryCodes,
  decryptSecret,
  encryptSecret,
  generateSecret,
  matchingStep,
  provisioningUri,
  recoveryHash,
} = require('./two-factor')
const {
  processApprovedCreatorPayouts,
  reconcileCreatorPayouts,
} = require('./creator-payout-routes')

const serviceName = 'conectate-local-fallback'
const version = '0.1.0'
const isProduction = String(process.env.NODE_ENV || '').trim().toLowerCase() === 'production'
const port = Number(process.env.PORT || 3001)
const jwtSecret = String(process.env.JWT_SECRET || '')
// Keep this stable across JWT rotations once factors have been enrolled.
const twoFactorEncryptionSecret = String(process.env.TOTP_ENCRYPTION_KEY || jwtSecret)
const jwtExpiresIn = process.env.JWT_EXPIRES_IN || '7d'
const jwtPreviousSecrets = String(process.env.JWT_PREVIOUS_SECRETS || '')
  .split(',')
  .map((value) => value.trim())
  .filter((value) => value.length >= 32 && value !== jwtSecret)
const authRefreshTokenTtlDays = boundedInteger(
  process.env.AUTH_REFRESH_TOKEN_TTL_DAYS,
  90,
  7,
  365,
)
const webSessionTtlDays = boundedInteger(
  process.env.WEB_SESSION_TTL_DAYS,
  30,
  1,
  90,
)
const webJwtExpiresIn = /^\d+[smhd]$/i.test(String(process.env.WEB_JWT_EXPIRES_IN || '').trim())
  ? String(process.env.WEB_JWT_EXPIRES_IN).trim()
  : '15m'
const databaseUrl = process.env.DATABASE_URL
const corsOrigin = String(process.env.CORS_ORIGIN || '*').trim()
const corsOrigins = parseCorsOrigins(corsOrigin, { production: isProduction })
const legacySupabaseImportEnabled = /^(1|true|yes|on)$/i.test(
  String(process.env.LEGACY_SUPABASE_IMPORT_ENABLED || '').trim(),
)
const supabaseUrl = legacySupabaseImportEnabled
  ? String(process.env.SUPABASE_URL || '').trim().replace(/\/+$/, '')
  : ''
const supabaseAnonKey = legacySupabaseImportEnabled
  ? String(process.env.SUPABASE_ANON_KEY || '').trim()
  : ''
const supabaseAdminKey = legacySupabaseImportEnabled
  ? String(process.env.SUPABASE_SECRET_KEY || process.env.SUPABASE_SERVICE_ROLE_KEY || '').trim()
  : ''
// Account deletion must also remove a migrated identity even after legacy
// imports are disabled. Keep these credentials isolated from login/sync paths:
// they are used only by the audited hard-delete operation below.
const externalAuthDeleteUrl = String(process.env.SUPABASE_URL || '').trim().replace(/\/+$/, '')
const externalAuthDeleteKey = String(
  process.env.SUPABASE_SECRET_KEY || process.env.SUPABASE_SERVICE_ROLE_KEY || '',
).trim()
const adminSyncToken = String(process.env.ADMIN_SYNC_TOKEN || '').trim()
const passwordResetDelivery = String(
  process.env.PASSWORD_RESET_DELIVERY || (isProduction ? 'disabled' : 'console'),
).trim().toLowerCase()
if (!['disabled', 'smtp', 'console', 'response'].includes(passwordResetDelivery)) {
  throw new Error('PASSWORD_RESET_DELIVERY must be disabled, smtp, console or response')
}
const passwordResetMailer = passwordResetDelivery === 'smtp'
  ? createPasswordResetMailer(process.env)
  : null
const storageRoot = process.env.STORAGE_ROOT || require('path').join(process.cwd(), 'storage-data')
const realtimeInternalUrl = String(process.env.REALTIME_INTERNAL_URL || '').trim()
const signalingInternalUrl = String(process.env.SIGNALING_INTERNAL_URL || '').trim()
const passwordResetTokenTtlMinutes = Math.max(
  10,
  Math.min(Number(process.env.PASSWORD_RESET_TOKEN_TTL_MINUTES || 30), 24 * 60),
)
const migrationPasswordCutoff = new Date('2026-05-25T00:00:00.000Z')

if (!databaseUrl) {
  throw new Error('DATABASE_URL is required')
}

if (jwtSecret.length < 32) {
  throw new Error('JWT_SECRET must be at least 32 characters')
}

if (isProduction && ['console', 'response'].includes(passwordResetDelivery)) {
  throw new Error('PASSWORD_RESET_DELIVERY cannot expose reset tokens in production')
}

const pool = new Pool({
  connectionString: databaseUrl,
  // Keep conservative defaults for a single mini-PC, but allow production to
  // tune concurrency without changing code. Bounds protect PostgreSQL from an
  // accidental value that would exhaust all available connections.
  max: boundedInteger(process.env.DATABASE_POOL_MAX, 10, 4, 100),
  min: boundedInteger(process.env.DATABASE_POOL_MIN, 0, 0, 20),
  idleTimeoutMillis: boundedInteger(process.env.DATABASE_POOL_IDLE_TIMEOUT_MS, 30_000, 5_000, 300_000),
  connectionTimeoutMillis: boundedInteger(process.env.DATABASE_POOL_CONNECTION_TIMEOUT_MS, 10_000, 1_000, 60_000),
  // Last-resort protection for production: an abandoned transaction must not
  // retain row locks until every API connection is exhausted.
  options: '-c statement_timeout=30000 -c lock_timeout=10000 -c idle_in_transaction_session_timeout=15000',
})

function logDatabasePoolError(error) {
  console.error('[api] database pool connection lost', {
    severity: String(error?.severity || 'ERROR'),
    ...safeErrorLogDetails(error, {
      production: isProduction,
      fallbackCode: 'UNKNOWN',
      fallbackMessage: 'Database connection error',
    }),
  })
}

// pg emits an `error` event when an idle connection is interrupted (for
// example during a controlled PostgreSQL restart). Without a listener Node
// treats it as an uncaught EventEmitter error and terminates the API process.
// The pool discards that client and establishes a fresh connection on demand.
pool.on('error', logDatabasePoolError)

const app = express()
app.disable('x-powered-by')
app.set('trust proxy', (address) => isTrustedProxyAddress(address))
app.use(helmet())
app.use(cors({
  origin: corsOrigins === '*' ? true : corsOrigins,
  credentials: true,
  exposedHeaders: [
    'x-request-id', 'server-timing', 'x-response-time', 'location',
    'tus-resumable', 'tus-version', 'tus-extension', 'tus-max-size',
    'upload-length', 'upload-offset', 'upload-expires', 'upload-object-path', 'upload-state',
  ],
}))
app.use(express.json({ limit: '1mb' }))
app.use((req, res, next) => {
  const supplied = String(req.headers['x-request-id'] || '').trim()
  const requestId = /^[a-zA-Z0-9._:-]{8,80}$/.test(supplied)
    ? supplied
    : crypto.randomUUID()
  req.requestId = requestId
  res.set('x-request-id', requestId)
  next()
})
app.use(createRequestObservabilityMiddleware({
  slowRequestMs: boundedInteger(process.env.SLOW_REQUEST_LOG_MS, 1_500, 100, 60_000),
}))
app.use(canonicalStorageResponseMiddleware)

const authIpKey = (req) => `ip:${requestIpAddress(req)}`
const authCredentialKey = (req) => {
  const email = normalizeEmail(req.body?.email || req.body?.identifier)
  return `${authIpKey(req)}:credential:${stableKey(email || 'missing')}`
}

app.use('/v1/auth/register', createDistributedRateLimitMiddleware({
  pool,
  scope: 'auth:register',
  limit: boundedInteger(process.env.AUTH_REGISTER_RATE_LIMIT_PER_HOUR, 10, 2, 100),
  windowMs: 60 * 60_000,
  code: 'AUTH_REGISTER_RATE_LIMITED',
  key: authIpKey,
  methods: ['POST'],
}))
app.use('/v1/auth/login', createDistributedRateLimitMiddleware({
  pool,
  scope: 'auth:login',
  limit: boundedInteger(process.env.AUTH_LOGIN_RATE_LIMIT_PER_15_MINUTES, 20, 5, 200),
  windowMs: 15 * 60_000,
  code: 'AUTH_LOGIN_RATE_LIMITED',
  key: authCredentialKey,
  methods: ['POST'],
}))
app.use('/v1/auth/refresh', createDistributedRateLimitMiddleware({
  pool,
  scope: 'auth:refresh',
  limit: boundedInteger(process.env.AUTH_REFRESH_RATE_LIMIT_PER_15_MINUTES, 60, 10, 300),
  windowMs: 15 * 60_000,
  code: 'AUTH_REFRESH_RATE_LIMITED',
  key: authIpKey,
  methods: ['POST'],
}))
app.use('/v1/auth/logout', createDistributedRateLimitMiddleware({
  pool,
  scope: 'auth:logout',
  limit: boundedInteger(process.env.AUTH_LOGOUT_RATE_LIMIT_PER_15_MINUTES, 60, 10, 300),
  windowMs: 15 * 60_000,
  code: 'AUTH_LOGOUT_RATE_LIMITED',
  key: authIpKey,
  methods: ['POST'],
}))
app.use('/v1/auth/2fa', createDistributedRateLimitMiddleware({
  pool,
  scope: 'auth:2fa',
  limit: boundedInteger(process.env.AUTH_2FA_RATE_LIMIT_PER_15_MINUTES, 20, 5, 100),
  windowMs: 15 * 60_000,
  code: 'AUTH_2FA_RATE_LIMITED',
  key: authCredentialKey,
  methods: ['POST'],
}))
app.use('/v1/auth/web-session', createDistributedRateLimitMiddleware({
  pool,
  scope: 'auth:web-session',
  limit: boundedInteger(process.env.WEB_SESSION_RATE_LIMIT_PER_15_MINUTES, 120, 20, 600),
  windowMs: 15 * 60_000,
  code: 'WEB_SESSION_RATE_LIMITED',
  key: authIpKey,
  methods: ['POST', 'DELETE'],
}))
app.use('/v1/auth/password-reset/request', createDistributedRateLimitMiddleware({
  pool,
  scope: 'auth:password-reset-request',
  limit: boundedInteger(process.env.AUTH_PASSWORD_RESET_RATE_LIMIT_PER_15_MINUTES, 5, 2, 50),
  windowMs: 15 * 60_000,
  code: 'AUTH_PASSWORD_RESET_RATE_LIMITED',
  key: authCredentialKey,
  methods: ['POST'],
}))
app.post('/v1/auth/account-recovery/cases', createDistributedRateLimitMiddleware({
  pool,
  scope: 'auth:assisted-recovery-request',
  limit: 3,
  windowMs: 60 * 60_000,
  code: 'ACCOUNT_RECOVERY_RATE_LIMITED',
  key: authIpKey,
  methods: ['POST'],
}))
app.post('/v1/auth/account-recovery/cases/:id/confirm-contact', createDistributedRateLimitMiddleware({
  pool,
  scope: 'auth:assisted-recovery-contact',
  limit: 10,
  windowMs: 60 * 60_000,
  code: 'ACCOUNT_RECOVERY_CONTACT_RATE_LIMITED',
  key: authIpKey,
  methods: ['POST'],
}))
app.use([
  '/v1/auth/password-reset/confirm',
  '/v1/auth/password-recovery-codes/confirm',
  '/v1/auth/password-recovery-codes/rotate',
  '/v1/auth/complete-migrated-password-reset',
  '/v1/auth/activate-local-password',
], createDistributedRateLimitMiddleware({
  pool,
  scope: 'auth:sensitive-action',
  limit: boundedInteger(process.env.AUTH_SENSITIVE_RATE_LIMIT_PER_15_MINUTES, 12, 3, 100),
  windowMs: 15 * 60_000,
  code: 'AUTH_SENSITIVE_ACTION_RATE_LIMITED',
  key: authIpKey,
  methods: ['POST'],
}))
app.use([
  '/v1/auth/web-login-pairing',
  '/functions/v1/web-login-pairing',
], createDistributedRateLimitMiddleware({
  pool,
  scope: 'auth:web-login-pairing',
  limit: boundedInteger(process.env.AUTH_PAIRING_RATE_LIMIT_PER_MINUTE, 90, 10, 300),
  windowMs: 60_000,
  code: 'AUTH_PAIRING_RATE_LIMITED',
  key: authIpKey,
  methods: ['POST'],
}))
app.use([
  '/v1/storage/upload',
  '/storage/v1/object',
  '/storage/v1/upload/resumable',
], createDistributedRateLimitMiddleware({
  pool,
  scope: 'storage:upload',
  limit: boundedInteger(process.env.STORAGE_UPLOAD_RATE_LIMIT_PER_10_MINUTES, 120, 10, 1000),
  windowMs: 10 * 60_000,
  code: 'STORAGE_UPLOAD_RATE_LIMITED',
  key: authIpKey,
  methods: ['POST', 'PUT', 'PATCH', 'DELETE'],
}))

const userSelectColumns = `
  id,
  email,
  password_hash,
  password_enabled,
  display_name,
  avatar_url,
  supabase_user_id,
  sync_source,
  sync_status,
  sync_error,
  local_password_set_at,
  password_changed_by_user_at,
  supabase_synced_at,
  created_at,
  updated_at,
  last_login_at
`

function normalizeEmail(value) {
  return String(value || '').trim().toLowerCase()
}

function cleanText(value, maxLength = 160) {
  const text = String(value || '').trim()
  return text ? text.slice(0, maxLength) : ''
}

function isValidEmail(email) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
}

function sanitizeUser(row) {
  const accountCreatedAt = row.account_created_at || row.created_at
  const accountCreatedTime = accountCreatedAt ? new Date(accountCreatedAt).getTime() : 0
  const importedFromSupabase = Boolean(row.supabase_user_id || ['supabase', 'both'].includes(row.sync_source))
  const passwordChangeRequired =
    importedFromSupabase &&
    accountCreatedTime > 0 &&
    accountCreatedTime < migrationPasswordCutoff.getTime() &&
    !row.password_changed_by_user_at

  return {
    id: row.id,
    email: row.email,
    hasLocalPassword: Boolean(row.password_enabled && row.password_hash),
    displayName: row.display_name || '',
    avatarUrl: row.avatar_url || null,
    supabaseUserId: row.supabase_user_id || null,
    syncSource: row.sync_source || 'local',
    syncStatus: row.sync_status || 'local_only',
    syncError: row.sync_error || null,
    localPasswordSetAt: row.local_password_set_at || null,
    passwordChangedByUserAt: row.password_changed_by_user_at || null,
    supabaseSyncedAt: row.supabase_synced_at || null,
    accountCreatedAt,
    passwordChangeRequired,
    createdAt: accountCreatedAt,
    lastLoginAt: row.last_login_at || null,
  }
}

async function parseJsonResponse(response) {
  const text = await response.text()
  if (!text.trim()) return {}
  try {
    return JSON.parse(text)
  } catch {
    return { message: text }
  }
}

function toRemoteAuthError(response, payload) {
  const message =
    payload?.msg ||
    payload?.message ||
    payload?.error_description ||
    payload?.error ||
    `Remote auth returned ${response.status}`
  const error = new Error(String(message))
  error.status = response.status
  error.payload = payload
  return error
}

async function remoteSupabaseAuthRequest(path, options = {}) {
  const headers = {
    apikey: supabaseAdminKey,
    authorization: options.authorization || `Bearer ${supabaseAdminKey}`,
  }
  if (options.body != null) {
    headers['content-type'] = 'application/json'
  }

  const response = await fetch(`${supabaseUrl}${path}`, {
    method: options.method || 'GET',
    headers,
    body: options.body == null ? undefined : JSON.stringify(options.body),
  })
  const payload = await parseJsonResponse(response)
  if (!response.ok) {
    return { data: null, error: toRemoteAuthError(response, payload) }
  }
  return { data: payload, error: null }
}

function getSupabaseAdminClient() {
  if (!legacySupabaseImportEnabled) return null
  if (!supabaseUrl || !supabaseAdminKey) return null

  return {
    auth: {
      admin: {
        async createUser(payload) {
          const result = await remoteSupabaseAuthRequest('/auth/v1/admin/users', {
            method: 'POST',
            body: payload,
          })
          if (result.error) return result
          return { data: { user: result.data?.user || result.data }, error: null }
        },
        async listUsers({ page = 1, perPage = 100 } = {}) {
          const params = new URLSearchParams({
            page: String(page),
            per_page: String(perPage),
          })
          const result = await remoteSupabaseAuthRequest(`/auth/v1/admin/users?${params}`)
          if (result.error) return result
          return { data: { ...result.data, users: result.data?.users || [] }, error: null }
        },
        async deleteUser(userId) {
          return remoteSupabaseAuthRequest(`/auth/v1/admin/users/${encodeURIComponent(userId)}`, {
            method: 'DELETE',
          })
        },
      },
      async getUser(accessToken) {
        const result = await remoteSupabaseAuthRequest('/auth/v1/user', {
          authorization: `Bearer ${accessToken}`,
        })
        if (result.error) return result
        return { data: { user: result.data?.user || result.data }, error: null }
      },
    },
  }
}

function isSupabaseRestriction(error) {
  const text = String(error?.message || error || '').toLowerCase()
  return (
    text.includes('service for this project is restricted') ||
    text.includes('exceed_storage_size_quota') ||
    text.includes('quota') ||
    text.includes('402')
  )
}

function getSupabaseUserDisplayName(user) {
  const meta = user?.user_metadata || {}
  return cleanText(
    meta.full_name ||
      meta.display_name ||
      meta.name ||
      meta.username ||
      normalizeEmail(user?.email).split('@')[0],
    80,
  )
}

function getSupabaseUserAvatar(user) {
  const meta = user?.user_metadata || {}
  return cleanText(meta.avatar_url || meta.picture || meta.photo_url, 500) || null
}

function adminRequired(req, res, next) {
  if (adminSyncToken.length < 32) {
    return res.status(503).json({ error: 'ADMIN_SYNC_TOKEN_NOT_CONFIGURED' })
  }

  const provided = String(req.headers['x-admin-token'] || '').trim()
  if (!constantTimeSecretEqual(provided, adminSyncToken)) {
    return res.status(401).json({ error: 'ADMIN_TOKEN_REQUIRED' })
  }

  return next()
}

function isUuidText(value) {
  const text = String(value || '').trim().toLowerCase()
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(text)
}

function signUserToken(user, options = {}) {
  const sessionId = String(options.sessionId || '').trim()
  const client = cleanText(options.client || '', 40)
  const payload = {
    email: user.email,
    provider: 'local-fallback',
  }
  if (sessionId) {
    payload.sid = sessionId
  }
  if (client) {
    payload.client = client
  }

  return jwt.sign(
    payload,
    jwtSecret,
    {
      subject: user.id,
      expiresIn: options.expiresIn || jwtExpiresIn,
      issuer: serviceName,
      audience: 'conectate-clients',
    },
  )
}

function verifyLegacyUserToken(token) {
  let lastError
  for (const secret of [jwtSecret, ...jwtPreviousSecrets]) {
    try {
      return jwt.verify(token, secret, {
        issuer: serviceName,
        audience: 'conectate-clients',
      })
    } catch (error) {
      lastError = error
    }
  }
  throw lastError || new Error('INVALID_TOKEN')
}

function signAppealToken(userId) {
  return jwt.sign(
    {
      provider: 'local-fallback',
      tokenType: 'moderation-appeal',
      scope: 'moderation:appeal',
    },
    jwtSecret,
    {
      subject: String(userId || '').trim(),
      expiresIn: '14d',
      issuer: serviceName,
      audience: 'conectate-moderation-appeals',
    },
  )
}

function signMigrationPasswordToken(user) {
  return jwt.sign(
    {
      email: user.email,
      provider: 'local-fallback',
      purpose: 'migrated-password-reset',
    },
    jwtSecret,
    {
      subject: user.id,
      expiresIn: '15m',
      issuer: serviceName,
      audience: 'conectate-migration',
    },
  )
}

function verifyMigrationPasswordToken(token) {
  const payload = jwt.verify(token, jwtSecret, {
    issuer: serviceName,
    audience: 'conectate-migration',
  })
  if (payload?.purpose !== 'migrated-password-reset') {
    throw new Error('INVALID_MIGRATION_TOKEN')
  }
  return payload
}

function createPasswordResetToken() {
  return crypto.randomBytes(32).toString('hex')
}

function hashPasswordResetToken(token) {
  return crypto.createHash('sha256').update(String(token || '').trim()).digest('hex')
}

function createAuthRefreshToken() {
  return crypto.randomBytes(48).toString('base64url')
}

function hashAuthRefreshToken(token) {
  return crypto.createHash('sha256').update(String(token || '').trim()).digest('hex')
}

async function issueUserSession(user, req, options = {}) {
  const executor = options.executor || pool
  const client = cleanText(options.client || 'android', 40) || 'android'
  const refreshToken = createAuthRefreshToken()
  const { rows } = await executor.query(
    `INSERT INTO fallback_auth_sessions(
        user_id,
        refresh_token_hash,
        client,
        ip_address,
        user_agent,
        expires_at
     )
     VALUES ($1, $2, $3, $4, $5, NOW() + ($6::text || ' days')::interval)
     RETURNING id`,
    [
      user.id,
      hashAuthRefreshToken(refreshToken),
      client,
      requestIpAddress(req),
      requestUserAgent(req),
      authRefreshTokenTtlDays,
    ],
  )
  const sessionId = rows[0].id

  await executor.query(
    `UPDATE fallback_auth_sessions
     SET revoked_at = COALESCE(revoked_at, NOW()),
         updated_at = NOW()
     WHERE user_id = $1
       AND revoked_at IS NULL
       AND expires_at <= NOW()`,
    [user.id],
  )

  return {
    user,
    accessToken: signUserToken(user, { sessionId, client }),
    refreshToken,
    tokenType: 'Bearer',
    expiresIn: jwtExpiresIn,
  }
}

async function revokeUserAuthSessions(userId, executor = pool) {
  await executor.query(
    `UPDATE fallback_auth_sessions
     SET revoked_at = COALESCE(revoked_at, NOW()),
         updated_at = NOW()
     WHERE user_id = $1
       AND revoked_at IS NULL`,
    [userId],
  )
}

async function revokeUserWebSessions(userId, executor = pool) {
  await executor.query(
    `UPDATE fallback_web_sessions
     SET revoked_at = COALESCE(revoked_at, NOW()),
         updated_at = NOW()
     WHERE user_id = $1
       AND revoked_at IS NULL`,
    [userId],
  )
}

async function revokeAllUserSessions(userId, executor = pool) {
  await revokeUserAuthSessions(userId, executor)
  await revokeUserWebSessions(userId, executor)
  await executor.query(
    `UPDATE fallback_web_login_pairings
     SET status = 'rejected', updated_at = NOW()
     WHERE approved_by_user_id = $1 AND status = 'approved'`,
    [userId],
  )
}

function requestedAuthClient(req) {
  return cleanText(req.body?.client || '', 40).toLowerCase()
}

function isWebClientRequest(req) {
  return requestedAuthClient(req) === 'web'
}

function requireTrustedWebSessionRequest(req, res) {
  if (isTrustedWebSessionRequest(req, { allowedOrigins: corsOrigins, production: isProduction })) {
    return true
  }
  res.status(403).json({ error: 'UNTRUSTED_WEB_SESSION_REQUEST' })
  return false
}

function setWebSessionCookie(res, refreshToken) {
  res.append('Set-Cookie', serializeWebSessionCookie(refreshToken, {
    production: isProduction,
    maxAgeSeconds: webSessionTtlDays * 24 * 60 * 60,
  }))
}

function clearWebSessionCookie(res) {
  res.append('Set-Cookie', serializeWebSessionCookie('', {
    production: isProduction,
    maxAgeSeconds: 0,
  }))
}

async function issueWebSession(user, req, res, options = {}) {
  const refreshToken = createAuthRefreshToken()
  const refreshTokenHash = hashAuthRefreshToken(refreshToken)
  const requestedSessionId = isUuidText(options.sessionId) ? String(options.sessionId) : ''
  let row

  if (requestedSessionId) {
    const updated = await pool.query(
      `UPDATE fallback_web_sessions
       SET refresh_token_hash = $3,
           expires_at = NOW() + ($4::text || ' days')::interval,
           last_seen_at = NOW(),
           ip_address = COALESCE($5, ip_address),
           user_agent = COALESCE($6, user_agent),
           updated_at = NOW()
       WHERE id = $1
         AND user_id = $2
         AND revoked_at IS NULL
       RETURNING *`,
      [
        requestedSessionId,
        user.id,
        refreshTokenHash,
        webSessionTtlDays,
        requestIpAddress(req),
        requestUserAgent(req),
      ],
    )
    row = updated.rows[0]
  }

  if (!row) {
    const inserted = await pool.query(
      `INSERT INTO fallback_web_sessions(
          user_id,
          pairing_id,
          device_label,
          ip_address,
          user_agent,
          refresh_token_hash,
          expires_at
       )
       VALUES ($1, $2, $3, $4, $5, $6, NOW() + ($7::text || ' days')::interval)
       RETURNING *`,
      [
        user.id,
        isUuidText(options.pairingId) ? options.pairingId : null,
        cleanText(options.deviceLabel || req.body?.deviceLabel || 'Novvin Web', 120),
        requestIpAddress(req),
        requestUserAgent(req),
        refreshTokenHash,
        webSessionTtlDays,
      ],
    )
    row = inserted.rows[0]
  }

  setWebSessionCookie(res, refreshToken)
  return {
    user,
    accessToken: signUserToken(user, {
      sessionId: row.id,
      client: 'web',
      expiresIn: webJwtExpiresIn,
    }),
    tokenType: 'Bearer',
    expiresIn: webJwtExpiresIn,
    webSession: serializeWebSession(row),
  }
}

async function hasEnabledSecondFactor(userId, executor = pool) {
  const { rows } = await executor.query(
    `SELECT 1 FROM fallback_totp_factors
     WHERE user_id = $1 AND enabled_at IS NOT NULL
     LIMIT 1`,
    [userId],
  )
  return Boolean(rows[0])
}

async function verifySecondFactor(userId, totpCode, backupCode) {
  const client = await pool.connect()
  try {
    await client.query('BEGIN')
    const { rows } = await client.query(
      `SELECT secret_ciphertext, enabled_at, last_used_step, failed_attempts, locked_until
       FROM fallback_totp_factors WHERE user_id = $1 FOR UPDATE`,
      [userId],
    )
    const factor = rows[0]
    if (!factor?.enabled_at) {
      await client.query('COMMIT')
      return { required: false, verified: true }
    }
    if (!totpCode && !backupCode) {
      await client.query('COMMIT')
      return { required: true, verified: false, error: 'TWO_FACTOR_REQUIRED' }
    }
    if (factor.locked_until && new Date(factor.locked_until).getTime() > Date.now()) {
      await client.query('COMMIT')
      return { required: true, verified: false, error: 'TWO_FACTOR_LOCKED' }
    }
    const lastStep = Number(factor.last_used_step ?? -1)
    let acceptedStep = null
    let recoveryUsed = false
    if (totpCode) {
      acceptedStep = matchingStep(
        decryptSecret(factor.secret_ciphertext, twoFactorEncryptionSecret),
        totpCode, Date.now(), lastStep,
      )
    } else if (backupCode) {
      const hash = recoveryHash(backupCode, twoFactorEncryptionSecret)
      if (hash) {
        const used = await client.query(
          `UPDATE fallback_totp_recovery_codes SET used_at = NOW()
           WHERE user_id = $1 AND code_hash = $2 AND used_at IS NULL
           RETURNING code_hash`,
          [userId, hash],
        )
        recoveryUsed = Boolean(used.rows[0])
      }
    }
    if (acceptedStep === null && !recoveryUsed) {
      await client.query(
        `UPDATE fallback_totp_factors
         SET failed_attempts = CASE WHEN locked_until IS NOT NULL AND locked_until <= NOW()
               THEN 1 ELSE failed_attempts + 1 END,
             locked_until = CASE WHEN locked_until IS NOT NULL AND locked_until <= NOW()
               THEN NULL WHEN failed_attempts + 1 >= 5
               THEN NOW() + INTERVAL '15 minutes' ELSE NULL END,
             updated_at = NOW()
         WHERE user_id = $1`,
        [userId],
      )
      await client.query('COMMIT')
      return { required: true, verified: false, error: 'TWO_FACTOR_INVALID' }
    }
    await client.query(
      `UPDATE fallback_totp_factors
       SET last_used_step = CASE WHEN $2::bigint IS NULL THEN last_used_step ELSE $2 END,
           failed_attempts = 0, locked_until = NULL, updated_at = NOW()
       WHERE user_id = $1`,
      [userId, acceptedStep],
    )
    await client.query('COMMIT')
    return { required: true, verified: true, recoveryUsed }
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {})
    throw error
  } finally {
    client.release()
  }
}

async function issueSessionForRequest(user, req, res, options = {}) {
  if (!options.twoFactorVerified && !req.auth?.sid && await hasEnabledSecondFactor(user.id)) {
    const error = new Error('TWO_FACTOR_REQUIRED')
    error.status = 401
    error.code = 'TWO_FACTOR_REQUIRED'
    error.expose = true
    throw error
  }
  const webClient = options.webClient !== undefined
    ? Boolean(options.webClient)
    : isWebClientRequest(req) || req.auth?.client === 'web'
  return webClient
    ? issueWebSession(user, req, res, options)
    : issueUserSession(user, req, options)
}

function shouldExposePasswordResetToken() {
  return passwordResetDelivery === 'response'
}

async function deliverPasswordResetToken(user, token) {
  if (passwordResetDelivery === 'disabled') {
    return { delivery: 'disabled' }
  }

  if (passwordResetDelivery === 'response') {
    return { delivery: 'response', resetToken: token }
  }

  if (passwordResetMailer) {
    await passwordResetMailer.deliver(user.email, token, passwordResetTokenTtlMinutes)
    return { delivery: 'email' }
  }

  console.warn(
    `[auth] password reset token for ${user.email}: ${token} (expires in ${passwordResetTokenTtlMinutes} minutes)`,
  )
  return { delivery: 'console' }
}

function requestUserAgent(req) {
  return cleanText(req.headers['user-agent'] || '', 500) || null
}

async function touchWebSessionForAuth(auth, req) {
  const sessionId = String(auth?.sid || '').trim()
  // Legacy JWTs without a session cannot be revoked after password reset.
  if (!sessionId) return false
  if (!isUuidText(sessionId) || !isUuidText(auth?.sub)) return false

  if (String(auth?.client || '').trim().toLowerCase() !== 'web') {
    const { rows } = await pool.query(
      `UPDATE fallback_auth_sessions
       SET last_used_at = NOW(),
           ip_address = COALESCE($3, ip_address),
           user_agent = COALESCE($4, user_agent),
           updated_at = NOW()
       WHERE id = $1
         AND user_id = $2
         AND revoked_at IS NULL
         AND expires_at > NOW()
       RETURNING id`,
      [sessionId, auth.sub, requestIpAddress(req), requestUserAgent(req)],
    )
    return Boolean(rows[0])
  }

  const { rows } = await pool.query(
    `UPDATE fallback_web_sessions
     SET last_seen_at = NOW(),
         ip_address = COALESCE($3, ip_address),
         user_agent = COALESCE($4, user_agent),
         updated_at = NOW()
     WHERE id = $1
       AND user_id = $2
       AND revoked_at IS NULL
       AND (expires_at IS NULL OR expires_at > NOW())
     RETURNING id`,
    [sessionId, auth.sub, requestIpAddress(req), requestUserAgent(req)],
  )

  return Boolean(rows[0])
}

function bearerTokenFromRequest(req) {
  const authorization = String(req.headers.authorization || '')
  return authorization.replace(/^Bearer\s+/i, '').trim()
}

async function authenticateClientRequest(req) {
  return authenticateClientSession({
    token: bearerTokenFromRequest(req),
    verifyToken: (token) => jwt.verify(token, jwtSecret, {
      issuer: serviceName,
      audience: 'conectate-clients',
    }),
    userExists: async (userId) => {
      const { rows } = await pool.query(
        `SELECT 1
         FROM fallback_users
         WHERE id = $1::uuid
         LIMIT 1`,
        [userId],
      )
      return Boolean(rows[0])
    },
    touchSession: (auth) => touchWebSessionForAuth(auth, req),
  })
}

async function authRequired(req, res, next) {
  try {
    req.auth = await authenticateClientRequest(req)
    const suspension = await findActiveSuspension(pool, req.auth.sub)
    if (suspension) {
      return res.status(403).json({
        ...accountSuspendedPayload(suspension),
        appealToken: signAppealToken(req.auth.sub),
        appealTokenExpiresIn: '14d',
      })
    }
    return next()
  } catch (error) {
    if (isPublicAuthError(error)) {
      return res.status(error.status).json({ error: error.code })
    }
    return next(error)
  }
}

async function authenticatedUserHasAdminCapability(req, capability) {
  const { rows } = await pool.query(
    `SELECT id::text, supabase_user_id::text
     FROM fallback_users
     WHERE id = $1::uuid
     LIMIT 1`,
    [req.auth.sub],
  )
  const user = rows[0]
  if (!user) return false
  const ids = new Set(
    [user.id, user.supabase_user_id]
      .map((value) => String(value || '').trim().toLowerCase())
      .filter(Boolean),
  )
  return appAdminHasCapability(pool, {
    localUserId: String(user.id || '').toLowerCase(),
    preferredUserId: String(user.supabase_user_id || user.id || '').toLowerCase(),
    ids,
    values: [...ids],
  }, capability)
}

async function appealAuthRequired(req, res, next) {
  const authorization = String(req.headers.authorization || '')
  const token = authorization.replace(/^Bearer\s+/i, '').trim()
  if (!token) return res.status(401).json({ error: 'APPEAL_AUTH_REQUIRED' })
  try {
    req.auth = jwt.verify(token, jwtSecret, {
      issuer: serviceName,
      audience: 'conectate-moderation-appeals',
    })
    if (req.auth.tokenType !== 'moderation-appeal' || req.auth.scope !== 'moderation:appeal') {
      return res.status(403).json({ error: 'APPEAL_TOKEN_SCOPE_INVALID' })
    }
    const { rows } = await pool.query(
      `SELECT 1 FROM fallback_users WHERE id = $1::uuid LIMIT 1`,
      [req.auth.sub],
    )
    if (!rows[0]) return res.status(401).json({ error: 'ACCOUNT_DELETED' })
    return next()
  } catch (error) {
    if (error?.name === 'TokenExpiredError') {
      return res.status(401).json({ error: 'APPEAL_TOKEN_EXPIRED' })
    }
    if (error?.name && String(error.name).includes('JsonWebToken')) {
      return res.status(401).json({ error: 'APPEAL_TOKEN_INVALID' })
    }
    return next(error)
  }
}

function serializeWebSession(row) {
  return {
    sessionId: row.id,
    deviceLabel: row.device_label || 'Conectate Web',
    ipAddress: row.ip_address || null,
    userAgent: row.user_agent || null,
    createdAt: row.created_at || null,
    lastSeenAt: row.last_seen_at || null,
    expiresAt: row.expires_at || null,
    revokedAt: row.revoked_at || null,
  }
}

async function authOptional(req, res, next) {
  const token = bearerTokenFromRequest(req)

  if (!token) {
    return next()
  }

  try {
    req.auth = await authenticateClientRequest(req)
    return next()
  } catch (error) {
    if (isPublicAuthError(error)) {
      return res.status(error.status).json({ error: error.code })
    }
    return next(error)
  }
}

function authPayloadFromRequest(req) {
  const authorization = String(req.headers.authorization || '')
  const token = authorization.replace(/^Bearer\s+/i, '').trim()

  if (!token) {
    const error = new Error('AUTH_REQUIRED')
    error.status = 401
    throw error
  }

  try {
    return jwt.verify(token, jwtSecret, {
      issuer: serviceName,
      audience: 'conectate-clients',
    })
  } catch {
    const error = new Error('INVALID_TOKEN')
    error.status = 401
    throw error
  }
}

async function authenticatedUserFromRequest(req) {
  const auth = authPayloadFromRequest(req)
  const { rows } = await pool.query(
    `SELECT ${userSelectColumns}
     FROM fallback_users
     WHERE id = $1
     LIMIT 1`,
    [auth.sub],
  )

  if (!rows[0]) {
    const error = new Error('USER_NOT_FOUND')
    error.status = 404
    throw error
  }

  const hydrated = await hydrateUserRow(rows[0])
  return { row: hydrated, user: sanitizeUser(hydrated) }
}

async function recordAudit(eventName, userId, metadata = {}, executor = pool) {
  try {
    await executor.query(
      'INSERT INTO fallback_audit_events(event_name, user_id, metadata) VALUES ($1, $2, $3)',
      [eventName, userId || null, metadata],
    )
  } catch (error) {
    console.warn('[audit] failed', safeErrorLogDetails(error, { production: isProduction }))
  }
}

function isMissingAuthUsersTable(error) {
  return ['42P01', '3F000'].includes(String(error?.code || ''))
}

let authUsersTableAvailable
let authUsersTableProbe

async function hasRestoredAuthUsersTable() {
  if (typeof authUsersTableAvailable === 'boolean') return authUsersTableAvailable
  if (authUsersTableProbe) return authUsersTableProbe

  authUsersTableProbe = pool.query(
    "SELECT to_regclass('auth.users') IS NOT NULL AS available",
  ).then(({ rows }) => {
    authUsersTableAvailable = rows[0]?.available === true
    return authUsersTableAvailable
  }).catch((error) => {
    console.warn('[auth] could not inspect restored auth schema', safeErrorLogDetails(error, { production: isProduction }))
    return false
  }).finally(() => {
    authUsersTableProbe = null
  })

  return authUsersTableProbe
}

async function hydrateUserRow(row) {
  if (!row) return null
  if (!row.supabase_user_id) {
    return { ...row, account_created_at: row.created_at }
  }
  if (!(await hasRestoredAuthUsersTable())) {
    return { ...row, account_created_at: row.created_at }
  }

  try {
    const { rows } = await pool.query(
      'SELECT created_at FROM auth.users WHERE id = $1::uuid LIMIT 1',
      [row.supabase_user_id],
    )
    return {
      ...row,
      account_created_at: rows[0]?.created_at || row.created_at,
    }
  } catch (error) {
    if (!isMissingAuthUsersTable(error)) {
      console.warn('[auth] could not read restored auth.users metadata', safeErrorLogDetails(error, { production: isProduction }))
    }
    return { ...row, account_created_at: row.created_at }
  }
}

async function getRestoredAuthUserForFallback(row) {
  if (!row?.supabase_user_id && !row?.email) return null
  if (!(await hasRestoredAuthUsersTable())) return null
  try {
    const { rows } = await pool.query(
      `SELECT id, email, encrypted_password, created_at
       FROM auth.users
       WHERE ($1::uuid IS NOT NULL AND id = $1::uuid)
          OR lower(email) = lower($2)
       ORDER BY CASE WHEN id = $1::uuid THEN 0 ELSE 1 END
       LIMIT 1`,
      [row.supabase_user_id || null, row.email],
    )
    return rows[0] || null
  } catch (error) {
    if (!isMissingAuthUsersTable(error)) {
      console.warn('[auth] could not read restored auth.users password', safeErrorLogDetails(error, { production: isProduction }))
    }
    return null
  }
}

async function verifyRestoredSupabasePassword(row, password) {
  const authUser = await getRestoredAuthUserForFallback(row)
  const hash = String(authUser?.encrypted_password || '').trim()
  if (!hash) return false

  try {
    return await bcrypt.compare(password, hash)
  } catch (error) {
    console.warn('[auth] restored password hash could not be checked', safeErrorLogDetails(error, { production: isProduction }))
    return false
  }
}

async function ensureAuthMetadataColumns(executor = pool) {
  await executor.query(
    'ALTER TABLE fallback_users ADD COLUMN IF NOT EXISTS password_changed_by_user_at TIMESTAMPTZ',
  )
  await executor.query(`
    CREATE TABLE IF NOT EXISTS fallback_totp_factors (
      user_id UUID PRIMARY KEY REFERENCES fallback_users(id) ON DELETE CASCADE,
      secret_ciphertext TEXT,
      pending_ciphertext TEXT,
      enabled_at TIMESTAMPTZ,
      last_used_step BIGINT NOT NULL DEFAULT -1,
      failed_attempts INTEGER NOT NULL DEFAULT 0,
      locked_until TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await executor.query(`
    CREATE TABLE IF NOT EXISTS fallback_totp_recovery_codes (
      user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      code_hash TEXT NOT NULL,
      used_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (user_id, code_hash)
    )
  `)
  await executor.query(`CREATE INDEX IF NOT EXISTS idx_fallback_totp_recovery_unused
    ON fallback_totp_recovery_codes(user_id) WHERE used_at IS NULL`)
  await executor.query(`
    CREATE TABLE IF NOT EXISTS fallback_password_recovery_codes (
      user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      code_hash TEXT NOT NULL,
      used_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (user_id, code_hash)
    )
  `)
  await executor.query(`CREATE INDEX IF NOT EXISTS idx_fallback_password_recovery_unused
    ON fallback_password_recovery_codes(user_id) WHERE used_at IS NULL`)
  await executor.query(`
    CREATE TABLE IF NOT EXISTS fallback_profile_login_emails (
      user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      email TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (user_id, email)
    )
  `)
  await executor.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_profile_login_emails_unique
    ON fallback_profile_login_emails(lower(email))
  `)
  await executor.query(`
    CREATE TABLE IF NOT EXISTS fallback_password_reset_tokens (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      token_hash TEXT NOT NULL UNIQUE,
      expires_at TIMESTAMPTZ NOT NULL,
      consumed_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await executor.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_password_reset_tokens_user
    ON fallback_password_reset_tokens(user_id, created_at DESC)
  `)
  await executor.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_password_reset_tokens_active
    ON fallback_password_reset_tokens(expires_at)
    WHERE consumed_at IS NULL
  `)
  await executor.query(`
    CREATE TABLE IF NOT EXISTS fallback_account_recovery_cases (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      identifier TEXT NOT NULL,
      alternate_contact TEXT NOT NULL,
      description TEXT NOT NULL,
      status TEXT NOT NULL DEFAULT 'pending',
      user_id UUID REFERENCES fallback_users(id) ON DELETE SET NULL,
      reviewed_by TEXT,
      verification_ref TEXT,
      two_factor_reset BOOLEAN NOT NULL DEFAULT FALSE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      reviewed_at TIMESTAMPTZ
    )
  `)
  await executor.query(`
    ALTER TABLE fallback_account_recovery_cases
      ADD COLUMN IF NOT EXISTS contact_type TEXT NOT NULL DEFAULT 'other',
      ADD COLUMN IF NOT EXISTS profile_hint TEXT,
      ADD COLUMN IF NOT EXISTS candidate_user_id UUID REFERENCES fallback_users(id) ON DELETE SET NULL,
      ADD COLUMN IF NOT EXISTS contact_code_hash TEXT,
      ADD COLUMN IF NOT EXISTS contact_code_expires_at TIMESTAMPTZ,
      ADD COLUMN IF NOT EXISTS contact_code_attempts INTEGER NOT NULL DEFAULT 0,
      ADD COLUMN IF NOT EXISTS contact_verified_at TIMESTAMPTZ
  `)
  await executor.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_account_recovery_cases_pending
    ON fallback_account_recovery_cases(created_at DESC) WHERE status = 'pending'
  `)
  await executor.query(`
    ALTER TABLE fallback_password_reset_tokens
    ADD COLUMN IF NOT EXISTS recovery_case_id UUID
    REFERENCES fallback_account_recovery_cases(id) ON DELETE SET NULL
  `)
  await executor.query(`
    CREATE TABLE IF NOT EXISTS fallback_auth_sessions (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      refresh_token_hash TEXT NOT NULL UNIQUE,
      client TEXT NOT NULL DEFAULT 'android',
      ip_address TEXT,
      user_agent TEXT,
      expires_at TIMESTAMPTZ NOT NULL,
      last_used_at TIMESTAMPTZ,
      revoked_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await executor.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_auth_sessions_user_active
    ON fallback_auth_sessions(user_id, expires_at DESC)
    WHERE revoked_at IS NULL
  `)
  await executor.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_auth_sessions_expiry
    ON fallback_auth_sessions(expires_at)
  `)
  await executor.query(`
    CREATE TABLE IF NOT EXISTS fallback_web_login_pairings (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      nonce TEXT NOT NULL,
      device_label TEXT,
      status TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'approved', 'rejected', 'consumed', 'expired')),
      approved_by_user_id UUID REFERENCES fallback_users(id) ON DELETE SET NULL,
      approved_by_email TEXT,
      expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '5 minutes'),
      approved_at TIMESTAMPTZ,
      rejected_at TIMESTAMPTZ,
      consumed_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await executor.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_web_login_pairings_status
    ON fallback_web_login_pairings(status, expires_at)
  `)
  await executor.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_web_login_pairings_created
    ON fallback_web_login_pairings(created_at DESC)
  `)
  await executor.query(`
    CREATE TABLE IF NOT EXISTS fallback_web_sessions (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      pairing_id UUID REFERENCES fallback_web_login_pairings(id) ON DELETE SET NULL,
      device_label TEXT,
      ip_address TEXT,
      user_agent TEXT,
      refresh_token_hash TEXT,
      expires_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      revoked_at TIMESTAMPTZ,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await executor.query(`
    ALTER TABLE fallback_web_sessions
    ADD COLUMN IF NOT EXISTS refresh_token_hash TEXT
  `)
  await executor.query(`
    ALTER TABLE fallback_web_sessions
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ
  `)
  await executor.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_web_sessions_user_active
    ON fallback_web_sessions(user_id, last_seen_at DESC)
    WHERE revoked_at IS NULL
  `)
  await executor.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_web_sessions_pairing
    ON fallback_web_sessions(pairing_id)
  `)
  await executor.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_web_sessions_refresh_token
    ON fallback_web_sessions(refresh_token_hash)
    WHERE refresh_token_hash IS NOT NULL
  `)
  await executor.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_web_sessions_expiry
    ON fallback_web_sessions(expires_at)
    WHERE revoked_at IS NULL AND expires_at IS NOT NULL
  `)
  await executor.query(`
    CREATE TABLE IF NOT EXISTS fallback_rate_limit_windows (
      scope TEXT NOT NULL,
      key_hash CHAR(64) NOT NULL,
      window_started_at TIMESTAMPTZ NOT NULL,
      request_count BIGINT NOT NULL DEFAULT 0 CHECK (request_count >= 0),
      expires_at TIMESTAMPTZ NOT NULL,
      PRIMARY KEY (scope, key_hash)
    )
  `)
  await executor.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_rate_limit_windows_expiry
    ON fallback_rate_limit_windows(expires_at)
  `)
}

async function syncLocalUserToSupabase(user, password) {
  const supabaseAdmin = getSupabaseAdminClient()
  if (!supabaseAdmin) {
    return { attempted: false, ok: false, error: 'SUPABASE_ADMIN_NOT_CONFIGURED' }
  }

  const { data, error } = await supabaseAdmin.auth.admin.createUser({
    email: user.email,
    password,
    email_confirm: true,
    user_metadata: {
      display_name: user.displayName,
      local_fallback_id: user.id,
    },
  })

  if (error) {
    return {
      attempted: true,
      ok: false,
      error: isSupabaseRestriction(error) ? 'SUPABASE_RESTRICTED' : error.message,
    }
  }

  const supabaseUserId = data?.user?.id || null
  if (supabaseUserId) {
    const { rows } = await pool.query(
      `UPDATE fallback_users
       SET supabase_user_id = $2,
           sync_source = 'both',
           sync_status = 'synced',
           sync_error = NULL,
           supabase_synced_at = NOW()
       WHERE id = $1
       RETURNING ${userSelectColumns}`,
      [user.id, supabaseUserId],
    )
    return { attempted: true, ok: true, user: sanitizeUser(rows[0]) }
  }

  return { attempted: true, ok: false, error: 'SUPABASE_USER_ID_MISSING' }
}

async function upsertImportedSupabaseUser(supabaseUser) {
  const email = normalizeEmail(supabaseUser.email)
  if (!isValidEmail(email)) return null

  const normalizedSupabaseId = String(supabaseUser.id || '').trim().toLowerCase()
  if (normalizedSupabaseId) {
    const { rows: deletedRows } = await pool.query(
      `SELECT 1 FROM fallback_deleted_identities WHERE lower(user_id) = $1 LIMIT 1`,
      [normalizedSupabaseId],
    )
    if (deletedRows[0]) return null
  }

  const displayName = getSupabaseUserDisplayName(supabaseUser)
  const avatarUrl = getSupabaseUserAvatar(supabaseUser)

  const { rows } = await pool.query(
    `INSERT INTO fallback_users(
        email,
        password_hash,
        password_enabled,
        display_name,
        avatar_url,
        supabase_user_id,
        sync_source,
        sync_status,
        supabase_synced_at
     )
     VALUES ($1, NULL, FALSE, $2, $3, $4, 'supabase', 'needs_local_password', NOW())
     ON CONFLICT (email)
     DO UPDATE SET
        supabase_user_id = EXCLUDED.supabase_user_id,
        display_name = CASE
            WHEN fallback_users.display_name IS NULL OR fallback_users.display_name = ''
            THEN EXCLUDED.display_name
            ELSE fallback_users.display_name
        END,
        avatar_url = COALESCE(fallback_users.avatar_url, EXCLUDED.avatar_url),
        password_enabled = CASE
            WHEN fallback_users.password_hash IS NULL THEN FALSE
            ELSE fallback_users.password_enabled
        END,
        sync_source = CASE
            WHEN fallback_users.password_hash IS NULL THEN 'supabase'
            ELSE 'both'
        END,
        sync_status = CASE
            WHEN fallback_users.password_hash IS NULL THEN 'needs_local_password'
            ELSE 'synced'
        END,
        sync_error = NULL,
        supabase_synced_at = NOW()
     RETURNING ${userSelectColumns}`,
    [email, displayName, avatarUrl, supabaseUser.id],
  )

  return sanitizeUser(rows[0])
}

function createWebLoginQrUrl(pairId, nonce) {
  const params = new URLSearchParams({
    pairId: String(pairId || ''),
    nonce: String(nonce || ''),
  })
  return `conectate://web-login?${params.toString()}`
}

function isPairingExpired(row) {
  if (!row || row.status !== 'pending') return false
  const expiresAt = new Date(row.expires_at).getTime()
  return Number.isFinite(expiresAt) && expiresAt <= Date.now()
}

function serializePairing(row, extra = {}) {
  const status = isPairingExpired(row) ? 'expired' : row.status
  return {
    pairId: row.id,
    nonce: row.nonce,
    status,
    deviceLabel: row.device_label || null,
    approvedByUserId: row.approved_by_user_id || null,
    approvedByEmail: row.approved_by_email || null,
    expiresAt: row.expires_at || null,
    approvedAt: row.approved_at || null,
    rejectedAt: row.rejected_at || null,
    consumedAt: row.consumed_at || null,
    qrUrl: createWebLoginQrUrl(row.id, row.nonce),
    ...extra,
  }
}

async function markExpiredPairingIfNeeded(row) {
  if (!isPairingExpired(row)) return row
  const { rows } = await pool.query(
    `UPDATE fallback_web_login_pairings
     SET status = 'expired',
         updated_at = NOW()
     WHERE id = $1
       AND status = 'pending'
     RETURNING *`,
    [row.id],
  )
  return rows[0] || { ...row, status: 'expired' }
}

async function findPairing(pairId, nonce) {
  const cleanPairId = String(pairId || '').trim()
  const cleanNonce = String(nonce || '').trim()
  if (!cleanPairId || !cleanNonce) {
    const error = new Error('PAIR_ID_AND_NONCE_REQUIRED')
    error.status = 400
    throw error
  }

  const { rows } = await pool.query(
    `SELECT *
     FROM fallback_web_login_pairings
     WHERE id = $1
       AND nonce = $2
     LIMIT 1`,
    [cleanPairId, cleanNonce],
  )

  if (!rows[0]) {
    const error = new Error('PAIRING_NOT_FOUND')
    error.status = 404
    throw error
  }

  return markExpiredPairingIfNeeded(rows[0])
}

function assertPairingPending(row) {
  if (row.status === 'expired' || isPairingExpired(row)) {
    const error = new Error('PAIRING_EXPIRED')
    error.status = 410
    throw error
  }
  if (row.status !== 'pending') {
    const error = new Error(`PAIRING_${String(row.status || 'INVALID').toUpperCase()}`)
    error.status = 409
    throw error
  }
}

app.get('/', (_req, res) => {
  res.json({ ok: true, service: serviceName, version })
})

app.get('/health', (_req, res) => {
  res.json({
    ok: true,
    service: serviceName,
    version,
    time: new Date().toISOString(),
    pushNotifications: pushNotificationsStatus(),
  })
})

app.get('/ready', async (_req, res, next) => {
  try {
    const { rows } = await pool.query('SELECT NOW() AS now')
    const release = await loadReleaseMetadata()
    res.json({
      ok: true,
      service: serviceName,
      version,
      release,
      capabilities: [
        'admin_users_v1',
        'hard_delete_suspended_users_v1',
        'image_variants_v1',
        'profile_ranking_v1',
        'system_health_v1',
        'feed_modes_v1',
        'saved_collections_v1',
        'moderation_appeals_v1',
        'notification_digest_v1',
        'community_roles_v1',
        'community_channels_v1',
        'community_member_profiles_v1',
        'community_events_v1',
        'voice_room_stage_v1',
        'anonymous_bottles_v1',
        'community_music_v1',
        'moment_music_v1',
        'moment_music_aac_lc_v1',
        'image_moments_exact_6s_v1',
        'video_sound_identity_v1',
        'video_feed_session_v1',
        'video_feed_live_v1',
        'message_markers_v1',
        'video_watch_later_v1',
        'video_collections_v1',
      ],
      databaseTime: rows[0].now,
      pushNotifications: pushNotificationsStatus(),
    })
  } catch (error) {
    next(error)
  }
})

app.get('/v1/system/health', authRequired, async (req, res, next) => {
  try {
    if (!(await authenticatedUserHasAdminCapability(req, ADMIN_CAPABILITY.SYSTEM_READ))) {
      return res.status(403).json({
        error: 'ADMIN_CAPABILITY_REQUIRED',
        capability: ADMIN_CAPABILITY.SYSTEM_READ,
      })
    }
    res.json(await collectSystemHealth({
      pool,
      storageRoot,
      realtimeUrl: realtimeInternalUrl,
      signalingUrl: signalingInternalUrl,
      service: serviceName,
      version,
    }))
  } catch (error) {
    next(error)
  }
})

app.get('/v1/upstream/supabase', authRequired, async (req, res, next) => {
  try {
    if (!(await authenticatedUserHasAdminCapability(req, ADMIN_CAPABILITY.SYSTEM_READ))) {
      return res.status(403).json({
        error: 'ADMIN_CAPABILITY_REQUIRED',
        capability: ADMIN_CAPABILITY.SYSTEM_READ,
      })
    }
  if (!legacySupabaseImportEnabled) {
    return res.json({
      configured: false,
      ok: false,
      restricted: false,
      legacyImportEnabled: false,
    })
  }

  if (!supabaseUrl || !supabaseAnonKey) {
    return res.json({ configured: false, ok: false, restricted: false, legacyImportEnabled: true })
  }

  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 8_000)

  try {
    const response = await fetch(`${supabaseUrl}/auth/v1/settings`, {
      headers: {
        apikey: supabaseAnonKey,
        authorization: `Bearer ${supabaseAnonKey}`,
      },
      signal: controller.signal,
    })
    const text = await response.text()
    const lower = text.toLowerCase()
    const restricted =
      response.status === 402 ||
      lower.includes('service for this project is restricted') ||
      lower.includes('exceed_storage_size_quota') ||
      lower.includes('quota')

    return res.json({
      configured: true,
      ok: response.ok,
      status: response.status,
      restricted,
      summary: text.slice(0, 240),
    })
  } catch (error) {
    return res.status(200).json({
      configured: true,
      ok: false,
      restricted: false,
      error: error.name === 'AbortError' ? 'TIMEOUT' : 'UPSTREAM_ERROR',
    })
  } finally {
    clearTimeout(timeout)
  }
  } catch (error) {
    next(error)
  }
})

app.get('/v1/admin/sync/status', adminRequired, async (_req, res, next) => {
  try {
    const { rows } = await pool.query(
      `SELECT sync_status, COUNT(*)::int AS count
       FROM fallback_users
       GROUP BY sync_status
       ORDER BY sync_status`,
    )
    const jobs = await pool.query(
      `SELECT id, job_type, status, totals, error, started_at, finished_at, created_at
       FROM sync_jobs
       ORDER BY created_at DESC
       LIMIT 10`,
    )
    res.json({
      supabaseAdminConfigured: Boolean(getSupabaseAdminClient()),
      users: rows,
      recentJobs: jobs.rows,
    })
  } catch (error) {
    next(error)
  }
})

app.post('/v1/admin/sync/import-supabase-users', adminRequired, async (_req, res, next) => {
  const supabaseAdmin = getSupabaseAdminClient()
  if (!supabaseAdmin) {
    return res.status(503).json({ error: 'SUPABASE_ADMIN_NOT_CONFIGURED' })
  }

  let jobId = null
  try {
    const job = await pool.query(
      `INSERT INTO sync_jobs(job_type, status, started_at)
       VALUES ('import_supabase_users', 'running', NOW())
       RETURNING id`,
    )
    jobId = job.rows[0].id

    let page = 1
    const perPage = 1000
    let imported = 0
    let skipped = 0

    while (true) {
      const { data, error } = await supabaseAdmin.auth.admin.listUsers({ page, perPage })
      if (error) throw error

      const users = data?.users || []
      if (users.length === 0) break

      for (const user of users) {
        const importedUser = await upsertImportedSupabaseUser(user)
        if (importedUser) imported += 1
        else skipped += 1
      }

      if (users.length < perPage) break
      page += 1
    }

    const totals = { imported, skipped }
    await pool.query(
      `UPDATE sync_jobs
       SET status = 'completed', totals = $2, finished_at = NOW()
       WHERE id = $1`,
      [jobId, totals],
    )

    res.json({ ok: true, jobId, ...totals })
  } catch (error) {
    if (jobId) {
      await pool.query(
        `UPDATE sync_jobs
         SET status = 'failed', error = $2, finished_at = NOW()
         WHERE id = $1`,
        [jobId, isSupabaseRestriction(error) ? 'SUPABASE_RESTRICTED' : error.message],
      )
    }
    if (isSupabaseRestriction(error)) {
      return res.status(409).json({ error: 'SUPABASE_RESTRICTED' })
    }
    return next(error)
  }
})

app.post('/v1/admin/users/set-local-password', adminRequired, async (req, res, next) => {
  let client
  try {
    const email = normalizeEmail(req.body?.email)
    const password = String(req.body?.password || '')

    if (!isValidEmail(email) || password.length < 8) {
      return res.status(400).json({ error: 'EMAIL_AND_STRONG_PASSWORD_REQUIRED' })
    }

    client = await pool.connect()
    await client.query('BEGIN')
    const account = await client.query(
      `SELECT id FROM fallback_users WHERE email = $1 FOR UPDATE`,
      [email],
    )
    if (!account.rows[0]) {
      await client.query('ROLLBACK')
      return res.status(404).json({ error: 'USER_NOT_FOUND' })
    }

    const passwordHash = await bcrypt.hash(password, 12)
    const { rows } = await client.query(
      `UPDATE fallback_users
       SET password_hash = $2,
           password_enabled = TRUE,
           local_password_set_at = NOW(),
           password_changed_by_user_at = NULL,
           sync_status = CASE
             WHEN supabase_user_id IS NULL THEN 'local_only'
             ELSE 'synced'
           END,
           sync_source = CASE
             WHEN supabase_user_id IS NULL THEN 'local'
             ELSE 'both'
           END,
           sync_error = NULL
       WHERE email = $1
       RETURNING ${userSelectColumns}`,
      [email, passwordHash],
    )

    await client.query(
      `UPDATE fallback_password_reset_tokens SET consumed_at = NOW()
       WHERE user_id = $1 AND consumed_at IS NULL`,
      [account.rows[0].id],
    )
    await revokeAllUserSessions(account.rows[0].id, client)
    await client.query('COMMIT')
    client.release()
    client = null

    const user = sanitizeUser(await hydrateUserRow(rows[0]))
    await recordAudit('admin_set_local_password', user.id, { email })
      .catch((error) => console.error('[auth] admin password audit failed', error))
    return res.json({ ok: true, user })
  } catch (error) {
    if (client) await client.query('ROLLBACK').catch(() => {})
    return next(error)
  } finally {
    client?.release()
  }
})

async function recoveryAdminRequired(req, res, next) {
  if (req.headers['x-admin-token']) return adminRequired(req, res, next)
  try {
    req.auth = await authenticateClientRequest(req)
    if (await findActiveSuspension(pool, req.auth.sub)) {
      return res.status(403).json({ error: 'ACCOUNT_SUSPENDED' })
    }
    const capability = req.method === 'GET'
      ? ADMIN_CAPABILITY.READ : ADMIN_CAPABILITY.ACCESS_MANAGE
    if (!(await authenticatedUserHasAdminCapability(req, capability))) {
      return res.status(403).json({ error: 'ADMIN_CAPABILITY_REQUIRED', capability })
    }
    return next()
  } catch (error) {
    if (isPublicAuthError(error)) return res.status(error.status).json({ error: error.code })
    return next(error)
  }
}

app.get(['/v1/admin/account-recovery/cases', '/v1/data/admin/account-recovery/cases'], recoveryAdminRequired, async (req, res, next) => {
  try {
    const { rows } = await pool.query(
      `SELECT c.id, c.identifier, c.alternate_contact, c.description, c.status, c.user_id,
              c.reviewed_by, c.verification_ref, c.two_factor_reset, c.created_at, c.reviewed_at,
              c.contact_type, c.profile_hint,
              COALESCE(c.user_id, c.candidate_user_id) AS candidate_user_id,
              c.contact_verified_at,
              u.email AS candidate_email, u.display_name AS candidate_name,
              o.profile->>'username' AS candidate_username
       FROM fallback_account_recovery_cases c
       LEFT JOIN fallback_users u ON u.id = COALESCE(c.user_id, c.candidate_user_id)
       LEFT JOIN fallback_profile_overrides o ON o.user_id = u.id
       ORDER BY CASE WHEN c.status = 'pending' THEN 0 ELSE 1 END, c.created_at DESC
       LIMIT 100`,
    )
    res.set('Cache-Control', 'no-store')
    return res.json({ cases: rows })
  } catch (error) {
    return next(error)
  }
})

app.get(['/v1/admin/account-recovery/accounts', '/v1/data/admin/account-recovery/accounts'], recoveryAdminRequired, async (req, res, next) => {
  try {
    const query = cleanText(req.query?.q, 120)
    if (query.length < 3) {
      return res.status(400).json({ error: 'SEARCH_QUERY_REQUIRED' })
    }
    const { rows } = await pool.query(
      `SELECT u.id, u.email, u.display_name, o.profile->>'username' AS username, u.created_at
       FROM fallback_users u
       LEFT JOIN fallback_profile_overrides o ON o.user_id = u.id
       WHERE u.email ILIKE $1 OR u.display_name ILIKE $1
          OR o.profile->>'username' ILIKE $1 OR u.id::text = $2
       ORDER BY u.created_at DESC LIMIT 20`,
      [`%${query.replace(/[\\%_]/g, '\\$&')}%`, query],
    )
    res.set('Cache-Control', 'no-store')
    return res.json({ accounts: rows })
  } catch (error) {
    return next(error)
  }
})

app.post(['/v1/admin/account-recovery/cases/:id/contact-code', '/v1/data/admin/account-recovery/cases/:id/contact-code'], recoveryAdminRequired, async (req, res, next) => {
  try {
    if (!isUuidText(req.params.id)) return res.status(400).json({ error: 'CASE_REQUIRED' })
    const code = String(crypto.randomInt(100000, 1000000))
    const { rows } = await pool.query(
      `UPDATE fallback_account_recovery_cases
       SET contact_code_hash = $2, contact_code_expires_at = NOW() + INTERVAL '30 minutes',
           contact_code_attempts = 0, contact_verified_at = NULL
       WHERE id = $1 AND status = 'pending'
       RETURNING id`,
      [req.params.id, hashPasswordResetToken(code)],
    )
    if (!rows[0]) return res.status(404).json({ error: 'PENDING_RECOVERY_CASE_NOT_FOUND' })
    res.set('Cache-Control', 'no-store')
    return res.json({ ok: true, code, expiresInMinutes: 30 })
  } catch (error) { return next(error) }
})

app.post(['/v1/admin/account-recovery/cases/:id/issue-code', '/v1/data/admin/account-recovery/cases/:id/issue-code'], recoveryAdminRequired, async (req, res, next) => {
  let client
  try {
    if (!isUuidText(req.params.id) || !isUuidText(req.body?.userId)) {
      return res.status(400).json({ error: 'CASE_AND_USER_REQUIRED' })
    }
    const reviewedBy = cleanText(req.body?.reviewedBy, 120)
    const verificationRef = cleanText(req.body?.verificationRef, 240)
    const resetTwoFactor = req.body?.resetTwoFactor === true
    if (reviewedBy.length < 3 || verificationRef.length < 20) {
      return res.status(400).json({ error: 'REVIEWER_AND_VERIFICATION_REFERENCE_REQUIRED' })
    }

    client = await pool.connect()
    await client.query('BEGIN')
    const account = await client.query(
      `SELECT id FROM fallback_users WHERE id = $1 FOR UPDATE`,
      [req.body.userId],
    )
    if (!account.rows[0]) {
      await client.query('ROLLBACK')
      return res.status(404).json({ error: 'USER_NOT_FOUND' })
    }
    const recoveryCase = await client.query(
      `SELECT id, user_id, alternate_contact, contact_type FROM fallback_account_recovery_cases
       WHERE id = $1 AND status IN ('pending', 'issued') FOR UPDATE`,
      [req.params.id],
    )
    if (!recoveryCase.rows[0]) {
      await client.query('ROLLBACK')
      return res.status(404).json({ error: 'OPEN_RECOVERY_CASE_NOT_FOUND' })
    }
    // The administrator verifies account ownership before issuing one recovery
    // code. Redeeming that code at the reviewed contact completes the flow;
    // a separate six-digit contact challenge is no longer required.
    if (!['email', 'whatsapp'].includes(recoveryCase.rows[0].contact_type) ||
        !recoveryCase.rows[0].alternate_contact) {
      await client.query('ROLLBACK')
      return res.status(409).json({ error: 'RECOVERY_CONTACT_REQUIRED' })
    }
    if (recoveryCase.rows[0].user_id && recoveryCase.rows[0].user_id !== account.rows[0].id) {
      await client.query('ROLLBACK')
      return res.status(409).json({ error: 'RECOVERY_CASE_ACCOUNT_MISMATCH' })
    }

    const resetToken = crypto.randomBytes(12).toString('base64url')
    await client.query(
      `UPDATE fallback_password_reset_tokens SET consumed_at = NOW()
       WHERE user_id = $1 AND consumed_at IS NULL`,
      [account.rows[0].id],
    )
    await client.query(
      `INSERT INTO fallback_password_reset_tokens(user_id, token_hash, expires_at, recovery_case_id)
       VALUES ($1, $2, 'infinity'::timestamptz, $3)`,
      [account.rows[0].id, hashPasswordResetToken(resetToken), req.params.id],
    )
    // Keep the existing password and sessions usable until the new code is
    // actually redeemed. Issuing or reissuing a code must not lock someone out.
    await client.query(
      `UPDATE fallback_account_recovery_cases
       SET status = 'issued', user_id = $2, reviewed_by = $3,
           verification_ref = $4, two_factor_reset = $5, reviewed_at = NOW()
       WHERE id = $1`,
      [req.params.id, account.rows[0].id, reviewedBy, verificationRef, resetTwoFactor],
    )
    await client.query(
      `INSERT INTO fallback_audit_events(event_name, user_id, metadata)
       VALUES ($1, $2, $3)`,
      ['admin_account_recovery_code_issued', account.rows[0].id, {
        caseId: req.params.id, reviewedBy, verificationRef, resetTwoFactor,
      }],
    )
    await client.query('COMMIT')
    client.release()
    client = null

    res.set('Cache-Control', 'no-store')
    return res.json({ ok: true, resetToken, expiresInMinutes: null })
  } catch (error) {
    if (client) await client.query('ROLLBACK').catch(() => {})
    return next(error)
  } finally {
    client?.release()
  }
})

app.post(['/v1/admin/account-recovery/cases/:id/reject', '/v1/data/admin/account-recovery/cases/:id/reject'], recoveryAdminRequired, async (req, res, next) => {
  try {
    if (!isUuidText(req.params.id)) {
      return res.status(400).json({ error: 'CASE_REQUIRED' })
    }
    const reviewedBy = cleanText(req.body?.reviewedBy, 120)
    const verificationRef = cleanText(req.body?.verificationRef, 240)
    if (reviewedBy.length < 3 || verificationRef.length < 8) {
      return res.status(400).json({ error: 'REVIEWER_AND_REASON_REQUIRED' })
    }
    const { rows } = await pool.query(
      `UPDATE fallback_account_recovery_cases
       SET status = 'rejected', reviewed_by = $2, verification_ref = $3, reviewed_at = NOW()
       WHERE id = $1 AND status = 'pending' RETURNING id`,
      [req.params.id, reviewedBy, verificationRef],
    )
    if (!rows[0]) return res.status(404).json({ error: 'PENDING_RECOVERY_CASE_NOT_FOUND' })
    await recordAudit('admin_account_recovery_case_rejected', null, {
      caseId: req.params.id, reviewedBy, verificationRef,
    })
    res.set('Cache-Control', 'no-store')
    return res.json({ ok: true })
  } catch (error) {
    return next(error)
  }
})

app.post('/v1/auth/account-recovery/cases', async (req, res, next) => {
  try {
    const identifier = cleanText(req.body?.identifier, 160)
    const alternateContact = cleanText(req.body?.alternateContact, 240)
    const description = cleanText(req.body?.description, 1000)
    const profileHint = cleanText(req.body?.profileHint, 160)
    const contactType = ['email', 'whatsapp'].includes(req.body?.contactType)
      ? req.body.contactType : 'other'
    if (identifier.length < 3 || alternateContact.length < 5 || description.length < 20) {
      return res.status(400).json({ error: 'RECOVERY_CASE_DETAILS_REQUIRED' })
    }
    if (contactType === 'email' && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(alternateContact)) {
      return res.status(400).json({ error: 'RECOVERY_CONTACT_INVALID' })
    }
    if (contactType === 'whatsapp' && !/^\+?[0-9 ()-]{8,25}$/.test(alternateContact)) {
      return res.status(400).json({ error: 'RECOVERY_CONTACT_INVALID' })
    }
    const normalizedIdentifier = identifier.replace(/^@/, '').toLowerCase()
    const candidates = await pool.query(
      `SELECT DISTINCT u.id FROM fallback_users u
       LEFT JOIN fallback_profile_overrides o ON o.user_id = u.id
       LEFT JOIN fallback_profile_login_emails e ON e.user_id = u.id
       WHERE lower(u.email) = $1 OR lower(o.profile->>'username') = $1
          OR lower(e.email) = $1 OR u.id::text = $1
       LIMIT 2`,
      [normalizedIdentifier],
    )
    const candidateUserId = candidates.rows.length === 1 ? candidates.rows[0].id : null
    const { rows } = await pool.query(
      `INSERT INTO fallback_account_recovery_cases
         (identifier, alternate_contact, description, contact_type, profile_hint, candidate_user_id)
       VALUES ($1, $2, $3, $4, $5, $6) RETURNING id`,
      [identifier, alternateContact, description, contactType, profileHint || null, candidateUserId],
    )
    res.set('Cache-Control', 'no-store')
    return res.status(202).json({ ok: true, caseId: rows[0].id })
  } catch (error) {
    return next(error)
  }
})

app.post('/v1/auth/account-recovery/cases/:id/confirm-contact', async (req, res, next) => {
  try {
    if (!isUuidText(req.params.id) || !/^\d{6}$/.test(String(req.body?.code || ''))) {
      return res.status(400).json({ error: 'RECOVERY_CONTACT_CODE_REQUIRED' })
    }
    const { rows } = await pool.query(
      `UPDATE fallback_account_recovery_cases
       SET contact_verified_at = NOW(), contact_code_hash = NULL, contact_code_expires_at = NULL
       WHERE id = $1 AND status = 'pending' AND contact_code_hash = $2
         AND contact_code_expires_at > NOW() AND contact_code_attempts < 5
       RETURNING id`,
      [req.params.id, hashPasswordResetToken(req.body.code)],
    )
    if (!rows[0]) {
      await pool.query(
        `UPDATE fallback_account_recovery_cases
         SET contact_code_attempts = contact_code_attempts + 1
         WHERE id = $1 AND status = 'pending' AND contact_code_hash IS NOT NULL
           AND contact_code_attempts < 5`,
        [req.params.id],
      )
      return res.status(401).json({ error: 'INVALID_OR_EXPIRED_CONTACT_CODE' })
    }
    res.set('Cache-Control', 'no-store')
    return res.json({ ok: true })
  } catch (error) { return next(error) }
})

app.post('/v1/auth/register', async (req, res, next) => {
  try {
    if (isWebClientRequest(req) && !requireTrustedWebSessionRequest(req, res)) return
    const email = normalizeEmail(req.body?.email)
    const password = String(req.body?.password || '')
    const displayName = cleanText(req.body?.displayName || email.split('@')[0], 80)

    if (!isValidEmail(email)) {
      return res.status(400).json({ error: 'INVALID_EMAIL' })
    }

    if (password.length < 8) {
      return res.status(400).json({
        error: 'PASSWORD_TOO_SHORT',
        message: 'La contraseña debe tener al menos 8 caracteres.',
      })
    }

    const passwordHash = await bcrypt.hash(password, 12)
    const { rows } = await pool.query(
      `INSERT INTO fallback_users(
          email,
          password_hash,
          password_enabled,
          display_name,
          sync_source,
          sync_status,
          local_password_set_at,
          password_changed_by_user_at
       )
       VALUES ($1, $2, TRUE, $3, 'local', 'local_only', NOW(), NOW())
       RETURNING ${userSelectColumns}`,
      [email, passwordHash, displayName],
    )
    let user = sanitizeUser(rows[0])
    const supabaseSync = await syncLocalUserToSupabase(user, password)
    if (supabaseSync.ok && supabaseSync.user) {
      user = supabaseSync.user
    } else if (supabaseSync.attempted && !supabaseSync.ok) {
      await pool.query(
        `UPDATE fallback_users
         SET sync_status = 'needs_supabase_password',
             sync_error = $2
         WHERE id = $1`,
        [user.id, supabaseSync.error],
      )
      user.syncStatus = 'needs_supabase_password'
      user.syncError = supabaseSync.error
    }

    await recordAudit('auth_register', user.id, { email })

    const session = await issueSessionForRequest(user, req, res)
    return res.status(201).json({ ...session, supabaseSync })
  } catch (error) {
    if (error.code === '23505') {
      return res.status(409).json({ error: 'EMAIL_ALREADY_EXISTS' })
    }
    return next(error)
  }
})

app.post('/v1/auth/login', async (req, res, next) => {
  try {
    if (isWebClientRequest(req) && !requireTrustedWebSessionRequest(req, res)) return
    const email = normalizeEmail(req.body?.email)
    const password = String(req.body?.password || '')

    if (!isValidEmail(email) || !password) {
      return res.status(400).json({ error: 'EMAIL_AND_PASSWORD_REQUIRED' })
    }

    const { rows } = await pool.query(
      `SELECT ${userSelectColumns}
       FROM fallback_users
       WHERE email = $1
          OR id = (
            SELECT user_id
            FROM fallback_profile_login_emails
            WHERE lower(email) = $1
            LIMIT 1
          )
       LIMIT 1`,
      [email],
    )

    let row = rows[0]
    if (!row) {
      return res.status(404).json({
        error: 'ACCOUNT_NOT_FOUND',
        message: 'No existe una cuenta con ese correo. Regístrate para crear una cuenta.',
      })
    }

    const hasLocalPassword = Boolean(row.password_enabled && row.password_hash)
    if (!hasLocalPassword) {
      const restoredPasswordOk = await verifyRestoredSupabasePassword(row, password)
      if (!restoredPasswordOk) {
        await recordAudit('auth_migrated_password_verification_failed', row.id, { email })
        return res.status(401).json({
          error: 'INVALID_PASSWORD',
          message:
            'La contraseña actual no coincide. Escríbela correctamente para autorizar el cambio de contraseña.',
        })
      }

      row = await hydrateUserRow(row)
      const user = sanitizeUser(row)
      await recordAudit('auth_migrated_password_verified', user.id, { email })
      return res.status(409).json({
        error: 'MIGRATED_ACCOUNT_PASSWORD_RESET_REQUIRED',
        migrationToken: signMigrationPasswordToken(user),
        email: user.email,
        displayName: user.displayName,
      })
    }

    if (!(await bcrypt.compare(password, row.password_hash))) {
      return res.status(401).json({
        error: 'INVALID_PASSWORD',
        message: 'Contraseña incorrecta. Intenta otra vez o restablece tu contraseña.',
      })
    }

    const suspension = await findActiveSuspension(pool, row.id)
    if (suspension) {
      await recordAudit('auth_login_suspended', row.id, { email })
      return res.status(403).json({
        ...accountSuspendedPayload(suspension),
        appealToken: signAppealToken(row.id),
        appealTokenExpiresIn: '14d',
      })
    }

    const secondFactor = await verifySecondFactor(
      row.id, req.body?.totpCode, req.body?.backupCode,
    )
    if (!secondFactor.verified) {
      await recordAudit('auth_second_factor_rejected', row.id, {
        reason: secondFactor.error,
      })
      return res.status(secondFactor.error === 'TWO_FACTOR_LOCKED' ? 429 : 401)
        .json({ error: secondFactor.error, requiresTwoFactor: true })
    }

    const updated = await pool.query(
      `UPDATE fallback_users
       SET last_login_at = NOW()
       WHERE id = $1
       RETURNING ${userSelectColumns}`,
      [row.id],
    )
    const user = sanitizeUser(await hydrateUserRow(updated.rows[0]))
    await recordAudit('auth_login', user.id, { email })

    return res.json(await issueSessionForRequest(user, req, res, {
      twoFactorVerified: secondFactor.required,
    }))
  } catch (error) {
    return next(error)
  }
})

app.post('/v1/auth/refresh', async (req, res, next) => {
  const refreshToken = String(req.body?.refreshToken || '').trim()
  if (refreshToken.length < 32 || refreshToken.length > 4096) {
    return res.status(401).json({ error: 'INVALID_REFRESH_TOKEN' })
  }

  const client = await pool.connect()
  try {
    await client.query('BEGIN')
    const qualifiedUserColumns = userSelectColumns
      .split(',')
      .map((column) => `fallback_users.${column.trim()}`)
      .join(', ')
    const tokenHash = hashAuthRefreshToken(refreshToken)
    const activeSession = await client.query(
      `SELECT auth_session.id AS auth_session_id, ${qualifiedUserColumns}
       FROM fallback_auth_sessions auth_session
       JOIN fallback_users ON fallback_users.id = auth_session.user_id
       WHERE auth_session.refresh_token_hash = $1
         AND auth_session.revoked_at IS NULL
         AND auth_session.expires_at > NOW()
       LIMIT 1
       FOR UPDATE OF auth_session`,
      [tokenHash],
    )

    const row = activeSession.rows[0]
    const consumedSessionId = row?.auth_session_id || null

    if (!row) {
      await client.query('ROLLBACK')
      return res.status(401).json({ error: 'INVALID_REFRESH_TOKEN' })
    }

    const suspension = await findActiveSuspension(client, row.id)
    if (suspension) {
      if (consumedSessionId) {
        await client.query(
          `UPDATE fallback_auth_sessions
           SET revoked_at = NOW(), updated_at = NOW()
           WHERE id = $1`,
          [consumedSessionId],
        )
      }
      await client.query('COMMIT')
      return res.status(403).json(accountSuspendedPayload(suspension))
    }

    const user = sanitizeUser(await hydrateUserRow(row))
    // Keep the opaque mobile refresh credential reusable until its explicit
    // expiry/revocation. An access JWT alone must never mint a new session.
    await client.query(
      `UPDATE fallback_auth_sessions
       SET last_used_at = NOW(),
           updated_at = NOW()
       WHERE id = $1`,
      [consumedSessionId],
    )
    const session = {
      user,
      accessToken: signUserToken(user, {
        sessionId: consumedSessionId,
        client: 'android',
      }),
      refreshToken,
      tokenType: 'Bearer',
      expiresIn: jwtExpiresIn,
    }
    // Keep the audit insert on the same transaction connection. Using `pool`
    // here waits for a user-row lock held by this transaction, leaving the
    // transaction idle forever and eventually exhausting the whole pool.
    await recordAudit('auth_refresh', user.id, {
      recoveryMode: 'active_refresh',
    }, client)
    await client.query('COMMIT')
    return res.json(session)
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {})
    return next(error)
  } finally {
    client.release()
  }
})

// The opaque mobile refresh credential identifies exactly one device session.
// Logout stays usable even after its short-lived access JWT has expired.
app.post('/v1/auth/logout', async (req, res, next) => {
  const refreshToken = String(req.body?.refreshToken || '').trim()
  if (refreshToken.length < 32 || refreshToken.length > 4096) {
    return res.status(401).json({ error: 'INVALID_REFRESH_TOKEN' })
  }
  try {
    const { rows } = await pool.query(
      `UPDATE fallback_auth_sessions
       SET revoked_at = NOW(), updated_at = NOW()
       WHERE refresh_token_hash = $1
         AND revoked_at IS NULL
       RETURNING id, user_id`,
      [hashAuthRefreshToken(refreshToken)],
    )
    if (rows[0]) {
      await recordAudit('auth_logout', rows[0].user_id, { sessionId: rows[0].id })
    }
    return res.json({ ok: true, revoked: Boolean(rows[0]) })
  } catch (error) {
    return next(error)
  }
})

async function passwordMatchesUser(userId, password) {
  if (!password) return false
  const { rows } = await pool.query(
    `SELECT password_hash FROM fallback_users WHERE id = $1 AND password_enabled = TRUE`,
    [userId],
  )
  return Boolean(rows[0]?.password_hash && await bcrypt.compare(password, rows[0].password_hash))
}

app.get('/v1/auth/password-recovery-codes/status', authRequired, async (req, res, next) => {
  try {
    const { rows } = await pool.query(
      `SELECT count(*)::int AS remaining FROM fallback_password_recovery_codes
       WHERE user_id = $1 AND used_at IS NULL`,
      [req.auth.sub],
    )
    res.set('Cache-Control', 'no-store')
    return res.json({ recoveryCodesRemaining: rows[0]?.remaining || 0 })
  } catch (error) { return next(error) }
})

app.post('/v1/auth/password-recovery-codes/rotate', authRequired, async (req, res, next) => {
  let client
  try {
    if (isWebClientRequest(req) && !requireTrustedWebSessionRequest(req, res)) return
    if (!(await passwordMatchesUser(req.auth.sub, String(req.body?.password || '')))) {
      return res.status(401).json({ error: 'INVALID_PASSWORD' })
    }
    const factor = await verifySecondFactor(req.auth.sub, req.body?.totpCode, req.body?.backupCode)
    if (!factor.verified) {
      return res.status(401).json({ error: factor.error || 'TWO_FACTOR_REQUIRED' })
    }

    const codes = issuePasswordRecoveryCodes()
    client = await pool.connect()
    await client.query('BEGIN')
    await client.query('DELETE FROM fallback_password_recovery_codes WHERE user_id = $1', [req.auth.sub])
    for (const code of codes) {
      await client.query(
        `INSERT INTO fallback_password_recovery_codes(user_id, code_hash) VALUES ($1, $2)`,
        [req.auth.sub, hashPasswordRecoveryCode(code, twoFactorEncryptionSecret)],
      )
    }
    await client.query('COMMIT')
    client.release()
    client = null
    await recordAudit('auth_password_recovery_codes_rotated', req.auth.sub).catch((error) => {
      console.error('[auth] password recovery rotation audit failed', error)
    })
    res.set('Cache-Control', 'no-store')
    return res.json({ ok: true, recoveryCodes: codes })
  } catch (error) {
    if (client) await client.query('ROLLBACK').catch(() => {})
    return next(error)
  } finally {
    client?.release()
  }
})

app.get('/v1/auth/2fa/status', authRequired, async (req, res, next) => {
  try {
    const enabled = await hasEnabledSecondFactor(req.auth.sub)
    const { rows } = await pool.query(
      `SELECT count(*)::int AS remaining FROM fallback_totp_recovery_codes
       WHERE user_id = $1 AND used_at IS NULL`,
      [req.auth.sub],
    )
    return res.json({ enabled, recoveryCodesRemaining: enabled ? rows[0].remaining : 0 })
  } catch (error) { return next(error) }
})

app.post('/v1/auth/2fa/setup', authRequired, async (req, res, next) => {
  try {
    if (!(await passwordMatchesUser(req.auth.sub, String(req.body?.password || '')))) {
      return res.status(401).json({ error: 'INVALID_PASSWORD' })
    }
    const secret = generateSecret()
    const { rows } = await pool.query(
      `INSERT INTO fallback_totp_factors(user_id, pending_ciphertext)
       VALUES ($1, $2)
       ON CONFLICT (user_id) DO UPDATE
         SET pending_ciphertext = EXCLUDED.pending_ciphertext, updated_at = NOW()
       WHERE fallback_totp_factors.enabled_at IS NULL
       RETURNING user_id`,
      [req.auth.sub, encryptSecret(secret, twoFactorEncryptionSecret)],
    )
    if (!rows[0]) return res.status(409).json({ error: 'TWO_FACTOR_ALREADY_ENABLED' })
    const { rows: users } = await pool.query('SELECT email FROM fallback_users WHERE id = $1', [req.auth.sub])
    await recordAudit('auth_second_factor_setup', req.auth.sub)
    return res.json({ secret, provisioningUri: provisioningUri(secret, users[0]?.email || '') })
  } catch (error) { return next(error) }
})

app.post('/v1/auth/2fa/enable', authRequired, async (req, res, next) => {
  let client
  try {
    if (!(await passwordMatchesUser(req.auth.sub, String(req.body?.password || '')))) {
      return res.status(401).json({ error: 'INVALID_PASSWORD' })
    }
    client = await pool.connect()
    await client.query('BEGIN')
    const { rows } = await client.query(
      `SELECT pending_ciphertext, enabled_at FROM fallback_totp_factors
       WHERE user_id = $1 FOR UPDATE`, [req.auth.sub],
    )
    if (!rows[0]?.pending_ciphertext || rows[0].enabled_at) {
      await client.query('ROLLBACK')
      return res.status(409).json({ error: 'TWO_FACTOR_SETUP_REQUIRED' })
    }
    const secret = decryptSecret(rows[0].pending_ciphertext, twoFactorEncryptionSecret)
    const matched = matchingStep(secret, req.body?.totpCode)
    if (matched === null) {
      await client.query('ROLLBACK')
      return res.status(401).json({ error: 'TWO_FACTOR_INVALID' })
    }
    const codes = createRecoveryCodes()
    await client.query(
      `UPDATE fallback_totp_factors
       SET secret_ciphertext = pending_ciphertext, pending_ciphertext = NULL,
           enabled_at = NOW(), last_used_step = $2, failed_attempts = 0,
           locked_until = NULL, updated_at = NOW()
       WHERE user_id = $1`, [req.auth.sub, matched],
    )
    for (const code of codes) {
      await client.query(
        `INSERT INTO fallback_totp_recovery_codes(user_id, code_hash) VALUES ($1, $2)`,
        [req.auth.sub, recoveryHash(code, twoFactorEncryptionSecret)],
      )
    }
    await revokeAllUserSessions(req.auth.sub, client)
    await client.query('COMMIT')
    const { rows: users } = await pool.query(
      `SELECT ${userSelectColumns} FROM fallback_users WHERE id = $1`, [req.auth.sub],
    )
    const user = sanitizeUser(await hydrateUserRow(users[0]))
    await recordAudit('auth_second_factor_enabled', req.auth.sub)
    const session = await issueSessionForRequest(user, req, res, { twoFactorVerified: true })
    return res.json({ ok: true, recoveryCodes: codes, ...session })
  } catch (error) {
    if (client) await client.query('ROLLBACK').catch(() => {})
    return next(error)
  } finally {
    client?.release()
  }
})

app.post('/v1/auth/2fa/disable', authRequired, async (req, res, next) => {
  let client
  try {
    if (!(await passwordMatchesUser(req.auth.sub, String(req.body?.password || '')))) {
      return res.status(401).json({ error: 'INVALID_PASSWORD' })
    }
    const factor = await verifySecondFactor(
      req.auth.sub, req.body?.totpCode, req.body?.backupCode,
    )
    if (!factor.required || !factor.verified) {
      return res.status(401).json({ error: factor.error || 'TWO_FACTOR_REQUIRED' })
    }
    client = await pool.connect()
    await client.query('BEGIN')
    await client.query('DELETE FROM fallback_totp_recovery_codes WHERE user_id = $1', [req.auth.sub])
    await client.query('DELETE FROM fallback_totp_factors WHERE user_id = $1', [req.auth.sub])
    await revokeAllUserSessions(req.auth.sub, client)
    await client.query('COMMIT')
    await recordAudit('auth_second_factor_disabled', req.auth.sub)
    return res.json({ ok: true, signInRequired: true })
  } catch (error) {
    if (client) await client.query('ROLLBACK').catch(() => {})
    return next(error)
  } finally {
    client?.release()
  }
})

app.post('/v1/auth/change-password', authRequired, async (req, res, next) => {
  let client
  try {
    const currentPassword = String(req.body?.currentPassword || '')
    const newPassword = String(req.body?.newPassword || '')

    if (!currentPassword || newPassword.length < 8) {
      return res.status(400).json({ error: 'CURRENT_AND_NEW_PASSWORD_REQUIRED' })
    }
    if (currentPassword === newPassword) {
      return res.status(400).json({ error: 'NEW_PASSWORD_MUST_BE_DIFFERENT' })
    }

    client = await pool.connect()
    await client.query('BEGIN')
    const { rows } = await client.query(
      `SELECT ${userSelectColumns}
       FROM fallback_users
       WHERE id = $1
       LIMIT 1 FOR UPDATE`,
      [req.auth.sub],
    )

    const row = rows[0]
    if (!row) {
      await client.query('ROLLBACK')
      return res.status(404).json({ error: 'USER_NOT_FOUND' })
    }
    if (!row.password_enabled || !row.password_hash || !(await bcrypt.compare(currentPassword, row.password_hash))) {
      await client.query('ROLLBACK')
      return res.status(401).json({
        error: 'INVALID_PASSWORD',
        message: 'La contraseña actual es incorrecta.',
      })
    }

    const passwordHash = await bcrypt.hash(newPassword, 12)
    const updated = await client.query(
      `UPDATE fallback_users
       SET password_hash = $2,
           password_enabled = TRUE,
           local_password_set_at = NOW(),
           password_changed_by_user_at = NOW(),
           sync_status = CASE
             WHEN supabase_user_id IS NULL THEN 'local_only'
             ELSE 'synced'
           END,
           sync_source = CASE
             WHEN supabase_user_id IS NULL THEN 'local'
             ELSE 'both'
           END,
           sync_error = NULL
       WHERE id = $1
       RETURNING ${userSelectColumns}`,
      [row.id, passwordHash],
    )
    await client.query(
      `UPDATE fallback_password_reset_tokens SET consumed_at = NOW()
       WHERE user_id = $1 AND consumed_at IS NULL`,
      [row.id],
    )
    await revokeAllUserSessions(row.id, client)
    await client.query('COMMIT')
    client.release()
    client = null

    const user = sanitizeUser(await hydrateUserRow(updated.rows[0]))
    await recordAudit('auth_change_password', user.id, { email: user.email })
      .catch((error) => console.error('[auth] password change audit failed', error))
    return res.json({ ok: true, ...(await issueSessionForRequest(user, req, res)) })
  } catch (error) {
    if (client) await client.query('ROLLBACK').catch(() => {})
    return next(error)
  } finally {
    client?.release()
  }
})

app.post('/v1/auth/password-reset/request', async (req, res, next) => {
  try {
    const email = normalizeEmail(req.body?.email)
    const baseResponse = {
      ok: true,
      delivery: passwordResetDelivery === 'smtp' ? 'email' : passwordResetDelivery,
      expiresInMinutes: passwordResetTokenTtlMinutes,
    }

    if (!isValidEmail(email) || passwordResetDelivery === 'disabled') {
      return res.json(baseResponse)
    }

    const { rows } = await pool.query(
      `SELECT ${userSelectColumns}
       FROM fallback_users
       WHERE email = $1
       LIMIT 1`,
      [email],
    )

    if (!rows[0]) {
      return res.json(baseResponse)
    }

    const row = rows[0]
    const token = createPasswordResetToken()
    const tokenHash = hashPasswordResetToken(token)
    await pool.query(
      `UPDATE fallback_password_reset_tokens
       SET consumed_at = NOW()
       WHERE user_id = $1 AND consumed_at IS NULL`,
      [row.id],
    )
    await pool.query(
      `INSERT INTO fallback_password_reset_tokens(user_id, token_hash, expires_at)
       VALUES ($1, $2, NOW() + ($3::text || ' minutes')::interval)`,
      [row.id, tokenHash, passwordResetTokenTtlMinutes],
    )

    let delivery
    try {
      delivery = await deliverPasswordResetToken(sanitizeUser(await hydrateUserRow(row)), token)
    } catch (error) {
      // Keep the same public response for an unknown account and a mail outage.
      // The token remains unusable without the email and the next request revokes it.
      console.error('[auth] password reset delivery failed', error?.code || error?.name || 'unknown')
      return res.json(baseResponse)
    }
    await recordAudit('auth_password_reset_requested', row.id, {
      email,
      delivery: delivery.delivery,
    })

    return res.json({
      ...baseResponse,
      delivery: delivery.delivery,
      resetToken: shouldExposePasswordResetToken() ? delivery.resetToken : undefined,
    })
  } catch (error) {
    return next(error)
  }
})

app.post('/v1/auth/password-reset/confirm', async (req, res, next) => {
  let client
  try {
    if (isWebClientRequest(req) && !requireTrustedWebSessionRequest(req, res)) return
    const resetToken = String(req.body?.resetToken || req.body?.token || '').trim()
    const newPassword = String(req.body?.newPassword || req.body?.password || '')

    if (!resetToken || newPassword.length < 8) {
      return res.status(400).json({ error: 'RESET_TOKEN_AND_PASSWORD_REQUIRED' })
    }

    const tokenHash = hashPasswordResetToken(resetToken)
    client = await pool.connect()
    await client.query('BEGIN')
    const { rows } = await client.query(
      `SELECT token.id AS token_id, token.user_id, token.recovery_case_id, ${userSelectColumns
        .split(',')
        .map((column) => `fallback_users.${column.trim()}`)
        .join(', ')}
       FROM fallback_password_reset_tokens token
       JOIN fallback_users ON fallback_users.id = token.user_id
       WHERE token.token_hash = $1
         AND token.consumed_at IS NULL
         AND token.expires_at > NOW()
       LIMIT 1
       FOR UPDATE OF token, fallback_users`,
      [tokenHash],
    )

    const row = rows[0]
    if (!row) {
      await client.query('ROLLBACK')
      return res.status(401).json({ error: 'INVALID_OR_EXPIRED_RESET_TOKEN' })
    }

    const passwordHash = await bcrypt.hash(newPassword, 12)
    await client.query(
      `UPDATE fallback_password_reset_tokens
       SET consumed_at = NOW()
       WHERE user_id = $1 AND consumed_at IS NULL`,
      [row.user_id],
    )
    const updated = await client.query(
      `UPDATE fallback_users
       SET password_hash = $2,
           password_enabled = TRUE,
           local_password_set_at = NOW(),
           password_changed_by_user_at = NOW(),
           sync_status = CASE
             WHEN supabase_user_id IS NULL THEN 'local_only'
             ELSE 'synced'
           END,
           sync_source = CASE
             WHEN supabase_user_id IS NULL THEN 'local'
             ELSE 'both'
           END,
           sync_error = NULL,
           last_login_at = NOW()
       WHERE id = $1
       RETURNING ${userSelectColumns}`,
      [row.user_id, passwordHash],
    )
    await revokeAllUserSessions(row.user_id, client)
    if (row.recovery_case_id) {
      const reviewedCase = await client.query(
        `SELECT two_factor_reset FROM fallback_account_recovery_cases
         WHERE id = $1 AND user_id = $2 FOR UPDATE`,
        [row.recovery_case_id, row.user_id],
      )
      if (!reviewedCase.rows[0]) {
        throw new Error('ASSISTED_RECOVERY_CASE_MISSING')
      }
      if (reviewedCase.rows[0].two_factor_reset) {
        await client.query('DELETE FROM fallback_totp_recovery_codes WHERE user_id = $1', [row.user_id])
        await client.query('DELETE FROM fallback_totp_factors WHERE user_id = $1', [row.user_id])
      }
      await client.query(
        `UPDATE fallback_account_recovery_cases SET status = 'completed' WHERE id = $1`,
        [row.recovery_case_id],
      )
    }
    await client.query('COMMIT')
    client.release()
    client = null

    const user = sanitizeUser(await hydrateUserRow(updated.rows[0]))
    await recordAudit('auth_password_reset_completed', user.id, { email: user.email })
      .catch((error) => console.error('[auth] password reset audit failed', error))
    if (await hasEnabledSecondFactor(user.id)) {
      return res.json({ ok: true, requiresTwoFactor: true })
    }
    return res.json({ ok: true, ...(await issueSessionForRequest(user, req, res)) })
  } catch (error) {
    if (client) await client.query('ROLLBACK').catch(() => {})
    return next(error)
  } finally {
    client?.release()
  }
})

app.post('/v1/auth/password-recovery-codes/confirm', async (req, res, next) => {
  let client
  try {
    if (isWebClientRequest(req) && !requireTrustedWebSessionRequest(req, res)) return
    const email = normalizeEmail(req.body?.email)
    const codeHash = hashPasswordRecoveryCode(req.body?.recoveryCode, twoFactorEncryptionSecret)
    const newPassword = String(req.body?.newPassword || '')
    if (!isValidEmail(email) || !codeHash || newPassword.length < 8) {
      return res.status(400).json({ error: 'RECOVERY_CODE_AND_PASSWORD_REQUIRED' })
    }

    client = await pool.connect()
    await client.query('BEGIN')
    const { rows } = await client.query(
      `SELECT u.id, u.email
       FROM fallback_password_recovery_codes code
       JOIN fallback_users u ON u.id = code.user_id
       WHERE u.email = $1 AND code.code_hash = $2 AND code.used_at IS NULL
       FOR UPDATE OF code, u`,
      [email, codeHash],
    )
    const account = rows[0]
    if (!account) {
      await client.query('ROLLBACK')
      client.release()
      client = null
      return res.status(401).json({ error: 'INVALID_RECOVERY_CODE' })
    }

    const factor = await verifySecondFactor(account.id, req.body?.totpCode, req.body?.backupCode)
    if (!factor.verified) {
      await client.query('ROLLBACK')
      client.release()
      client = null
      return res.status(401).json({ error: factor.error || 'TWO_FACTOR_REQUIRED' })
    }

    const passwordHash = await bcrypt.hash(newPassword, 12)
    await client.query(
      `UPDATE fallback_password_recovery_codes SET used_at = NOW()
       WHERE user_id = $1 AND code_hash = $2 AND used_at IS NULL`,
      [account.id, codeHash],
    )
    const updated = await client.query(
      `UPDATE fallback_users
       SET password_hash = $2, password_enabled = TRUE,
           local_password_set_at = NOW(), password_changed_by_user_at = NOW(),
           sync_status = CASE WHEN supabase_user_id IS NULL THEN 'local_only' ELSE 'synced' END,
           sync_source = CASE WHEN supabase_user_id IS NULL THEN 'local' ELSE 'both' END,
           sync_error = NULL, last_login_at = NOW()
       WHERE id = $1 RETURNING ${userSelectColumns}`,
      [account.id, passwordHash],
    )
    await client.query(
      `UPDATE fallback_password_reset_tokens SET consumed_at = NOW()
       WHERE user_id = $1 AND consumed_at IS NULL`,
      [account.id],
    )
    await revokeAllUserSessions(account.id, client)
    await client.query('COMMIT')
    client.release()
    client = null

    const user = sanitizeUser(await hydrateUserRow(updated.rows[0]))
    await recordAudit('auth_password_recovered_with_saved_code', user.id).catch((error) => {
      console.error('[auth] password recovery audit failed', error)
    })
    res.set('Cache-Control', 'no-store')
    return res.json({ ok: true, ...(await issueSessionForRequest(user, req, res, {
      twoFactorVerified: factor.required,
    })) })
  } catch (error) {
    if (client) await client.query('ROLLBACK').catch(() => {})
    return next(error)
  } finally {
    client?.release()
  }
})

app.post('/v1/auth/complete-migrated-password-reset', async (req, res, next) => {
  try {
    if (isWebClientRequest(req) && !requireTrustedWebSessionRequest(req, res)) return
    const migrationToken = String(req.body?.migrationToken || '').trim()
    const newPassword = String(req.body?.newPassword || '')

    if (!migrationToken || newPassword.length < 8) {
      return res.status(400).json({ error: 'MIGRATION_TOKEN_AND_PASSWORD_REQUIRED' })
    }

    let tokenPayload
    try {
      tokenPayload = verifyMigrationPasswordToken(migrationToken)
    } catch {
      return res.status(401).json({ error: 'INVALID_MIGRATION_TOKEN' })
    }

    const { rows } = await pool.query(
      `SELECT ${userSelectColumns}
       FROM fallback_users
       WHERE id = $1
       LIMIT 1`,
      [tokenPayload.sub],
    )

    if (!rows[0]) {
      return res.status(404).json({ error: 'USER_NOT_FOUND' })
    }

    const passwordHash = await bcrypt.hash(newPassword, 12)
    const updated = await pool.query(
      `UPDATE fallback_users
       SET password_hash = $2,
           password_enabled = TRUE,
           local_password_set_at = NOW(),
           password_changed_by_user_at = NOW(),
           sync_status = CASE
             WHEN supabase_user_id IS NULL THEN 'local_only'
             ELSE 'synced'
           END,
           sync_source = CASE
             WHEN supabase_user_id IS NULL THEN 'local'
             ELSE 'both'
           END,
           sync_error = NULL,
           last_login_at = NOW()
       WHERE id = $1
       RETURNING ${userSelectColumns}`,
      [tokenPayload.sub, passwordHash],
    )

    const user = sanitizeUser(await hydrateUserRow(updated.rows[0]))
    await recordAudit('auth_complete_migrated_password_reset', user.id, { email: user.email })
    await pool.query(
      `UPDATE fallback_password_reset_tokens SET consumed_at = NOW()
       WHERE user_id = $1 AND consumed_at IS NULL`,
      [user.id],
    )
    await revokeAllUserSessions(user.id)
    if (await hasEnabledSecondFactor(user.id)) {
      return res.json({ ok: true, requiresTwoFactor: true })
    }
    return res.json({ ok: true, ...(await issueSessionForRequest(user, req, res)) })
  } catch (error) {
    return next(error)
  }
})

app.get('/v1/auth/me', authRequired, async (req, res, next) => {
  try {
    const { rows } = await pool.query(
      `SELECT ${userSelectColumns}
       FROM fallback_users
       WHERE id = $1
       LIMIT 1`,
      [req.auth.sub],
    )

    if (!rows[0]) {
      return res.status(404).json({ error: 'USER_NOT_FOUND' })
    }

    return res.json({ user: sanitizeUser(await hydrateUserRow(rows[0])) })
  } catch (error) {
    return next(error)
  }
})

app.post('/v1/auth/web-session/refresh', async (req, res, next) => {
  if (!requireTrustedWebSessionRequest(req, res)) return
  const refreshToken = readWebSessionCookie(req, { production: isProduction })
  if (refreshToken.length < 32 || refreshToken.length > 4096) {
    clearWebSessionCookie(res)
    return res.status(401).json({ error: 'WEB_SESSION_REQUIRED' })
  }

  try {
    const qualifiedUserColumns = userSelectColumns
      .split(',')
      .map((column) => `fallback_users.${column.trim()}`)
      .join(', ')
    const { rows } = await pool.query(
      `UPDATE fallback_web_sessions web_session
       SET last_seen_at = NOW(),
           ip_address = COALESCE($2, web_session.ip_address),
           user_agent = COALESCE($3, web_session.user_agent),
           updated_at = NOW()
       FROM fallback_users
       WHERE web_session.refresh_token_hash = $1
         AND web_session.user_id = fallback_users.id
         AND web_session.revoked_at IS NULL
         AND web_session.expires_at > NOW()
       RETURNING
         web_session.id AS web_session_id,
         web_session.device_label,
         web_session.ip_address,
         web_session.user_agent,
         web_session.created_at,
         web_session.last_seen_at,
         web_session.revoked_at,
         ${qualifiedUserColumns}`,
      [hashAuthRefreshToken(refreshToken), requestIpAddress(req), requestUserAgent(req)],
    )
    const row = rows[0]
    if (!row) {
      clearWebSessionCookie(res)
      return res.status(401).json({ error: 'WEB_SESSION_INVALID' })
    }

    const suspension = await findActiveSuspension(pool, row.id)
    if (suspension) {
      await pool.query(
        `UPDATE fallback_web_sessions
         SET revoked_at = COALESCE(revoked_at, NOW()), updated_at = NOW()
         WHERE id = $1`,
        [row.web_session_id],
      )
      clearWebSessionCookie(res)
      return res.status(403).json(accountSuspendedPayload(suspension))
    }

    const user = sanitizeUser(await hydrateUserRow(row))
    return res.json({
      user,
      accessToken: signUserToken(user, {
        sessionId: row.web_session_id,
        client: 'web',
        expiresIn: webJwtExpiresIn,
      }),
      tokenType: 'Bearer',
      expiresIn: webJwtExpiresIn,
      webSession: serializeWebSession({ ...row, id: row.web_session_id }),
    })
  } catch (error) {
    return next(error)
  }
})

app.post('/v1/auth/web-session/upgrade', authRequired, async (req, res, next) => {
  if (!requireTrustedWebSessionRequest(req, res)) return
  try {
    const { rows } = await pool.query(
      `SELECT ${userSelectColumns}
       FROM fallback_users
       WHERE id = $1
       LIMIT 1`,
      [req.auth.sub],
    )
    if (!rows[0]) return res.status(404).json({ error: 'USER_NOT_FOUND' })

    const user = sanitizeUser(await hydrateUserRow(rows[0]))
    const payload = await issueWebSession(user, req, res, {
      sessionId: req.auth.client === 'web' ? req.auth.sid : '',
      deviceLabel: req.body?.deviceLabel,
    })
    await recordAudit('auth_web_session_upgraded', user.id, {
      previousClient: cleanText(req.auth.client || 'legacy', 40),
    })
    return res.json(payload)
  } catch (error) {
    return next(error)
  }
})

app.delete('/v1/auth/web-session', async (req, res, next) => {
  if (!requireTrustedWebSessionRequest(req, res)) return
  const refreshToken = readWebSessionCookie(req, { production: isProduction })
  clearWebSessionCookie(res)
  if (refreshToken.length < 32 || refreshToken.length > 4096) {
    return res.json({ ok: true, revoked: false })
  }

  try {
    const { rows } = await pool.query(
      `UPDATE fallback_web_sessions
       SET revoked_at = COALESCE(revoked_at, NOW()),
           updated_at = NOW()
       WHERE refresh_token_hash = $1
         AND revoked_at IS NULL
       RETURNING id, user_id`,
      [hashAuthRefreshToken(refreshToken)],
    )
    if (rows[0]) {
      await recordAudit('auth_web_session_logout', rows[0].user_id, { sessionId: rows[0].id })
    }
    return res.json({ ok: true, revoked: Boolean(rows[0]) })
  } catch (error) {
    return next(error)
  }
})

async function handleWebLoginPairing(req, res, next) {
  try {
    const action = String(req.body?.action || '').trim().toLowerCase()

    if (action === 'create') {
      const deviceLabel = cleanText(req.body?.deviceLabel || 'Conectate Web', 120)
      const nonce = crypto.randomBytes(24).toString('hex')
      const { rows } = await pool.query(
        `INSERT INTO fallback_web_login_pairings(nonce, device_label)
         VALUES ($1, $2)
         RETURNING *`,
        [nonce, deviceLabel],
      )
      await recordAudit('auth_web_login_pairing_created', null, { pairId: rows[0].id, deviceLabel })
      return res.json(serializePairing(rows[0]))
    }

    if (action === 'status') {
      const pairing = await findPairing(req.body?.pairId, req.body?.nonce)
      return res.json(serializePairing(pairing))
    }

    if (action === 'approve') {
      const pairing = await findPairing(req.body?.pairId, req.body?.nonce)
      assertPairingPending(pairing)

      const { user } = await authenticatedUserFromRequest(req)
      const { rows } = await pool.query(
        `UPDATE fallback_web_login_pairings
         SET status = 'approved',
             approved_by_user_id = $2,
             approved_by_email = $3,
             approved_at = NOW(),
             updated_at = NOW()
         WHERE id = $1
           AND status = 'pending'
         RETURNING *`,
        [pairing.id, user.id, user.email],
      )

      const updated = rows[0]
      if (!updated) {
        const error = new Error('PAIRING_NOT_PENDING')
        error.status = 409
        throw error
      }

      await recordAudit('auth_web_login_pairing_approved', user.id, { pairId: pairing.id })
      return res.json(serializePairing(updated))
    }

    if (action === 'reject') {
      const pairing = await findPairing(req.body?.pairId, req.body?.nonce)
      assertPairingPending(pairing)

      const { user } = await authenticatedUserFromRequest(req)
      const { rows } = await pool.query(
        `UPDATE fallback_web_login_pairings
         SET status = 'rejected',
             approved_by_user_id = $2,
             approved_by_email = $3,
             rejected_at = NOW(),
             updated_at = NOW()
         WHERE id = $1
           AND status = 'pending'
         RETURNING *`,
        [pairing.id, user.id, user.email],
      )

      const updated = rows[0]
      if (!updated) {
        const error = new Error('PAIRING_NOT_PENDING')
        error.status = 409
        throw error
      }

      await recordAudit('auth_web_login_pairing_rejected', user.id, { pairId: pairing.id })
      return res.json(serializePairing(updated))
    }

    if (action === 'consume') {
      if (!requireTrustedWebSessionRequest(req, res)) return
      const pairing = await findPairing(req.body?.pairId, req.body?.nonce)
      if (pairing.status === 'expired' || isPairingExpired(pairing)) {
        return res.status(410).json({ error: 'PAIRING_EXPIRED' })
      }
      if (pairing.status !== 'approved' || !pairing.approved_by_user_id) {
        return res.status(409).json({ error: `PAIRING_${String(pairing.status || 'INVALID').toUpperCase()}` })
      }

      const userResult = await pool.query(
        `SELECT ${userSelectColumns}
         FROM fallback_users
         WHERE id = $1
         LIMIT 1`,
        [pairing.approved_by_user_id],
      )
      if (!userResult.rows[0]) {
        return res.status(404).json({ error: 'USER_NOT_FOUND' })
      }

      const updateResult = await pool.query(
        `UPDATE fallback_web_login_pairings
         SET status = 'consumed',
             consumed_at = NOW(),
             updated_at = NOW()
         WHERE id = $1
           AND status = 'approved'
           AND consumed_at IS NULL
         RETURNING *`,
        [pairing.id],
      )

      if (!updateResult.rows[0]) {
        return res.status(409).json({ error: 'PAIRING_ALREADY_CONSUMED' })
      }

      const user = sanitizeUser(await hydrateUserRow(userResult.rows[0]))
      await pool.query('UPDATE fallback_users SET last_login_at = NOW() WHERE id = $1', [user.id])
      const webSessionPayload = await issueWebSession(user, req, res, {
        pairingId: updateResult.rows[0].id,
        deviceLabel: updateResult.rows[0].device_label,
      })
      await recordAudit('auth_web_login_pairing_consumed', user.id, { pairId: pairing.id })

      return res.json(
        serializePairing(updateResult.rows[0], {
          ...webSessionPayload,
          refreshToken: null,
        }),
      )
    }

    return res.status(400).json({ error: 'PAIRING_ACTION_REQUIRED' })
  } catch (error) {
    if (error?.status) {
      return res.status(error.status).json({ error: error.message })
    }
    return next(error)
  }
}

app.post('/v1/auth/web-login-pairing', handleWebLoginPairing)
app.post('/functions/v1/web-login-pairing', handleWebLoginPairing)

app.get('/v1/auth/web-sessions', authRequired, async (req, res, next) => {
  try {
    const { rows } = await pool.query(
      `SELECT *
       FROM fallback_web_sessions
       WHERE user_id = $1
         AND revoked_at IS NULL
       ORDER BY last_seen_at DESC, created_at DESC
       LIMIT 50`,
      [req.auth.sub],
    )
    return res.json({ sessions: rows.map(serializeWebSession) })
  } catch (error) {
    return next(error)
  }
})

app.delete('/v1/auth/web-sessions', authRequired, async (req, res, next) => {
  try {
    const { rows } = await pool.query(
      `UPDATE fallback_web_sessions
       SET revoked_at = NOW(),
           updated_at = NOW()
       WHERE user_id = $1
         AND revoked_at IS NULL
       RETURNING *`,
      [req.auth.sub],
    )
    await recordAudit('auth_web_sessions_revoked_all', req.auth.sub, { count: rows.length })
    return res.json({ ok: true, revoked: rows.length, sessions: rows.map(serializeWebSession) })
  } catch (error) {
    return next(error)
  }
})

app.delete('/v1/auth/web-sessions/:sessionId', authRequired, async (req, res, next) => {
  try {
    const sessionId = String(req.params.sessionId || '').trim()
    if (!isUuidText(sessionId)) {
      return res.status(400).json({ error: 'WEB_SESSION_ID_REQUIRED' })
    }

    const { rows } = await pool.query(
      `UPDATE fallback_web_sessions
       SET revoked_at = NOW(),
           updated_at = NOW()
       WHERE id = $1
         AND user_id = $2
         AND revoked_at IS NULL
       RETURNING *`,
      [sessionId, req.auth.sub],
    )
    if (!rows[0]) {
      return res.status(404).json({ error: 'WEB_SESSION_NOT_FOUND' })
    }

    await recordAudit('auth_web_session_revoked', req.auth.sub, { sessionId })
    return res.json({ ok: true, session: serializeWebSession(rows[0]) })
  } catch (error) {
    return next(error)
  }
})

app.post('/v1/auth/activate-local-password', async (req, res, next) => {
  try {
    if (isWebClientRequest(req) && !requireTrustedWebSessionRequest(req, res)) return
    const supabaseAccessToken = String(req.body?.supabaseAccessToken || '').trim()
    const password = String(req.body?.password || '')
    const supabaseAdmin = getSupabaseAdminClient()

    if (!supabaseAdmin) {
      return res.status(503).json({ error: 'SUPABASE_ADMIN_NOT_CONFIGURED' })
    }
    if (!supabaseAccessToken || password.length < 8) {
      return res.status(400).json({ error: 'SUPABASE_TOKEN_AND_PASSWORD_REQUIRED' })
    }

    const { data, error } = await supabaseAdmin.auth.getUser(supabaseAccessToken)
    if (error || !data?.user?.email) {
      return res.status(401).json({ error: 'INVALID_SUPABASE_SESSION' })
    }

    const supabaseUser = data.user
    const email = normalizeEmail(supabaseUser.email)
    const passwordHash = await bcrypt.hash(password, 12)
    const { rows } = await pool.query(
      `INSERT INTO fallback_users(
          email,
          password_hash,
          password_enabled,
          display_name,
          avatar_url,
          supabase_user_id,
          sync_source,
          sync_status,
          local_password_set_at,
          password_changed_by_user_at,
          supabase_synced_at
       )
       VALUES ($1, $2, TRUE, $3, $4, $5, 'both', 'synced', NOW(), NOW(), NOW())
       ON CONFLICT (email)
       DO UPDATE SET
          password_hash = EXCLUDED.password_hash,
          password_enabled = TRUE,
          display_name = COALESCE(NULLIF(fallback_users.display_name, ''), EXCLUDED.display_name),
          avatar_url = COALESCE(fallback_users.avatar_url, EXCLUDED.avatar_url),
          supabase_user_id = EXCLUDED.supabase_user_id,
          sync_source = 'both',
          sync_status = 'synced',
          sync_error = NULL,
          local_password_set_at = NOW(),
          password_changed_by_user_at = NOW(),
          supabase_synced_at = NOW()
       RETURNING ${userSelectColumns}`,
      [
        email,
        passwordHash,
        getSupabaseUserDisplayName(supabaseUser),
        getSupabaseUserAvatar(supabaseUser),
        supabaseUser.id,
      ],
    )

    const user = sanitizeUser(await hydrateUserRow(rows[0]))
    await recordAudit('auth_activate_local_password', user.id, { email })
    await revokeAllUserSessions(user.id)
    if (await hasEnabledSecondFactor(user.id)) {
      return res.json({ ok: true, requiresTwoFactor: true })
    }
    return res.json({ ok: true, ...(await issueSessionForRequest(user, req, res)) })
  } catch (error) {
    return next(error)
  }
})

app.post('/v1/auth/sync-to-supabase', authRequired, async (req, res, next) => {
  try {
    const password = String(req.body?.password || '')
    if (password.length < 8) {
      return res.status(400).json({ error: 'PASSWORD_REQUIRED' })
    }

    const { rows } = await pool.query(
      `SELECT ${userSelectColumns}
       FROM fallback_users
       WHERE id = $1
       LIMIT 1`,
      [req.auth.sub],
    )

    if (!rows[0]) {
      return res.status(404).json({ error: 'USER_NOT_FOUND' })
    }

    const row = rows[0]
    if (!row.password_hash || !(await bcrypt.compare(password, row.password_hash))) {
      return res.status(401).json({
        error: 'INVALID_PASSWORD',
        message: 'Contraseña incorrecta.',
      })
    }

    const sync = await syncLocalUserToSupabase(sanitizeUser(row), password)
    if (!sync.ok) {
      await pool.query(
        `UPDATE fallback_users
         SET sync_status = 'needs_supabase_password',
             sync_error = $2
         WHERE id = $1`,
        [row.id, sync.error],
      )
      return res.status(409).json({ error: sync.error, sync })
    }

    return res.json({ ok: true, sync, user: sync.user })
  } catch (error) {
    return next(error)
  }
})

async function deleteExternalAuthUser(userId) {
  // The legacy identity provider is retired when imports are disabled. There
  // is no external account service left to mutate; the hard-delete transaction
  // writes durable identity tombstones so an old export cannot recreate it.
  // Avoid making permanent deletion depend on dead DNS or expired credentials.
  if (!legacySupabaseImportEnabled) {
    return { deleted: false, alreadyMissing: true, providerRetired: true }
  }
  const supabaseAdmin = getSupabaseAdminClient()
  if (supabaseAdmin) {
    const { error } = await supabaseAdmin.auth.admin.deleteUser(userId)
    if (!error) return { deleted: true, alreadyMissing: false }
    if (Number(error.status) === 404) return { deleted: false, alreadyMissing: true }
    const deleteError = new Error('EXTERNAL_AUTH_DELETE_FAILED')
    deleteError.status = 502
    deleteError.cause = error
    throw deleteError
  }
  if (!externalAuthDeleteUrl || !externalAuthDeleteKey) {
    const error = new Error('EXTERNAL_AUTH_DELETE_UNAVAILABLE')
    error.status = 409
    throw error
  }

  let response
  try {
    response = await fetch(
      `${externalAuthDeleteUrl}/auth/v1/admin/users/${encodeURIComponent(userId)}`,
      {
        method: 'DELETE',
        headers: {
          apikey: externalAuthDeleteKey,
          authorization: `Bearer ${externalAuthDeleteKey}`,
        },
      },
    )
  } catch (error) {
    // The retired migration provider has no DNS record anymore. In that exact
    // case there is no reachable external identity to delete; local tombstones
    // still prevent a future import from recreating the account. Other network
    // failures remain fatal so temporary outages cannot cause partial purges.
    if (!legacySupabaseImportEnabled && error?.cause?.code === 'ENOTFOUND') {
      return { deleted: false, alreadyMissing: true, providerRetired: true }
    }
    const deleteError = new Error('EXTERNAL_AUTH_DELETE_FAILED')
    deleteError.status = 502
    deleteError.cause = error
    throw deleteError
  }
  if (response.ok) return { deleted: true, alreadyMissing: false }
  if (response.status === 404) return { deleted: false, alreadyMissing: true }
  const payload = await parseJsonResponse(response)
  const deleteError = new Error('EXTERNAL_AUTH_DELETE_FAILED')
  deleteError.status = 502
  deleteError.cause = toRemoteAuthError(response, payload)
  throw deleteError
}

registerStorageRoutes(app, { pool, authRequired })
registerDataRoutes(app, {
  pool,
  authRequired,
  appealAuthRequired,
  deleteExternalAuthUser,
  signStorageObjectReference: signStoredObjectReference,
})
registerGooglePlayRtdnRoutes(app, {
  pool,
  packageName: googlePlayConfiguration().packageName,
  processNotification: (event, config) => processGooglePlayRtdnNotification(pool, event, config),
})
registerPostgrestCompatRoutes(app, { pool, authRequired, authOptional })
registerAiRoutes(app, { authRequired })

app.use((req, res) => {
  res.status(404).json({ error: 'NOT_FOUND', path: req.path })
})

app.use((error, _req, res, _next) => {
  console.error('[api] request failed', {
    requestId: _req.requestId,
    ...safeErrorLogDetails(error, {
      production: isProduction,
      fallbackCode: 'INTERNAL_ERROR',
      fallbackMessage: 'Unexpected request error',
    }),
  })
  const status = Number(error?.status || error?.statusCode || 500)
  const safeStatus = status >= 400 && status <= 599 ? status : 500
  const explicitlySafeCode = error?.expose === true && /^[A-Z][A-Z0-9_]{1,79}$/.test(String(error?.code || ''))
    ? String(error.code)
    : null
  const publicCode = explicitlySafeCode || (safeStatus < 500
    ? String(error?.message || 'REQUEST_REJECTED').replace(/[^A-Z0-9_]/gi, '_').slice(0, 80)
    : 'INTERNAL_ERROR')
  res.status(safeStatus).json({ error: publicCode, requestId: _req.requestId })
})

if (process.env.ROUTE_CHECK_ONLY === '1') {
  console.log(`${serviceName} ${version} route registration OK`)
  process.exit(0)
}

let server
let scheduledMessageTimer
let notificationDigestTimer
let communityEventReminderTimer
let birthdayNotificationTimer
let googlePlayRefundTimer
let liveFollowerNotificationTimer
let liveDrawGuessReconciler
let googlePlayRefundRunning = false
let creatorPayoutTimer
let creatorPayoutRunning = false
let rateLimitCleanupTimer
let resumableUploadCleanupTimer

withSchemaInitializationLock({
  pool,
  statementTimeoutMs: boundedInteger(
    process.env.SCHEMA_INITIALIZATION_TIMEOUT_MS,
    180_000,
    30_000,
    15 * 60_000,
  ),
  task: async (client) => {
    await ensureAuthMetadataColumns(client)
    await ensureLocalStorageTables(client)
    await ensureLocalDataTables(client)
    await ensureScheduledMessageTables(client)
    await ensurePostgrestCompatTables(client)
    await ensureGooglePlayRtdnTables(client)
  },
})
  .then(() => {
    server = app.listen(port, '0.0.0.0', () => {
      console.log(`${serviceName} ${version} listening on ${port}`)
    })
    // Match the Android upload budget while retaining strict header and idle
    // connection limits. TUS chunks normally complete much sooner; this also
    // protects the rolling-deployment fallback that still sends one PUT.
    server.requestTimeout = 20 * 60_000
    server.headersTimeout = 15_000
    server.keepAliveTimeout = 65_000
    server.maxHeadersCount = 100
    const runRateLimitCleanup = () => {
      void pruneExpiredRateLimitWindows(pool).catch((error) => {
        console.error('[rate-limit] cleanup failed', safeErrorLogDetails(error, {
          production: isProduction,
          fallbackCode: 'UNKNOWN',
        }))
      })
    }
    runRateLimitCleanup()
    rateLimitCleanupTimer = setInterval(runRateLimitCleanup, 60 * 60_000)
    rateLimitCleanupTimer.unref?.()
    const runResumableUploadCleanup = () => {
      void cleanupExpiredResumableUploads(pool).catch((error) => {
        console.error('[storage] resumable cleanup failed', safeErrorLogDetails(error, {
          production: isProduction,
          fallbackCode: 'UNKNOWN',
        }))
      })
    }
    runResumableUploadCleanup()
    resumableUploadCleanupTimer = setInterval(runResumableUploadCleanup, 15 * 60_000)
    resumableUploadCleanupTimer.unref?.()
    liveDrawGuessReconciler = startLiveDrawGuessReconciler({ pool })
    const runScheduledMessages = () => {
      void processDueScheduledMessageJobs(pool).catch((error) => {
        console.error('[scheduled-messages] worker failed', safeErrorLogDetails(error, { production: isProduction }))
      })
    }
    runScheduledMessages()
    scheduledMessageTimer = setInterval(runScheduledMessages, 5_000)
    scheduledMessageTimer.unref?.()
    const runNotificationDigests = () => {
      void processDueNotificationDigests(pool).catch((error) => {
        console.error('[notification-digest] worker failed', safeErrorLogDetails(error, { production: isProduction }))
      })
    }
    runNotificationDigests()
    notificationDigestTimer = setInterval(runNotificationDigests, 60_000)
    notificationDigestTimer.unref?.()
    const runLiveFollowerNotifications = () => {
      void processPendingLiveFollowerNotifications(pool).catch((error) => {
        console.error('[live-followers] notification worker failed', safeErrorLogDetails(error, {
          production: isProduction,
          fallbackCode: 'UNKNOWN',
        }))
      })
    }
    runLiveFollowerNotifications()
    liveFollowerNotificationTimer = setInterval(runLiveFollowerNotifications, 30_000)
    liveFollowerNotificationTimer.unref?.()
    const runCommunityEventReminders = () => {
      void processDueCommunityEventReminders(pool).catch((error) => {
        console.error('[community-event-reminders] worker failed', safeErrorLogDetails(error, { production: isProduction }))
      })
    }
    runCommunityEventReminders()
    communityEventReminderTimer = setInterval(runCommunityEventReminders, 30_000)
    communityEventReminderTimer.unref?.()
    const runBirthdayNotifications = () => {
      void processBirthdayNotifications(pool).catch((error) => {
        console.error('[birthday-notifications] worker failed', safeErrorLogDetails(error, { production: isProduction }))
      })
    }
    runBirthdayNotifications()
    birthdayNotificationTimer = setInterval(runBirthdayNotifications, 15 * 60_000)
    birthdayNotificationTimer.unref?.()
    const refundReconciliationSetting = String(
      process.env.GOOGLE_PLAY_REFUND_RECONCILIATION_ENABLED || '',
    ).trim()
    const refundReconciliationEnabled = refundReconciliationSetting
      ? /^(1|true|yes|on)$/i.test(refundReconciliationSetting)
      : true
    const refundIntervalMinutes = Math.max(5, Math.min(
      Math.trunc(Number(process.env.GOOGLE_PLAY_REFUND_RECONCILIATION_INTERVAL_MINUTES || 5) || 5),
      24 * 60,
    ))
    const runGooglePlayRefundReconciliation = () => {
      if (!refundReconciliationEnabled || googlePlayRefundRunning) return
      googlePlayRefundRunning = true
      void reconcileGooglePlayVoidedPurchases(pool)
        .then((result) => {
          console.log('[google-play-refunds] reconciliation complete', {
            fetched: result.fetched,
            reversed: result.reversed,
            unmatched: result.unmatched,
          })
        })
        .catch((error) => {
          console.error('[google-play-refunds] reconciliation failed', safeErrorLogDetails(error, {
            production: isProduction,
            fallbackCode: 'UNKNOWN',
          }))
        })
        .finally(() => { googlePlayRefundRunning = false })
    }
    runGooglePlayRefundReconciliation()
    googlePlayRefundTimer = setInterval(
      runGooglePlayRefundReconciliation,
      refundIntervalMinutes * 60_000,
    )
    googlePlayRefundTimer.unref?.()
    const runCreatorPayouts = () => {
      if (creatorPayoutRunning) return
      creatorPayoutRunning = true
      void processApprovedCreatorPayouts(pool)
        .then(() => reconcileCreatorPayouts(pool))
        .catch((error) => {
          console.error('[creator-payouts] worker failed', safeErrorLogDetails(error, {
            production: isProduction,
            fallbackCode: 'UNKNOWN',
          }))
        })
        .finally(() => { creatorPayoutRunning = false })
    }
    runCreatorPayouts()
    creatorPayoutTimer = setInterval(runCreatorPayouts, 60_000)
    creatorPayoutTimer.unref?.()
  })
  .catch((error) => {
    console.error('[api] failed to initialize local data tables', safeErrorLogDetails(error, { production: isProduction }))
    process.exit(1)
  })

async function shutdown(signal) {
  console.log(`received ${signal}, shutting down`)
  if (scheduledMessageTimer) clearInterval(scheduledMessageTimer)
  if (notificationDigestTimer) clearInterval(notificationDigestTimer)
  if (communityEventReminderTimer) clearInterval(communityEventReminderTimer)
  if (birthdayNotificationTimer) clearInterval(birthdayNotificationTimer)
  if (googlePlayRefundTimer) clearInterval(googlePlayRefundTimer)
  if (liveFollowerNotificationTimer) clearInterval(liveFollowerNotificationTimer)
  if (creatorPayoutTimer) clearInterval(creatorPayoutTimer)
  if (rateLimitCleanupTimer) clearInterval(rateLimitCleanupTimer)
  if (resumableUploadCleanupTimer) clearInterval(resumableUploadCleanupTimer)
  liveDrawGuessReconciler?.stop()
  if (!server) {
    await pool.end()
    process.exit(0)
  }
  server.close(async () => {
    await pool.end()
    process.exit(0)
  })
}

process.on('SIGTERM', shutdown)
process.on('SIGINT', shutdown)
