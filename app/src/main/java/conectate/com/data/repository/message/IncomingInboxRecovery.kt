package conectate.com.data.repository.message

import conectate.com.data.local.AppDatabase
import conectate.com.data.local.entity.ChatEntity
import conectate.com.data.model.Chat
import conectate.com.data.model.Message

/** A message row alone cannot appear in the inbox: verified membership is needed too. */
internal suspend fun recoverIncomingInbox(
    database: AppDatabase,
    message: Message,
    account: String,
    requireCurrent: () -> Unit,
    loadChat: suspend (String) -> Chat?,
    commit: suspend (suspend () -> Unit) -> Unit,
) {
    requireCurrent()
    val id = message.chat_id.trim().lowercase()
    val cached = database.chatDao().getChatById(id)
    if (cached?.toDomain()?.isAccessibleTo(account) == true && !cached.is_deleted) return
    if (message.deleted_for_all) return
    // Never infer participants from the sender of an alert. Fetch the authorized chat.
    val verified = cached?.toDomain()?.takeIf { it.isAccessibleTo(account) } ?: loadChat(id) ?: return
    requireCurrent()
    if (verified.id.trim().lowercase() != id || !verified.isAccessibleTo(account)) return
    commit {
        withRealtimeAccountTransaction(database, requireCurrent) {
            val current = database.chatDao().getChatById(id)
            val clearedAt = current?.cleared_at ?: verified.cleared_at
            if (!isAfterInboxClear(message.created_at, clearedAt)) return@withRealtimeAccountTransaction
            if (current?.toDomain()?.isAccessibleTo(account) != true) {
                val other = verified.participants.firstOrNull { !it.trim().equals(account, true) }
                val profile = other?.let { database.userDao().getUser(it.trim().lowercase()) }
                database.chatDao().insertChat(ChatEntity.fromDomain(
                    verified.copy(id = id), profile?.full_name ?: profile?.username ?: verified.group_name,
                    profile?.avatar_url ?: verified.group_image, 0, verified.last_message,
                    verified.last_message_at, otherUserId = other, clearedAt = clearedAt,
                ))
            }
            database.chatDao().updateLastMessage(id, message.content, message.created_at.orEmpty(), message.id, message.sender_id)
            database.chatDao().restoreChatVisibility(id)
            val unread = database.messageDao().countUnreadInboxIncoming(id, account, clearedAt)
            database.chatDao().ensureUnreadCount(id, unread)
        }
    }
}
