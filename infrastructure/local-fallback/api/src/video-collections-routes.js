'use strict'

const { randomUUID } = require('node:crypto')
const { safeVideoCard } = require('./video-watch-later-routes')
const { publishRealtimeEvent } = require('./realtime-publish')

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const cleanId = (value) => String(value || '').trim().toLowerCase()
const collectionName = (value) => typeof value === 'string' && value.trim().length >= 1 &&
  value.trim().length <= 60 && !/[\u0000-\u001f\u007f]/.test(value) ? value.trim() : null

async function ensureVideoCollectionTables(pool) {
  await pool.query(`CREATE TABLE IF NOT EXISTS fallback_video_collections (
    id UUID PRIMARY KEY, user_id TEXT NOT NULL, name TEXT NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW())`)
  await pool.query(`CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_video_collection_name
    ON fallback_video_collections(user_id, lower(name))`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_fallback_video_collection_owner
    ON fallback_video_collections(user_id, sort_order, created_at)`)
  await pool.query(`CREATE TABLE IF NOT EXISTS fallback_video_collection_items (
    collection_id UUID NOT NULL REFERENCES fallback_video_collections(id) ON DELETE CASCADE,
    video_id TEXT NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0,
    added_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), PRIMARY KEY (collection_id, video_id))`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_fallback_video_collection_items_order
    ON fallback_video_collection_items(collection_id, sort_order, added_at, video_id)`)
}

async function transaction(pool, work) {
  const client = await pool.connect()
  try {
    await client.query('BEGIN')
    const result = await work(client)
    await client.query('COMMIT')
    return result
  } catch (error) {
    await client.query('ROLLBACK')
    throw error
  } finally { client.release() }
}

