'use strict'

// A room's creation/update timestamp is not evidence of a sent message.
function directConversationHasActivity(chat, hasStoredMessage) {
  return Boolean(hasStoredMessage || String(chat?.last_message_id || '').trim() || String(chat?.last_message || '').trim())
}

module.exports = { directConversationHasActivity }
