package conectate.com.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import conectate.com.data.local.entity.ChatEntity
import conectate.com.data.local.entity.ChatWithUserProfile
import conectate.com.data.model.ChatWithLastMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChats(chats: List<ChatEntity>)

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getChatById(chatId: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE id = :chatId")
    fun getChatByIdSync(chatId: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE id = :chatId")
    fun getChatByIdFlow(chatId: String): Flow<ChatEntity?>

    @androidx.room.Transaction
    @Query("SELECT * FROM chats WHERE is_deleted = 0 ORDER BY is_archived ASC, is_pinned DESC, is_favorite DESC, last_message_at DESC")
    fun getAllChatsFlow(): Flow<List<ChatWithUserProfile>>

    @androidx.room.Transaction
    @Query("SELECT * FROM chats WHERE is_deleted = 0 ORDER BY is_archived ASC, is_pinned DESC, is_favorite DESC, last_message_at DESC")
    suspend fun getAllChatsWithUsers(): List<ChatWithUserProfile>

    suspend fun getAllChatsWithLastMessage(): List<ChatWithLastMessage> =
        getAllChatsWithUsers().map { it.toDomainWithDetails() }

    fun observeAllChatsWithLastMessage(): Flow<List<ChatWithLastMessage>> =
        getAllChatsFlow().map { chats -> chats.map { it.toDomainWithDetails() } }
    
    @Query("UPDATE chats SET unread_count = 0 WHERE id = :chatId")
    suspend fun markAsRead(chatId: String)

    suspend fun markChatAsRead(chatId: String) {
        markAsRead(chatId)
    }

    @Query(
        """
        UPDATE chats
        SET last_message_preview = :content,
            last_message_timestamp = :timestamp,
            last_message_at = :timestamp,
            last_message_id = :messageId,
            last_message_sender_id = :senderId,
            is_deleted = 0
        WHERE id = :chatId
        """
    )
    suspend fun updateLastMessage(
        chatId: String,
        content: String,
        timestamp: String,
        messageId: String?,
        senderId: String?
    )

    @Query(
        """
        UPDATE chats
        SET last_message_preview = NULL,
            last_message_timestamp = :timestamp,
            last_message_at = :timestamp,
            last_message_id = NULL,
            last_message_sender_id = NULL
        WHERE id = :chatId
        """
    )
    suspend fun clearLastMessage(chatId: String, timestamp: String?)

    suspend fun updateChat(chat: ChatEntity) {
        insertChat(chat)
    }

    @Query("UPDATE chats SET unread_count = unread_count + 1 WHERE id = :chatId")
    suspend fun incrementUnreadCount(chatId: String)

    @Query("SELECT * FROM chats WHERE is_group = 0 AND other_user_id = :otherUserId AND is_deleted = 0 LIMIT 1")
    suspend fun getChatByOtherUserId(otherUserId: String): ChatEntity?

    // Get all chats including deleted (for checking deletion status)
    @Query("SELECT * FROM chats")
    suspend fun getAllChatsSync(): List<ChatEntity>

    // SOFT DELETE: Hide from UI immediately
    @Query("UPDATE chats SET is_deleted = 1, cleared_at = :deletedAt, unread_count = 0 WHERE id = :chatId")
    suspend fun deleteChat(chatId: String, deletedAt: String)

    // HARD DELETE: For repairing corrupted data
    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun hardDeleteChat(chatId: String)

    @Query("UPDATE chats SET nickname = :nickname WHERE id = :chatId")
    suspend fun updateNickname(chatId: String, nickname: String?)

    @Query("UPDATE chats SET cleared_at = :clearedAt, last_message_preview = NULL, last_message_timestamp = :clearedAt, last_message_id = NULL, last_message_sender_id = NULL, unread_count = 0 WHERE id = :chatId")
    suspend fun updateClearedAt(chatId: String, clearedAt: String?)

    @Query("UPDATE chats SET is_muted = :isMuted WHERE LOWER(id) = LOWER(:chatId)")
    suspend fun updateMuteState(chatId: String, isMuted: Boolean)

    @Query("UPDATE chats SET is_pinned = :isPinned WHERE LOWER(id) = LOWER(:chatId)")
    suspend fun updatePinState(chatId: String, isPinned: Boolean)

    @Query("UPDATE chats SET is_archived = :isArchived WHERE LOWER(id) = LOWER(:chatId)")
    suspend fun updateArchiveState(chatId: String, isArchived: Boolean)

    @Query("UPDATE chats SET is_favorite = :isFavorite WHERE LOWER(id) = LOWER(:chatId)")
    suspend fun updateFavoriteState(chatId: String, isFavorite: Boolean)

    // NUCLEAR: Clear local cache to force re-sync
    @Query("DELETE FROM chats")
    suspend fun nukeAllChats()
}
