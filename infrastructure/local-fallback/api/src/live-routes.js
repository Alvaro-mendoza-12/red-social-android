const crypto = require('crypto')
const { publishRealtimeEvent } = require('./realtime-publish')

function normalizeId(value) {
  return String(value || '').trim().toLowerCase()
}

function validUuid(value) {
  const text = normalizeId(value)
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(text)
    ? text
    : null
}

function cleanText(value, maxLength) {
  return String(value || '').trim().slice(0, maxLength)
}

function cleanHashtags(value) {
  const entries = Array.isArray(value) ? value : []
  return [...new Set(entries
    .map((entry) => cleanText(entry, 40).replace(/^#+/, '').toLowerCase())
    .filter(Boolean))]
    .slice(0, 10)
}

function sourceOf(value) {
  return normalizeId(value) === 'screen' ? 'screen' : 'camera'
}

function intBetween(value, min, max, fallback = min) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isFinite(parsed)) return fallback
  return Math.min(max, Math.max(min, parsed))
}

function profileValue(profile, keys, fallback = '') {
  for (const key of keys) {
    const value = profile?.[key]
    if (value !== undefined && value !== null && String(value).trim()) return String(value).trim()
  }
  return fallback
}

async function ensureLiveTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_live_streams (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      host_user_id TEXT NOT NULL,
      host_name TEXT NOT NULL DEFAULT 'Usuario',
      host_avatar_url TEXT,
      title TEXT NOT NULL DEFAULT '',
      source TEXT NOT NULL DEFAULT 'camera' CHECK (source IN ('camera', 'screen')),
      hashtags JSONB NOT NULL DEFAULT '[]'::jsonb,
      viewer_count INT NOT NULL DEFAULT 0,
      peak_viewer_count INT NOT NULL DEFAULT 0,
      total_comments INT NOT NULL DEFAULT 0,
      total_reactions INT NOT NULL DEFAULT 0,
      is_active BOOLEAN NOT NULL DEFAULT FALSE,
      started_at TIMESTAMPTZ,
      ended_at TIMESTAMPTZ,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_live_streams_active_created
    ON fallback_live_streams(is_active, created_at DESC)
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_live_streams_host_created
    ON fallback_live_streams(lower(host_user_id), created_at DESC)
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_live_comments (
      id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
      live_id UUID NOT NULL REFERENCES fallback_live_streams(id) ON DELETE CASCADE,
      user_id TEXT NOT NULL,
      user_name TEXT NOT NULL DEFAULT 'Usuario',
      avatar_url TEXT,
      content TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_live_comments_live_created
    ON fallback_live_comments(live_id, created_at DESC)
  `)
}

async function resolvedProfile(pool, user) {
  const { rows } = await pool.query(
    `SELECT profile FROM fallback_profile_overrides WHERE user_id = $1 LIMIT 1`,
    [user.id],
  )
  const profile = rows[0]?.profile || {}
  return {
    name: profileValue(
      profile,
      ['display_name', 'displayName', 'full_name', 'fullName', 'username'],
      user.display_name || user.email || 'Usuario',
    ).slice(0, 160),
    avatarUrl: profileValue(
      profile,
      ['avatar_url', 'avatarUrl', 'profile_image', 'profileImage'],
      user.avatar_url || '',
    ).slice(0, 900) || null,
  }
}

function ownsStream(stream, identity) {
  return identity.ids.has(normalizeId(stream?.host_user_id))
}

async function streamById(pool, liveId) {
  const { rows } = await pool.query(
    `SELECT id::text, host_user_id, host_name, host_avatar_url, title, source, hashtags,
            viewer_count, peak_viewer_count, total_comments, total_reactions, is_active,
            started_at, ended_at, created_at, updated_at
     FROM fallback_live_streams
     WHERE id = $1::uuid
     LIMIT 1`,
    [liveId],
  )
  return rows[0] || null
}

function registerLiveRoutes(app, { pool, authRequired, getCurrentUser, userIdentity }) {
  app.get('/v1/data/live', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const limit = intBetween(req.query.limit, 1, 100, 60)
      const { rows } = await pool.query(
        `SELECT id::text, host_user_id, host_name, host_avatar_url, title, source, hashtags,
                viewer_count, peak_viewer_count, total_comments, total_reactions, is_active,
                started_at, ended_at, created_at, updated_at
         FROM fallback_live_streams
         WHERE is_active = TRUE
         ORDER BY viewer_count DESC, started_at DESC NULLS LAST, created_at DESC
         LIMIT $1`,
        [limit],
      )
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ items: rows })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/live/:liveId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      if (!stream.is_active && !ownsStream(stream, userIdentity(user))) {
        return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      }
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ stream })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/live', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const profile = await resolvedProfile(pool, user)
      const title = cleanText(req.body?.title, 80) || `${profile.name} en vivo`
      const source = sourceOf(req.body?.source)
      const hashtags = cleanHashtags(req.body?.hashtags)
      const liveId = validUuid(req.body?.id) || crypto.randomUUID()

      await pool.query(
        `UPDATE fallback_live_streams
         SET is_active = FALSE,
             viewer_count = 0,
             ended_at = COALESCE(ended_at, NOW()),
             updated_at = NOW()
         WHERE lower(host_user_id) = ANY($1::text[])
           AND is_active = TRUE`,
        [identity.values],
      )
      const { rows } = await pool.query(
        `INSERT INTO fallback_live_streams(
            id, host_user_id, host_name, host_avatar_url, title, source, hashtags,
            viewer_count, is_active, created_at, updated_at
         )
         VALUES ($1::uuid, $2, $3, $4, $5, $6, $7::jsonb, 0, FALSE, NOW(), NOW())
         RETURNING id::text, host_user_id, host_name, host_avatar_url, title, source, hashtags,
                   viewer_count, peak_viewer_count, total_comments, total_reactions, is_active,
                   started_at, ended_at, created_at, updated_at`,
        [liveId, identity.displayUserId, profile.name, profile.avatarUrl, title, source, JSON.stringify(hashtags)],
      )
      res.status(201).json({ stream: rows[0] })
    } catch (error) {
      if (error?.code === '23505') return res.status(409).json({ error: 'LIVE_ALREADY_EXISTS' })
      next(error)
    }
  })

  app.patch('/v1/data/live/:liveId/state', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const existing = await streamById(pool, liveId)
      if (!existing) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      if (!ownsStream(existing, userIdentity(user))) return res.status(403).json({ error: 'LIVE_FORBIDDEN' })

      const hasActive = req.body?.isActive !== undefined || req.body?.is_active !== undefined
      const isActive = Boolean(req.body?.isActive ?? req.body?.is_active)
      const viewerCount = intBetween(req.body?.viewerCount ?? req.body?.viewer_count, 0, 1_000_000, existing.viewer_count)
      const peakViewerCount = intBetween(
        req.body?.peakViewerCount ?? req.body?.peak_viewer_count,
        0,
        1_000_000,
        existing.peak_viewer_count,
      )
      const totalComments = intBetween(
        req.body?.totalComments ?? req.body?.total_comments,
        0,
        10_000_000,
        existing.total_comments,
      )
      const totalReactions = intBetween(
        req.body?.totalReactions ?? req.body?.total_reactions,
        0,
        100_000_000,
        existing.total_reactions,
      )
      const { rows } = await pool.query(
        `UPDATE fallback_live_streams
         SET is_active = CASE WHEN $2 THEN $3 ELSE is_active END,
             viewer_count = $4,
             peak_viewer_count = GREATEST(peak_viewer_count, $4, $5),
             total_comments = GREATEST(total_comments, $6),
             total_reactions = GREATEST(total_reactions, $7),
             started_at = CASE WHEN $2 AND $3 THEN COALESCE(started_at, NOW()) ELSE started_at END,
             ended_at = CASE WHEN $2 AND NOT $3 THEN COALESCE(ended_at, NOW()) ELSE ended_at END,
             updated_at = NOW()
         WHERE id = $1::uuid
         RETURNING id::text, host_user_id, host_name, host_avatar_url, title, source, hashtags,
                   viewer_count, peak_viewer_count, total_comments, total_reactions, is_active,
                   started_at, ended_at, created_at, updated_at`,
        [liveId, hasActive, isActive, hasActive && !isActive ? 0 : viewerCount, peakViewerCount, totalComments, totalReactions],
      )
      res.json({ stream: rows[0] })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/live/:liveId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      if (!ownsStream(stream, userIdentity(user))) return res.status(403).json({ error: 'LIVE_FORBIDDEN' })
      if (stream.is_active) return res.status(409).json({ error: 'LIVE_STILL_ACTIVE' })
      await pool.query(`DELETE FROM fallback_live_streams WHERE id = $1::uuid`, [liveId])
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/live/:liveId/comments', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      if (!stream.is_active && !ownsStream(stream, userIdentity(user))) {
        return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      }
      const limit = intBetween(req.query.limit, 1, 200, 80)
      const { rows } = await pool.query(
        `SELECT id::text, live_id::text, user_id, user_name, avatar_url, content, created_at
         FROM fallback_live_comments
         WHERE live_id = $1::uuid
         ORDER BY created_at DESC
         LIMIT $2`,
        [liveId, limit],
      )
      res.setHeader('Cache-Control', 'private, no-store')
      res.json({ items: rows.reverse() })
    } catch (error) {
      next(error)
    }
  })

  app.post('/v1/data/live/:liveId/comments', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      if (!liveId) return res.status(400).json({ error: 'LIVE_ID_INVALID' })
      const content = cleanText(req.body?.content, 300)
      if (!content) return res.status(400).json({ error: 'LIVE_COMMENT_REQUIRED' })
      const stream = await streamById(pool, liveId)
      if (!stream?.is_active) return res.status(409).json({ error: 'LIVE_NOT_ACTIVE' })
      const identity = userIdentity(user)
      const profile = await resolvedProfile(pool, user)
      const { rows } = await pool.query(
        `INSERT INTO fallback_live_comments(live_id, user_id, user_name, avatar_url, content)
         VALUES ($1::uuid, $2, $3, $4, $5)
         RETURNING id::text, live_id::text, user_id, user_name, avatar_url, content, created_at`,
        [liveId, identity.displayUserId, profile.name, profile.avatarUrl, content],
      )
      await pool.query(
        `UPDATE fallback_live_streams
         SET total_comments = total_comments + 1, updated_at = NOW()
         WHERE id = $1::uuid`,
        [liveId],
      )
      await publishRealtimeEvent(`live-comments:${liveId}`, {
        event: 'live_comment_created',
        liveId,
        commentId: rows[0].id,
      })
      res.status(201).json({ comment: rows[0] })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/live/:liveId/comments/:commentId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const liveId = validUuid(req.params.liveId)
      const commentId = validUuid(req.params.commentId)
      if (!liveId || !commentId) return res.status(400).json({ error: 'LIVE_COMMENT_ID_INVALID' })
      const stream = await streamById(pool, liveId)
      if (!stream) return res.status(404).json({ error: 'LIVE_NOT_FOUND' })
      const identity = userIdentity(user)
      const { rows } = await pool.query(
        `DELETE FROM fallback_live_comments
         WHERE id = $1::uuid
           AND live_id = $2::uuid
           AND (lower(user_id) = ANY($3::text[]) OR $4::boolean)
         RETURNING id::text`,
        [commentId, liveId, identity.values, ownsStream(stream, identity)],
      )
      if (rows.length === 0) return res.status(404).json({ error: 'LIVE_COMMENT_NOT_FOUND' })
      await publishRealtimeEvent(`live-comments:${liveId}`, {
        event: 'live_comment_deleted',
        liveId,
        commentId,
      })
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })
}

module.exports = {
  ensureLiveTables,
  registerLiveRoutes,
}
