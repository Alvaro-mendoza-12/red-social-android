const { randomUUID } = require('node:crypto')
const { isSharedMessageType } = require('./message-shared-content')

const directColumns = ['id', 'chat_id', 'sender_id', 'content', 'content_type', 'media_url',
  'thumbnail_url', 'media_size', 'reply_to_id', 'poll_id', 'is_forwarded',
  'forwarded_from_message_id', 'forwarded_from_user_id']
const collectionColumns = ['id', 'room_kind', 'room_id', 'channel_id', 'sender_id', 'content',
  'content_type', 'media_url', 'media_size', 'reply_to_id', 'poll_id', 'is_forwarded',
  'forwarded_from_message_id', 'forwarded_from_user_id']
const stateColumns = ['status', 'delivered_at', 'read_at', 'reactions', 'deleted_by',
  'deleted_for_all', 'is_edited', 'created_at', 'updated_at']

const normalize = (value) => String(value || '').trim().toLowerCase()

function conflict() {
  const error = new Error('MESSAGE_ID_CONFLICT')
  error.status = 409
  return error
}

function sameSharedContent(previous, reference, content) {
  return isSharedMessageType(previous.content_type) && previous.shared_content?.kind === reference?.kind &&
    previous.shared_content?.id === reference?.id && previous.content === content
}

// Called only after the destination has been authorized. A missing original
// cannot undo a previously accepted send, but a read of its preview still fails.
async function findSharedMessageReplay(db, { kind, scopeId, channelId, actorIds, id, reference, content }) {
  if (!['dm', 'group', 'community'].includes(kind)) return null
  const direct = kind === 'dm'
  const { rows } = await db.query(direct
    ? 'SELECT * FROM fallback_messages WHERE id = $1::uuid AND lower(chat_id) = $2 AND lower(sender_id) = ANY($3::text[])'
    : `SELECT * FROM fallback_room_messages WHERE id = $1::uuid AND lower(room_id) = $2 AND lower(sender_id) = ANY($3::text[])
       AND room_kind = $4 AND channel_id IS NOT DISTINCT FROM $5::text`,
  direct ? [id, scopeId, [...actorIds]] : [id, scopeId, [...actorIds], kind, channelId || null])
  if (!rows[0]) return null
  if (!sameSharedContent(rows[0], reference, content)) throw conflict()
  return rows[0]
}

/** Call only after the route has authorized the conversation and media references. */
async function insertMessageOnce(pool, { kind, scopeId, channelId = null, actorIds, message }) {
  if (!['dm', 'group', 'community'].includes(kind)) throw new Error('INVALID_MESSAGE_SCOPE')
  if (message.content_type === 'birthday_card') message = { ...message, content: require('./birthday-card').birthdayCardContent(message.content, { kind, mediaUrl: message.media_url, pollId: message.poll_id }) }
  const senderId = normalize(message.sender_id)
  const scope = normalize(scopeId)
  const owners = [...new Set(Array.from(actorIds || [], normalize).filter(Boolean))]
  if (!scope || !senderId || !owners.includes(senderId)) throw conflict()

  const direct = kind === 'dm'
  const table = direct ? 'fallback_messages' : 'fallback_room_messages'
  const shared = isSharedMessageType(message.content_type)
  const columns = [...(direct ? directColumns : collectionColumns), ...(shared ? ['shared_content'] : [])]
  const row = {
    ...message,
    id: message.id || randomUUID(),
    sender_id: senderId,
    chat_id: scope,
    room_kind: kind,
    room_id: scope,
    channel_id: kind === 'community' ? normalize(channelId) || null : null,
  }
  const returning = [...columns, ...stateColumns].map((column) =>
    column === 'id' ? 'id::text' : column).join(', ') + (direct ? '' :
    ", CASE WHEN room_kind = 'group' THEN room_id ELSE NULL END AS group_id" +
    ", CASE WHEN room_kind = 'community' THEN room_id ELSE NULL END AS community_id")
  const parameters = columns.map((column) => row[column] ?? null)
  const inserted = await pool.query(
    `INSERT INTO ${table} (${columns.join(', ')})
     VALUES (${columns.map((_, index) => `$${index + 1}`).join(', ')})
     ON CONFLICT (id) DO NOTHING RETURNING ${returning}`,
    parameters,
  )
  if (inserted.rows[0]) return { message: inserted.rows[0], inserted: true }

  // A separate command sees the committed winner of a concurrent INSERT under
  // READ COMMITTED. Never UPDATE a duplicate: edits, receipts and deletions win.
  const existing = await pool.query(
    `SELECT ${returning} FROM ${table}
     WHERE id = $1::uuid AND lower(${direct ? 'chat_id' : 'room_id'}) = $2
       AND lower(sender_id) = ANY($3::text[])${direct ? '' :
      ' AND room_kind = $4 AND channel_id IS NOT DISTINCT FROM $5::text'}
     LIMIT 1`,
    direct ? [row.id, scope, owners] : [row.id, scope, owners, kind, row.channel_id],
  )
  if (!existing.rows[0]) throw conflict()
  const previous = existing.rows[0]
  if (shared ? !sameSharedContent(previous, row.shared_content, row.content) : isSharedMessageType(previous.content_type)) throw conflict()
  return { message: existing.rows[0], inserted: false }
}

module.exports = { insertMessageOnce, findSharedMessageReplay }
