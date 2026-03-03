package conectate.com.data.repository

import conectate.com.data.local.dao.ChatSettingsDao
import conectate.com.data.local.entity.ChatSettingsEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class ChatBackgroundSettings(
    val backgroundType: String = "default",
    val backgroundUri: String? = null
)

@Singleton
class ChatSettingsRepository @Inject constructor(
    private val chatSettingsDao: ChatSettingsDao
) {
    fun observeChatBackground(chatId: String): Flow<ChatBackgroundSettings> {
        val cleanId = chatId.trim().lowercase()
        return chatSettingsDao.observeByChatId(cleanId).map { entity ->
            if (entity == null) {
                ChatBackgroundSettings()
            } else {
                ChatBackgroundSettings(
                    backgroundType = entity.backgroundType.ifBlank { "default" },
                    backgroundUri = entity.backgroundUri
                )
            }
        }
    }

    suspend fun upsertChatBackground(chatId: String, backgroundType: String, backgroundUri: String?) {
        val cleanId = chatId.trim().lowercase()
        if (cleanId.isBlank()) return
        val normalizedType = backgroundType.trim().lowercase().ifBlank { "default" }
        chatSettingsDao.upsert(
            ChatSettingsEntity(
                chatId = cleanId,
                backgroundType = normalizedType,
                backgroundUri = backgroundUri?.trim()?.ifBlank { null },
                updatedAt = java.time.Instant.now().toString()
            )
        )
    }

    suspend fun clearChatBackground(chatId: String) {
        val cleanId = chatId.trim().lowercase()
        if (cleanId.isBlank()) return
        chatSettingsDao.clearByChatId(cleanId)
    }
}

