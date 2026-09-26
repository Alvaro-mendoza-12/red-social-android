'use strict'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const KINDS = new Set(['chat', 'group', 'community'])
const { publishRealtimeEvent } = require('./realtime-publish')

async function ensureMessageMarkerTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_message_pins (
      room_kind TEXT NOT NULL CHECK (room_kind IN ('chat', 'group', 'community')),
      room_id TEXT NOT NULL,
      message_id TEXT NOT NULL,
      pinned_by TEXT NOT NULL,
      pinned_by_name TEXT NOT NULL DEFAULT '',
      pinned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (room_kind, room_id, message_id)
    )
  `)
  await pool.query(`ALTER TABLE fallback_message_pins ADD COLUMN IF NOT EXISTS pinned_by_name TEXT NOT NULL DEFAULT ''`)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_message_pin_events (
      id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
      room_kind TEXT NOT NULL,
      room_id TEXT NOT NULL,
      message_id TEXT NOT NULL,
      actor_id TEXT NOT NULL,
      action TEXT NOT NULL CHECK (action IN ('pin', 'unpin')),
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_saved_messages (
      user_id TEXT NOT NULL,
      room_kind TEXT NOT NULL CHECK (room_kind IN ('chat', 'group', 'community')),
      room_id TEXT NOT NULL,
      message_id TEXT NOT NULL,
      label TEXT NOT NULL DEFAULT '',
      saved_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (user_id, room_kind, room_id, message_id)
    )
  `)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_fallback_message_pins_room
    ON fallback_message_pins(room_kind, room_id, pinned_at DESC)`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_fallback_saved_messages_user
    ON fallback_saved_messages(user_id, saved_at DESC)`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_fallback_saved_messages_cursor
    ON fallback_saved_messages(user_id, saved_at DESC, room_kind DESC, room_id DESC, message_id DESC)`)
}

function markerParams(req) {
  const kind = String(req.params.kind || '').toLowerCase()
  const roomId = String(req.params.roomId || '').toLowerCase()
  const messageId = String(req.params.messageId || '').toLowerCase()
  if (!KINDS.has(kind) || !UUID.test(roomId) || (req.params.messageId && !UUID.test(messageId))) return null
  return { kind, roomId, messageId }
}

function isActiveMember(row, identity, memberUserId) {
  if (!identity.ids.has(memberUserId(row))) return false
  const status = String(row.status || row.member_status || '').toLowerCase()
  return !['left', 'removed', 'deleted', 'banned'].includes(status)
}

function registerMessageMarkerRoutes(app, {
  pool, authRequired, getCurrentUser, userIdentity, privateChatAccessContext,
  loadCollectionRoomAccess, lookupMessage, lookupMessages, memberUserId, rowMatchesAny, boolOf,
  importedMessageTableExists = async () => false,
}) {
  async function roomContext(user, params) {
    const identity = userIdentity(user)
    if (params.kind === 'chat') {
      const access = await privateChatAccessContext(pool, params.roomId, identity)
      if (!access.hasAccess) return { status: 403, error: 'CHAT_ACCESS_DENIED' }
      if (boolOf(access.chat || {}, ['is_group', 'isGroup'], false)) {
        return { status: 400, error: 'INVALID_MARKER_TARGET' }
      }
      return { ...params, identity, canPin: true,
        roomName: String(access.chat?.name || access.chat?.title || `Chat ${params.roomId.slice(0, 8)}`).slice(0, 100),
        actorName: String(user.display_name || user.username || 'Usuario').trim().slice(0, 80) }
    }
    const access = await loadCollectionRoomAccess(pool, user, params.kind, params.roomId)
    if (!access) return { status: 403, error: 'ROOM_ACCESS_DENIED' }
    const isOwner = rowMatchesAny(access.room, ['creator_id', 'owner_id', 'user_id'], identity.ids)
    const revoked = access.roomMembers.some((row) => identity.ids.has(memberUserId(row)) &&
      ['left', 'removed', 'deleted', 'banned'].includes(String(row.status || row.member_status || '').toLowerCase()))
    if (revoked && !isOwner) return { status: 403, error: 'ROOM_ACCESS_DENIED' }
    const membership = access.roomMembers.find((row) => isActiveMember(row, identity, memberUserId))
    const isPublic = boolOf(access.room, ['is_public', 'public'], false)
    if (!isOwner && !membership && !isPublic) {
      return { status: 403, error: 'ROOM_ACCESS_DENIED' }
    }
    const role = String(membership?.role || '').toLowerCase()
    return { ...params, identity, canPin: isOwner || ['owner', 'admin'].includes(role),
      roomName: String(access.room.name || access.room.title || `${params.kind} ${params.roomId.slice(0, 8)}`).slice(0, 100),
      actorName: String(user.display_name || user.username || 'Usuario').trim().slice(0, 80) }
  }

  async function context(req, res) {
    const params = markerParams(req)
    if (!params) { res.status(400).json({ error: 'INVALID_MARKER_TARGET' }); return null }
    const user = await getCurrentUser(pool, req)
    if (!user) { res.status(404).json({ error: 'USER_NOT_FOUND' }); return null }
    const ctx = await roomContext(user, params)
    if (ctx.error) { res.status(ctx.status).json({ error: ctx.error }); return null }
    return ctx
  }

  async function visibleMessage(ctx, res) {
    const message = await lookupMessage(pool, ctx.kind, ctx.roomId, ctx.messageId, ctx.identity)
    if (!message) { res.status(404).json({ error: 'MESSAGE_NOT_FOUND' }); return null }
    return message
  }

  async function list(ctx) {
    const [pinRows, savedRows] = await Promise.all([
      pool.query(`SELECT message_id, pinned_by, pinned_by_name, pinned_at FROM fallback_message_pins
        WHERE room_kind=$1 AND room_id=$2 ORDER BY pinned_at DESC LIMIT 10`, [ctx.kind, ctx.roomId]),
      pool.query(`SELECT message_id, label, saved_at FROM fallback_saved_messages
        WHERE user_id=$1 AND room_kind=$2 AND room_id=$3 ORDER BY saved_at DESC LIMIT 200`,
      [ctx.identity.displayUserId, ctx.kind, ctx.roomId]),
    ])
    const ids = [...pinRows.rows, ...savedRows.rows].map((row) => row.message_id)
    const messages = lookupMessages
      ? await lookupMessages(pool, ctx.kind, ctx.roomId, ids, ctx.identity)
      : new Map(await Promise.all(ids.map(async (id) =>
        [id, await lookupMessage(pool, ctx.kind, ctx.roomId, id, ctx.identity)])))
    const hydrate = (rows) => rows.map((row) => {
      const message = messages.get(row.message_id) || null
      return { ...row, message, unavailable: !message }
    })
    return { pins: hydrate(pinRows.rows), saved: hydrate(savedRows.rows), canPin: ctx.canPin }
  }

  function publishMarkerChange(ctx, shared) {
    const channel = shared
      ? `message-markers:${ctx.kind}:${ctx.roomId}`
      : `user:${ctx.identity.displayUserId}`
    void publishRealtimeEvent(channel, {
      event: 'message_marker', kind: ctx.kind, roomId: ctx.roomId,
    })
  }

  app.get('/v1/data/messages/markers/:kind/:roomId/access', authRequired, async (req, res, next) => {
    try { const ctx = await context(req, res); if (ctx) res.json({ allowed: true }) } catch (error) { next(error) }
  })

  app.get('/v1/data/messages/markers/:kind/:roomId', authRequired, async (req, res, next) => {
    try { const ctx = await context(req, res); if (ctx) res.json(await list(ctx)) } catch (error) { next(error) }
  })

  app.get('/v1/data/messages/saved', authRequired, async (req, res, next) => {
    try {
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const kind = String(req.query?.kind || '').toLowerCase()
      const roomId = String(req.query?.roomId || '').toLowerCase()
      const type = String(req.query?.type || '').toLowerCase()
      const from = String(req.query?.from || '')
      const to = String(req.query?.to || '')
      const search = String(req.query?.search || '').trim().toLowerCase()
      if ((kind && !KINDS.has(kind)) || (roomId && !UUID.test(roomId)) ||
        (type && !['text', 'document', 'image', 'video', 'audio'].includes(type)) ||
        search.length > 120 ||
        (from && !Number.isFinite(Date.parse(from))) || (to && !Number.isFinite(Date.parse(to)))) {
        return res.status(400).json({ error: 'INVALID_SAVED_FILTER' })
      }
      let cursor = null
      if (req.query?.cursor) {
        try {
          const raw = String(req.query.cursor)
          if (raw.length > 256 || !/^[A-Za-z0-9_-]+$/.test(raw)) throw Error('bad cursor')
          cursor = JSON.parse(Buffer.from(raw, 'base64url').toString('utf8'))
          if (!Number.isFinite(Date.parse(cursor.savedAt)) || !KINDS.has(cursor.kind) ||
            !UUID.test(cursor.roomId) || !UUID.test(cursor.messageId)) throw Error('bad cursor')
        } catch (_) { return res.status(400).json({ error: 'INVALID_SAVED_CURSOR' }) }
      }
      const importedTables = []
      if (search || type) {
        for (const [table, roomKind, roomField] of [
          ['messages', 'chat', 'chat_id'],
          ['group_messages', 'group', 'group_id'],
          ['community_messages', 'community', 'community_id'],
        ]) {
          if (await importedMessageTableExists(table)) importedTables.push({ table, roomKind, roomField })
        }
      }
      const importedMatch = (table, roomKind, roomField, predicate) => `
            EXISTS (SELECT 1 FROM supabase_import.${table} AS m
              WHERE s.room_kind='${roomKind}'
                AND lower(COALESCE(to_jsonb(m)->>'id', ''))=s.message_id
                AND lower(COALESCE(to_jsonb(m)->>'${roomField}', ''))=s.room_id
                AND lower(COALESCE(to_jsonb(m)->>'deleted_for_all', 'false')) <> 'true'
                AND lower(COALESCE(to_jsonb(m)->>'content_type', '')) NOT LIKE 'view_once_%'
                AND ${predicate})`
      const searchIndex = type ? 11 : 10
      const hiddenIndex = searchIndex + 1
      const importedSearch = importedTables.map(({ table, roomKind, roomField }) =>
        importedMatch(table, roomKind, roomField,
          `(strpos(lower(COALESCE(to_jsonb(m)->>'content', '')), $${searchIndex})>0
             OR strpos(lower(COALESCE(to_jsonb(m)->>'sender_id', '')), $${searchIndex})>0)`))
      const typeValues = type === 'document' ? "'document','file'" : `'${type}'`
      const importedTypes = importedTables.map(({ table, roomKind, roomField }) =>
        importedMatch(table, roomKind, roomField,
          `lower(COALESCE(to_jsonb(m)->>'content_type', 'text')) IN (${typeValues})`))
      const typeSql = type ? `AND (
        EXISTS (SELECT 1 FROM fallback_messages AS m
          WHERE s.room_kind='chat' AND m.id::text=s.message_id AND lower(m.chat_id)=s.room_id
            AND m.deleted_for_all=FALSE AND NOT (m.deleted_by ?| $10::text[])
            AND lower(m.content_type) IN (${typeValues}))
        OR EXISTS (SELECT 1 FROM fallback_room_messages AS m
          WHERE s.room_kind IN ('group', 'community') AND m.room_kind=s.room_kind
            AND m.id::text=s.message_id AND lower(m.room_id)=s.room_id
            AND m.deleted_for_all=FALSE AND NOT (m.deleted_by ?| $10::text[])
            AND lower(m.content_type) IN (${typeValues}))
        ${importedTypes.map((condition) => `OR ${condition}`).join('')}
      )` : ''
      const searchSql = search ? `AND (
        strpos(lower(s.label), $${searchIndex})>0
        OR EXISTS (SELECT 1 FROM fallback_messages AS m
          WHERE s.room_kind='chat' AND m.id::text=s.message_id AND lower(m.chat_id)=s.room_id
            AND m.deleted_for_all=FALSE AND NOT (m.deleted_by ?| $${hiddenIndex}::text[])
            AND lower(m.content_type) NOT LIKE 'view_once_%'
            AND (strpos(lower(m.content), $${searchIndex})>0 OR strpos(lower(m.sender_id), $${searchIndex})>0))
        OR EXISTS (SELECT 1 FROM fallback_room_messages AS m
          WHERE s.room_kind IN ('group', 'community') AND m.room_kind=s.room_kind
            AND m.id::text=s.message_id AND lower(m.room_id)=s.room_id
            AND m.deleted_for_all=FALSE AND NOT (m.deleted_by ?| $${hiddenIndex}::text[])
            AND lower(m.content_type) NOT LIKE 'view_once_%'
            AND (strpos(lower(m.content), $${searchIndex})>0 OR strpos(lower(m.sender_id), $${searchIndex})>0))
        ${importedSearch.map((condition) => `OR ${condition}`).join('')}
      )` : ''
      const queryParams = [identity.displayUserId, kind, roomId, from || null, to || null,
        cursor?.savedAt || null, cursor?.kind || null, cursor?.roomId || null, cursor?.messageId || null]
      if (type) queryParams.push([...identity.ids])
      if (search) queryParams.push(search, [...identity.ids])
      const { rows } = await pool.query(`SELECT s.room_kind, s.room_id, s.message_id, s.label, s.saved_at
        FROM fallback_saved_messages AS s WHERE s.user_id=$1
          AND ($2::text = '' OR room_kind=$2)
          AND ($3::text = '' OR room_id=$3)
          AND ($4::timestamptz IS NULL OR saved_at >= $4::timestamptz)
          AND ($5::timestamptz IS NULL OR saved_at <= $5::timestamptz)
          AND ($6::timestamptz IS NULL OR (saved_at, room_kind, room_id, message_id) <
            ($6::timestamptz, $7::text, $8::text, $9::text))
          ${typeSql}
          ${searchSql}
        ORDER BY s.saved_at DESC, s.room_kind DESC, s.room_id DESC, s.message_id DESC LIMIT 51`, queryParams)
      const page = rows.slice(0, 50)
      const groups = new Map()
      for (const row of page) {
        const key = `${row.room_kind}:${row.room_id}`
        if (!groups.has(key)) groups.set(key, [])
        groups.get(key).push(row)
      }
      const hydrated = new Map()
      const roomGroups = [...groups]
      for (let offset = 0; offset < roomGroups.length; offset += 6) {
        await Promise.all(roomGroups.slice(offset, offset + 6).map(async ([key, entries]) => {
          const [roomKind, targetRoomId] = key.split(':')
          const ctx = await roomContext(user, { kind: roomKind, roomId: targetRoomId })
          if (ctx.error) return
          const messages = lookupMessages
            ? await lookupMessages(pool, roomKind, targetRoomId, entries.map((entry) => entry.message_id), identity)
            : new Map(await Promise.all(entries.map(async (entry) =>
              [entry.message_id, await lookupMessage(pool, roomKind, targetRoomId, entry.message_id, identity)])))
          for (const entry of entries) {
            const message = messages.get(entry.message_id) || null
            if (type && (!message || String(message.content_type || '').toLowerCase() !== type &&
              !(type === 'document' && message.content_type === 'file'))) continue
            if (search && !message && !entry.label.toLowerCase().includes(search)) continue
            hydrated.set(`${entry.room_kind}:${entry.room_id}:${entry.message_id}`, {
              ...entry, room_name: ctx.roomName, message, unavailable: !message,
            })
          }
        }))
      }
      const last = page.at(-1)
      res.json({
        saved: page.map((entry) => hydrated.get(`${entry.room_kind}:${entry.room_id}:${entry.message_id}`)).filter(Boolean),
        nextCursor: rows.length > 50 && last
          ? Buffer.from(JSON.stringify({ savedAt: last.saved_at, kind: last.room_kind,
            roomId: last.room_id, messageId: last.message_id })).toString('base64url')
          : null,
      })
    } catch (error) { next(error) }
  })

  app.put('/v1/data/messages/markers/:kind/:roomId/:messageId/pin', authRequired, async (req, res, next) => {
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      if (!ctx.canPin) return res.status(403).json({ error: 'PIN_REQUIRES_ADMIN' })
      if (!await visibleMessage(ctx, res)) return
      const client = await pool.connect()
      try {
        await client.query('BEGIN')
        await client.query(`SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, [`pin:${ctx.kind}:${ctx.roomId}`])
        const existing = await client.query(`SELECT 1 FROM fallback_message_pins WHERE room_kind=$1 AND room_id=$2 AND message_id=$3`,
          [ctx.kind, ctx.roomId, ctx.messageId])
        if (!existing.rowCount) {
          const count = await client.query(`SELECT count(*)::int AS total FROM fallback_message_pins WHERE room_kind=$1 AND room_id=$2`,
            [ctx.kind, ctx.roomId])
          if (count.rows[0].total >= 10) {
            await client.query('ROLLBACK')
            return res.status(409).json({ error: 'PIN_LIMIT_REACHED' })
          }
          await client.query(`INSERT INTO fallback_message_pins(room_kind,room_id,message_id,pinned_by,pinned_by_name)
            VALUES($1,$2,$3,$4,$5)`, [ctx.kind, ctx.roomId, ctx.messageId, ctx.identity.displayUserId, ctx.actorName])
          await client.query(`INSERT INTO fallback_message_pin_events(room_kind,room_id,message_id,actor_id,action)
            VALUES($1,$2,$3,$4,'pin')`, [ctx.kind, ctx.roomId, ctx.messageId, ctx.identity.displayUserId])
        }
        await client.query('COMMIT')
      } catch (error) { await client.query('ROLLBACK'); throw error } finally { client.release() }
      publishMarkerChange(ctx, true)
      res.json(await list(ctx))
    } catch (error) { next(error) }
  })

  app.delete('/v1/data/messages/markers/:kind/:roomId/:messageId/pin', authRequired, async (req, res, next) => {
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      if (!ctx.canPin) return res.status(403).json({ error: 'PIN_REQUIRES_ADMIN' })
      const client = await pool.connect()
      try {
        await client.query('BEGIN')
        const deleted = await client.query(`DELETE FROM fallback_message_pins WHERE room_kind=$1 AND room_id=$2 AND message_id=$3 RETURNING message_id`,
          [ctx.kind, ctx.roomId, ctx.messageId])
        if (deleted.rowCount) await client.query(`INSERT INTO fallback_message_pin_events(room_kind,room_id,message_id,actor_id,action)
          VALUES($1,$2,$3,$4,'unpin')`, [ctx.kind, ctx.roomId, ctx.messageId, ctx.identity.displayUserId])
        await client.query('COMMIT')
      } catch (error) { await client.query('ROLLBACK'); throw error } finally { client.release() }
      publishMarkerChange(ctx, true)
      res.json(await list(ctx))
    } catch (error) { next(error) }
  })

  app.put('/v1/data/messages/markers/:kind/:roomId/:messageId/saved', authRequired, async (req, res, next) => {
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      if (!await visibleMessage(ctx, res)) return
      const label = String(req.body?.label || '').trim().slice(0, 64)
      await pool.query(`INSERT INTO fallback_saved_messages(user_id,room_kind,room_id,message_id,label)
        VALUES($1,$2,$3,$4,$5) ON CONFLICT (user_id,room_kind,room_id,message_id)
      DO UPDATE SET label=EXCLUDED.label`, [ctx.identity.displayUserId, ctx.kind, ctx.roomId, ctx.messageId, label])
      publishMarkerChange(ctx, false)
      res.json(await list(ctx))
    } catch (error) { next(error) }
  })

  app.delete('/v1/data/messages/markers/:kind/:roomId/:messageId/saved', authRequired, async (req, res, next) => {
    try {
      const ctx = await context(req, res)
      if (!ctx) return
      await pool.query(`DELETE FROM fallback_saved_messages WHERE user_id=$1 AND room_kind=$2 AND room_id=$3 AND message_id=$4`,
        [ctx.identity.displayUserId, ctx.kind, ctx.roomId, ctx.messageId])
      publishMarkerChange(ctx, false)
      res.json(await list(ctx))
    } catch (error) { next(error) }
  })
}

module.exports = { ensureMessageMarkerTables, registerMessageMarkerRoutes, markerParams }
