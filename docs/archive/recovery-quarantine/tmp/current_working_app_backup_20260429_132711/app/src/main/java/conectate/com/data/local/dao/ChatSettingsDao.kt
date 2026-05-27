package conectate.com.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import conectate.com.data.local.entity.ChatSettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSettingsDao {
    @Query("SELECT * FROM chat_settings WHERE chat_id = :chatId LIMIT 1")
    fun observeByChatId(chatId: String): Flow<ChatSettingsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: ChatSettingsEntity)

    @Query("DELETE FROM chat_settings WHERE chat_id = :chatId")
    suspend fun clearByChatId(chatId: String)
}

