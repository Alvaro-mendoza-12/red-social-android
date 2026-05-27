package conectate.com.data.repository.message

import android.util.Log
import conectate.com.data.local.dao.ChatDao
import conectate.com.data.local.dao.MessageDao
import conectate.com.data.supabase.AuthService
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageUnreadCounter @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val authService: AuthService
) {
    companion object {
        private const val TAG = "MessageUnreadCounter"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Cache de conteos por chat (TTL 5 min)
    private val unreadCountCache = ConcurrentHashMap<String, Pair<Long, Int>>()

    /**
     * Calcula el número total de mensajes no leídos para un usuario.
     *Suma los no leídos de todos sus chats.
     */
    suspend fun getTotalUnreadCount(): Int {
        return try {
            var allChats = chatDao.getAllChatsWithLastMessage()
            allChats.sumOf { chatWithLast ->
                getUnreadCountForChat(chatWithLast.chat.id)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculando total unread", e)
            0
        }
    }

    /**
     * Calcula mensajes no leídos para un chat específico.
     */
    suspend fun getUnreadCountForChat(chatId: String): Int {
        val cacheKey = chatId
        val now = System.currentTimeMillis()

        // Check cache (TTL 5 min = 300,000 ms)
        unreadCountCache[cacheKey]?.let { (timestamp, count) ->
            if (now - timestamp < 300_000) {
                return count
            }
        }

        return try {
            var chat = chatDao.getChatById(chatId) ?: return 0
            val messages = messageDao.getMessagesForChat(chatId)
            val currentUserId = getCurrentUserId() ?: return 0

            val unread = messages.count { msg ->
                msg.sender_id != currentUserId &&
                msg.status != "read" &&
                msg.status != "deleted"
            }

            // Cache result
            unreadCountCache[cacheKey] = now to unread
            unread
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo unread para chat $chatId", e)
            0
        }
    }

    /**
     * Actualiza el contador de no leídos cuando un mensaje es leído.
     * Esto debe ser llamado desde MessageRepository cuando se marca mensaje como leído.
     */
    suspend fun onMessageRead(chatId: String, userId: String) {
        try {
            // Invalidar cache del chat
            unreadCountCache.remove(chatId)

            // Recalcular y persistir en Room (como campo redundante en Chat)
            val unreadCount = getUnreadCountForChat(chatId)
            var chat = chatDao.getChatById(chatId)
            chat?.let {
                var updated = it.copy(unread_count = unreadCount)
                chatDao.updateChat(updated)
            }

            // Emitir evento global de actualización (para UI badge)
            // AppMetrics increment
        } catch (e: Exception) {
            Log.e(TAG, "Error en onMessageRead", e)
        }
    }

    /**
     * Batch refresh: recalcula no leídos para todos los chats del usuario.
     * Útil al hacer login o sync completo.
     */
    suspend fun refreshAllUnreadCounts() {
        try {
            var allChats = chatDao.getAllChatsWithLastMessage()
            allChats.forEach { chatWithLast ->
                val chatId = chatWithLast.chat.id
                var count = getUnreadCountForChat(chatId)
                var chat = chatDao.getChatById(chatId)
                chat?.let {
                    var updated = it.copy(unread_count = count)
                    chatDao.updateChat(updated)
                }
            }
            Log.d(TAG, "Refresh unread counts completado para ${allChats.size} chats")
        } catch (e: Exception) {
            Log.e(TAG, "Error en refreshAllUnreadCounts", e)
        }
    }

    /**
     * Limpia cache (logout).
     */
    fun clearCache() {
        unreadCountCache.clear()
    }

    private fun getCurrentUserId(): String? {
        return authService.getCurrentUserId()?.trim()?.lowercase()
    }
}



