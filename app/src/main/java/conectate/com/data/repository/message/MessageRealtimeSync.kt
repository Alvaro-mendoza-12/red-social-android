package conectate.com.data.repository.message

import android.util.Log
import androidx.room.withTransaction
import conectate.com.data.local.AppDatabase
import conectate.com.data.supabase.AuthSessionLease
import conectate.com.data.localfallback.UbuntuRealtimeEnvelope
import conectate.com.core.crash.CrashReporter
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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private val database: AppDatabase,
    private val chatDao: ChatDao,
    private val appMetrics: AppMetrics,
    private val crashlytics: CrashReporter,
    private val conversationReadReceiver: ConversationReadReceiver,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) {
    companion object {
        private const val TAG = "MessageRealtimeSync"
    }

    private val deletedMessages by lazy { conectate.com.util.DeletedMessageStore(context) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val chatMutexes = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private val transientIdMap = ConcurrentHashMap<String, String>()

    // Eventos de mensaje para que UI escuche
    private data class SessionMessage(val session: RealtimeSession, val message: Message)
    private val _messageEvents = MutableSharedFlow<SessionMessage>(extraBufferCapacity = 100)
    val messageEvents: Flow<Message> = _messageEvents.mapNotNull { event ->
        event.message.takeIf { activeSession === event.session && event.session.owner.isCurrent() }
    }

    private enum class QueuedMessageAction { Insert, Update }

    private data class QueuedMessage(
        val action: QueuedMessageAction,
        val record: JsonObject,
        val envelope: UbuntuRealtimeEnvelope? = null,
    )

    // Never reuse a queue across account visits, including A -> B -> A.
    private class RealtimeSession(val owner: AuthSessionLease) {
        val queue = kotlinx.coroutines.channels.Channel<QueuedMessage>(capacity = 1000)
        var processor: Job? = null
        var listener: Job? = null
        var lastListRefreshAt = 0L
        fun cancel() {
            queue.cancel()
            processor?.cancel()
            listener?.cancel()
        }
    }

    @Volatile private var activeSession: RealtimeSession? = null

    private fun requireSession(session: RealtimeSession) {
        session.owner.requireCurrent()
        if (activeSession !== session) throw CancellationException("Realtime session ended")
    }

    // Lista de chats actualizada
    private val _userChats = MutableStateFlow<List<ChatWithLastMessage>>(emptyList())
    val userChats: StateFlow<List<ChatWithLastMessage>> = _userChats.asStateFlow()

    /**
     * Inicia el listener global de mensajes para el usuario autenticado.
     * Se llama cuando el session status cambia a Authenticated.
     */
    @Synchronized
    fun start(userId: String) {
        val cleanUserId = userId.trim().lowercase(java.util.Locale.ROOT)
        val owner = authService.captureAccountLease()
        if (owner.accountId != cleanUserId || !owner.isCurrent()) return
        activeSession?.cancel()
        val session = RealtimeSession(owner)
        activeSession = session
        transientIdMap.clear()
        _userChats.value = emptyList()
        session.processor = scope.launch {
            for (item in session.queue) {
                try {
                    requireSession(session)
                    if (item.envelope != null && !ubuntuRealtimeClient.isCurrent(item.envelope)) continue
                    val msg = deletedMessages.redact(parseMessageFromJson(item.record), cleanUserId)
                    when (item.action) {
                        QueuedMessageAction.Insert -> handleGlobalMessageInsert(msg, session)
                        QueuedMessageAction.Update -> handleGlobalMessageUpdate(msg, session)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(TAG, "Error in queue processor", error)
                    crashlytics.recordException(error, mapOf("component" to TAG, "operation" to "receive"))
                }
            }
        }

        if (supabaseConfig.isLocalFallbackPrimary) {
            startUbuntuRealtime(cleanUserId, session)
            return
        }

        session.listener = scope.launch {
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
                                    session.queue.send(QueuedMessage(QueuedMessageAction.Insert, change.record))
                                is PostgresAction.Update ->
                                    session.queue.send(QueuedMessage(QueuedMessageAction.Update, change.record))
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

    private fun startUbuntuRealtime(userId: String, session: RealtimeSession) {
        session.listener = scope.launch {
            val token = authService.getAccessToken()?.trim().orEmpty()
            requireSession(session)
            if (token.isBlank()) return@launch
            publishForSession(session) {
                ubuntuRealtimeClient.updateAccessToken(token)
                ubuntuRealtimeClient.replaceUserSubscription(userId)
            }
            requireSession(session)
            ubuntuRealtimeClient.events.collect { envelope ->
                requireSession(session)
                if (!ubuntuRealtimeClient.isCurrent(envelope)) return@collect
                if (envelope.channel != "user:$userId") return@collect
                if (envelope.event == "conversation_read") {
                    val payload=envelope.payload
                    val event=conectate.com.util.conversationReadEvent(mapOf(
                        "type" to "conversation_read", "recipient_id" to (payload["accountId"]?.jsonPrimitive?.contentOrNull?:""),
                        "target_type" to (payload["kind"]?.jsonPrimitive?.contentOrNull?:""),
                        "conversation_id" to (payload["conversationId"]?.jsonPrimitive?.contentOrNull?:""),
                        "message_ids" to (payload["messageIds"]?.toString()?:"[]"),
                        "read_through" to (payload["through"]?.jsonPrimitive?.contentOrNull?:"")))
                    if(event!=null && event.accountId==userId) { conversationReadReceiver.apply(event, session.owner); refreshUserChatsFlow(session, force = true) }
                    return@collect
                }
                if (envelope.event != "message") return@collect
                val eventChatId = envelope.payload["chatId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (eventChatId.startsWith("room:")) return@collect
                val record = envelope.payload["message"]?.jsonObject ?: return@collect
                val action = envelope.payload["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                val queuedAction = when (action) {
                    "updated", "edited", "deleted", "reaction", "reacted" -> QueuedMessageAction.Update
                    else -> QueuedMessageAction.Insert
                }
                session.queue.send(QueuedMessage(queuedAction, record, envelope))
            }
        }
    }

    /** SQL and inbox metadata commit together under the account activation gate. */
    private suspend fun <T> withSessionStorage(session: RealtimeSession, write: suspend () -> T): T =
        authService.withAccountLocalStorage(session.owner) {
            withRealtimeAccountTransaction(database, { requireSession(session) }, write)
        }

    private suspend fun handleGlobalMessageInsert(msg: Message, session: RealtimeSession) {
        val userId = session.owner.accountId ?: return
        val chatId = msg.chat_id.trim().lowercase(java.util.Locale.ROOT)
        getMutexForChat(chatId).withLock {
            var placeholderId: String? = null
            val saved = withSessionStorage(session) {
                val similar = messageDao.getRecentSimilarMessagesAgnostic(chatId, msg.content)
                if (similar.any { it.id == msg.id && it.status != "sending" }) return@withSessionStorage null
                placeholderId = similar.firstOrNull { it.id.startsWith("fcm_") }?.id
                placeholderId?.let { messageDao.deleteMessageById(it) }
                val wasRead = conectate.com.util.ConversationReadStore(context)
                    .isRead(userId, "chat", chatId, msg.id, msg.created_at)
                val safe = deletedMessages.redact(if (wasRead) msg.copy(status = "read") else msg, userId)
                messageDao.insertMessage(safe)
                if (!safe.deleted_for_all) updateChatLastMessage(chatId, safe, userId)
                safe
            } ?: return@withLock
            publishForSession(session) {
                placeholderId?.let { transientIdMap[it] = saved.id }
            }
            _messageEvents.emit(SessionMessage(session, saved))
        }
        refreshUserChatsFlow(session)
    }

    private suspend fun handleGlobalMessageUpdate(msg: Message, session: RealtimeSession) {
        val userId = session.owner.accountId ?: return
        val chatId = msg.chat_id.trim().lowercase(java.util.Locale.ROOT)
        getMutexForChat(chatId).withLock {
            val saved = withSessionStorage(session) {
                val local = messageDao.getMessageById(msg.id)?.toDomain()?.takeIf { it.chat_id == chatId }
                val wasRead = local?.status == "read" || conectate.com.util.ConversationReadStore(context)
                    .isRead(userId, "chat", chatId, msg.id, msg.created_at)
                val safe = deletedMessages.redact(msg.copy(
                    status = if (wasRead) "read" else msg.status,
                    deleted_for_all = msg.deleted_for_all || local?.deleted_for_all == true,
                ), userId)
                messageDao.updateMessage(safe)
                if (chatDao.getChatById(chatId)?.last_message_id == msg.id) {
                    if (safe.deleted_for_all) chatDao.redactDeletedPreview(chatId, msg.id)
                    else chatDao.updateLastMessage(
                        chatId = chatId, content = previewFor(safe.content_type, safe.content),
                        timestamp = safe.created_at ?: safe.updated_at ?: Instant.now().toString(),
                        messageId = safe.id, senderId = safe.sender_id,
                    )
                }
                safe
            }
            publishForSession(session) {
                if (saved.deleted_for_all) conectate.com.util.DeletedMessageIdentity.create(userId, "chat", chatId, saved.id)?.let {
                    conectate.com.util.MessageNotificationFactory.redactMessage(context, it, saved.created_at)
                }
            }
            _messageEvents.emit(SessionMessage(session, saved))
        }
        refreshUserChatsFlow(session)
    }

    /** Non-suspending publication shares the same guard as session invalidation. */
    @Synchronized
    private fun publishForSession(session: RealtimeSession, publish: () -> Unit) {
        if (activeSession !== session) return
        authService.commitForAccountLease(session.owner, publish)
    }

    private suspend fun updateChatLastMessage(chatId: String, msg: Message, userId: String) {
        val chat = chatDao.getChatById(chatId) ?: return
        val messageAt = normalizeInstantText(msg.created_at)
        val clearedAt = normalizeInstantText(chat.cleared_at)
        if (chat.is_deleted && clearedAt.isNotBlank() && messageAt.isNotBlank() && !isAfterInstant(messageAt, clearedAt)) return
        chatDao.updateChat(chat.copy(
            last_message_id = msg.id,
            last_message_preview = previewFor(msg.content_type, msg.content),
            last_message_timestamp = msg.created_at,
            last_message_at = msg.created_at,
            last_message_sender_id = msg.sender_id,
            unread_count = if (!msg.sender_id.trim().equals(userId, ignoreCase = true) &&
                conectate.com.core.messaging.UnreadPolicy.isEligibleStatusForReadTransition(msg.status)) {
                chat.unread_count + 1
            } else chat.unread_count,
            is_deleted = false,
        ))
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
    private suspend fun refreshUserChatsFlow(session: RealtimeSession, force: Boolean = false) {
        requireSession(session)
        val now = System.currentTimeMillis()
        if (!force && now - session.lastListRefreshAt < 800) return
        val chats = withSessionStorage(session) { chatDao.getAllChatsWithLastMessage() }
        publishForSession(session) {
            session.lastListRefreshAt = now
            _userChats.value = chats
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
    @Synchronized
    fun clearAll() {
        val previous = activeSession
        activeSession = null
        previous?.cancel()
        ubuntuRealtimeClient.clearUserSubscriptions()
        ubuntuRealtimeClient.updateAccessToken("")
        // Preserve mutex identities until all old waiters have observed cancellation.
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
     * Parsea el contrato JSON de mensajes del servidor a objeto Message.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun parseMessageFromJson(record: JsonObject): Message {
        val parser = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        return parser.decodeFromJsonElement(Message.serializer(), record)
    }
}




/** Keep cancellation and session revocation inside Room, so neither can commit partial rows. */
internal suspend fun <T> withRealtimeAccountTransaction(
    database: AppDatabase,
    requireCurrent: () -> Unit,
    write: suspend () -> T,
): T = database.withTransaction {
    currentCoroutineContext().ensureActive()
    requireCurrent()
    val result = write()
    currentCoroutineContext().ensureActive()
    requireCurrent()
    result
}
