const { publishRealtimeEvent } = require('./realtime-publish')

async function publishLiveCollaborationEvent(liveId, payload = {}) {
  const envelope = { ...payload, event: 'live_collaboration', liveId }
  const invitation = payload.invitation
  if (!invitation) {
    return publishRealtimeEvent(`live-collaboration:${liveId}`, envelope)
  }

  // Invitations belong only to the two accounts involved. A room-wide event
  // carries an invalidation, never the private invitation or its request nonce.
  const recipients = [...new Set([invitation.host_user_id, invitation.invitee_user_id]
    .map(id => String(id || '').trim().toLowerCase()).filter(Boolean))]
  if (String(invitation.live_id || '').toLowerCase() !== String(liveId).toLowerCase() ||
      !invitation.id || recipients.length === 0) return false
  const deliveries = await Promise.all([
    publishRealtimeEvent(`live-collaboration:${liveId}`, {
      event: 'live_collaboration', liveId, type: payload.type,
    }),
    ...recipients.map(id => publishRealtimeEvent(`user:${id}`, envelope)),
  ])
  return deliveries.every(Boolean)
}

module.exports = { publishLiveCollaborationEvent }
