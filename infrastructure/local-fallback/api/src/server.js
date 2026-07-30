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
const { ensurePostgrestCompatTables, registerPostgrestCompatRoutes } = require('./postgrest-compat-routes')
const { ensureLocalStorageTables, registerStorageRoutes } = require('./storage-routes')
const { registerAiRoutes } = require('./ai-routes')
const { pushNotificationsStatus } = require('./push-notifications')
const { accountSuspendedPayload, findActiveSuspension } = require('./account-status')
const { canonicalStorageResponseMiddleware } = require('./storage-url-canonicalization')
const { collectSystemHealth, loadReleaseMetadata } = require('./system-health')
const {
  boundedInteger,
  createRateLimitMiddleware,
  isTrustedProxyAddress,
  requestIpAddress,
  stableKey,
} = require('./request-security')
const {
  ensureScheduledMessageTables,
  processDueScheduledMessageJobs,
} = require('./scheduled-message-jobs')
const { processDueNotificationDigests } = require('./notification-digest-routes')

const serviceName = 'conectate-local-fallback'
const version = '0.1.0'
const port = Number(process.env.PORT || 3001)
const jwtSecret = String(process.env.JWT_SECRET || '')
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
const databaseUrl = process.env.DATABASE_URL
const corsOrigin = process.env.CORS_ORIGIN || '*'
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
const adminSyncToken = String(process.env.ADMIN_SYNC_TOKEN || '').trim()
const passwordResetDelivery = String(process.env.PASSWORD_RESET_DELIVERY || 'console').trim().toLowerCase()
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

const pool = new Pool({
  connectionString: databaseUrl,
  max: 10,
  idleTimeoutMillis: 30_000,
  connectionTimeoutMillis: 10_000,
  // Last-resort protection for production: an abandoned transaction must not
  // retain row locks until every API connection is exhausted.
  options: '-c statement_timeout=30000 -c lock_timeout=10000 -c idle_in_transaction_session_timeout=15000',
})

