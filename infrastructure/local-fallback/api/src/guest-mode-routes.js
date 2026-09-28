'use strict'

const crypto = require('crypto')

const GUEST_AUDIENCE = 'novvin-guests'
const GUEST_LIFETIME_SECONDS = 30 * 24 * 60 * 60

async function importedTableExists(pool, table) {
  const { rows } = await pool.query('SELECT to_regclass($1) AS table_oid', [`supabase_import.${table}`])
  return Boolean(rows[0]?.table_oid)
}

function mergedPublicContent(local, imported, limit = 30, ascending = false) {
  const byId = new Map()
  for (const row of imported) if (row?.id) byId.set(String(row.id), row)
  for (const row of local) if (row?.id) byId.set(String(row.id), row)
  return [...byId.values()]
    .sort((a, b) => (ascending ? 1 : -1) *
      (Date.parse(a.created_at || 0) - Date.parse(b.created_at || 0)))
    .slice(0, limit)
}

function importedVideo(row) {
  return {
    id: String(row.id), user_id: row.user_id || row.userid || '',
    user_name: row.user_name || 'Usuario',
    user_profile_image: row.user_profile_image || row.user_avatar || null,
    title: row.title || '', description: row.description || '',
    video_url: row.video_url || row.url || '',
    cover_url: row.thumbnail_url || null, thumbnail_url: row.thumbnail_url || null,
    created_at: row.created_at,
  }
}

function normalizeGuestPost(row) {
  let mediaUrls = row.media_urls || []
  if (typeof mediaUrls === 'string') {
    try { mediaUrls = JSON.parse(mediaUrls) } catch (_) { mediaUrls = [] }
  }
  if (!Array.isArray(mediaUrls)) mediaUrls = []
  mediaUrls = [...new Set(mediaUrls.map((entry) =>
    typeof entry === 'string' ? entry : entry?.url || entry?.media_url)
    .filter((url) => typeof url === 'string' && url.trim()))]
  const mediaUrl = typeof row.media_url === 'string' && !row.media_url.trim().startsWith('{')
    ? row.media_url : mediaUrls[0] || null
  return { ...row, media_url: mediaUrl || mediaUrls[0] || null, media_urls: mediaUrls }
}

function importedPost(row) {
  const media = normalizeGuestPost(row)
  return {
    id: String(row.id), user_id: row.user_id || '', user_name: row.user_name || 'Usuario',
    user_profile_image: row.user_profile_image || null,
    content: row.content || '', media_url: media.media_url,
    media_urls: media.media_urls, media_type: row.media_type || null,
    created_at: row.created_at,
  }
}

function importedMoment(row) {
  return {
    id: String(row.id), user_id: row.user_id || '', media_url: row.media_url || null,
    media_type: row.media_type || 'image', thumbnail_url: row.thumbnail_url || null,
    caption: row.caption || '', created_at: row.created_at, expires_at: row.expires_at,
  }
}

function importedProfile(row) {
  const handle = String(row.username || '').trim()
  // Imported usernames predate the explicit choice step. Some were derived from
  // the email plus an ID suffix, so comparing only the exact email prefix leaks
  // those addresses and presents an unchosen identifier as permanent.
  const verifiedHandle = ['chosen', 'migrated_name'].includes(row.username_origin)
  return {
    id: String(row.id),
    display_name: row.display_name || row.full_name || 'Usuario',
    username: handle && verifiedHandle ? handle : null,
    avatar_url: row.avatar_url || null, bio: row.bio || null,
    is_private: row.is_private === true || row.is_private === 'true',
  }
}

