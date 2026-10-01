'use strict'

const { randomUUID } = require('node:crypto')
const { publishRealtimeEvent } = require('./realtime-publish')
const { sendPushForNotification } = require('./push-notifications')
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const KINDS = new Set(['info', 'maintenance', 'update'])

function inputError(code) { const e = new Error(code); e.status = 400; e.expose = true; return e }
function normalizeAnnouncement(input, now = Date.now()) {
  const kind = String(input?.kind || '').trim()
  const title = String(input?.title || '').trim()
  const body = String(input?.body || '').trim()
  const expiresAt = new Date(input?.expiresAt || '').getTime()
  if (!KINDS.has(kind) || title.length < 3 || title.length > 100 || body.length < 5 || body.length > 1200) throw inputError('ANNOUNCEMENT_TEXT_INVALID')
  if (!Number.isFinite(expiresAt) || expiresAt <= now || expiresAt > now + 90 * 86400000) throw inputError('ANNOUNCEMENT_EXPIRY_INVALID')
  let startsAt = null, endsAt = null, versionCode = null
  if (kind === 'maintenance') {
    const start = new Date(input.startsAt || '').getTime(), end = new Date(input.endsAt || '').getTime()
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start || end <= now || expiresAt < end) throw inputError('MAINTENANCE_SCHEDULE_INVALID')
    startsAt = new Date(start).toISOString(); endsAt = new Date(end).toISOString()
  }
  if (kind === 'update') {
    versionCode = Number(input.versionCode)
    if (!Number.isInteger(versionCode) || versionCode < 1 || versionCode > 2100000000) throw inputError('UPDATE_VERSION_INVALID')
  }
  const id = String(input.id || randomUUID()).trim()
  if (!UUID.test(id)) throw inputError('ANNOUNCEMENT_ID_INVALID')
  // An update always opens the official application listing. No administrator
  // supplied download URL can lead users to an unrelated or unsigned APK.
  return { id, kind, title, body, startsAt, endsAt, expiresAt: new Date(expiresAt).toISOString(), versionCode,
    updateUrl: kind === 'update' ? 'https://play.google.com/store/apps/details?id=conectate.com' : null }
}

