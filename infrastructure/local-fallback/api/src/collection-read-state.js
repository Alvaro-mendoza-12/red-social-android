const clean = (value) => String(value || '').trim().toLowerCase()
const millis = (value) => Date.parse(value || '') || 0

async function loadCollectionReceipts(pool, kind, identity, roomIds) {
  if (!roomIds.length) return new Map()
  const { rows } = await pool.query(
    `SELECT room_id, message_id, delivered_at, read_at
     FROM fallback_collection_message_receipts
     WHERE room_kind = $1 AND lower(user_id) = ANY($2::text[]) AND lower(room_id) = ANY($3::text[])`,
    [kind, Array.from(identity.ids), roomIds],
  )
  const result = new Map()
  for (const row of rows) {
    const key = `${clean(row.room_id)}/${clean(row.message_id)}`
    const previous = result.get(key)
    if (!previous || (!previous.read_at && row.read_at)) result.set(key, row)
  }
  return result
}

function wasCollectionMessageRead(message, lastReadAt, receipt) {
  if (receipt?.read_at) return true
  const createdAt = millis(message.created_at)
  return Boolean(createdAt && millis(lastReadAt) >= createdAt)
}

function isCollectionMessageUnread(message, identity, lastReadAt, receipt) {
  if (identity.ids.has(clean(message.sender_id))) return false
  if (message.deleted_for_all || ['deleted', 'recalled'].includes(clean(message.status))) return false
  if ((message.deleted_by || []).map(clean).some((id) => identity.ids.has(id))) return false
  return !wasCollectionMessageRead(message, lastReadAt, receipt)
}

function projectCollectionReceipt(message, identity, lastReadAt, receipt) {
  // Global delivery/read fields describe recipients collectively, not this viewer.
  if (identity.ids.has(clean(message.sender_id)) || message.deleted_for_all || clean(message.status) === 'deleted') return message
  const read = wasCollectionMessageRead(message, lastReadAt, receipt)
  return { ...message, status: read ? 'read' : (receipt?.delivered_at ? 'delivered' : 'sent'),
    read_at: read ? (receipt?.read_at || lastReadAt) : null,
    delivered_at: receipt?.delivered_at || null }
}

module.exports = { loadCollectionReceipts, isCollectionMessageUnread, projectCollectionReceipt }
