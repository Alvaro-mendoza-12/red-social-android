const { publishRealtimeEvent } = require('./realtime-publish')

async function publishWebSessionsRevoked(userId, sessions, publish = publishRealtimeEvent) {
  const owner = String(userId || '').trim().toLowerCase()
  const sessionIds = [...new Set((sessions || []).map(session => String(session.id || '').trim()).filter(Boolean))]
  if (!owner || !sessionIds.length) return false
  return publish(`user:${owner}`, { event: 'web_sessions_revoked', sessionIds })
}

module.exports = { publishWebSessionsRevoked }
