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

function toMillis(value) {
  const text = String(value || '').trim()
  if (!text) return 0
  const millis = Date.parse(text)
  return Number.isFinite(millis) ? millis : 0
}

function versionedAssetUrl(url, versionSource) {
  const cleanUrl = String(url || '').trim()
  if (!cleanUrl || cleanUrl.startsWith('data:')) return cleanUrl
  const versionMs = toMillis(versionSource)
  const version = versionMs > 0 ? String(versionMs) : String(versionSource || '').trim()
  if (!version) return cleanUrl
  const [base, hash = ''] = cleanUrl.split('#', 2)
  const stripped = base
    .replace(/([?&])v=[^&#]*&?/g, '$1')
    .replace(/[?&]$/, '')
  const separator = stripped.includes('?') ? '&' : '?'
  return `${stripped}${separator}v=${encodeURIComponent(version)}${hash ? `#${hash}` : ''}`
}

function normalizeMomentMediaType(rawType, mediaUrl = '') {
  const type = String(rawType || '').trim().toLowerCase()
  const url = String(mediaUrl || '').split('?')[0].toLowerCase()
  if (
    type === 'video' ||
    type.startsWith('video/') ||
    url.endsWith('.mp4') ||
    url.endsWith('.mov') ||
    url.endsWith('.m4v') ||
    url.endsWith('.webm')
  ) {
    return 'video'
  }
  return 'image'
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
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_moment_deletions (
      moment_id TEXT PRIMARY KEY,
      user_id TEXT NOT NULL DEFAULT '',
      deleted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
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

function profileAliases(row) {
  return new Set([
    textOf(row, ['id'], ''),
    textOf(row, ['user_id', 'userId'], ''),
    textOf(row, ['profile_id', 'profileId'], ''),
    textOf(row, ['supabase_user_id', 'supabaseUserId'], ''),
    textOf(row, ['email'], ''),
    textOf(row, ['username', 'handle'], ''),
  ].map(normalizeId).filter(Boolean))
}

async function profileFor(pool, userId, profilesById) {
  const cleanUserId = normalizeId(userId)
  if (profilesById?.has(cleanUserId)) return profilesById.get(cleanUserId)
  const [profiles, localUsers] = await Promise.all([
    importedRows(pool, 'profiles', 5000),
    pool.query(
      `SELECT id, email, display_name, avatar_url, supabase_user_id, created_at, updated_at, last_login_at
       FROM fallback_users
       LIMIT 10000`,
    ).then((result) => result.rows),
  ])
  const profile = [...profiles, ...localUsers].find((row) => profileAliases(row).has(cleanUserId)) || {}
  const normalized = {
    name: textOf(profile, ['display_name', 'displayName', 'full_name', 'username', 'email'], 'Usuario'),
    avatarUrl: versionedAssetUrl(
      textOf(profile, ['avatar_url', 'avatarUrl', 'photo_url', 'profile_image', 'picture', 'image'], ''),
      textOf(profile, ['updated_at', 'updatedAt', 'created_at', 'createdAt'], ''),
    ),
  }
  profilesById?.set(cleanUserId, normalized)
  return normalized
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
  const { rows: deletedRows } = await pool.query(
    `SELECT lower(moment_id) AS moment_id FROM fallback_moment_deletions`,
  )
  const deletedIds = new Set(deletedRows.map((row) => row.moment_id).filter(Boolean))
  const merged = [...localRows.map((row) => ({ ...row, id: textOf(row, ['id'], '') })), ...imported]
  const byId = new Map()
  merged.forEach((row) => {
    const id = textOf(row, ['id'], crypto.randomUUID())
    if (!id) return
    byId.set(id, { ...row, id })
  })
  const moments = Array.from(byId.values())
    .filter((row) => !deletedIds.has(normalizeId(textOf(row, ['id'], ''))))

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
    const ownerProfile = await profileFor(pool, ownerId, profilesById)
    const viewerPreview = Array.from(
      new Set(
        (viewersByMoment.get(id) || [])
          .filter((entry) => entry.userId !== ownerId)
          .slice(0, 5)
          .map((entry) => profilesById.get(entry.userId)?.name || entry.userId),
      ),
    )
    items.push({
      id,
      user_id: ownerId,
      owner_name: ownerProfile.name,
      owner_avatar_url: ownerProfile.avatarUrl,
      media_url: textOf(row, ['media_url'], ''),
      media_type: normalizeMomentMediaType(textOf(row, ['media_type'], 'image'), textOf(row, ['media_url'], '')),
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

async function momentFollowerRecipientIds(pool, ownerId) {
  const cleanOwnerId = normalizeId(ownerId)
  if (!cleanOwnerId) return []
  const { rows: localRows } = await pool.query(
    `SELECT follower_id
     FROM fallback_follows
     WHERE lower(following_id) = $1
     LIMIT 2000`,
    [cleanOwnerId],
  )
  const importedRowsForFollows = await importedRows(pool, 'follows', 20_000)
  const recipients = new Set(
    localRows
      .map((row) => normalizeId(textOf(row, ['follower_id'], '')))
      .filter(Boolean),
  )
  importedRowsForFollows.forEach((row) => {
    const following = normalizeId(textOf(row, ['following_id', 'target_user_id'], ''))
    const follower = normalizeId(textOf(row, ['follower_id', 'user_id'], ''))
    if (following === cleanOwnerId && follower) recipients.add(follower)
  })
  recipients.delete(cleanOwnerId)
  return Array.from(recipients)
}

function registerMomentsRoutes(app, { pool, authRequired, getCurrentUser, userIdentity, createLocalNotification }) {
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
      const mediaType = normalizeMomentMediaType(textOf(req.body, ['mediaType', 'media_type'], 'image'), mediaUrl)

      const durationHours = Math.max(1, Math.min(Number(req.body?.durationHours || req.body?.duration_hours || 24), 168))
      const expiresAt = new Date(Date.now() + durationHours * 60 * 60 * 1000).toISOString()
      const visibility = textOf(req.body, ['visibility'], 'all')
      const requestedVisibilityType = textOf(req.body, ['visibilityType', 'visibility_type'], '')
      const visibilityType = ['private', 'silent', 'public'].includes(requestedVisibilityType)
        ? requestedVisibilityType
        : visibility === 'private'
          ? 'private'
          : visibility === 'silent'
            ? 'silent'
            : 'public'

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
          mediaType,
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

      if (typeof createLocalNotification === 'function') {
        const recipients = await momentFollowerRecipientIds(pool, identity.displayUserId)
        await Promise.all(
          recipients.map((recipientId) =>
            createLocalNotification(pool, {
              recipientId,
              actorId: identity.displayUserId,
              actorName: user.display_name || user.email || 'Usuario',
              actorAvatarUrl: user.avatar_url || '',
              type: 'new_moment',
              targetId: rows[0]?.id,
              targetType: 'moment',
              content: 'Subió un nuevo momento.',
              extraData: { moment_id: rows[0]?.id },
            }),
          ),
        )
      }

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

      const { rows: momentRows } = await pool.query(
        `SELECT id::text, user_id
         FROM fallback_moments
         WHERE lower(id::text) = $1
         LIMIT 1`,
        [normalizeId(momentId)],
      )
      const ownerId = normalizeId(textOf(momentRows[0] || {}, ['user_id'], ''))
      const alreadyViewed = await pool.query(
        `SELECT 1
         FROM fallback_moment_views
         WHERE lower(moment_id::text) = $1 AND lower(user_id) = $2
         LIMIT 1`,
        [normalizeId(momentId), identity.displayUserId],
      )

      await pool.query(
        `INSERT INTO fallback_moment_views(moment_id, user_id, viewed_at)
         VALUES ($1::uuid, $2, NOW())
         ON CONFLICT (moment_id, user_id)
         DO UPDATE SET viewed_at = NOW()`,
        [momentId, identity.displayUserId],
      )
      if (
        typeof createLocalNotification === 'function' &&
        alreadyViewed.rows.length === 0 &&
        ownerId &&
        ownerId !== identity.displayUserId
      ) {
        await createLocalNotification(pool, {
          recipientId: ownerId,
          actorId: identity.displayUserId,
          actorName: user.display_name || user.email || 'Usuario',
          actorAvatarUrl: user.avatar_url || '',
          type: 'view_moment',
          targetId: momentId,
          targetType: 'moment',
          content: 'Vio tu momento.',
          extraData: { moment_id: momentId },
        })
      }
      void publishRealtimeEvent('moments:live', { event: 'moment', action: 'view', momentId })
      res.json({ ok: true })
    } catch (error) {
      next(error)
    }
  })

  app.get('/v1/data/moments/:momentId/viewers', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const momentId = normalizeId(req.params.momentId)
      if (!momentId) return res.status(400).json({ error: 'MOMENT_ID_REQUIRED' })

      const { rows: localRows } = await pool.query(
        `SELECT id::text, user_id
         FROM fallback_moments
         WHERE lower(id::text) = $1
         LIMIT 1`,
        [momentId],
      )
      let ownerId = normalizeId(textOf(localRows[0] || {}, ['user_id'], ''))
      if (!ownerId) {
        const imported = await importedRows(pool, 'moments', 5000)
        const importedRow = imported.find((row) => normalizeId(textOf(row, ['id'], '')) === momentId)
        ownerId = normalizeId(textOf(importedRow || {}, ['user_id', 'userId', 'owner_id', 'profile_id', 'author_id'], ''))
      }
      if (!ownerId) return res.status(404).json({ error: 'MOMENT_NOT_FOUND' })
      if (!identity.ids.has(ownerId) && ownerId !== identity.email) return res.status(403).json({ error: 'MOMENT_NOT_OWNED' })

      const { rows: viewRows } = await pool.query(
        `SELECT moment_id::text, user_id, viewed_at
         FROM fallback_moment_views
         WHERE lower(moment_id::text) = $1
         ORDER BY viewed_at DESC
         LIMIT 500`,
        [momentId],
      )
      const importedViews = (await importedRows(pool, 'moment_views', 5000))
        .filter((row) => normalizeId(textOf(row, ['moment_id'], '')) === momentId)
        .map((row) => ({
          moment_id: textOf(row, ['moment_id'], ''),
          user_id: textOf(row, ['user_id'], ''),
          viewed_at: textOf(row, ['viewed_at'], ''),
        }))
      const seen = new Set()
      const profilesById = new Map()
      const items = []

      for (const row of [...viewRows, ...importedViews]) {
        const viewerId = normalizeId(textOf(row, ['user_id'], ''))
        if (!viewerId || seen.has(viewerId)) continue
        seen.add(viewerId)
        const profile = await profileFor(pool, viewerId, profilesById)
        items.push({
          id: `${momentId}:${viewerId}`,
          moment_id: momentId,
          user_id: viewerId,
          viewed_at: textOf(row, ['viewed_at'], ''),
          username: profile.name,
          display_name: profile.name,
          user_display_name: profile.name,
          avatar_url: profile.avatarUrl,
          user_avatar_url: profile.avatarUrl,
        })
      }

      res.json({ items })
    } catch (error) {
      next(error)
    }
  })

  app.delete('/v1/data/moments/:momentId', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const momentId = normalizeId(req.params.momentId)
      if (!momentId) return res.status(400).json({ error: 'MOMENT_ID_REQUIRED' })

      const { rows: localRows } = await pool.query(
        `SELECT id::text, user_id
         FROM fallback_moments
         WHERE lower(id::text) = $1
         LIMIT 1`,
        [momentId],
      )
      let ownerId = normalizeId(textOf(localRows[0] || {}, ['user_id'], ''))
      if (!ownerId) {
        const imported = await importedRows(pool, 'moments', 5000)
        const importedRow = imported.find((row) => normalizeId(textOf(row, ['id'], '')) === momentId)
        ownerId = normalizeId(textOf(importedRow || {}, ['user_id', 'userId', 'owner_id', 'profile_id', 'author_id'], ''))
      }
      if (!ownerId) return res.status(404).json({ error: 'MOMENT_NOT_FOUND' })
      if (!identity.ids.has(ownerId) && ownerId !== identity.email) return res.status(403).json({ error: 'MOMENT_NOT_OWNED' })

      await pool.query(
        `DELETE FROM fallback_moments
         WHERE lower(id::text) = $1`,
        [momentId],
      )
      await pool.query(
        `INSERT INTO fallback_moment_deletions(moment_id, user_id, deleted_at)
         VALUES ($1, $2, NOW())
         ON CONFLICT (moment_id)
         DO UPDATE SET user_id = EXCLUDED.user_id, deleted_at = NOW()`,
        [momentId, identity.displayUserId],
      )
      await pool.query(`DELETE FROM fallback_moment_views WHERE lower(moment_id::text) = $1`, [momentId])

      void publishRealtimeEvent('moments:live', { event: 'moment', action: 'deleted', momentId })
      res.json({ ok: true, deleted: true })
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
