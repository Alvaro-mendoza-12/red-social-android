package conectate.com.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import conectate.com.data.local.entity.MessageDeletionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDeletionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(deletion: MessageDeletionEntity)

    @Query("SELECT * FROM message_deletions WHERE actor_id = :actorId AND state = 'pending' ORDER BY created_at, message_id LIMIT :limit")
    suspend fun pending(actorId: String, limit: Int): List<MessageDeletionEntity>

    @Query("SELECT * FROM message_deletions WHERE message_id = :messageId AND actor_id = :actorId AND for_everyone = :forEveryone")
    suspend fun get(messageId: String, actorId: String, forEveryone: Boolean): MessageDeletionEntity?

    @Query("SELECT * FROM message_deletions WHERE chat_id = :chatId")
    suspend fun forChat(chatId: String): List<MessageDeletionEntity>

    @Query("SELECT * FROM message_deletions WHERE chat_id = :chatId")
    fun observeChat(chatId: String): Flow<List<MessageDeletionEntity>>

    @Query("SELECT * FROM message_deletions WHERE chat_id IN (:chatIds)")
    suspend fun forChats(chatIds: List<String>): List<MessageDeletionEntity>

    @Query("UPDATE message_deletions SET state = :state, error = :error WHERE revision = :revision AND state = 'pending'")
    suspend fun finish(revision: String, state: String, error: String? = null)
}
