'use strict'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const cleanId = (value) => String(value || '').trim().toLowerCase()
const { publishRealtimeEvent } = require('./realtime-publish')

async function ensureVideoWatchLaterTables(pool) {
  await pool.query(`CREATE TABLE IF NOT EXISTS fallback_video_watch_later (
    user_id TEXT NOT NULL,
    video_id TEXT NOT NULL,
    added_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, video_id)
  )`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_fallback_video_watch_later_cursor
    ON fallback_video_watch_later(user_id, added_at DESC, video_id DESC)`)
}

function decodeCursor(raw) {
  if (!raw) return null
  if (typeof raw !== 'string' || raw.length > 256 || !/^[A-Za-z0-9_-]+$/.test(raw)) return false
  try {
    const value = JSON.parse(Buffer.from(raw, 'base64url').toString('utf8'))
    if (!Number.isFinite(Date.parse(value.addedAt)) || !UUID.test(value.videoId)) return false
    return value
  } catch { return false }
}

function safeVideoCard(row, identity, deleted) {
  if (!row || deleted) return null
  const isPublic = ![false, 'false', 0, '0'].includes(row.is_public ?? row.isPublic ?? true)
  const owners = [row.user_id, row.userId, row.owner_id, row.creator_id, row.profile_id]
    .map(cleanId).filter(Boolean)
  if (!isPublic && !owners.some((id) => identity.ids.has(id))) return null
  return {
    title: String(row.title || row.caption || 'Video').slice(0, 160),
    coverUrl: String(row.cover_url || row.coverUrl || row.thumbnail_url || row.thumbnailUrl || '').slice(0, 2048),
    creatorName: String(row.user_name || row.userName || 'Creador').slice(0, 80),
    durationSeconds: Math.max(0, Number(row.duration_seconds || row.durationSeconds) || 0),
  }
}

function registerVideoWatchLaterRoutes(app, {
  pool, authRequired, getCurrentUser, userIdentity, videoRowsByIds, localVideoDeletionIds,
  visibleVideoForIdentity, publishChange = publishRealtimeEvent,
}) {
  async function viewer(req, res) {
    const user = await getCurrentUser(pool, req)
    if (!user) { res.status(404).json({ error: 'USER_NOT_FOUND' }); return null }
    return userIdentity(user)
  }

  app.get('/v1/data/videos/watch-later/list', authRequired, async (req, res, next) => {
    try {
      const identity = await viewer(req, res)
      if (!identity) return
      const sort = String(req.query?.sort || 'newest').toLowerCase()
      if (!['newest', 'oldest'].includes(sort)) return res.status(400).json({ error: 'INVALID_WATCH_LATER_SORT' })
      const cursor = decodeCursor(req.query?.cursor)
      if (cursor === false || (cursor && cursor.sort && cursor.sort !== sort)) {
        return res.status(400).json({ error: 'INVALID_WATCH_LATER_CURSOR' })
      }
      const direction = sort === 'oldest' ? 'ASC' : 'DESC'
      const comparator = sort === 'oldest' ? '>' : '<'
      const { rows } = await pool.query(`SELECT video_id, added_at, added_at::text AS cursor_added_at
        FROM fallback_video_watch_later
        WHERE user_id=$1 AND ($2::timestamptz IS NULL OR (added_at, video_id) ${comparator} ($2::timestamptz, $3::text))
        ORDER BY added_at ${direction}, video_id ${direction} LIMIT 51`,
      [identity.displayUserId, cursor?.addedAt || null, cursor?.videoId || null])
      const page = rows.slice(0, 50)
      const ids = page.map((row) => cleanId(row.video_id))
      const [videos, deletedIds] = ids.length ? await Promise.all([
        videoRowsByIds(pool, ids), localVideoDeletionIds(pool, ids),
      ]) : [[], new Set()]
      const byId = new Map(videos.map((row) => [cleanId(row.id), row]))
      const items = page.map((entry) => {
        const videoId = cleanId(entry.video_id)
        const card = safeVideoCard(byId.get(videoId), identity, deletedIds.has(videoId))
        return { videoId, addedAt: entry.added_at, unavailable: !card, ...(card || {}) }
      })
      const last = page.at(-1)
      res.setHeader?.('Cache-Control', 'private, no-store')
      res.json({ items, nextCursor: rows.length > 50 && last
        ? Buffer.from(JSON.stringify({ addedAt: last.cursor_added_at || last.added_at,
          videoId: last.video_id, sort })).toString('base64url')
        : null })
    } catch (error) { next(error) }
  })

  app.put('/v1/data/videos/watch-later/:videoId', authRequired, async (req, res, next) => {
    try {
      const identity = await viewer(req, res)
      if (!identity) return
      const videoId = cleanId(req.params.videoId)
      if (!UUID.test(videoId)) return res.status(400).json({ error: 'INVALID_VIDEO_ID' })
      if (!await visibleVideoForIdentity(pool, videoId, identity)) {
        return res.status(404).json({ error: 'VIDEO_NOT_FOUND' })
      }
      await pool.query(`INSERT INTO fallback_video_watch_later(user_id, video_id) VALUES($1,$2)
        ON CONFLICT (user_id, video_id) DO NOTHING`, [identity.displayUserId, videoId])
      void publishChange(`user:${identity.displayUserId}`,
        { event: 'video_watch_later', videoId })
      res.json({ saved: true, videoId })
    } catch (error) { next(error) }
  })

  app.delete('/v1/data/videos/watch-later/:videoId', authRequired, async (req, res, next) => {
    try {
      const identity = await viewer(req, res)
      if (!identity) return
      const videoId = cleanId(req.params.videoId)
      if (!UUID.test(videoId)) return res.status(400).json({ error: 'INVALID_VIDEO_ID' })
      await pool.query(`DELETE FROM fallback_video_watch_later WHERE user_id=$1 AND video_id=$2`,
        [identity.displayUserId, videoId])
      void publishChange(`user:${identity.displayUserId}`,
        { event: 'video_watch_later', videoId })
      res.json({ saved: false, videoId })
    } catch (error) { next(error) }
  })
}

module.exports = { ensureVideoWatchLaterTables, registerVideoWatchLaterRoutes, decodeCursor, safeVideoCard }
