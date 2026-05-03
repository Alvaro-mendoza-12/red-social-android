package conectate.com.data.repository.message

import android.util.Log
import conectate.com.core.observability.AppMetrics
import conectate.com.data.local.dao.ChatDao
import conectate.com.data.local.dao.MessageDao
import conectate.com.data.local.dao.UserDao
import conectate.com.data.model.ChatWithLastMessage
import conectate.com.data.model.Message
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageCacheManager @Inject constructor(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val userDao: UserDao,
    private val appMetrics: AppMetrics
) {
    companion object {
        private const val TAG = "MessageCacheManager"
        private const val MAX_CACHED_CHATS = 200 // Límite para evitar OOM
        private const val MAX_CACHED_MESSAGES_PER_CHAT = 200
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Cache en memoria de chats (para UI rápida)
    private val _cachedChats = MutableStateFlow<List<ChatWithLastMessage>>(emptyList())
    val cachedChats: StateFlow<List<ChatWithLastMessage>> = _cachedChats.asStateFlow()

    // Cache de mensajes activos por chatId
    private val activeChatMessages = ConcurrentHashMap<String, MutableStateFlow<List<Message>>>()

    // Estadísticas
    private var cacheHits = 0L
    private var cacheMisses = 0L

    init {
        // Cargar chats iniciales desde DB
        scope.launch {
            try {
                refreshChatsFromDb()
                // Observar cambios en chats y actualizar cache
                // v22.3: Added conflate() to prevent UI saturation when DB updates frequently
                chatDao.observeAllChatsWithLastMessage().conflate().collect { chats ->
                    _cachedChats.value = chats
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error inicializando cache de chats", e)
            }
        }
    }

    /**
     * Refresca la lista de chats desde la base de datos local.
     */
    suspend fun refreshChatsFromDb() {
        try {
            var chats = chatDao.getAllChatsWithLastMessage()
            _cachedChats.value = chats
            appMetrics.incrementCounter("chat_cache_refresh")
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing chats from DB", e)
            appMetrics.recordFailure("chat_cache_refresh", e)
        }
    }

    /**
     * Obtiene mensajes de un chat desde cache o DB.
     */
    fun getMessagesForChat(chatId: String): Flow<List<Message>> {
        val cleanId = chatId.trim().lowercase()
        return activeChatMessages.getOrPut(cleanId) {
            MutableStateFlow<List<Message>>(emptyList()).apply {
                // Cargar inicial desde DB
                scope.launch {
                    try {
                        val messages = messageDao.getMessagesForChat(chatId)
                            .sortedBy { it.created_at }
                        value = messages
                    } catch (e: Exception) {
                        Log.e(TAG, "Error cargando mensajes para chat $cleanId", e)
                    }
                }
                // Observar cambios en mensajes del chat
                scope.launch {
                    try {
                        messageDao.observeMessagesForChat(chatId).collect { msgs ->
                            value = msgs
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error observando mensajes para chat $cleanId", e)
                    }
                }
            }
        }.asStateFlow()
    }

    /**
     * Inserta un mensaje en el cache y DB local.
     */
    suspend fun insertMessage(message: Message) {
        try {
            messageDao.insertMessage(message)
            // El StateFlow se actualizará automáticamente via observer
            cacheHits++
        } catch (e: Exception) {
            Log.e(TAG, "Error insertando mensaje en cache", e)
            cacheMisses++
            throw e
        }
    }

    /**
     * Actualiza un mensaje en cache.
     */
    suspend fun updateMessage(message: Message) {
        try {
            messageDao.updateMessage(message)
            cacheHits++
        } catch (e: Exception) {
            Log.e(TAG, "Error actualizando mensaje en cache", e)
            cacheMisses++
            throw e
        }
    }

    /**
     * Elimina un mensaje del cache.
     */
    suspend fun deleteMessage(messageId: String) {
        try {
            messageDao.deleteMessageById(messageId)
            cacheHits++
        } catch (e: Exception) {
            Log.e(TAG, "Error eliminando mensaje del cache", e)
            cacheMisses++
        }
    }

    /**
     * Marca chat como leído.
     */
    suspend fun markChatRead(chatId: String) {
        try {
            chatDao.markChatAsRead(chatId)
            appMetrics.incrementCounter(
                metric = "chat_marked_read",
                attributes = mapOf("chat_id" to chatId)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error marcando chat como leído", e)
        }
    }

    /**
     * Limpia todos los caches en memoria. Usar en logout.
     */
    fun clearAll() {
        Log.w(TAG, "Clearing all in-memory caches")
        activeChatMessages.clear()
        _cachedChats.value = emptyList()
        cacheHits = 0
        cacheMisses = 0
    }

    /**
     * Obtiene estadísticas del cache.
     */
    fun getStats(): String {
        return "CacheStats{hits=$cacheHits, misses=$cacheMisses, " +
                "chatsCached=${_cachedChats.value.size}, " +
                "activeChats=${activeChatMessages.size}}"
    }
}



