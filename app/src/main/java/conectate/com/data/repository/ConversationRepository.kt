package conectate.com.data.repository

import conectate.com.data.local.dao.ChatDao
import conectate.com.data.local.entity.ChatEntity
import conectate.com.data.model.Conversation
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val chatDao: ChatDao
) {

    fun getConversations(): Flow<List<Conversation>> =
        chatDao.getAllChatsFlow().map { rows ->
            rows.map { it.chat.toConversation() }
        }

    fun createConversation(): Result<Unit> =
        Result.failure(IllegalArgumentException("createConversation requiere datos de participantes"))

    suspend fun archiveConversation(conversationId: String): Result<Unit> =
        updateArchiveState(conversationId, true)

    suspend fun unarchiveConversation(conversationId: String): Result<Unit> =
        updateArchiveState(conversationId, false)

    suspend fun favoriteConversation(conversationId: String): Result<Unit> =
        updateFavoriteState(conversationId, true)

    suspend fun unfavoriteConversation(conversationId: String): Result<Unit> =
        updateFavoriteState(conversationId, false)

    suspend fun pinConversation(conversationId: String): Result<Unit> =
        updatePinState(conversationId, true)

    suspend fun unpinConversation(conversationId: String): Result<Unit> =
        updatePinState(conversationId, false)

    suspend fun muteConversation(conversationId: String): Result<Unit> =
        updateMuteState(conversationId, true)

    suspend fun unmuteConversation(conversationId: String): Result<Unit> =
        updateMuteState(conversationId, false)

    suspend fun markAsRead(conversationId: String): Result<Unit> = runCatching {
        val cleanId = normalizeId(conversationId)
        chatDao.markAsRead(cleanId)
    }

    suspend fun deleteConversation(conversationId: String): Result<Unit> = runCatching {
        val cleanId = normalizeId(conversationId)
        chatDao.deleteChat(cleanId, java.time.Instant.now().toString())
    }

    private suspend fun updateArchiveState(conversationId: String, isArchived: Boolean): Result<Unit> =
        runCatching {
            val cleanId = normalizeId(conversationId)
            chatDao.updateArchiveState(cleanId, isArchived)
        }

    private suspend fun updateFavoriteState(conversationId: String, isFavorite: Boolean): Result<Unit> =
        runCatching {
            val cleanId = normalizeId(conversationId)
            chatDao.updateFavoriteState(cleanId, isFavorite)
        }

    private suspend fun updatePinState(conversationId: String, isPinned: Boolean): Result<Unit> =
        runCatching {
            val cleanId = normalizeId(conversationId)
            chatDao.updatePinState(cleanId, isPinned)
        }

    private suspend fun updateMuteState(conversationId: String, isMuted: Boolean): Result<Unit> =
        runCatching {
            val cleanId = normalizeId(conversationId)
            chatDao.updateMuteState(cleanId, isMuted)
        }

    private fun normalizeId(conversationId: String): String {
        val cleanId = conversationId.trim().lowercase()
        require(cleanId.isNotBlank()) { "Conversación inválida" }
        return cleanId
    }

    private fun ChatEntity.toConversation(): Conversation {
        val participants = runCatching {
            kotlinx.serialization.json.Json.decodeFromString<List<String>>(participantsJson)
        }.getOrDefault(emptyList())

        return Conversation(
            id = id,
            participants = participants,
            lastMessage = last_message_preview.orEmpty(),
            lastMessageTimestamp = last_message_timestamp ?: last_message_at,
            lastMessageSenderId = last_message_sender_id.orEmpty(),
            unreadCount = if (unread_count > 0) mapOf("total" to unread_count) else emptyMap(),
            createdAt = last_message_at,
            isArchived = is_archived,
            isFavorite = is_favorite,
            name = nickname ?: display_name ?: name
        )
    }
}