function registerVideoCollectionRoutes(app, {
  pool, authRequired, getCurrentUser, userIdentity, videoRowsByIds,
  localVideoDeletionIds, visibleVideoForIdentity, publishChange = publishRealtimeEvent,
}) {
  async function viewer(req, res) {
    const user = await getCurrentUser(pool, req)
    if (!user) { res.status(404).json({ error: 'USER_NOT_FOUND' }); return null }
    return userIdentity(user)
  }
  async function owned(client, id, userId, lock = false) {
    const { rows } = await client.query(`SELECT id, name FROM fallback_video_collections
      WHERE id=$1 AND user_id=$2 ${lock ? 'FOR UPDATE' : ''}`, [id, userId])
    return rows[0] || null
  }
  function changed(userId, collectionId) {
    void publishChange(`user:${userId}`, { event: 'video_collections', collectionId })
  }
  function validCollection(req, res) {
    const id = cleanId(req.params.collectionId)
    if (!UUID.test(id)) { res.status(400).json({ error: 'INVALID_COLLECTION_ID' }); return null }
    return id
  }

  app.get('/v1/data/videos/collections', authRequired, async (req, res, next) => {
    try {
      const identity = await viewer(req, res)
      if (!identity) return
      const { rows } = await pool.query(`SELECT c.id, c.name, c.created_at, c.updated_at,
          COUNT(i.video_id)::int AS item_count
        FROM fallback_video_collections c
        LEFT JOIN fallback_video_collection_items i ON i.collection_id=c.id
        WHERE c.user_id=$1 GROUP BY c.id
        ORDER BY c.sort_order, c.created_at, c.id LIMIT 101`, [identity.displayUserId])
      res.setHeader?.('Cache-Control', 'private, no-store')
      res.json({ items: rows.map((row) => ({ id: row.id, name: row.name,
        itemCount: Number(row.item_count), createdAt: row.created_at, updatedAt: row.updated_at })) })
    } catch (error) { next(error) }
  })

  app.post('/v1/data/videos/collections', authRequired, async (req, res, next) => {
    try {
      const identity = await viewer(req, res)
      if (!identity) return
      const name = collectionName(req.body?.name)
      if (!name) return res.status(400).json({ error: 'INVALID_COLLECTION_NAME' })
      const result = await transaction(pool, async (client) => {
        await client.query('SELECT pg_advisory_xact_lock(hashtext($1))',
          [`video_collections:${identity.displayUserId}`])
        const { rows } = await client.query(`SELECT COUNT(*)::int AS total,
          COALESCE(MAX(sort_order), -1)::int AS last_order FROM fallback_video_collections WHERE user_id=$1`,
        [identity.displayUserId])
        if (Number(rows[0].total) >= 100) return { limit: true }
        const id = randomUUID()
        await client.query(`INSERT INTO fallback_video_collections(id,user_id,name,sort_order)
          VALUES($1,$2,$3,$4)`, [id, identity.displayUserId, name, Number(rows[0].last_order) + 1])
        return { id, name, itemCount: 0 }
      })
      if (result.limit) return res.status(409).json({ error: 'COLLECTION_LIMIT_REACHED' })
      changed(identity.displayUserId, result.id)
      res.status(201).json(result)
    } catch (error) {
      if (error.code === '23505') return res.status(409).json({ error: 'COLLECTION_NAME_IN_USE' })
      next(error)
    }
  })

  app.patch('/v1/data/videos/collections/:collectionId', authRequired, async (req, res, next) => {
    try {
      const identity = await viewer(req, res)
      if (!identity) return
      const id = validCollection(req, res)
      if (!id) return
      const name = collectionName(req.body?.name)
      if (!name) return res.status(400).json({ error: 'INVALID_COLLECTION_NAME' })
      const { rows } = await pool.query(`UPDATE fallback_video_collections SET name=$3, updated_at=NOW()
        WHERE id=$1 AND user_id=$2 RETURNING id, name`, [id, identity.displayUserId, name])
      if (!rows[0]) return res.status(404).json({ error: 'COLLECTION_NOT_FOUND' })
      changed(identity.displayUserId, id)
      res.json(rows[0])
    } catch (error) {
      if (error.code === '23505') return res.status(409).json({ error: 'COLLECTION_NAME_IN_USE' })
      next(error)
    }
  })

  app.delete('/v1/data/videos/collections/:collectionId', authRequired, async (req, res, next) => {
    try {
      const identity = await viewer(req, res)
      if (!identity) return
      const id = validCollection(req, res)
      if (!id) return
      const { rowCount } = await pool.query(`DELETE FROM fallback_video_collections
        WHERE id=$1 AND user_id=$2`, [id, identity.displayUserId])
      if (!rowCount) return res.status(404).json({ error: 'COLLECTION_NOT_FOUND' })
      changed(identity.displayUserId, id)
      res.json({ ok: true })
    } catch (error) { next(error) }
  })

  app.get('/v1/data/videos/collections/:collectionId/items', authRequired, async (req, res, next) => {
    try {
      const identity = await viewer(req, res)
      if (!identity) return
      const id = validCollection(req, res)
      if (!id) return
      if (!await owned(pool, id, identity.displayUserId)) {
        return res.status(404).json({ error: 'COLLECTION_NOT_FOUND' })
      }
      const search = String(req.query?.search || '').trim().toLowerCase()
      const offset = Number(req.query?.offset || 0)
      if (search.length > 80 || !Number.isInteger(offset) || offset < 0 || offset > 500) {
        return res.status(400).json({ error: 'INVALID_COLLECTION_QUERY' })
      }
      const [itemsResult, countResult] = await Promise.all([
        pool.query(`SELECT video_id, sort_order, added_at
          FROM fallback_video_collection_items WHERE collection_id=$1
          ORDER BY sort_order, added_at, video_id LIMIT $2 OFFSET $3`,
        [id, search ? 500 : 50, search ? 0 : offset]),
        search ? Promise.resolve(null) : pool.query(`SELECT COUNT(*)::int AS total
          FROM fallback_video_collection_items WHERE collection_id=$1`, [id]),
      ])
      const rows = itemsResult.rows
      const ids = rows.map((row) => cleanId(row.video_id))
      const [videos, deleted] = ids.length ? await Promise.all([
        videoRowsByIds(pool, ids), localVideoDeletionIds(pool, ids),
      ]) : [[], new Set()]
      const byId = new Map(videos.map((row) => [cleanId(row.id), row]))
      const entries = rows.map((row) => {
        const videoId = cleanId(row.video_id)
        const card = safeVideoCard(byId.get(videoId), identity, deleted.has(videoId))
        return { videoId, sortOrder: Number(row.sort_order), addedAt: row.added_at,
          unavailable: !card, ...(card || {}) }
      }).filter((item) => !search || (!item.unavailable &&
        `${item.title} ${item.creatorName}`.toLowerCase().includes(search)))
      res.setHeader?.('Cache-Control', 'private, no-store')
      const total = search ? entries.length : Number(countResult.rows[0]?.total) || 0
      res.json({ items: search ? entries.slice(offset, offset + 50) : entries, total,
        nextOffset: offset + 50 < total ? offset + 50 : null })
    } catch (error) { next(error) }
  })

  app.put('/v1/data/videos/collections/:collectionId/items/:videoId', authRequired, async (req, res, next) => {
    try {
      const identity = await viewer(req, res)
      if (!identity) return
      const id = validCollection(req, res)
      if (!id) return
      const videoId = cleanId(req.params.videoId)
      if (!UUID.test(videoId)) return res.status(400).json({ error: 'INVALID_VIDEO_ID' })
      if (!await visibleVideoForIdentity(pool, videoId, identity)) {
        return res.status(404).json({ error: 'VIDEO_NOT_FOUND' })
      }
      const result = await transaction(pool, async (client) => {
        if (!await owned(client, id, identity.displayUserId, true)) return { missing: true }
        const { rows } = await client.query(`SELECT COUNT(*)::int AS total,
          COALESCE(MAX(sort_order), -1)::int AS last_order,
          BOOL_OR(video_id=$2) AS exists
          FROM fallback_video_collection_items WHERE collection_id=$1`, [id, videoId])
        if (rows[0].exists) return { added: false }
        if (Number(rows[0].total) >= 500) return { limit: true }
        await client.query(`INSERT INTO fallback_video_collection_items(collection_id,video_id,sort_order)
          VALUES($1,$2,$3)`, [id, videoId, Number(rows[0].last_order) + 1])
        return { added: true }
      })
      if (result.missing) return res.status(404).json({ error: 'COLLECTION_NOT_FOUND' })
      if (result.limit) return res.status(409).json({ error: 'COLLECTION_ITEM_LIMIT_REACHED' })
      if (result.added) changed(identity.displayUserId, id)
      res.json({ ok: true, added: result.added })
    } catch (error) { next(error) }
  })

  app.delete('/v1/data/videos/collections/:collectionId/items/:videoId', authRequired, async (req, res, next) => {
    try {
      const identity = await viewer(req, res)
      if (!identity) return
      const id = validCollection(req, res)
      if (!id) return
      const videoId = cleanId(req.params.videoId)
      if (!UUID.test(videoId)) return res.status(400).json({ error: 'INVALID_VIDEO_ID' })
      const { rowCount } = await pool.query(`DELETE FROM fallback_video_collection_items i
        USING fallback_video_collections c
        WHERE i.collection_id=c.id AND c.id=$1 AND c.user_id=$2 AND i.video_id=$3`,
      [id, identity.displayUserId, videoId])
      if (rowCount) changed(identity.displayUserId, id)
      res.json({ ok: true, removed: Boolean(rowCount) })
    } catch (error) { next(error) }
  })

  app.post('/v1/data/videos/collections/:collectionId/items/reorder', authRequired, async (req, res, next) => {
    try {
      const identity = await viewer(req, res)
      if (!identity) return
      const id = validCollection(req, res)
      if (!id) return
      const ids = req.body?.orderedVideoIds
      if (!Array.isArray(ids) || ids.length > 500 ||
          ids.some((videoId) => typeof videoId !== 'string' || !UUID.test(videoId)) ||
          new Set(ids.map(cleanId)).size !== ids.length) {
        return res.status(400).json({ error: 'INVALID_COLLECTION_ORDER' })
      }
      const result = await transaction(pool, async (client) => {
        if (!await owned(client, id, identity.displayUserId, true)) return { missing: true }
        const { rows } = await client.query(`SELECT video_id FROM fallback_video_collection_items
          WHERE collection_id=$1`, [id])
        const current = new Set(rows.map((row) => cleanId(row.video_id)))
        if (current.size !== ids.length || ids.some((videoId) => !current.has(cleanId(videoId)))) {
          return { stale: true }
        }
        await client.query(`UPDATE fallback_video_collection_items AS item SET sort_order=ordered.ordinality - 1
          FROM unnest($2::text[]) WITH ORDINALITY AS ordered(video_id, ordinality)
          WHERE item.collection_id=$1 AND lower(item.video_id)=lower(ordered.video_id)`, [id, ids])
        return { ok: true }
      })
      if (result.missing) return res.status(404).json({ error: 'COLLECTION_NOT_FOUND' })
      if (result.stale) return res.status(409).json({ error: 'COLLECTION_ORDER_STALE' })
      changed(identity.displayUserId, id)
      res.json({ ok: true })
    } catch (error) { next(error) }
  })

  app.patch('/v1/data/videos/collections/:collectionId/items/:videoId/move', authRequired, async (req, res, next) => {
    try {
      const identity = await viewer(req, res)
      if (!identity) return
      const id = validCollection(req, res)
      if (!id) return
      const videoId = cleanId(req.params.videoId)
      const direction = req.body?.direction
      if (!UUID.test(videoId) || !['up', 'down'].includes(direction)) {
        return res.status(400).json({ error: 'INVALID_COLLECTION_MOVE' })
      }
      const result = await transaction(pool, async (client) => {
        if (!await owned(client, id, identity.displayUserId, true)) return { missing: true }
        const { rows } = await client.query(`SELECT video_id, sort_order FROM fallback_video_collection_items
          WHERE collection_id=$1 ORDER BY sort_order, added_at, video_id`, [id])
        const index = rows.findIndex((row) => cleanId(row.video_id) === videoId)
        if (index < 0) return { missingItem: true }
        const adjacent = direction === 'up' ? index - 1 : index + 1
        if (adjacent < 0 || adjacent >= rows.length) return { moved: false }
        const swapped = rows[adjacent]
        await client.query(`UPDATE fallback_video_collection_items SET sort_order=
          CASE WHEN video_id=$2 THEN $4::integer ELSE $5::integer END
          WHERE collection_id=$1 AND video_id IN ($2,$3)`,
        [id, videoId, swapped.video_id, Number(swapped.sort_order), Number(rows[index].sort_order)])
        return { moved: true }
      })
      if (result.missing) return res.status(404).json({ error: 'COLLECTION_NOT_FOUND' })
      if (result.missingItem) return res.status(404).json({ error: 'VIDEO_NOT_IN_COLLECTION' })
      if (result.moved) changed(identity.displayUserId, id)
      res.json({ ok: true, moved: result.moved })
    } catch (error) { next(error) }
  })
}

module.exports = { ensureVideoCollectionTables, registerVideoCollectionRoutes, collectionName }
