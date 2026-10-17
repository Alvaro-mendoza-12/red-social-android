const { randomUUID } = require('node:crypto')

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
  const columns = direct ? directColumns : collectionColumns
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
  return { message: existing.rows[0], inserted: false }
}

module.exports = { insertMessageOnce }
