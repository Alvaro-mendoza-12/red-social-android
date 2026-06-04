const crypto = require('crypto')

function normalizeId(value) {
  return String(value || '').trim().toLowerCase()
}

function textOf(row, keys, fallback = '') {
  for (const key of keys) {
    const value = row?.[key]
    if (value !== undefined && value !== null && String(value).trim() !== '') {
      return String(value).trim()
    }
  }
  return fallback
}

function boolOf(row, keys, fallback = false) {
  for (const key of keys) {
    const value = row?.[key]
    if (value === undefined || value === null) continue
    if (typeof value === 'boolean') return value
    const text = String(value).toLowerCase()
    if (text === 'true' || text === '1') return true
    if (text === 'false' || text === '0') return false
  }
  return fallback
}

async function ensureAdminModerationTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_profile_moderation (
      user_id TEXT PRIMARY KEY,
      is_suspended BOOLEAN NOT NULL DEFAULT FALSE,
      suspended_until TIMESTAMPTZ,
      suspension_end_date TIMESTAMPTZ,
      suspension_reason TEXT,
      suspension_count INT NOT NULL DEFAULT 0,
      is_admin BOOLEAN NOT NULL DEFAULT FALSE,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_moderation_events (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      actor_id TEXT,
      context TEXT,
      content_ref TEXT,
      verdict TEXT,
      metadata_preview TEXT,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_video_reports (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      video_id TEXT,
      video_owner_id TEXT,
      video_owner_name TEXT,
      video_title TEXT,
      reporter_id TEXT,
      reporter_name TEXT,
      reason TEXT,
      details TEXT,
      status TEXT NOT NULL DEFAULT 'pendiente',
      warn_sent BOOLEAN NOT NULL DEFAULT FALSE,
      strike_applied BOOLEAN NOT NULL DEFAULT FALSE,
      action_note TEXT,
      reviewed_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
}

async function isAppAdmin(pool, identity) {
  const configured = String(process.env.ADMIN_USER_IDS || '')
    .split(',')
    .map((entry) => normalizeId(entry))
    .filter(Boolean)
  if (configured.some((id) => identity.ids.has(id))) return true

  const emails = String(process.env.ADMIN_EMAILS || 'gamergamerff004@gmail.com,alvaromendozamarin004@gmail.com,gamergamerff017@gmail.com')
    .split(',')
    .map((entry) => entry.trim().toLowerCase())
    .filter(Boolean)

  const { rows } = await pool.query(
    `SELECT email, display_name FROM fallback_users WHERE id = $1 LIMIT 1`,
    [identity.localUserId],
  )
  const email = String(rows[0]?.email || '').trim().toLowerCase()
  if (email && emails.includes(email)) return true

  const { rows: modRows } = await pool.query(
    `SELECT is_admin FROM fallback_profile_moderation WHERE user_id = $1 LIMIT 1`,
    [identity.displayUserId],
  )
  if (modRows[0]?.is_admin) return true

  return false
}

async function recordModerationEvent(pool, actorId, payload) {
  await pool.query(
    `INSERT INTO fallback_moderation_events(actor_id, context, content_ref, verdict, metadata_preview)
     VALUES ($1, $2, $3, $4, $5)`,
    [
      actorId || null,
      textOf(payload, ['context'], 'admin'),
      textOf(payload, ['contentRef', 'content_ref'], ''),
      textOf(payload, ['verdict'], ''),
      textOf(payload, ['metadataPreview', 'metadata_preview'], ''),
    ],
  )
}

function registerAdminModerationRoutes(app, { pool, authRequired, getCurrentUser, userIdentity }) {
  async function adminGuard(req, res) {
    const user = await getCurrentUser(pool, req)
    if (!user) {
      res.status(404).json({ error: 'USER_NOT_FOUND' })
      return null
    }
    const identity = userIdentity(user)
    const allowed = await isAppAdmin(pool, identity)
    if (!allowed) {
      res.status(403).json({ error: 'ADMIN_REQUIRED' })
      return null
    }
    return { user, identity }
  }

  app.get('/v1/data/admin/access', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res)
      if (!context) return
      res.json({ isAdmin: true })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/admin/suspended-profiles', authRequired, async (req, res, next) => {
    try {
      const context = await adminGuard(req, res)
      if (!context) return
      const { rows } = await pool.query(
        `SELECT user_id AS id, is_suspended, suspended_until, suspension_end_date, suspension_reason, suspension_count, updated_at
         FROM fallback_profile_moderation
         WHERE is_suspended = TRUE
         ORDER BY updated_at DESC
         LIMIT 200`,
      )
      res.json({ items: rows })
    } catch (error) {
      next(error)
    }
  })
}

module.exports = {
  ensureAdminModerationTables,
  registerAdminModerationRoutes,
  isAppAdmin,
  recordModerationEvent,
}
