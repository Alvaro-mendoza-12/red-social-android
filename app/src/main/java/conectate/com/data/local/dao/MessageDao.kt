package conectate.com.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import conectate.com.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY created_at ASC")
    fun getMessagesFlow(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)
    
    // OFFLINE SYNC QUERIES
    @Query("SELECT * FROM messages WHERE is_synced = 0 AND pending_action = 'send'")
    suspend fun getPendingSendMessages(): List<MessageEntity>
    
    @Query("SELECT * FROM messages WHERE pending_action = 'delete'")
    suspend fun getPendingDeleteMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE pending_action = 'edit'")
    suspend fun getPendingEditMessages(): List<MessageEntity>

    @Query("UPDATE messages SET status = :status, is_synced = :isSynced, pending_action = :pendingAction WHERE id = :messageId")
    suspend fun updateSyncStatus(messageId: String, status: String, isSynced: Boolean, pendingAction: String?)

    @Query("UPDATE messages SET media_url = :mediaUrl WHERE id = :messageId")
    suspend fun updateMessageMediaUrl(messageId: String, mediaUrl: String?)

    @Query("DELETE FROM messages WHERE chat_id = :chatId")
    suspend fun deleteMessagesByChatId(chatId: String)

    @Query("DELETE FROM messages")
    suspend fun nukeAllMessages()

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
}
