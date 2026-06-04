const crypto = require('crypto')
const { publishRealtimeEvent } = require('./realtime-publish')

function textOf(row, keys, fallback = '') {
  for (const key of keys) {
    const value = row?.[key]
    if (value !== undefined && value !== null && String(value).trim() !== '') {
      return String(value).trim()
    }
  }
  return fallback
}

function normalizeId(value) {
  return String(value || '').trim().toLowerCase()
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

async function ensureMomentsTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_moments (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      user_id TEXT NOT NULL,
      media_url TEXT NOT NULL DEFAULT '',
      media_type TEXT NOT NULL DEFAULT 'image',
      thumbnail_url TEXT,
      caption TEXT,
      visibility TEXT NOT NULL DEFAULT 'all',
      visibility_type TEXT NOT NULL DEFAULT 'public',
      allow_replies BOOLEAN NOT NULL DEFAULT TRUE,
      duration_hours INT NOT NULL DEFAULT 24,
      duration_type TEXT NOT NULL DEFAULT '24h',
      excluded_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
      expires_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_moment_views (
      moment_id UUID NOT NULL,
      user_id TEXT NOT NULL,
      viewed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (moment_id, user_id)
    )
  `)
}

async function importedRows(pool, tableName, limit = 2000) {
  const { rows } = await pool.query(
    `SELECT to_regclass($1) AS oid`,
    [`supabase_import.${tableName}`],
  )
  if (!rows[0]?.oid) return []
  const { rows: data } = await pool.query(
    `SELECT to_jsonb(t) AS row
     FROM supabase_import."${tableName.replace(/"/g, '""')}" t
     LIMIT $1`,
    [limit],
  )
  return data.map((entry) => entry.row || {})
}

async function profileNameFor(pool, userId, profilesById) {
  if (profilesById?.has(userId)) return profilesById.get(userId)
  const profiles = await importedRows(pool, 'profiles', 5000)
  const profile = profiles.find((row) => normalizeId(textOf(row, ['id'], '')) === userId)
  const name = textOf(profile, ['display_name', 'displayName', 'full_name', 'username'], 'Usuario')
  profilesById?.set(userId, name)
  return name
}

async function buildMomentsFeed(pool, identity) {
  const now = Date.now()
  const { rows: localRows } = await pool.query(
    `SELECT id::text, user_id, media_url, media_type, thumbnail_url, caption, visibility, visibility_type,
            allow_replies, duration_hours, duration_type, excluded_ids, expires_at, created_at, updated_at
     FROM fallback_moments
     ORDER BY created_at DESC
     LIMIT 300`,
  )
  const imported = await importedRows(pool, 'moments', 500)
  const merged = [...localRows.map((row) => ({ ...row, id: textOf(row, ['id'], '') })), ...imported]
  const byId = new Map()
  merged.forEach((row) => {
    const id = textOf(row, ['id'], crypto.randomUUID())
    if (!id) return
    byId.set(id, { ...row, id })
  })
  const moments = Array.from(byId.values())

  const momentIds = moments.map((row) => textOf(row, ['id'], '')).filter(Boolean)
  const viewsByMoment = new Map()
  const viewedByMe = new Set()
  const viewersByMoment = new Map()

  if (momentIds.length > 0) {
    const { rows: viewRows } = await pool.query(
      `SELECT moment_id::text, user_id, viewed_at
       FROM fallback_moment_views
       WHERE moment_id::text = ANY($1::text[])`,
      [momentIds],
    )
    const importedViews = await importedRows(pool, 'moment_views', 5000)
    const allViews = [
      ...viewRows.map((row) => ({
        moment_id: textOf(row, ['moment_id'], ''),
        user_id: textOf(row, ['user_id'], ''),
        viewed_at: textOf(row, ['viewed_at'], ''),
      })),
      ...importedViews,
    ]
    allViews.forEach((view) => {
      const momentId = textOf(view, ['moment_id'], '')
      const viewerId = textOf(view, ['user_id'], '')
      if (!momentId) return
      viewsByMoment.set(momentId, (viewsByMoment.get(momentId) || 0) + 1)
      if (identity.ids.has(viewerId)) viewedByMe.add(momentId)
      if (viewerId) {
        const list = viewersByMoment.get(momentId) || []
        list.push({ userId: viewerId, viewedAt: textOf(view, ['viewed_at'], '') })
        viewersByMoment.set(momentId, list)
      }
    })
  }

  const profilesById = new Map()
  const items = []
  for (const row of moments) {
    const id = textOf(row, ['id'], '')
    const ownerId = normalizeId(textOf(row, ['user_id'], ''))
    const visibilityType = textOf(row, ['visibility_type'], 'public')
    if (visibilityType === 'private' && !identity.ids.has(ownerId)) continue
    if (visibilityType === 'silent' && !identity.ids.has(ownerId)) continue
    const expiresAt = textOf(row, ['expires_at'], '')
    if (expiresAt) {
      const expiresMs = Date.parse(expiresAt)
      if (!Number.isNaN(expiresMs) && expiresMs <= now) continue
    }
    const ownerName = await profileNameFor(pool, ownerId, profilesById)
    const viewerPreview = Array.from(
      new Set(
        (viewersByMoment.get(id) || [])
          .filter((entry) => entry.userId !== ownerId)
          .slice(0, 5)
          .map((entry) => profilesById.get(entry.userId) || entry.userId),
      ),
    )
    items.push({
      id,
      user_id: ownerId,
      owner_name: ownerName,
      media_url: textOf(row, ['media_url'], ''),
      media_type: textOf(row, ['media_type'], 'image'),
      caption: textOf(row, ['caption'], ''),
      visibility: textOf(row, ['visibility'], 'all'),
      allow_replies: boolOf(row, ['allow_replies'], true),
      created_at: textOf(row, ['created_at'], new Date().toISOString()),
      expires_at: expiresAt,
      views_count: viewsByMoment.get(id) || 0,
      viewed_by_me: viewedByMe.has(id),
      viewer_preview: viewerPreview,
    })
  }

  items.sort((a, b) => Date.parse(b.created_at || 0) - Date.parse(a.created_at || 0))
  return items
}

function registerMomentsRoutes(app, { pool, authRequired, getCurrentUser, userIdentity }) {
  app.get('/v1/data/moments/feed', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const items = await buildMomentsFeed(pool, identity)
      res.json({ items })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/moments', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const mediaUrl = textOf(req.body, ['mediaUrl', 'media_url'], '')
      if (!mediaUrl) return res.status(400).json({ error: 'MEDIA_URL_REQUIRED' })

      const durationHours = Math.max(1, Math.min(Number(req.body?.durationHours || req.body?.duration_hours || 24), 168))
      const expiresAt = new Date(Date.now() + durationHours * 60 * 60 * 1000).toISOString()
      const visibility = textOf(req.body, ['visibility'], 'all')
      const visibilityType = visibility === 'private' ? 'private' : visibility === 'silent' ? 'silent' : 'public'

      const { rows } = await pool.query(
        `INSERT INTO fallback_moments(
            user_id, media_url, media_type, thumbnail_url, caption, visibility, visibility_type,
            allow_replies, duration_hours, duration_type, expires_at
         )
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
         RETURNING id::text, user_id, media_url, media_type, thumbnail_url, caption, visibility, visibility_type,
                   allow_replies, duration_hours, duration_type, excluded_ids, expires_at, created_at, updated_at`,
        [
          identity.displayUserId,
          mediaUrl,
          textOf(req.body, ['mediaType', 'media_type'], 'image'),
          textOf(req.body, ['thumbnailUrl', 'thumbnail_url'], '') || null,
          textOf(req.body, ['caption'], '') || null,
          visibility,
          visibilityType,
          req.body?.allowReplies !== false && req.body?.allow_replies !== false,
          durationHours,
          textOf(req.body, ['durationType', 'duration_type'], '24h'),
          expiresAt,
        ],
      )

      void publishRealtimeEvent('moments:live', { event: 'moment', action: 'created', momentId: rows[0]?.id })
      res.status(201).json({ moment: rows[0] })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/moments/:momentId/view', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const momentId = textOf(req.params, ['momentId'], '')
      if (!momentId) return res.status(400).json({ error: 'MOMENT_ID_REQUIRED' })

      await pool.query(
        `INSERT INTO fallback_moment_views(moment_id, user_id, viewed_at)
         VALUES ($1::uuid, $2, NOW())
         ON CONFLICT (moment_id, user_id)
         DO UPDATE SET viewed_at = NOW()`,
        [momentId, identity.displayUserId],
      )
      void publishRealtimeEvent('moments:live', { event: 'moment', action: 'view', momentId })
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })
}

module.exports = {
  ensureMomentsTables,
  registerMomentsRoutes,
  buildMomentsFeed,
}
