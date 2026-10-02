function notificationRealtimeEnvelope(notification, extraData = {}) {
  const type = notification.type
  const isCallInvite = type === 'call_invite'
  const isCallResponse = type === 'call_response' || type === 'call_cancel'
  const envelope = {
    event: isCallInvite ? 'call_invite' : isCallResponse ? 'call_response' : 'notification',
    notification,
    extraData,
  }
  if (isCallInvite) {
    Object.assign(envelope, {
      callId: extraData.call_id || extraData.callId || notification.target_id,
      title: extraData.call_title || extraData.callTitle || notification.content,
      callerUserId: notification.actor_id,
      callerName: notification.actor_name,
      callerAvatarUrl: notification.actor_avatar_url,
      contextType: extraData.call_context_type || extraData.callContextType || 'dm',
      contextId: extraData.call_context_id || extraData.callContextId || '',
      isVideo: Boolean(extraData.call_is_video ?? extraData.callIsVideo),
    })
  } else if (isCallResponse) {
    Object.assign(envelope, {
      callId: extraData.call_id || extraData.callId || notification.target_id,
      action: extraData.call_action || extraData.callAction || (type === 'call_cancel' ? 'cancel' : ''),
      fromUserId: extraData.from_user_id || extraData.fromUserId || notification.actor_id,
    })
  }
  return envelope
}

async function publishEphemeralCallEvent(notification, extraData = {}, publish) {
  if (!notification?.id || !notification.recipient_id ||
      !['call_invite', 'call_cancel', 'call_response'].includes(notification.type)) return false
  const send = publish || require('./realtime-publish').publishRealtimeEvent
  return send(`user:${notification.recipient_id}`, notificationRealtimeEnvelope(notification, extraData))
}

module.exports = { notificationRealtimeEnvelope, publishEphemeralCallEvent }