function logDatabasePoolError(error) {
  console.error('[api] database pool connection lost', {
    code: String(error?.code || 'UNKNOWN'),
    severity: String(error?.severity || 'ERROR'),
    message: String(error?.message || 'Database connection error').slice(0, 240),
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
app.use(cors({ origin: corsOrigin === '*' ? true : corsOrigin }))
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
app.use(canonicalStorageResponseMiddleware)

const authIpKey = (req) => `ip:${requestIpAddress(req)}`
const authCredentialKey = (req) => {
  const email = normalizeEmail(req.body?.email || req.body?.identifier)
  return `${authIpKey(req)}:credential:${stableKey(email || 'missing')}`
}

app.use('/v1/auth/register', createRateLimitMiddleware({
  limit: boundedInteger(process.env.AUTH_REGISTER_RATE_LIMIT_PER_HOUR, 10, 2, 100),
  windowMs: 60 * 60_000,
  code: 'AUTH_REGISTER_RATE_LIMITED',
  key: authIpKey,
  methods: ['POST'],
}))
app.use('/v1/auth/login', createRateLimitMiddleware({
  limit: boundedInteger(process.env.AUTH_LOGIN_RATE_LIMIT_PER_15_MINUTES, 20, 5, 200),
  windowMs: 15 * 60_000,
  code: 'AUTH_LOGIN_RATE_LIMITED',
  key: authCredentialKey,
  methods: ['POST'],
}))
app.use('/v1/auth/refresh', createRateLimitMiddleware({
  limit: boundedInteger(process.env.AUTH_REFRESH_RATE_LIMIT_PER_15_MINUTES, 60, 10, 300),
  windowMs: 15 * 60_000,
  code: 'AUTH_REFRESH_RATE_LIMITED',
  key: authIpKey,
  methods: ['POST'],
}))
app.use('/v1/auth/password-reset/request', createRateLimitMiddleware({
  limit: boundedInteger(process.env.AUTH_PASSWORD_RESET_RATE_LIMIT_PER_15_MINUTES, 5, 2, 50),
  windowMs: 15 * 60_000,
  code: 'AUTH_PASSWORD_RESET_RATE_LIMITED',
  key: authCredentialKey,
  methods: ['POST'],
}))
app.use([
  '/v1/auth/password-reset/confirm',
  '/v1/auth/complete-migrated-password-reset',
  '/v1/auth/activate-local-password',
], createRateLimitMiddleware({
  limit: boundedInteger(process.env.AUTH_SENSITIVE_RATE_LIMIT_PER_15_MINUTES, 12, 3, 100),
  windowMs: 15 * 60_000,
  code: 'AUTH_SENSITIVE_ACTION_RATE_LIMITED',
  key: authIpKey,
  methods: ['POST'],
}))
app.use([
  '/v1/auth/web-login-pairing',
  '/functions/v1/web-login-pairing',
], createRateLimitMiddleware({
  limit: boundedInteger(process.env.AUTH_PAIRING_RATE_LIMIT_PER_MINUTE, 90, 10, 300),
  windowMs: 60_000,
  code: 'AUTH_PAIRING_RATE_LIMITED',
  key: authIpKey,
  methods: ['POST'],
}))
app.use([
  '/v1/storage/upload',
  '/storage/v1/object',
], createRateLimitMiddleware({
  limit: boundedInteger(process.env.STORAGE_UPLOAD_RATE_LIMIT_PER_10_MINUTES, 120, 10, 1000),
  windowMs: 10 * 60_000,
  code: 'STORAGE_UPLOAD_RATE_LIMITED',
  key: authIpKey,
  methods: ['POST', 'PUT'],
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
  if (!provided || provided !== adminSyncToken) {
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
      expiresIn: jwtExpiresIn,
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
  if (!sessionId) return true
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
     RETURNING id`,
    [sessionId, auth.sub, requestIpAddress(req), requestUserAgent(req)],
  )

  return Boolean(rows[0])
}

async function authRequired(req, res, next) {
  const authorization = String(req.headers.authorization || '')
  const token = authorization.replace(/^Bearer\s+/i, '').trim()

  if (!token) {
    return res.status(401).json({ error: 'AUTH_REQUIRED' })
  }

  try {
    req.auth = jwt.verify(token, jwtSecret, {
      issuer: serviceName,
      audience: 'conectate-clients',
    })
    const { rows: activeUsers } = await pool.query(
      `SELECT 1
       FROM fallback_users
       WHERE id = $1::uuid
       LIMIT 1`,
      [req.auth.sub],
    )
    if (!activeUsers[0]) {
      return res.status(401).json({ error: 'ACCOUNT_DELETED' })
    }
    const suspension = await findActiveSuspension(pool, req.auth.sub)
    if (suspension) {
      return res.status(403).json({
        ...accountSuspendedPayload(suspension),
        appealToken: signAppealToken(req.auth.sub),
        appealTokenExpiresIn: '14d',
      })
    }
    const activeSession = await touchWebSessionForAuth(req.auth, req)
    if (!activeSession) {
      return res.status(401).json({ error: 'WEB_SESSION_REVOKED' })
    }
    return next()
  } catch (error) {
    if (error?.name && String(error.name).includes('JsonWebToken')) {
      return res.status(401).json({ error: 'INVALID_TOKEN' })
    }
    if (error?.name === 'TokenExpiredError') {
      return res.status(401).json({ error: 'INVALID_TOKEN' })
    }
    return next(error)
  }
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
    revokedAt: row.revoked_at || null,
  }
}

async function createWebSessionForPairing(user, pairing, req) {
  const { rows } = await pool.query(
    `INSERT INTO fallback_web_sessions(user_id, pairing_id, device_label, ip_address, user_agent)
     VALUES ($1, $2, $3, $4, $5)
     RETURNING *`,
    [
      user.id,
      pairing.id,
      cleanText(pairing.device_label || 'Conectate Web', 120),
      requestIpAddress(req),
      requestUserAgent(req),
    ],
  )
  return rows[0]
}

function authOptional(req, _res, next) {
  const authorization = String(req.headers.authorization || '')
  const token = authorization.replace(/^Bearer\s+/i, '').trim()

  if (!token) {
    return next()
  }

  try {
    req.auth = jwt.verify(token, jwtSecret, {
      issuer: serviceName,
      audience: 'conectate-clients',
    })
  } catch {
    // Compatibility routes still need to answer legacy read/RPC calls gracefully.
  }

  return next()
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
    console.warn('[audit] failed', error.message)
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
    console.warn('[auth] could not inspect restored auth schema', error.message)
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
      console.warn('[auth] could not read restored auth.users metadata', error.message)
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
      console.warn('[auth] could not read restored auth.users password', error.message)
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
    console.warn('[auth] restored password hash could not be checked', error.message)
    return false
  }
}

async function ensureAuthMetadataColumns() {
  await pool.query(
    'ALTER TABLE fallback_users ADD COLUMN IF NOT EXISTS password_changed_by_user_at TIMESTAMPTZ',
  )
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_profile_login_emails (
      user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      email TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (user_id, email)
    )
  `)
  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_profile_login_emails_unique
    ON fallback_profile_login_emails(lower(email))
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_password_reset_tokens (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      token_hash TEXT NOT NULL UNIQUE,
      expires_at TIMESTAMPTZ NOT NULL,
      consumed_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_password_reset_tokens_user
    ON fallback_password_reset_tokens(user_id, created_at DESC)
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_password_reset_tokens_active
    ON fallback_password_reset_tokens(expires_at)
    WHERE consumed_at IS NULL
  `)
  await pool.query(`
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
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_auth_sessions_user_active
    ON fallback_auth_sessions(user_id, expires_at DESC)
    WHERE revoked_at IS NULL
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_auth_sessions_expiry
    ON fallback_auth_sessions(expires_at)
  `)
  await pool.query(`
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
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_web_login_pairings_status
    ON fallback_web_login_pairings(status, expires_at)
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_web_login_pairings_created
    ON fallback_web_login_pairings(created_at DESC)
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_web_sessions (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id UUID NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      pairing_id UUID REFERENCES fallback_web_login_pairings(id) ON DELETE SET NULL,
      device_label TEXT,
      ip_address TEXT,
      user_agent TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      revoked_at TIMESTAMPTZ,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_web_sessions_user_active
    ON fallback_web_sessions(user_id, last_seen_at DESC)
    WHERE revoked_at IS NULL
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_web_sessions_pairing
    ON fallback_web_sessions(pairing_id)
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
      ],
      databaseTime: rows[0].now,
      pushNotifications: pushNotificationsStatus(),
    })
  } catch (error) {
    next(error)
  }
})

app.get('/v1/system/health', async (_req, res, next) => {
  try {
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

app.get('/v1/upstream/supabase', async (_req, res) => {
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
  try {
    const email = normalizeEmail(req.body?.email)
    const password = String(req.body?.password || '')

    if (!isValidEmail(email) || password.length < 8) {
      return res.status(400).json({ error: 'EMAIL_AND_STRONG_PASSWORD_REQUIRED' })
    }

    const passwordHash = await bcrypt.hash(password, 12)
    const { rows } = await pool.query(
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

    if (!rows[0]) {
      return res.status(404).json({ error: 'USER_NOT_FOUND' })
    }

    const user = sanitizeUser(await hydrateUserRow(rows[0]))
    await recordAudit('admin_set_local_password', user.id, { email })
    return res.json({ ok: true, user })
  } catch (error) {
    return next(error)
  }
})

app.post('/v1/auth/register', async (req, res, next) => {
  try {
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

    const session = await issueUserSession(user, req)
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

    const updated = await pool.query(
      `UPDATE fallback_users
       SET last_login_at = NOW()
       WHERE id = $1
       RETURNING ${userSelectColumns}`,
      [row.id],
    )
    const user = sanitizeUser(await hydrateUserRow(updated.rows[0]))
    await recordAudit('auth_login', user.id, { email })

    return res.json(await issueUserSession(user, req))
  } catch (error) {
    return next(error)
  }
})

app.post('/v1/auth/refresh', async (req, res, next) => {
  const refreshToken = String(req.body?.refreshToken || '').trim()
  const accessToken = String(req.body?.accessToken || '').trim()
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

    let row = activeSession.rows[0]
    let consumedSessionId = row?.auth_session_id || null
    let recoveryMode = consumedSessionId ? 'active_refresh' : null

    // One-time migration path for APKs that stored the old access JWT as
    // their refresh credential. It only accepts a currently valid token
    // signed by this server; an email alone can never restore a session.
    if (!row) {
      let legacyPayload
      try {
        legacyPayload = verifyLegacyUserToken(refreshToken)
      } catch {
        legacyPayload = null
      }
      if (
        legacyPayload?.provider === 'local-fallback' &&
        isUuidText(legacyPayload?.sub) &&
        legacyPayload?.client !== 'web' &&
        !legacyPayload?.sid
      ) {
        const legacyUser = await client.query(
          `SELECT ${userSelectColumns}
           FROM fallback_users
           WHERE id = $1
           LIMIT 1
           FOR UPDATE`,
          [legacyPayload.sub],
        )
        row = legacyUser.rows[0]
        recoveryMode = row ? 'legacy_access_token' : null
      }
    }

    // Recovery for an APK that lost the response from the former rotating
    // refresh flow. The signed access token must still be valid, its own
    // session must be revoked, and a newer active replacement must exist.
    // A normal logout has no active replacement and therefore cannot use this.
    if (!row && accessToken) {
      let accessPayload
      try {
        accessPayload = verifyLegacyUserToken(accessToken)
      } catch {
        accessPayload = null
      }
      if (
        accessPayload?.provider === 'local-fallback' &&
        isUuidText(accessPayload?.sub) &&
        isUuidText(accessPayload?.sid) &&
        accessPayload?.client !== 'web'
      ) {
        const recoverableUser = await client.query(
          `SELECT ${userSelectColumns}
           FROM fallback_users
           WHERE fallback_users.id = $1
             AND EXISTS (
               SELECT 1
               FROM fallback_auth_sessions lost_session
               WHERE lost_session.id = $2
                 AND lost_session.user_id = fallback_users.id
                 AND lost_session.revoked_at IS NOT NULL
             )
             AND EXISTS (
               SELECT 1
               FROM fallback_auth_sessions replacement_session
               WHERE replacement_session.user_id = fallback_users.id
                 AND replacement_session.revoked_at IS NULL
                 AND replacement_session.expires_at > NOW()
             )
           LIMIT 1
           FOR UPDATE`,
          [accessPayload.sub, accessPayload.sid],
        )
        row = recoverableUser.rows[0]
        recoveryMode = row ? 'lost_rotation_response' : null
      }
    }

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
    let session
    if (consumedSessionId) {
      // Keep the opaque mobile refresh credential reusable until its explicit
      // expiry/revocation. Rotating it on every request made a valid account
      // unusable when the response was lost after the old token was revoked
      // (app closed, radio change or timeout).
      await client.query(
        `UPDATE fallback_auth_sessions
         SET last_used_at = NOW(),
             updated_at = NOW()
         WHERE id = $1`,
        [consumedSessionId],
      )
      session = {
        user,
        accessToken: signUserToken(user, {
          sessionId: consumedSessionId,
          client: 'android',
        }),
        refreshToken,
        tokenType: 'Bearer',
        expiresIn: jwtExpiresIn,
      }
    } else {
      // Legacy APKs and safely recovered lost rotation responses both need a
      // new opaque refresh session.
      session = await issueUserSession(user, req, { executor: client, client: 'android' })
    }
    // Keep the audit insert on the same transaction connection. Using `pool`
    // here waits for a user-row lock held by this transaction, leaving the
    // transaction idle forever and eventually exhausting the whole pool.
    await recordAudit('auth_refresh', user.id, {
      recoveryMode,
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

app.post('/v1/auth/change-password', authRequired, async (req, res, next) => {
  try {
    const currentPassword = String(req.body?.currentPassword || '')
    const newPassword = String(req.body?.newPassword || '')

    if (!currentPassword || newPassword.length < 8) {
      return res.status(400).json({ error: 'CURRENT_AND_NEW_PASSWORD_REQUIRED' })
    }
    if (currentPassword === newPassword) {
      return res.status(400).json({ error: 'NEW_PASSWORD_MUST_BE_DIFFERENT' })
    }

    const { rows } = await pool.query(
      `SELECT ${userSelectColumns}
       FROM fallback_users
       WHERE id = $1
       LIMIT 1`,
      [req.auth.sub],
    )

    const row = rows[0]
    if (!row) {
      return res.status(404).json({ error: 'USER_NOT_FOUND' })
    }
    if (!row.password_enabled || !row.password_hash || !(await bcrypt.compare(currentPassword, row.password_hash))) {
      return res.status(401).json({
        error: 'INVALID_PASSWORD',
        message: 'La contraseña actual es incorrecta.',
      })
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
           sync_error = NULL
       WHERE id = $1
       RETURNING ${userSelectColumns}`,
      [row.id, passwordHash],
    )

    const user = sanitizeUser(await hydrateUserRow(updated.rows[0]))
    await recordAudit('auth_change_password', user.id, { email: user.email })
    await revokeUserAuthSessions(user.id)
    return res.json({ ok: true, ...(await issueUserSession(user, req)) })
  } catch (error) {
    return next(error)
  }
})

app.post('/v1/auth/password-reset/request', async (req, res, next) => {
  try {
    const email = normalizeEmail(req.body?.email)
    const baseResponse = {
      ok: true,
      delivery: passwordResetDelivery === 'response' ? 'response' : passwordResetDelivery,
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

    const delivery = await deliverPasswordResetToken(sanitizeUser(await hydrateUserRow(row)), token)
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
  try {
    const resetToken = String(req.body?.resetToken || req.body?.token || '').trim()
    const newPassword = String(req.body?.newPassword || req.body?.password || '')

    if (!resetToken || newPassword.length < 8) {
      return res.status(400).json({ error: 'RESET_TOKEN_AND_PASSWORD_REQUIRED' })
    }

    const tokenHash = hashPasswordResetToken(resetToken)
    const { rows } = await pool.query(
      `SELECT token.id AS token_id, token.user_id, ${userSelectColumns
        .split(',')
        .map((column) => `fallback_users.${column.trim()}`)
        .join(', ')}
       FROM fallback_password_reset_tokens token
       JOIN fallback_users ON fallback_users.id = token.user_id
       WHERE token.token_hash = $1
         AND token.consumed_at IS NULL
         AND token.expires_at > NOW()
       LIMIT 1`,
      [tokenHash],
    )

    const row = rows[0]
    if (!row) {
      return res.status(401).json({ error: 'INVALID_OR_EXPIRED_RESET_TOKEN' })
    }

    const passwordHash = await bcrypt.hash(newPassword, 12)
    await pool.query(
      `UPDATE fallback_password_reset_tokens
       SET consumed_at = NOW()
       WHERE id = $1`,
      [row.token_id],
    )
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
      [row.user_id, passwordHash],
    )

    const user = sanitizeUser(await hydrateUserRow(updated.rows[0]))
    await recordAudit('auth_password_reset_completed', user.id, { email: user.email })
    await revokeUserAuthSessions(user.id)
    return res.json({ ok: true, ...(await issueUserSession(user, req)) })
  } catch (error) {
    return next(error)
  }
})

app.post('/v1/auth/complete-migrated-password-reset', async (req, res, next) => {
  try {
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
    await revokeUserAuthSessions(user.id)
    return res.json({ ok: true, ...(await issueUserSession(user, req)) })
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
      const webSession = await createWebSessionForPairing(user, updateResult.rows[0], req)
      const webAccessToken = signUserToken(user, { sessionId: webSession.id, client: 'web' })
      await recordAudit('auth_web_login_pairing_consumed', user.id, { pairId: pairing.id })

      return res.json(
        serializePairing(updateResult.rows[0], {
          user,
          accessToken: webAccessToken,
          refreshToken: webAccessToken,
          tokenType: 'Bearer',
          expiresIn: jwtExpiresIn,
          webSession: serializeWebSession(webSession),
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
    await revokeUserAuthSessions(user.id)
    return res.json({ ok: true, ...(await issueUserSession(user, req)) })
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
  const supabaseAdmin = getSupabaseAdminClient()
  if (!supabaseAdmin) {
    const error = new Error('EXTERNAL_AUTH_DELETE_UNAVAILABLE')
    error.status = 409
    throw error
  }
  const { error } = await supabaseAdmin.auth.admin.deleteUser(userId)
  if (!error) return { deleted: true, alreadyMissing: false }
  if (Number(error.status) === 404) return { deleted: false, alreadyMissing: true }
  const deleteError = new Error('EXTERNAL_AUTH_DELETE_FAILED')
  deleteError.status = 502
  deleteError.cause = error
  throw deleteError
}

registerStorageRoutes(app, { pool, authRequired })
registerDataRoutes(app, { pool, authRequired, appealAuthRequired, deleteExternalAuthUser })
registerPostgrestCompatRoutes(app, { pool, authRequired, authOptional })
registerAiRoutes(app, { authRequired })

app.use((req, res) => {
  res.status(404).json({ error: 'NOT_FOUND', path: req.path })
})

app.use((error, _req, res, _next) => {
  console.error('[api] request failed', {
    requestId: _req.requestId,
    code: String(error?.code || 'INTERNAL_ERROR'),
    message: String(error?.message || 'Unexpected request error').slice(0, 240),
  })
  const status = Number(error?.status || error?.statusCode || 500)
  const safeStatus = status >= 400 && status <= 599 ? status : 500
  const publicCode = safeStatus < 500
    ? String(error?.message || 'REQUEST_REJECTED').replace(/[^A-Z0-9_]/gi, '_').slice(0, 80)
    : 'INTERNAL_ERROR'
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

ensureAuthMetadataColumns()
  .then(() => ensureLocalStorageTables(pool))
  .then(() => ensureLocalDataTables(pool))
  .then(() => ensureScheduledMessageTables(pool))
  .then(() => ensurePostgrestCompatTables(pool))
  .then(() => {
    server = app.listen(port, '0.0.0.0', () => {
      console.log(`${serviceName} ${version} listening on ${port}`)
    })
    const runScheduledMessages = () => {
      void processDueScheduledMessageJobs(pool).catch((error) => {
        console.error('[scheduled-messages] worker failed', {
          message: String(error?.message || error).slice(0, 240),
        })
      })
    }
    runScheduledMessages()
    scheduledMessageTimer = setInterval(runScheduledMessages, 5_000)
    scheduledMessageTimer.unref?.()
    const runNotificationDigests = () => {
      void processDueNotificationDigests(pool).catch((error) => {
        console.error('[notification-digest] worker failed', {
          message: String(error?.message || error).slice(0, 240),
        })
      })
    }
    runNotificationDigests()
    notificationDigestTimer = setInterval(runNotificationDigests, 60_000)
    notificationDigestTimer.unref?.()
    const runCommunityEventReminders = () => {
      void processDueCommunityEventReminders(pool).catch((error) => {
        console.error('[community-event-reminders] worker failed', {
          message: String(error?.message || error).slice(0, 240),
        })
      })
    }
    runCommunityEventReminders()
    communityEventReminderTimer = setInterval(runCommunityEventReminders, 30_000)
    communityEventReminderTimer.unref?.()
    const runBirthdayNotifications = () => {
      void processBirthdayNotifications(pool).catch((error) => {
        console.error('[birthday-notifications] worker failed', {
          message: String(error?.message || error).slice(0, 240),
        })
      })
    }
    runBirthdayNotifications()
    birthdayNotificationTimer = setInterval(runBirthdayNotifications, 15 * 60_000)
    birthdayNotificationTimer.unref?.()
  })
  .catch((error) => {
    console.error('[api] failed to initialize local data tables', error)
    process.exit(1)
  })

async function shutdown(signal) {
  console.log(`received ${signal}, shutting down`)
  if (scheduledMessageTimer) clearInterval(scheduledMessageTimer)
  if (notificationDigestTimer) clearInterval(notificationDigestTimer)
  if (communityEventReminderTimer) clearInterval(communityEventReminderTimer)
  if (birthdayNotificationTimer) clearInterval(birthdayNotificationTimer)
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
