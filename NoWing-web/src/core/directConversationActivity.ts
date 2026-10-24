import type { GenericRow } from './utils'

export function directConversationHasActivity(chat: GenericRow, hasStoredMessage = false): boolean {
  return Boolean(hasStoredMessage || String(chat.last_message_id || '').trim() || String(chat.last_message || '').trim())
}
