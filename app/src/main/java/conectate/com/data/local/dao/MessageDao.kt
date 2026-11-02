package conectate.com.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import conectate.com.data.local.entity.MessageEntity
import conectate.com.data.local.entity.withPersistedReceipts
import conectate.com.data.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessageRaw(message: MessageEntity)

    @Transaction
    suspend fun insertMessage(message: MessageEntity) {
        insertMessageRaw(message.withPersistedReceipts(getMessageById(message.id)))
    }

    @Transaction
    suspend fun insertMessageOwned(message: MessageEntity, requireCurrent: () -> Unit) {
        persistMessageOwned(message, requireCurrent)
    }

    @Transaction
    suspend fun persistMessageOwned(message: MessageEntity, requireCurrent: () -> Unit): MessageEntity {
        requireCurrent()
        val merged = message.withPersistedReceipts(getMessageById(message.id))
        insertMessageRaw(merged)
        requireCurrent()
        return merged
    }

    @Transaction
    suspend fun markPendingSendFailedOwned(messageId: String, requireCurrent: () -> Unit) {
        requireCurrent()
        markPendingSendFailed(messageId)
        requireCurrent()
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessagesRaw(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE id IN (:messageIds)")
    suspend fun getMessagesByIds(messageIds: List<String>): List<MessageEntity>

    @Transaction
    suspend fun insertMessages(messages: List<MessageEntity>) {
        if (messages.isEmpty()) return
        val latest = messages.map { it.id }.distinct().chunked(800)
            .flatMap { getMessagesByIds(it) }.associateBy { it.id }.toMutableMap()
        val merged = messages.map { incoming -> incoming.withPersistedReceipts(latest[incoming.id]).also { latest[it.id] = it } }
        insertMessagesRaw(merged)
    }

    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY created_at ASC")
    fun getMessagesFlow(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY created_at ASC")
    suspend fun getMessagesSync(chatId: String): List<MessageEntity>

    suspend fun getMessagesForChat(chatId: String): List<Message> =
        getMessagesSync(chatId).map { it.toDomain() }

    fun observeMessagesForChat(chatId: String): Flow<List<Message>> =
        getMessagesFlow(chatId).map { messages -> messages.map { it.toDomain() } }

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE id IN (:messageIds)")
    fun getMessagesByIdsFlow(messageIds: List<String>): Flow<List<MessageEntity>>

    /** One indexed latest-row lookup per chat; no full-history hydration for the inbox. */
    suspend fun getLatestInboxCandidates(chatIds: List<String>): List<MessageEntity> =
        chatIds.distinct().chunked(100).flatMap { readInboxCandidates(inboxCandidateQuery(it)) }

    @RawQuery
    suspend fun readInboxCandidates(query: androidx.sqlite.db.SupportSQLiteQuery): List<MessageEntity>

    @Query("""
        SELECT * FROM messages WHERE chat_id = :chatId
          AND (created_at < :beforeTime OR (created_at = :beforeTime AND id < :beforeId))
        ORDER BY created_at DESC, id DESC LIMIT :limit
    """)
    suspend fun getOlderInboxCandidates(chatId: String, beforeTime: String, beforeId: String, limit: Int): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    suspend fun deleteMessageById(messageId: String) {
        deleteMessage(messageId)
    }

    suspend fun insertMessage(message: Message) {
        insertMessage(MessageEntity.fromDomain(message))
    }

    suspend fun updateMessage(message: Message) {
        insertMessage(MessageEntity.fromDomain(message))
    }
    
    // OFFLINE SYNC QUERIES
    @Query("SELECT * FROM messages WHERE is_synced = 0 AND pending_action = 'send' AND NOT EXISTS (SELECT 1 FROM message_deletions d WHERE d.message_id = messages.id AND d.actor_id = LOWER(TRIM(messages.sender_id)) AND d.for_everyone = 1 AND d.state != 'failed') ORDER BY created_at ASC, id ASC")
    suspend fun getPendingSendMessages(): List<MessageEntity>

    @Query("UPDATE messages SET status = 'failed', pending_action = NULL WHERE id = :messageId AND is_synced = 0 AND pending_action = 'send'")
    suspend fun markPendingSendFailed(messageId: String)

    @Query("UPDATE messages SET status = 'failed', pending_action = NULL WHERE id = :messageId AND is_synced = 0 AND pending_action = 'send'")
    suspend fun cancelPendingSendForDeletion(messageId: String)
    
    @Query("SELECT * FROM messages WHERE pending_action = 'delete'")
    suspend fun getPendingDeleteMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE pending_action = 'edit'")
    suspend fun getPendingEditMessages(): List<MessageEntity>

    @Query("""
        UPDATE messages SET status = CASE
            WHEN LOWER(status) = 'read' OR NULLIF(TRIM(read_at), '') IS NOT NULL THEN 'read'
            WHEN LOWER(status) IN ('delivered','received') OR NULLIF(TRIM(delivered_at), '') IS NOT NULL THEN 'delivered'
            ELSE :status END, is_synced = :isSynced, pending_action = :pendingAction
        WHERE id = :messageId AND NOT (:isSynced = 0 AND COALESCE(:pendingAction, '') = 'send' AND is_synced = 1
            AND (LOWER(status) IN ('sent','delivered','received','read') OR NULLIF(TRIM(read_at), '') IS NOT NULL OR NULLIF(TRIM(delivered_at), '') IS NOT NULL))
    """)
    suspend fun updateSyncStatus(messageId: String, status: String, isSynced: Boolean, pendingAction: String?)

    @Query("UPDATE messages SET media_url = :mediaUrl WHERE id = :messageId")
    suspend fun updateMessageMediaUrl(messageId: String, mediaUrl: String?)

    @Query("DELETE FROM messages WHERE chat_id = :chatId")
    suspend fun deleteMessagesByChatId(chatId: String)

    @Query("SELECT * FROM messages WHERE chat_id = :chatId AND sender_id = :senderId AND content = :content LIMIT 1")
    suspend fun getSimilarMessage(chatId: String, senderId: String, content: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE chat_id = :chatId AND content != '' ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestMessageSync(chatId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE chat_id = :chatId AND content = :content ORDER BY created_at DESC LIMIT 5")
    suspend fun getRecentSimilarMessagesAgnostic(chatId: String, content: String): List<MessageEntity>

    @Query("UPDATE messages SET status = 'read', read_at = CASE WHEN read_at IS NULL THEN :now ELSE read_at END, delivered_at = CASE WHEN delivered_at IS NULL THEN :now ELSE delivered_at END WHERE chat_id = :chatId AND sender_id != :currentUserId AND LOWER(COALESCE(status, '')) IN ('sent','delivered','received')")
    suspend fun markAllAsRead(chatId: String, currentUserId: String, now: String)

    @Query("UPDATE messages SET status = 'delivered', delivered_at = CASE WHEN delivered_at IS NULL THEN :now ELSE delivered_at END WHERE chat_id = :chatId AND sender_id != :currentUserId AND LOWER(COALESCE(status, '')) = 'sent'")
    suspend fun markAllAsDelivered(chatId: String, currentUserId: String, now: String)

    @Query("SELECT COUNT(*) FROM messages WHERE chat_id = :chatId AND sender_id != :currentUserId AND LOWER(COALESCE(status, '')) IN ('sent','delivered','received')")
    suspend fun countUnreadIncoming(chatId: String, currentUserId: String): Int

    @Query("UPDATE messages SET status='read',read_at=COALESCE(read_at,:through) WHERE chat_id=:chatId AND LOWER(sender_id)!=LOWER(:account) AND julianday(created_at)<=julianday(:through) AND LOWER(COALESCE(status,'')) IN ('sent','delivered','received')")
    suspend fun markReadThrough(chatId: String, account: String, through: String)

    @Query(
        "UPDATE messages " +
            "SET status = 'read', " +
            "read_at = CASE WHEN read_at IS NULL THEN :now ELSE read_at END, " +
            "delivered_at = CASE WHEN delivered_at IS NULL THEN :now ELSE delivered_at END " +
            "WHERE chat_id = :chatId " +
            "AND sender_id != :currentUserId " +
            "AND id IN (:messageIds) " +
            "AND LOWER(COALESCE(status, '')) IN ('sent','delivered','received')"
    )
    suspend fun markMessagesAsReadByIds(
        chatId: String,
        currentUserId: String,
        now: String,
        messageIds: List<String>
    )

    @Query(
        "UPDATE messages " +
            "SET status = 'delivered', " +
            "delivered_at = CASE WHEN delivered_at IS NULL THEN :now ELSE delivered_at END " +
            "WHERE id = :messageId " +
            "AND chat_id = :chatId " +
            "AND sender_id != :currentUserId " +
            "AND LOWER(COALESCE(status, '')) = 'sent'"
    )
    suspend fun markMessageAsDelivered(
        messageId: String,
        chatId: String,
        currentUserId: String,
        now: String
    )

    @Query("UPDATE messages SET deleted_for_all = 1, content = '', content_type = 'text', media_url = NULL, thumbnail_url = NULL, media_size = 0, poll_id = NULL, reply_to_id = NULL, reply_to_json = NULL, reactions_json = '{}' WHERE id = :messageId AND chat_id = :conversationId")
    suspend fun redactDeletedMessage(conversationId: String, messageId: String)
}
