const { loadCollectionMembershipState, collectionHistoryAllows } = require('./collection-membership-policy')
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

function selectedMessageIds(body) {
  if (!Array.isArray(body?.messageIds) || body.messageIds.length > 200 ||
      body.messageIds.some((id) => typeof id !== 'string' || !UUID.test(id))) {
    throw Object.assign(new Error('INVALID_MESSAGE_IDS'), { status: 400, code: 'INVALID_MESSAGE_IDS' })
  }
  return [...new Set(body.messageIds.map((id) => id.toLowerCase()))]
}

async function markSelectedMessagesRead(pool, chatId, identity, ids) {
  if (!ids.length) return []
  const { rows } = await pool.query(
    `UPDATE fallback_messages
        SET status = 'read', delivered_at = COALESCE(delivered_at, NOW()),
            read_at = COALESCE(read_at, NOW()), updated_at = NOW()
      WHERE lower(chat_id) = $1
        AND NOT (lower(sender_id) = ANY($2::text[]))
        AND id = ANY($3::uuid[])
        AND deleted_for_all = FALSE
      RETURNING id::text`,
    [chatId, Array.from(identity.ids), ids],
  )
  // A partial viewport must never advance the conversation-wide read cursor.
  return rows.map((row) => row.id)
}

function createConversationActivityAuthorizer({ privateChatAccessContext, loadCollectionRoomAccess }) {
  return async function mayPublishActivity(pool, user, identity, kind, id) {
    if (kind === 'chat') return Boolean((await privateChatAccessContext(pool, id, identity)).hasAccess)
    if (!['group', 'community'].includes(kind)) return false
    const access = await loadCollectionRoomAccess(pool, user, kind, id)
    if (!access) return false
    const owner = String(access.room.creator_id || access.room.owner_id || access.room.user_id || '').toLowerCase()
    return identity.ids.has(owner) || access.roomMembers.some((member) => {
      const memberId = String(member.user_id || member.profile_id || member.member_id || '').toLowerCase()
      const status = String(member.status || member.member_status || '').toLowerCase()
      return identity.ids.has(memberId) && !['left', 'removed', 'deleted', 'banned'].includes(status)
    })
  }
}

function registerVisibleMessageReadRoute(app, {
  pool, authRequired, getCurrentUser, userIdentity, privateChatAccessContext,
  publishReadReceipts = async () => {},
}) {
  app.post('/v1/data/messages/chats/:chatId/read-visible', authRequired, async (req, res, next) => {
    try {
      let ids
      try { ids = selectedMessageIds(req.body) } catch {
        return res.status(400).json({ error: 'INVALID_MESSAGE_IDS' })
      }
      const chatId = String(req.params.chatId || '').toLowerCase()
      if (!UUID.test(chatId)) return res.status(400).json({ error: 'CHAT_ID_REQUIRED' })
      const user = await getCurrentUser(pool, req)
      if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
      const identity = userIdentity(user)
      const { chat, participantRows, hasAccess } = await privateChatAccessContext(pool, chatId, identity)
      if (!chat || !hasAccess) return res.status(403).json({ error: 'CHAT_ACCESS_DENIED' })
      const messageIds = await markSelectedMessagesRead(pool, chatId, identity, ids)
      // The database result is authoritative; publishing is best effort and must
      // not make acknowledging a visible message wait for the realtime gateway.
      if (messageIds.length) {
        void Promise.resolve().then(() => publishReadReceipts({
          chatId, messageIds, chat, participantRows, identity,
        })).catch(() => console.warn('[message-read-receipts] publication failed'))
      }
      return res.json({ ok: true, chatId, messageIds, lastReadAt: null })
    } catch (error) {
      next(error)
    }
  })
}

async function markSelectedCollectionMessagesRead(pool, kind, roomId, identity, ids) {
  if (!ids.length) return []
  const state = await loadCollectionMembershipState(pool, kind, roomId, identity.ids)
  const candidates = await pool.query('SELECT id::text,created_at FROM fallback_room_messages WHERE room_kind=$1 AND lower(room_id)=$2 AND id=ANY($3::uuid[])',[kind,roomId,ids])
  ids = candidates.rows.filter(row => collectionHistoryAllows(state.history,row)).map(row=>row.id)
  if (!ids.length) return []
  const { rows } = await pool.query(
    `INSERT INTO fallback_collection_message_receipts(room_kind, room_id, message_id, user_id, delivered_at, read_at, updated_at)
     SELECT room_kind, room_id, id, $3, NOW(), NOW(), NOW()
     FROM fallback_room_messages
     WHERE room_kind = $1 AND lower(room_id) = $2 AND id = ANY($4::uuid[])
       AND NOT (lower(sender_id) = ANY($5::text[])) AND deleted_for_all = FALSE
     ON CONFLICT (room_kind, message_id, user_id) DO UPDATE SET
       delivered_at = COALESCE(fallback_collection_message_receipts.delivered_at, EXCLUDED.delivered_at),
       read_at = COALESCE(fallback_collection_message_receipts.read_at, EXCLUDED.read_at), updated_at = NOW()
     RETURNING message_id::text AS id`,
    [kind, roomId, identity.displayUserId, ids, Array.from(identity.ids)],
  )
  return rows.map((row) => row.id)
}

function registerVisibleCollectionReadRoutes(app, dependencies) {
  const { pool, authRequired, getCurrentUser, userIdentity } = dependencies
  const authorize = createConversationActivityAuthorizer(dependencies)
  for (const [collection, kind] of [['groups', 'group'], ['communities', 'community']]) {
    app.post(`/v1/data/messages/${collection}/:roomId/read-visible`, authRequired, async (req, res, next) => {
      try {
        let ids
        try { ids = selectedMessageIds(req.body) } catch { return res.status(400).json({ error: 'INVALID_MESSAGE_IDS' }) }
        const roomId = String(req.params.roomId || '').toLowerCase()
        if (!UUID.test(roomId)) return res.status(400).json({ error: 'ROOM_ID_REQUIRED' })
        const user = await getCurrentUser(pool, req)
        if (!user) return res.status(404).json({ error: 'USER_NOT_FOUND' })
        const identity = userIdentity(user)
        if (!await authorize(pool, user, identity, kind, roomId)) return res.status(403).json({ error: 'ROOM_ACCESS_DENIED' })
        const messageIds = await markSelectedCollectionMessagesRead(pool, kind, roomId, identity, ids)
        return res.json({ ok: true, roomId, messageIds, lastReadAt: null })
      } catch (error) { next(error) }
    })
  }
}

module.exports = { createConversationActivityAuthorizer, markSelectedMessagesRead, registerVisibleMessageReadRoute, selectedMessageIds, markSelectedCollectionMessagesRead, registerVisibleCollectionReadRoutes }