async function readPublicContent(pool, kind, id) {
  const video = kind === 'videos'
  const { rows } = await pool.query(video
    ? `SELECT id::text, user_id, user_name, user_profile_image, title,
              description, video_url, cover_url, thumbnail_url, created_at
       FROM fallback_videos WHERE id = $1::uuid AND is_public = TRUE AND is_sensitive = FALSE`
    : `SELECT id::text, user_id, user_name, user_profile_image, content,
              COALESCE(media_url, media_urls->>0) AS media_url, media_urls, media_type, created_at
       FROM fallback_posts WHERE id = $1::uuid AND visibility = 'public' AND is_sensitive = FALSE`,
    [id])
  if (rows[0]) return video ? rows[0] : normalizeGuestPost(rows[0])
  if (!await importedTableExists(pool, kind)) return null
  // Local rows are authoritative, even when a private row has an old public import.
  const result = await pool.query(video
    ? `SELECT to_jsonb(v) AS row FROM supabase_import.videos v
       WHERE v.id = $1 AND COALESCE(NULLIF(to_jsonb(v)->>'is_public', ''),
         NULLIF(to_jsonb(v)->>'ispublic', '')) IN ('true', 't', '1')
         AND COALESCE(to_jsonb(v)->>'is_sensitive', 'false') NOT IN ('true', 't', '1')
         AND COALESCE(to_jsonb(v)->>'is_deleted', 'false') NOT IN ('true', 't', '1')
         AND NOT EXISTS (SELECT 1 FROM fallback_videos f WHERE f.id::text = v.id) LIMIT 1`
    : `SELECT to_jsonb(p) AS row FROM supabase_import.posts p
       WHERE p.id = $1 AND COALESCE(NULLIF(p.visibility, ''), 'public') = 'public'
         AND COALESCE(to_jsonb(p)->>'is_sensitive', 'false') NOT IN ('true', 't', '1')
         AND COALESCE(to_jsonb(p)->>'is_deleted', 'false') NOT IN ('true', 't', '1')
         AND NOT EXISTS (SELECT 1 FROM fallback_posts f WHERE f.id::text = p.id) LIMIT 1`,
    [id])
  return result.rows[0] ? (video ? importedVideo(result.rows[0].row) : importedPost(result.rows[0].row)) : null
}


async function readPublicProfile(pool, identifier) {
  const id = String(identifier || '').trim().replace(/^@/, '').toLowerCase()
  const { rows } = await pool.query(
    `SELECT u.id::text AS id, COALESCE(NULLIF(o.profile->>'display_name', ''), u.display_name, 'Usuario') AS display_name,
        CASE WHEN COALESCE(o.profile->>'username_origin', '') NOT IN ('chosen', 'migrated_name')
      THEN NULL ELSE NULLIF(o.profile->>'username', '') END AS username,
        COALESCE(NULLIF(o.profile->>'avatar_url', ''), u.avatar_url) AS avatar_url,
        NULLIF(o.profile->>'bio', '') AS bio,
        COALESCE(o.profile->>'is_private', 'false') IN ('true', 't', '1') AS is_private
     FROM fallback_users u
     LEFT JOIN fallback_profile_overrides o ON o.user_id = u.id
     WHERE (u.id::text = $1 OR u.supabase_user_id::text = $1 OR lower(o.profile->>'username') = $1)
       AND COALESCE(o.profile->>'profile_visibility', 'everyone') = 'everyone'
       AND COALESCE(o.profile->>'break_mode', 'off') <> 'hidden'
     LIMIT 1`,
    [id],
  )
  let profile = rows[0]
  if (await importedTableExists(pool, 'profiles')) {
    const result = await pool.query(
      `SELECT to_jsonb(p) AS row FROM supabase_import.profiles p
       LEFT JOIN fallback_users u ON u.supabase_user_id::text = p.id OR u.id::text = p.id
       LEFT JOIN fallback_profile_overrides o ON o.user_id = u.id
       WHERE (p.id = $1 OR u.id::text = $1 OR
         (COALESCE(to_jsonb(p)->>'username_origin', '') IN ('chosen', 'migrated_name')
          AND lower(p.username) = $1))
     AND COALESCE(NULLIF(p.is_suspended, ''), 'false') NOT IN ('true', 't', '1')
     AND COALESCE(o.profile->>'profile_visibility', 'everyone') = 'everyone'
     AND COALESCE(o.profile->>'break_mode', 'off') <> 'hidden'
       LIMIT 1`,
      [id],
    )
    if (result.rows[0]) {
      const legacy = importedProfile(result.rows[0].row)
      profile = profile
    ? { ...legacy, ...profile, username: profile.username || legacy.username,
      bio: profile.bio || legacy.bio, avatar_url: profile.avatar_url || legacy.avatar_url,
      is_private: profile.is_private || legacy.is_private }
    : legacy
    }
  }
  return profile || null
}

