package conectate.com.data.repository.message

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import conectate.com.BuildConfig
import conectate.com.core.observability.AppMetrics
import conectate.com.data.local.dao.ChatDao
import conectate.com.data.local.dao.MessageDao
import conectate.com.data.model.Chat
import conectate.com.data.model.Message
import conectate.com.data.model.ChatWithLastMessage
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.realtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRealtimeSync @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val ubuntuRealtimeClient: UbuntuRealtimeClient,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val appMetrics: AppMetrics,
    private val crashlytics: FirebaseCrashlytics
) {
    companion object {
        private const val TAG = "MessageRealtimeSync"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var globalListenerJob: Job? = null
    private val chatMutexes = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private val transientIdMap = ConcurrentHashMap<String, String>()

    // Eventos de mensaje para que UI escuche
    private val _messageEvents = MutableSharedFlow<Message>(extraBufferCapacity = 100)
    val messageEvents: SharedFlow<Message> = _messageEvents.asSharedFlow()

    private enum class QueuedMessageAction { Insert, Update }

    // v22.3: Canal para procesamiento secuencial y evitar saturación de corrutinas/DB
    private val messageChannel = kotlinx.coroutines.channels.Channel<Pair<QueuedMessageAction, JsonObject>>(
        capacity = 1000,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    private var queueProcessorJob: Job? = null
    private var lastListRefreshAt = 0L

    // Lista de chats actualizada
    private val _userChats = MutableStateFlow<List<ChatWithLastMessage>>(emptyList())
    val userChats: StateFlow<List<ChatWithLastMessage>> = _userChats.asStateFlow()

    /**
     * Inicia el listener global de mensajes para el usuario autenticado.
     * Se llama cuando el session status cambia a Authenticated.
     */
    fun start(userId: String) {
        val cleanUserId = userId.trim().lowercase()
        Log.d(TAG, "[START] Iniciando listener realtime para usuario: $cleanUserId")

        globalListenerJob?.cancel()
        
        // v22.3: Iniciar procesador de cola secuencial
        queueProcessorJob?.cancel()
        queueProcessorJob = scope.launch {
            for (item in messageChannel) {
                try {
                    val (action, record) = item
                    val msg = parseMessageFromJson(record)
                    when (action) {
                        QueuedMessageAction.Insert -> handleGlobalMessageInsert(msg, cleanUserId)
                        QueuedMessageAction.Update -> handleGlobalMessageUpdate(msg, cleanUserId)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in queue processor", e)
                }
            }
        }

        if (supabaseConfig.isLocalFallbackPrimary) {
            startUbuntuRealtime(cleanUserId)
            return
        }

        globalListenerJob = scope.launch {
            while (isActive) {
                var channel: RealtimeChannel? = null
                try {
                    val client = supabaseConfig.client
                    channel = client.channel("messages-realtime-$cleanUserId")

                    val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                        table = "messages"
                    }

                    launch {
                        changes.collect { change ->
                            // Enviar a la cola en lugar de procesar inmediatamente
                            when (change) {
                                is PostgresAction.Insert ->
                                    messageChannel.send(QueuedMessageAction.Insert to change.record)
                                is PostgresAction.Update ->
                                    messageChannel.send(QueuedMessageAction.Update to change.record)
                                else -> Unit
                            }
                        }
                    }

                    supabaseConfig.subscribeRealtimeChannel(
                        channel = channel,
                        reason = "messages-global-$cleanUserId"
                    )

                    // ... rest of start logic (heartbeat, status collect) ...
                    channel.status.collect { status ->
                        if (status == RealtimeChannel.Status.UNSUBSCRIBED ||
                            status.name == "DISCONNECTED" ||
                            status.name == "CLOSED" ||
                            status.name == "TIMED_OUT"
                        ) {
                            throw IllegalStateException("messages realtime channel dropped: $status")
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException && !isActive) throw e
                    Log.e(TAG, "[REALTIME-ERROR] Reintentando en 5s...", e)
                    delay(5000)
                } finally {
                    try {
                        channel?.unsubscribe()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun startUbuntuRealtime(userId: String) {
        globalListenerJob = scope.launch {
            val token = authService.getAccessToken()?.trim().orEmpty()
            if (token.isBlank()) return@launch
            ubuntuRealtimeClient.updateAccessToken(token)
            ubuntuRealtimeClient.subscribe("user:$userId")
            ubuntuRealtimeClient.events.collect { envelope ->
                if (envelope.event != "message") return@collect
                val record = envelope.payload["message"]?.jsonObject ?: return@collect
                messageChannel.send(QueuedMessageAction.Insert to record)
            }
        }
    }

    /**
     * Maneja inserción de mensaje desde realtime.
     */
    private suspend fun handleGlobalMessageInsert(
        msg: Message,
        sessionUserId: String?
    ) {
        var cleanId = msg.chat_id.trim().lowercase()
        var mutex = getMutexForChat(cleanId)
        mutex.withLock {
            try {
                    // 1. Deduplicación: buscar mensajes similares recientes
                    val similarMessages = messageDao.getRecentSimilarMessagesAgnostic(cleanId, msg.content)
                    val existingReal = similarMessages.firstOrNull { it.id == msg.id }
                    if (existingReal != null && existingReal.status != "sending") {
                        Log.d(TAG, "♻️ Duplicado completo omitido: ${msg.id}")
                        return@withLock
                    }

                    // 2. Swap de ID transitorio (FCM)
                    val fcmPlaceholder = similarMessages.firstOrNull { it.id.startsWith("fcm_") }
                    if (fcmPlaceholder != null) {
                        Log.d(TAG, "♻️ Intercambiando ID temporal ${fcmPlaceholder.id} -> ${msg.id}")
                        transientIdMap[fcmPlaceholder.id] = msg.id
                        // Eliminar el placeholder y reemplazar con mensaje real
                        messageDao.deleteMessageById(fcmPlaceholder.id)
                    }

                    // 3. Insertar/actualizar en BD local
                    messageDao.insertMessage(msg)

                    // 4. Emitir evento para UI
                    _messageEvents.emit(msg)

                    // 5. Actualizar last_message/unread en chats.
                    // Ubuntu realtime is the production path now. The notification could arrive,
                    // but the inbox badge/preview stayed stale because only the message row was
                    // inserted. Keep the conversation metadata authoritative here too.
                    updateChatLastMessage(cleanId, msg, sessionUserId)

                    Log.d(TAG, "✅ Mensaje insertado via realtime: ${msg.id} en chat $cleanId")
                } catch (e: Exception) {
                    Log.e(TAG, "Error procesando mensaje realtime insert", e)
                    crashlytics.setCustomKey("component", "MessageRealtimeSync")
                    crashlytics.setCustomKey("operation", "handleInsert")
                    crashlytics.setCustomKey("msg_id", msg.id)
                    crashlytics.recordException(e)
                }
            }
    }

    /**
     * Maneja actualización de mensaje desde realtime (read receipts, status changes).
     */
    private suspend fun handleGlobalMessageUpdate(
        msg: Message,
        sessionUserId: String?
    ) {
        var cleanId = msg.chat_id.trim().lowercase()
        var mutex = getMutexForChat(cleanId)
        mutex.withLock {
            try {
                    // Actualizar mensaje existente
                    messageDao.updateMessage(msg)

                    // Emitir evento
                    _messageEvents.emit(msg)

                    // Si es un read receipt, recalcular unread del chat
                    if (msg.status == "read" || msg.status == "delivered") {
                        // El cálculo de unread debería estar en MessageUnreadCounter
                        // Por ahora lo dejamos en el Dao/Repository
                    }

                    Log.d(TAG, "✅ Mensaje actualizado via realtime: ${msg.id}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error procesando mensaje realtime update", e)
                    crashlytics.setCustomKey("component", "MessageRealtimeSync")
                    crashlytics.setCustomKey("operation", "handleUpdate")
                    crashlytics.setCustomKey("msg_id", msg.id)
                    crashlytics.recordException(e)
                }
            }
    }

    /**
     * Actualiza el campo last_message del chat correspondiente.
     */
    private suspend fun updateChatLastMessage(chatId: String, msg: Message, sessionUserId: String?) {
        try {
            var chat = chatDao.getChatById(chatId)
            if (chat != null) {
                val messageAt = normalizeInstantText(msg.created_at)
                val clearedAt = normalizeInstantText(chat.cleared_at)
                if (chat.is_deleted && clearedAt.isNotBlank() && messageAt.isNotBlank() && !isAfterInstant(messageAt, clearedAt)) {
                    Log.d(TAG, "Manteniendo oculto chat eliminado $chatId; evento no es posterior a cleared_at")
                    return
                }
                val preview = previewFor(msg.content_type, msg.content)
                val updatedChat = chat.copy(
                    last_message_id = msg.id,
                    last_message_preview = preview,
                    last_message_timestamp = msg.created_at,
                    last_message_at = msg.created_at,
                    last_message_sender_id = msg.sender_id,
                    unread_count = if (msg.sender_id.trim().lowercase() != sessionUserId?.trim()?.lowercase()) {
                        chat.unread_count + 1
                    } else {
                        chat.unread_count
                    },
                    is_deleted = false
                )
                chatDao.updateChat(updatedChat)
                refreshUserChatsFlow()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error actualizando last_message para chat $chatId", e)
        }
    }

    private fun previewFor(contentType: String, fallbackContent: String): String {
        return when (contentType.trim().lowercase()) {
            "image" -> "📷 Foto"
            "video" -> "🎥 Video"
            "audio", "voice" -> "🎤 Audio"
            "view_once_image" -> "👁️ Foto ver una vez"
            "view_once_video" -> "👁️ Video ver una vez"
            "gif" -> "🌀 GIF"
            "sticker" -> "🧩 Sticker"
            "document", "file" -> "📎 Archivo"
            "location" -> "📍 Ubicación"
            "contact" -> "👤 Contacto"
            "poll" -> "📊 Encuesta"
            else -> fallbackContent.ifBlank { "Mensaje" }.take(100)
        }
    }

    private fun normalizeInstantText(value: String?): String {
        return value?.trim()?.replace(" ", "T").orEmpty()
    }

    private fun isAfterInstant(left: String, right: String): Boolean {
        return runCatching {
            Instant.parse(left).isAfter(Instant.parse(right))
        }.getOrElse {
            left > right
        }
    }

    /**
     * Refresca el flow de userChats desde la base local.
     */
    private suspend fun refreshUserChatsFlow() {
        val now = System.currentTimeMillis()
        // v22.3: Throttle list refreshes to avoid UI 'saturation' (max once per 800ms)
        if (now - lastListRefreshAt < 800) return
        lastListRefreshAt = now

        try {
            val chats = chatDao.getAllChatsWithLastMessage()
            _userChats.value = chats
        } catch (e: Exception) {
            Log.e(TAG, "Error refrescando userChats flow", e)
        }
    }

    /**
     * Obtiene mutex por chat para evitar race conditions.
     */
    fun getMutexForChat(chatId: String): kotlinx.coroutines.sync.Mutex {
        return chatMutexes.getOrPut(chatId.trim().lowercase()) { kotlinx.coroutines.sync.Mutex() }
    }

    /**
     * Limpia todo el estado en memoria. Llamar en logout.
     */
    fun clearAll() {
        Log.w(TAG, "CLEAR ALL: Limpiando estado en memoria de realtime sync")
        globalListenerJob?.cancel()
        globalListenerJob = null
        chatMutexes.clear()
        transientIdMap.clear()
        _userChats.value = emptyList()
    }

    /**
     * mapea ID transitorio (FCM) a ID real (UUID).
     */
    fun resolveTransientId(tempId: String): String? {
        return transientIdMap[tempId]
    }

    /**
     * Parsea un registro JSON de Supabase a objeto Message.
     */
    private fun parseMessageFromJson(record: JsonObject): Message {
        val parser = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        return parser.decodeFromJsonElement(Message.serializer(), record)
    }
}