async function ensureSystemAnnouncementSchema(pool) {
  await pool.query(`CREATE TABLE IF NOT EXISTS fallback_system_announcements (
    id UUID PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN ('info','maintenance','update')),
    title TEXT NOT NULL, body TEXT NOT NULL, starts_at TIMESTAMPTZ, ends_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL, version_code INTEGER, update_url TEXT,
    created_by TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), revoked_at TIMESTAMPTZ)`)
  await pool.query(`CREATE TABLE IF NOT EXISTS fallback_system_announcement_deliveries (
    announcement_id UUID NOT NULL REFERENCES fallback_system_announcements(id) ON DELETE CASCADE,
    recipient_id TEXT NOT NULL, notification_id UUID NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
    retry_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), locked_until TIMESTAMPTZ, delivered_at TIMESTAMPTZ,
    PRIMARY KEY(announcement_id, recipient_id))`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_system_announcement_delivery_pending
    ON fallback_system_announcement_deliveries(retry_at) WHERE delivered_at IS NULL`)
}

function publicNotice(row) {
  return { id: row.id, kind: row.kind, title: row.title, body: row.body, startsAt: row.starts_at,
    endsAt: row.ends_at, expiresAt: row.expires_at, versionCode: row.version_code,
    updateUrl: row.update_url, createdAt: row.created_at, revokedAt: row.revoked_at || null }
}

async function publishAnnouncement(pool, input, actorId) {
  const announcement = normalizeAnnouncement(input)
  const client = await pool.connect()
  try {
    await client.query('BEGIN')
    const inserted = await client.query(`INSERT INTO fallback_system_announcements
      (id,kind,title,body,starts_at,ends_at,expires_at,version_code,update_url,created_by)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) ON CONFLICT(id) DO NOTHING RETURNING *`,
      [announcement.id, announcement.kind, announcement.title, announcement.body, announcement.startsAt,
        announcement.endsAt, announcement.expiresAt, announcement.versionCode, announcement.updateUrl, actorId])
    if (!inserted.rows.length) {
      const old = await client.query('SELECT * FROM fallback_system_announcements WHERE id=$1', [announcement.id])
      const original = old.rows[0]
      if (!original || original.created_by !== actorId || original.kind !== announcement.kind || original.title !== announcement.title || original.body !== announcement.body ||
          new Date(original.expires_at).toISOString() !== announcement.expiresAt || Number(original.version_code || 0) !== Number(announcement.versionCode || 0) ||
          (original.starts_at ? new Date(original.starts_at).toISOString() : null) !== announcement.startsAt ||
          (original.ends_at ? new Date(original.ends_at).toISOString() : null) !== announcement.endsAt) {
        const e = new Error('ANNOUNCEMENT_ID_CONFLICT'); e.status = 409; throw e
      }
      await client.query('COMMIT')
      return { notice: publicNotice(original), audience: 0, duplicate: true }
    }
    const extra = { announcement_id: announcement.id, title: announcement.title, kind: announcement.kind,
      starts_at: announcement.startsAt, ends_at: announcement.endsAt, version_code: announcement.versionCode }
    const delivered = await client.query(`WITH notifications AS (
      INSERT INTO fallback_notifications(recipient_id,actor_name,type,target_id,target_type,content,extra_data)
      SELECT COALESCE(supabase_user_id::text,id::text), 'Novvin', 'system_announcement', $1, 'system', $2, $3::jsonb
      FROM fallback_users RETURNING id,recipient_id
    ) INSERT INTO fallback_system_announcement_deliveries(announcement_id,recipient_id,notification_id)
      SELECT $1::uuid,recipient_id,id FROM notifications RETURNING recipient_id`,
      [announcement.id, announcement.body, JSON.stringify(extra)])
    await client.query('COMMIT')
    return { notice: publicNotice(inserted.rows[0]), audience: delivered.rowCount, duplicate: false }
  } catch (error) { await client.query('ROLLBACK'); throw error }
  finally { client.release() }
}

function createAnnouncementWorker(pool, { publish = publishRealtimeEvent, push = sendPushForNotification } = {}) {
  let running = false
  return async function run() {
    if (running) return
    running = true
    try {
      const claimed = await pool.query(`WITH candidates AS (
        SELECT d.announcement_id,d.recipient_id FROM fallback_system_announcement_deliveries d
        JOIN fallback_system_announcements a ON a.id=d.announcement_id
        WHERE d.delivered_at IS NULL AND d.retry_at <= NOW() AND (d.locked_until IS NULL OR d.locked_until < NOW())
          AND a.revoked_at IS NULL AND a.expires_at > NOW()
        ORDER BY d.retry_at LIMIT 25 FOR UPDATE OF d SKIP LOCKED
      ) UPDATE fallback_system_announcement_deliveries d SET locked_until=NOW()+INTERVAL '2 minutes',attempts=d.attempts+1
        FROM candidates c WHERE d.announcement_id=c.announcement_id AND d.recipient_id=c.recipient_id RETURNING d.*`)
      await Promise.allSettled(claimed.rows.map(async delivery => {
        try {
          const result = await pool.query('SELECT * FROM fallback_notifications WHERE id=$1', [delivery.notification_id])
          const row = result.rows[0]
          if (!row) throw new Error('NOTICE_NOTIFICATION_MISSING')
          const notification = JSON.parse(JSON.stringify(row))
          const realtime = await publish(`user:${row.recipient_id}`, { event: 'notification', notification, extraData: row.extra_data })
          const pushed = await push(pool, { ...notification, content: Array.from(notification.content || '').slice(0, 240).join('') }, row.extra_data)
          if (pushed.failed || !realtime) throw new Error('NOTICE_DELIVERY_RETRY')
          await pool.query(`UPDATE fallback_system_announcement_deliveries SET delivered_at=NOW(),locked_until=NULL
            WHERE announcement_id=$1 AND recipient_id=$2`, [delivery.announcement_id, delivery.recipient_id])
        } catch {
          await pool.query(`UPDATE fallback_system_announcement_deliveries SET locked_until=NULL,retry_at=NOW()+INTERVAL '1 minute'
            WHERE announcement_id=$1 AND recipient_id=$2`, [delivery.announcement_id, delivery.recipient_id])
        }
      }))
    } finally { running = false }
  }
}

function registerSystemAnnouncementRoutes(app, { pool, adminRequired, runWorker }) {
  app.get('/v1/system/announcements', async (req, res, next) => {
    try {
      const versionCode = Math.max(0, Math.floor(Number(req.query.versionCode) || 0))
      const { rows } = await pool.query(`SELECT * FROM fallback_system_announcements
        WHERE revoked_at IS NULL AND expires_at > NOW()
          AND (kind <> 'update' OR version_code > $1) ORDER BY created_at DESC LIMIT 20`, [versionCode])
      res.set('Cache-Control', 'no-store'); res.json({ items: rows.map(publicNotice) })
    } catch (error) { next(error) }
  })
  app.get('/v1/admin/system/announcements', adminRequired, async (_req, res, next) => {
    try {
      const { rows } = await pool.query('SELECT * FROM fallback_system_announcements ORDER BY created_at DESC LIMIT 50')
      res.set('Cache-Control', 'no-store'); res.json({ items: rows.map(publicNotice) })
    } catch (error) { next(error) }
  })
  app.post('/v1/admin/system/announcements', adminRequired, async (req, res, next) => {
    try {
      const result = await publishAnnouncement(pool, req.body, req.auth.sub)
      res.status(result.duplicate ? 200 : 201).json(result)
      Promise.resolve().then(runWorker).catch(() => {})
    } catch (error) { next(error) }
  })
  app.delete('/v1/admin/system/announcements/:id', adminRequired, async (req, res, next) => {
    try {
      if (!UUID.test(req.params.id)) throw inputError('ANNOUNCEMENT_ID_INVALID')
      const { rows } = await pool.query('UPDATE fallback_system_announcements SET revoked_at=COALESCE(revoked_at,NOW()) WHERE id=$1 RETURNING *', [req.params.id])
      if (!rows.length) return res.status(404).json({ error: 'ANNOUNCEMENT_NOT_FOUND' })
      res.json({ notice: publicNotice(rows[0]) })
    } catch (error) { next(error) }
  })
}
module.exports = { normalizeAnnouncement, ensureSystemAnnouncementSchema, publicNotice, publishAnnouncement, createAnnouncementWorker, registerSystemAnnouncementRoutes }