function issueGuestIdentity(jwt, secret, issuer) {
  const id = `guest_${crypto.randomUUID()}`
  const username = `guest${crypto.randomBytes(6).toString('hex')}`
  const displayName = `Usuario${crypto.randomInt(1000, 10000)}`
  const accessToken = jwt.sign(
    { sub: id, role: 'guest', username }, secret,
    { issuer, audience: GUEST_AUDIENCE, expiresIn: GUEST_LIFETIME_SECONDS },
  )
  return { role: 'guest', id, username, displayName, accessToken,
    expiresIn: GUEST_LIFETIME_SECONDS }
}

function guestAuth(jwt, secret, issuer) {
  return (req, res, next) => {
    try {
      const token = String(req.headers.authorization || '').replace(/^Bearer\s+/i, '').trim()
      const claims = jwt.verify(token, secret, { issuer, audience: GUEST_AUDIENCE })
      if (claims.role !== 'guest' || !/^guest_[0-9a-f-]{36}$/.test(claims.sub)) {
        return res.status(401).json({ error: 'GUEST_SESSION_INVALID' })
      }
      req.guest = { id: claims.sub, username: claims.username }
      return next()
    } catch (_) {
      return res.status(401).json({ error: 'GUEST_SESSION_INVALID' })
    }
  }
}

