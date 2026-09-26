'use strict'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const cleanId = (value) => String(value || '').trim().toLowerCase()
const { safeVideoCard } = require('./video-watch-later-routes')
const { publishRealtimeEvent } = require('./realtime-publish')

async function ensureVideoHistoryColumns(pool) {
  await pool.query(`CREATE TABLE IF NOT EXISTS fallback_video_playback_history (
    user_id TEXT NOT NULL,
    video_id TEXT NOT NULL,
    position_ms BIGINT NOT NULL DEFAULT 0,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    viewed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, video_id)
  )`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_fallback_video_history_cursor
    ON fallback_video_playback_history(user_id, viewed_at DESC, video_id DESC)`)
}

function historyCursor(raw) {
  if (!raw) return null
  if (typeof raw !== 'string' || raw.length > 256 || !/^[A-Za-z0-9_-]+$/.test(raw)) return false
  try {
    const value = JSON.parse(Buffer.from(raw, 'base64url').toString('utf8'))
    return Number.isFinite(Date.parse(value.viewedAt)) && UUID.test(value.videoId) ? value : false
  } catch { return false }
}

function playbackPosition(body) {
  const positionMs = Number(body?.positionMs)
  const durationMs = Number(body?.durationMs)
  if (!Number.isSafeInteger(positionMs) || !Number.isSafeInteger(durationMs) ||
      positionMs < 0 || durationMs < 0 || durationMs > 24 * 60 * 60 * 1000 ||
      positionMs > durationMs || durationMs === 0) return null
  return { positionMs, durationMs }
}

function registerVideoHistoryRoutes(app, {
  pool, authRequired, getCurrentUser, userIdentity, videoRowsByIds, localVideoDeletionIds,
  visibleVideoForIdentity, publishChange = publishRealtimeEvent,
}) {
  async function viewer(req, res) {
    const user = await getCurrentUser(pool, req)
    if (!user) { res.status(404).json({ error: 'USER_NOT_FOUND' }); return null }
    return userIdentity(user)
  }

  app.get('/v1/data/videos/history/list', authRequired, async (req, res, next) => {
    try {
      const identity = await viewer(req, res)
      if (!identity) return
      const cursor = historyCursor(req.query?.cursor)
      if (cursor === false) return res.status(400).json({ error: 'INVALID_VIDEO_HISTORY_CURSOR' })
      const { rows } = await pool.query(`SELECT video_id, position_ms, duration_ms, viewed_at,
          viewed_at::text AS cursor_viewed_at
        FROM fallback_video_playback_history WHERE user_id=$1
          AND ($2::timestamptz IS NULL OR (viewed_at, video_id) < ($2::timestamptz, $3::text))
        ORDER BY viewed_at DESC, video_id DESC LIMIT 51`,
      [identity.displayUserId, cursor?.viewedAt || null, cursor?.videoId || null])
      const page = rows.slice(0, 50)
      const ids = page.map((row) => cleanId(row.video_id))
      const [videos, deletedIds] = ids.length ? await Promise.all([
        videoRowsByIds(pool, ids), localVideoDeletionIds(pool, ids),
      ]) : [[], new Set()]
      const byId = new Map(videos.map((row) => [cleanId(row.id), row]))
      const items = page.map((entry) => {
        const videoId = cleanId(entry.video_id)
        const card = safeVideoCard(byId.get(videoId), identity, deletedIds.has(videoId))
        return { videoId, viewedAt: entry.viewed_at,
          positionMs: card ? Number(entry.position_ms) || 0 : 0,
          durationMs: card ? Number(entry.duration_ms) || 0 : 0,
          unavailable: !card, ...(card || {}) }
      })
      const last = page.at(-1)
      res.setHeader?.('Cache-Control', 'private, no-store')
      res.json({ items, nextCursor: rows.length > 50 && last
        ? Buffer.from(JSON.stringify({ viewedAt: last.cursor_viewed_at || last.viewed_at,
          videoId: last.video_id })).toString('base64url') : null })
    } catch (error) { next(error) }
  })

  app.put('/v1/data/videos/history/:videoId', authRequired, async (req, res, next) => {
    try {
      const identity = await viewer(req, res)
      if (!identity) return
      const videoId = cleanId(req.params.videoId)
      const progress = playbackPosition(req.body)
      if (!UUID.test(videoId) || !progress) return res.status(400).json({ error: 'INVALID_VIDEO_PROGRESS' })
      if (!await visibleVideoForIdentity(pool, videoId, identity)) {
        return res.status(404).json({ error: 'VIDEO_NOT_FOUND' })
      }
      await pool.query(`INSERT INTO fallback_video_playback_history(video_id,user_id,position_ms,duration_ms,viewed_at)
        VALUES($1,$2,$3,$4,NOW())
        ON CONFLICT (video_id,user_id) DO UPDATE SET
          position_ms=EXCLUDED.position_ms, duration_ms=EXCLUDED.duration_ms, viewed_at=NOW()`,
      [videoId, identity.displayUserId, progress.positionMs, progress.durationMs])
      void publishChange(`user:${identity.displayUserId}`, { event: 'video_history', videoId })
      res.json({ ok: true, videoId, ...progress })
    } catch (error) { next(error) }
  })

  app.delete('/v1/data/videos/history/:videoId', authRequired, async (req, res, next) => {
    try {
      const identity = await viewer(req, res)
      if (!identity) return
      const videoId = cleanId(req.params.videoId)
      if (!UUID.test(videoId)) return res.status(400).json({ error: 'INVALID_VIDEO_ID' })
      await pool.query(`DELETE FROM fallback_video_playback_history WHERE user_id=$1 AND video_id=$2`,
        [identity.displayUserId, videoId])
      void publishChange(`user:${identity.displayUserId}`, { event: 'video_history', videoId })
      res.json({ ok: true })
    } catch (error) { next(error) }
  })

  app.delete('/v1/data/videos/history', authRequired, async (req, res, next) => {
    try {
      const identity = await viewer(req, res)
      if (!identity) return
      await pool.query(`DELETE FROM fallback_video_playback_history WHERE user_id=$1`, [identity.displayUserId])
      void publishChange(`user:${identity.displayUserId}`, { event: 'video_history' })
      res.json({ ok: true })
    } catch (error) { next(error) }
  })
}

module.exports = { ensureVideoHistoryColumns, registerVideoHistoryRoutes, historyCursor, playbackPosition }
