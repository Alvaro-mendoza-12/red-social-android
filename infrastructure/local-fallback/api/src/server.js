const bcrypt = require('bcryptjs')
const cors = require('cors')
const crypto = require('crypto')
const express = require('express')
const helmet = require('helmet')
const jwt = require('jsonwebtoken')
const { Pool } = require('pg')
const { ensureLocalDataTables, registerDataRoutes } = require('./data-routes')
const { ensureLocalStorageTables, registerStorageRoutes } = require('./storage-routes')

const serviceName = 'conectate-local-fallback'
const version = '0.1.0'
const port = Number(process.env.PORT || 3001)
const jwtSecret = String(process.env.JWT_SECRET || '')
const jwtExpiresIn = process.env.JWT_EXPIRES_IN || '7d'
const databaseUrl = process.env.DATABASE_URL
const corsOrigin = process.env.CORS_ORIGIN || '*'
const supabaseUrl = String(process.env.SUPABASE_URL || '').trim().replace(/\/+$/, '')
const supabaseAnonKey = String(process.env.SUPABASE_ANON_KEY || '').trim()
const supabaseAdminKey = String(
  process.env.SUPABASE_SECRET_KEY || process.env.SUPABASE_SERVICE_ROLE_KEY || '',
).trim()
const adminSyncToken = String(process.env.ADMIN_SYNC_TOKEN || '').trim()
const passwordResetDelivery = String(process.env.PASSWORD_RESET_DELIVERY || 'console').trim().toLowerCase()
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
})

const app = express()
app.disable('x-powered-by')
app.use(helmet())
app.use(cors({ origin: corsOrigin === '*' ? true : corsOrigin }))
app.use(express.json({ limit: '1mb' }))

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

function signUserToken(user) {
  return jwt.sign(
    {
      email: user.email,
      provider: 'local-fallback',
    },
    jwtSecret,
    {
      subject: user.id,
      expiresIn: jwtExpiresIn,
      issuer: serviceName,
      audience: 'conectate-clients',
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

function authRequired(req, res, next) {
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
    return next()
  } catch {
    return res.status(401).json({ error: 'INVALID_TOKEN' })
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

async function recordAudit(eventName, userId, metadata = {}) {
  try {
    await pool.query(
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

async function hydrateUserRow(row) {
  if (!row) return null
  if (!row.supabase_user_id) {
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
  res.json({ ok: true, service: serviceName, version, time: new Date().toISOString() })
})

app.get('/ready', async (_req, res, next) => {
  try {
    const { rows } = await pool.query('SELECT NOW() AS now')
    res.json({ ok: true, service: serviceName, version, databaseTime: rows[0].now })
  } catch (error) {
    next(error)
  }
})

app.get('/v1/upstream/supabase', async (_req, res) => {
  if (!supabaseUrl || !supabaseAnonKey) {
    return res.json({ configured: false, ok: false, restricted: false })
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
      return res.status(400).json({ error: 'PASSWORD_TOO_SHORT' })
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

    return res.status(201).json({
      user,
      accessToken: signUserToken(user),
      tokenType: 'Bearer',
      expiresIn: jwtExpiresIn,
      supabaseSync,
    })
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
       LIMIT 1`,
      [email],
    )

    let row = rows[0]
    if (!row) {
      return res.status(401).json({ error: 'INVALID_CREDENTIALS' })
    }

    const hasLocalPassword = Boolean(row.password_enabled && row.password_hash)
    if (!hasLocalPassword) {
      const restoredPasswordOk = await verifyRestoredSupabasePassword(row, password)
      if (!restoredPasswordOk) {
        return res.status(401).json({ error: 'INVALID_CREDENTIALS' })
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
      return res.status(401).json({ error: 'INVALID_CREDENTIALS' })
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

    return res.json({
      user,
      accessToken: signUserToken(user),
      tokenType: 'Bearer',
      expiresIn: jwtExpiresIn,
    })
  } catch (error) {
    return next(error)
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
      return res.status(401).json({ error: 'INVALID_CREDENTIALS' })
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
    return res.json({
      ok: true,
      user,
      accessToken: signUserToken(user),
      tokenType: 'Bearer',
      expiresIn: jwtExpiresIn,
    })
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
    return res.json({
      ok: true,
      user,
      accessToken: signUserToken(user),
      tokenType: 'Bearer',
      expiresIn: jwtExpiresIn,
    })
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
    return res.json({
      ok: true,
      user,
      accessToken: signUserToken(user),
      tokenType: 'Bearer',
      expiresIn: jwtExpiresIn,
    })
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

app.post('/v1/auth/web-login-pairing', async (req, res, next) => {
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
      await recordAudit('auth_web_login_pairing_consumed', user.id, { pairId: pairing.id })

      return res.json(
        serializePairing(updateResult.rows[0], {
          user,
          accessToken: signUserToken(user),
          refreshToken: signUserToken(user),
          tokenType: 'Bearer',
          expiresIn: jwtExpiresIn,
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
    return res.json({
      ok: true,
      user,
      accessToken: signUserToken(user),
      tokenType: 'Bearer',
      expiresIn: jwtExpiresIn,
    })
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
      return res.status(401).json({ error: 'INVALID_CREDENTIALS' })
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

registerStorageRoutes(app, { pool, authRequired })
registerDataRoutes(app, { pool, authRequired })

app.use((req, res) => {
  res.status(404).json({ error: 'NOT_FOUND', path: req.path })
})

app.use((error, _req, res, _next) => {
  console.error('[api] unhandled error', error)
  res.status(500).json({ error: 'INTERNAL_ERROR' })
})

if (process.env.ROUTE_CHECK_ONLY === '1') {
  console.log(`${serviceName} ${version} route registration OK`)
  process.exit(0)
}

let server

ensureAuthMetadataColumns()
  .then(() => ensureLocalStorageTables(pool))
  .then(() => ensureLocalDataTables(pool))
  .then(() => {
    server = app.listen(port, '0.0.0.0', () => {
      console.log(`${serviceName} ${version} listening on ${port}`)
    })
  })
  .catch((error) => {
    console.error('[api] failed to initialize local data tables', error)
    process.exit(1)
  })

async function shutdown(signal) {
  console.log(`received ${signal}, shutting down`)
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