function registerGuestModeRoutes(app, { pool, jwt, jwtSecret, serviceName }) {
  const requireGuest = guestAuth(jwt, jwtSecret, serviceName)
  app.post('/v1/guest/session', (_req, res) => {
    res.set('Cache-Control', 'no-store')
    res.status(201).json(issueGuestIdentity(jwt, jwtSecret, serviceName))
  })

  app.get('/v1/guest/videos', requireGuest, async (req, res, next) => {
    try {
      const { rows } = await pool.query(
        `SELECT id::text, user_id, user_name, user_profile_image, title,
                description, video_url, cover_url, thumbnail_url, created_at
         FROM fallback_videos WHERE is_public = TRUE AND is_sensitive = FALSE
         ORDER BY created_at DESC LIMIT 30`,
      )
      let imported = []
      if (await importedTableExists(pool, 'videos')) {
        const result = await pool.query(
          `SELECT to_jsonb(v) AS row FROM supabase_import.videos v
           WHERE COALESCE(NULLIF(to_jsonb(v)->>'is_public', ''),
                          NULLIF(to_jsonb(v)->>'ispublic', '')) IN ('true', 't', '1')
             AND COALESCE(to_jsonb(v)->>'is_sensitive', 'false') NOT IN ('true', 't', '1')
         AND COALESCE(to_jsonb(v)->>'is_deleted', 'false') NOT IN ('true', 't', '1')
         AND NOT EXISTS (SELECT 1 FROM fallback_videos f WHERE f.id::text = v.id)
           ORDER BY v.created_at DESC LIMIT 30`,
        )
        imported = result.rows.map(({ row }) => importedVideo(row)).filter((item) => item.video_url)
      }
      res.set('Cache-Control', 'private, max-age=30')
      res.json({ items: mergedPublicContent(rows, imported) })
    } catch (error) { next(error) }
  })

  app.get('/v1/guest/posts', requireGuest, async (req, res, next) => {
    try {
      const { rows } = await pool.query(
        `SELECT id::text, user_id, user_name, user_profile_image, content,
                COALESCE(media_url, media_urls->>0) AS media_url,
                media_urls, media_type, created_at
         FROM fallback_posts
         WHERE visibility = 'public' AND is_sensitive = FALSE
         ORDER BY created_at DESC LIMIT 30`,
      )
      let imported = []
      if (await importedTableExists(pool, 'posts')) {
        const result = await pool.query(
          `SELECT to_jsonb(p) AS row FROM supabase_import.posts p
           WHERE COALESCE(NULLIF(p.visibility, ''), 'public') = 'public'
             AND COALESCE(to_jsonb(p)->>'is_sensitive', 'false') NOT IN ('true', 't', '1')
         AND COALESCE(to_jsonb(p)->>'is_deleted', 'false') NOT IN ('true', 't', '1')
         AND NOT EXISTS (SELECT 1 FROM fallback_posts f WHERE f.id::text = p.id)
           ORDER BY p.created_at DESC LIMIT 30`,
        )
        imported = result.rows.map(({ row }) => importedPost(row))
      }
      res.set('Cache-Control', 'private, max-age=30')
      res.json({ items: mergedPublicContent(rows.map(normalizeGuestPost), imported) })
    } catch (error) { next(error) }
  })

  app.get('/v1/guest/moments', requireGuest, async (req, res, next) => {
    try {
      const { rows } = await pool.query(
        `SELECT id::text, user_id, media_url, media_type, thumbnail_url,
                COALESCE(caption, '') AS caption,
                created_at, expires_at
         FROM fallback_moments
         WHERE visibility_type = 'public' AND visibility = 'all'
           AND (expires_at IS NULL OR expires_at > NOW())
         ORDER BY created_at DESC LIMIT 30`,
      )
      let imported = []
      if (await importedTableExists(pool, 'moments')) {
        const result = await pool.query(
          `SELECT to_jsonb(m) AS row FROM supabase_import.moments m
           WHERE COALESCE(NULLIF(m.visibility_type, ''), 'public') = 'public'
             AND COALESCE(NULLIF(m.visibility, ''), 'all') = 'all'
             AND (NULLIF(m.expires_at, '') IS NULL OR m.expires_at::timestamptz > NOW())
             AND NOT EXISTS (SELECT 1 FROM fallback_moments f WHERE f.id::text = m.id)
           ORDER BY m.created_at DESC LIMIT 30`,
        )
        imported = result.rows.map(({ row }) => importedMoment(row))
      }
      res.set('Cache-Control', 'private, max-age=15')
      res.json({ items: mergedPublicContent(rows, imported) })
    } catch (error) { next(error) }
  })

  app.get(['/v1/guest/videos/:id', '/v1/guest/posts/:id'], requireGuest, async (req, res, next) => {
    try {
      const kind = req.path.split('/')[3]
      const id = String(req.params.id || '')
      if (!['videos', 'posts'].includes(kind) || !/^[0-9a-f-]{36}$/i.test(id)) {
        return res.status(400).json({ error: 'CONTENT_ID_INVALID' })
      }
      const content = await readPublicContent(pool, kind, id)
      if (!content) return res.status(404).json({ error: 'CONTENT_NOT_FOUND' })
      res.set('Cache-Control', 'private, max-age=30')
      res.json({ content })
    } catch (error) { next(error) }
  })

  app.get('/v1/guest/:kind/:id/comments', requireGuest, async (req, res, next) => {
    try {
      const kind = req.params.kind
      const id = String(req.params.id || '')
      if (!['videos', 'posts'].includes(kind) || !/^[0-9a-f-]{36}$/i.test(id)) {
        return res.status(400).json({ error: 'CONTENT_ID_INVALID' })
      }
      if (!await readPublicContent(pool, kind, id)) {
        return res.status(404).json({ error: 'CONTENT_NOT_FOUND' })
      }
      const video = kind === 'videos'
      const { rows } = await pool.query(video
        ? `SELECT c.id::text, c.user_name, c.content, c.created_at
           FROM fallback_video_comments c WHERE c.video_id = $1 AND c.is_deleted = FALSE
           ORDER BY c.created_at ASC LIMIT 100`
        : `SELECT c.id::text, COALESCE(u.display_name, 'Usuario') AS user_name,
                  c.content, c.created_at FROM fallback_post_comments c
           LEFT JOIN fallback_users u ON u.id::text = c.user_id
           WHERE c.post_id = $1 AND c.is_deleted = FALSE ORDER BY c.created_at ASC LIMIT 100`,
        [id])
      let imported = []
      const importTable = video ? 'video_comments' : 'post_comments'
      if (await importedTableExists(pool, importTable)) {
        // Identifiers come exclusively from the fixed kind allowlist above.
        const parentColumn = video ? 'video_id' : 'post_id'
        const localTable = video ? 'fallback_video_comments' : 'fallback_post_comments'
        const result = await pool.query(
          `SELECT to_jsonb(c) AS row FROM supabase_import.${importTable} c
           WHERE c.${parentColumn} = $1
             AND COALESCE(to_jsonb(c)->>'is_deleted', 'false') NOT IN ('true', 't', '1')
             AND NOT EXISTS (SELECT 1 FROM ${localTable} f WHERE f.id::text = c.id)
           ORDER BY c.created_at ASC LIMIT 100`, [id])
        imported = result.rows.map(({ row }) => ({
          id: String(row.id), user_name: row.user_name || 'Usuario',
          content: row.content || '', created_at: row.created_at,
        }))
      }
      res.set('Cache-Control', 'private, max-age=15')
      res.json({ items: mergedPublicContent(rows, imported, 100, true) })
    } catch (error) { next(error) }
  })

  app.get('/v1/guest/profiles/search', requireGuest, async (req, res, next) => {
    try {
      const query = String(req.query.q || '').trim().replace(/^@/, '').slice(0, 50)
      if (query.length < 2) return res.json({ items: [] })
      const { rows } = await pool.query(
        `SELECT u.id::text AS id, u.supabase_user_id::text AS supabase_user_id,
                COALESCE(NULLIF(o.profile->>'display_name', ''), u.display_name, 'Usuario') AS display_name,
                CASE WHEN COALESCE(o.profile->>'username_origin', '') NOT IN ('chosen', 'migrated_name')
                  THEN NULL ELSE NULLIF(o.profile->>'username', '') END AS username,
                COALESCE(NULLIF(o.profile->>'avatar_url', ''), u.avatar_url) AS avatar_url,
                NULLIF(o.profile->>'bio', '') AS bio
         FROM fallback_users u
         LEFT JOIN fallback_profile_overrides o ON o.user_id = u.id
         WHERE COALESCE(o.profile->>'profile_visibility', 'everyone') = 'everyone'
           AND COALESCE(o.profile->>'break_mode', 'off') <> 'hidden'
           AND (strpos(lower(CASE WHEN COALESCE(o.profile->>'username_origin', '') NOT IN ('chosen', 'migrated_name')
             THEN '' ELSE COALESCE(o.profile->>'username', '') END), $1) > 0
            OR strpos(lower(COALESCE(o.profile->>'display_name', u.display_name, '')), $1) > 0)
         ORDER BY CASE WHEN lower(o.profile->>'username') = $1 THEN 0
                       WHEN left(lower(o.profile->>'username'), length($1)) = $1 THEN 1 ELSE 2 END,
                  u.id
         LIMIT 20`,
        [query.toLowerCase()],
      )
      let imported = []
      if (await importedTableExists(pool, 'profiles')) {
        const result = await pool.query(
          `SELECT to_jsonb(p) AS row FROM supabase_import.profiles p
           LEFT JOIN fallback_users u ON u.supabase_user_id::text = p.id OR u.id::text = p.id
           LEFT JOIN fallback_profile_overrides o ON o.user_id = u.id
           WHERE COALESCE(NULLIF(p.is_suspended, ''), 'false') NOT IN ('true', 't', '1')
             AND COALESCE(o.profile->>'profile_visibility', 'everyone') = 'everyone'
             AND COALESCE(o.profile->>'break_mode', 'off') <> 'hidden'
             AND (strpos(lower(CASE WHEN COALESCE(to_jsonb(p)->>'username_origin', '') NOT IN ('chosen', 'migrated_name')
               THEN '' ELSE COALESCE(p.username, '') END), $1) > 0
               OR strpos(lower(COALESCE(p.display_name, p.full_name, '')), $1) > 0)
           ORDER BY CASE WHEN lower(p.username) = $1 THEN 0
                         WHEN left(lower(p.username), length($1)) = $1 THEN 1 ELSE 2 END,
                    p.id LIMIT 20`,
          [query.toLowerCase()],
        )
        imported = result.rows.map(({ row }) => importedProfile(row))
      }
      const byId = new Map()
      for (const row of imported) byId.set(row.id, row)
      for (const row of rows) {
        const existing = imported.find((item) => item.id === row.id || item.id === row.supabase_user_id)
        byId.set(row.id, { ...existing, ...row, username: row.username || existing?.username || null })
        if (existing && existing.id !== row.id) byId.delete(existing.id)
      }
      res.set('Cache-Control', 'private, max-age=15')
      res.json({ items: [...byId.values()].slice(0, 20) })
    } catch (error) { next(error) }
  })

  app.get('/v1/guest/profiles/:id', requireGuest, async (req, res, next) => {
    try {
      const id = String(req.params.id || '')
      if (!/^[0-9a-f-]{36}$/i.test(id)) return res.status(400).json({ error: 'PROFILE_ID_INVALID' })
      const profile = await readPublicProfile(pool, id)
      if (!profile) return res.status(404).json({ error: 'PROFILE_NOT_FOUND' })
      res.set('Cache-Control', 'private, max-age=30')
      res.json({ profile })
    } catch (error) { next(error) }
  })
}

module.exports = { GUEST_AUDIENCE, issueGuestIdentity, guestAuth, readPublicContent, readPublicProfile, registerGuestModeRoutes }
