const { publishRealtimeEvent } = require('./realtime-publish')
const { safeErrorLogDetails } = require('./request-observability')

// Invalidation only: clients re-fetch authorized state for their own account.
async function publishPollUpdate(pool, poll, publish = publishRealtimeEvent) {
  const kind = String(poll.scope_type || '').trim().toLowerCase()
  const room = String(poll.scope_id || '').trim().toLowerCase()
  const pollId = String(poll.id || '').trim().toLowerCase()
  if (!room || !pollId) return false
  try {
    let result
    if (kind === 'group' || kind === 'community') {
      result = await pool.query(
        `SELECT DISTINCT lower(user_id) AS user_id FROM fallback_collection_members
         WHERE room_kind = $1 AND lower(room_id) = $2`, [kind, room],
      )
    } else if (['chat', 'direct', 'message'].includes(kind)) {
      result = await pool.query(
        `SELECT DISTINCT lower(participant) AS user_id
         FROM fallback_chats, unnest(participants) AS participant WHERE lower(id::text) = $1
         UNION SELECT DISTINCT lower(user_id) AS user_id FROM fallback_chat_participants
         WHERE lower(chat_id::text) = $1`, [room],
      )
    } else return false
    const recipients = [...new Set(result.rows.map(row => String(row.user_id || '').trim()).filter(Boolean))]
    const payload = { event: 'poll_updated', poll_id: pollId, scope_type: kind, scope_id: room }
    let allPublished = true
    // Bound fan-out so a large group cannot exhaust gateway connections.
    for (let index = 0; index < recipients.length; index += 16) {
      const results = await Promise.all(recipients.slice(index, index + 16)
        .map(user => publish(`user:${user}`, payload)))
      allPublished = allPublished && results.every(Boolean)
    }
    return allPublished
  } catch (error) {
    console.warn('[poll-update] invalidation unavailable', safeErrorLogDetails(error))
    return false
  }
}

module.exports = { publishPollUpdate }
