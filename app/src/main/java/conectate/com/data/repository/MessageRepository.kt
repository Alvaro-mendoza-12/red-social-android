package conectate.com.data.repository

import android.content.Context
import android.net.Uri
import conectate.com.BuildConfig
import conectate.com.core.concurrency.newSafeScope
import conectate.com.core.messaging.MessageStatusNormalizer
import conectate.com.data.model.Chat
import conectate.com.data.model.ChatParticipant
import conectate.com.data.model.ChatWithLastMessage
import conectate.com.data.model.Message
import conectate.com.data.repository.collection.CollectionDeleteRetrySupport
import conectate.com.data.repository.message.MessageCacheManager
import conectate.com.data.repository.message.MessageRemoteStore
import conectate.com.data.repository.message.MessageRealtimeSync
import conectate.com.data.repository.message.MessagePolicyEngine
import conectate.com.data.repository.message.MessageNotificationService
import conectate.com.data.repository.message.MessageUnreadCounter
import conectate.com.data.repository.message.MessageSessionStateStore
import conectate.com.data.repository.message.MessageMediaResolver
import conectate.com.data.repository.message.MessageReportStore
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.StorageService
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.LocalFallbackInboxItem
import conectate.com.data.local.entity.ChatEntity
import conectate.com.core.messaging.UnreadPolicy
import conectate.com.core.moderation.ContentModerationService
import conectate.com.core.moderation.ModerationContext
import conectate.com.core.moderation.ModerationVerdict
import conectate.com.core.observability.AppMetrics
import conectate.com.core.security.GuardedAction
import conectate.com.core.security.GuardOutcome
import conectate.com.core.security.InputSecurityPolicy
import conectate.com.core.security.OperationGuard
import conectate.com.domain.sync.ChatSyncDataSource
import conectate.com.util.ChatSoundPlayer
import conectate.com.util.Constants
import conectate.com.util.buildCallEventPreview
import conectate.com.util.buildConversationBackgroundPreview
import conectate.com.util.guessMimeTypeFromUri
import conectate.com.util.inferMimeTypeFromBytes
import conectate.com.util.isCallEventContentType
import conectate.com.util.resolveMediaMeta
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.realtime.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class MessageRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val storageService: StorageService,
    private val localFallbackApiClient: LocalFallbackApiClient,
    private val userRepository: UserRepository,
    private val chatDao: conectate.com.data.local.dao.ChatDao,
    private val userDao: conectate.com.data.local.dao.UserDao,
    private val messageDao: conectate.com.data.local.dao.MessageDao,
    private val moderationService: ContentModerationService,
    private val appMetrics: AppMetrics,
    private val operationGuard: OperationGuard,
    private val chatSoundPlayer: ChatSoundPlayer,
    @ApplicationContext private val context: Context,
    // Nuevos stores inyectados
    private val messageCacheManager: MessageCacheManager,
    private val messageRemoteStore: MessageRemoteStore,
    private val messageRealtimeSync: MessageRealtimeSync,
    private val messagePolicyEngine: MessagePolicyEngine,
    private val messageNotificationService: MessageNotificationService,
    private val messageUnreadCounter: MessageUnreadCounter,
    private val sessionStateStore: MessageSessionStateStore
) : ChatSyncDataSource {
    companion object {
        private const val MAX_FORWARD_TARGETS_PER_BATCH = 20
    }

    private val scope = newSafeScope("MessageRepository", Dispatchers.IO)
    private var json = Json { 
        ignoreUnknownKeys = true 
        coerceInputValues = true
        isLenient = true
    }
    private val transientNetworkRetryCodes = listOf("timeout", "timed out", "connection", "network", "502", "503", "504")

    // --- STATE ---
    
    // 1. User Chats List (Global)
    private val _userChats = MutableStateFlow<List<ChatWithLastMessage>>(emptyList())
    val userChats: Flow<List<ChatWithLastMessage>> = _userChats

    // 2. Messages per Chat (Active)
    // Key: chat_id, Value: StateFlow of Messages
    private val _activeChatMessages = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<List<Message>>>()

    // 3. Global Message Events (Pulse) - Fail-safe for UI updates
    val messageEvents = MutableSharedFlow<Message>()

    // 4. Per-chat Mutex to prevent race conditions during message list updates
    private val chatMutexes = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    
    private fun getMutexForChat(chatId: String): kotlinx.coroutines.sync.Mutex {
        return chatMutexes.getOrPut(chatId.trim().lowercase()) { kotlinx.coroutines.sync.Mutex() }
    }

    // 5. Throttle expensive history fetches to avoid reloading on every chat-list pulse.
    private val chatHistoryFetchTimestamps = ConcurrentHashMap<String, Long>()

    private var hasPurgedZombies = false

    // 6. Transient ID Mapper (FCM -> UUID) allows replies to work even during race conditions
    private val transientIdMap = ConcurrentHashMap<String, String>()
    private val sessionWriteGuard = SessionWriteGuard { authService.getCurrentUserId() }
    private val mediaResolver = MessageMediaResolver(
        storageService = storageService,
        authService = authService,
        supabaseConfig = supabaseConfig,
        sessionStateStore = sessionStateStore
    )
    private val reportStore = MessageReportStore(
        context = context,
        supabaseConfig = supabaseConfig,
        authService = authService,
        storageService = storageService,
        userRepository = userRepository,
        moderationService = moderationService,
        appMetrics = appMetrics,
        operationGuard = operationGuard
    )
    private var sessionStatusJob: Job? = null
    private var globalListenerJob: Job? = null

    private fun currentSessionUserId(): String? =
        SessionWriteGuard.normalizeAccountId(authService.getCurrentUserId())

    private fun captureSessionSnapshot(expectedUserId: String? = currentSessionUserId()): SessionWriteGuard.Snapshot =
        sessionWriteGuard.capture(expectedUserId)

    private fun isSessionSnapshotValid(
        snapshot: SessionWriteGuard.Snapshot,
        source: String
    ): Boolean {
        val valid = sessionWriteGuard.isValid(snapshot)
        if (!valid) {
            android.util.Log.w(
                "MessageRepository",
                "Discarding stale session write from $source. expected=${snapshot.accountId}, active=${currentSessionUserId()}"
            )
        }
        return valid
    }

    init {
        // One-time nuke of local DMs to ensure clean state after critical identity bug
        scope.launch {
            try {
                val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val hasNuked = prefs.getBoolean("identity_fix_nuke_done_v4", false)
                
                if (!hasNuked) {
                    android.util.Log.w("MessageRepository", "Nuclear Reset: Purging ALL local chats (One-time migration).")
                    chatDao.nukeAllChats()
                    messageDao.nukeAllMessages()
                    prefs.edit().putBoolean("identity_fix_nuke_done_v4", true).apply()
                }
                hasPurgedZombies = true
            } catch (e: Exception) {
                android.util.Log.e("MessageRepository", "Failed to nuke chats", e)
            }
        }
        
        // Start Global Realtime Listener using new MessageRealtimeSync component
        sessionStatusJob = scope.launch {
            supabaseConfig.client.auth.sessionStatus.collect { status ->
                globalListenerJob?.cancel()
                if (status is SessionStatus.Authenticated) {
                    var userId = (status.session.user?.id ?: "").trim().lowercase()
                    globalListenerJob = scope.launch {
                        android.util.Log.d("MessageRepository", "[RECEIVE-FIX] Session Authenticated ($userId): Starting MessageRealtimeSync")
                        messageRealtimeSync.start(userId)

                        launch {
                            messageRealtimeSync.messageEvents.collect { msg ->
                                messageEvents.emit(msg)
                            }
                        }

                        launch {
                            messageCacheManager.cachedChats.collect { chats ->
                                _userChats.value = chats
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Clear all in-memory data. Called during logout to prevent data leakage between users.
     */
    fun clearData() {
        android.util.Log.w("MessageRepository", "CLEAR DATA: Purging in-memory state...")
        sessionWriteGuard.invalidate()
        _userChats.value = emptyList()
        _activeChatMessages.clear()
        chatHistoryFetchTimestamps.clear()
        transientIdMap.clear()
        chatMutexes.clear()
        sessionStateStore.clearAll()
        // Nuevos stores
        messageCacheManager.clearAll()
        messageRealtimeSync.clearAll()
        messageUnreadCounter.clearCache()
        globalListenerJob?.cancel()
        sessionStatusJob?.cancel()
    }

    fun setActiveChatSession(chatId: String?, isVisible: Boolean) {
        sessionStateStore.setActiveChatSession(chatId, isVisible)
    }

    // --- REALTIME: Delegado a MessageRealtimeSync ---
    // Los métodos de realtime se manejan ahora en MessageRealtimeSync
    // Esta clase actúa como facade/orquestador

    private suspend fun handleGlobalMessageInsert(
        msg: Message,
        sessionUserId: String? = currentSessionUserId()
    ) {
        val normalizedMsg = msg.withNormalizedStatus()
        var cleanId = normalizedMsg.chat_id.trim().lowercase()
        var sessionSnapshot = captureSessionSnapshot(sessionUserId)
        if (!isSessionSnapshotValid(sessionSnapshot, "dm-insert-start:$cleanId")) return
        android.util.Log.d("MessageRepository", "Global insert received: ${normalizedMsg.content} for chat $cleanId")
        
        var currentUserId = sessionSnapshot.accountId
        
        // 1. ATOMIC PROCESSING (Room + UI Flow)
        // We lock for the specific chat to ensure Realtime and FCM events happen sequentially.
        scope.launch {
            var mutex = getMutexForChat(cleanId)
            mutex.withLock {
                if (!isSessionSnapshotValid(sessionSnapshot, "dm-insert-room:$cleanId")) return@withLock
                try {
                    val chatRecord = _userChats.value.find { it.chat.id.trim().lowercase() == cleanId }?.chat
                    var isActuallyGroup = chatRecord?.is_group == true
                    
                    android.util.Log.d("MessageRepository", "[RECEIVE-TRACE] Chat lookup: $cleanId, Found: ${chatRecord != null}, isGroup: $isActuallyGroup")
                    if (chatRecord != null && !chatRecord.is_group && chatRecord.participants.size < 2) {
                        android.util.Log.w("MessageRepository", "⚠️ Recibido mensaje para DM con <2 participantes: $cleanId")
                    }
                    
                    val similarMessages = messageDao.getRecentSimilarMessagesAgnostic(cleanId, normalizedMsg.content)
                    val existingReal = similarMessages.firstOrNull { it.id == normalizedMsg.id }
                    if (existingReal != null && existingReal.status != "sending") {
                        android.util.Log.d("MessageRepository", "♻️ Full duplicate skipped (already processed/synced): ${normalizedMsg.id}")
                        return@withLock
                    }
                    if (existingReal?.status == "sending") {
                         android.util.Log.d("MessageRepository", "♻️ Updating optimistic message with server-authoritative data: ${normalizedMsg.id}")
                    }
                    
                    // Check for FCM pending message to replace/map
                    val fcmPlaceholder = similarMessages.firstOrNull { it.id.startsWith("fcm_") }
                    if (fcmPlaceholder != null) {
                         android.util.Log.d("MessageRepository", "♻️ Swapping temporary ID ${fcmPlaceholder.id} -> ${normalizedMsg.id}")
                         transientIdMap[fcmPlaceholder.id] = normalizedMsg.id // STORE MAPPING
                         messageDao.deleteMessage(fcmPlaceholder.id)
                         
                         // Also remove from active flow immediately so UI doesn't flicker
                         val currentList = _activeChatMessages[cleanId]?.value ?: emptyList()
                         if (currentList.any { it.id == fcmPlaceholder.id }) {
                             _activeChatMessages[cleanId]?.tryEmit(currentList.filter { it.id != fcmPlaceholder.id })
                         }
                    }
                    
                    // 1.5. ALWAYS TRUST THE REALTIME SENDER ID
                    // Previous erroneous logic was overwriting sender_id based on a "similar" local message,
                    // which caused messages to appear as if 'I' sent them when it was the other person.
                    
                    // HYDRATION: Realtime/FCM payloads lack the 'reply_to' object. We must fetch it locally.
                    var hydratedMsg = normalizedMsg
                    if (normalizedMsg.reply_to_id != null && normalizedMsg.reply_to == null) {
                        val replyMsgEntity = messageDao.getMessageById(normalizedMsg.reply_to_id)
                        if (replyMsgEntity != null) {
                            hydratedMsg = normalizedMsg.copy(reply_to = replyMsgEntity.toDomain())
                            android.util.Log.d("MessageRepository", "💧 Hydrated reply_to for incoming message")
                        }
                    }
                    val finalizedMsg = hydratedMsg

                    if (!isSessionSnapshotValid(sessionSnapshot, "dm-insert-before-db:$cleanId")) return@withLock
                    // Save to Room
                    var entity = conectate.com.data.local.entity.MessageEntity.fromDomain(finalizedMsg, isSynced = true)
                    messageDao.insertMessage(entity)
                    
                    // 2. FLOW UPDATES (Atomic update inside Mutex)
                    if (!isSessionSnapshotValid(sessionSnapshot, "dm-insert-before-flow:$cleanId")) return@withLock
                    addMessageToFlowLogic(cleanId, finalizedMsg)

                    // 3. RE-ROUTE TO PULSE
                    if (!isSessionSnapshotValid(sessionSnapshot, "dm-insert-before-pulse:$cleanId")) return@withLock
                    messageEvents.emit(finalizedMsg)
                } catch (e: Exception) {
                    android.util.Log.e("MessageRepository", "❌ Room handling error", e)
                }
            }
        }
        
        if (!isSessionSnapshotValid(sessionSnapshot, "dm-insert-post-room:$cleanId")) return
        if (currentUserId != null) {
            var cleanCurrentUserId = currentUserId.trim().lowercase()
            if (normalizedMsg.sender_id.trim().lowercase() != cleanCurrentUserId) {
                if (sessionStateStore.isActiveChatVisible(cleanId) && !isCallEventContentType(normalizedMsg.content_type)) {
                    chatSoundPlayer.playReceive()
                }
                // I am the recipient: sent -> delivered immediately.
                // Read is handled strictly by viewport logic in ChatViewModel (visible + focused chat).
                scope.launch {
                    markMessageAsDelivered(cleanId, normalizedMsg.id)
                }
            }
        }
        
        // 4. CHAT LIST UPDATE
        if (currentUserId != null) {
            scope.launch {
                if (!isSessionSnapshotValid(sessionSnapshot, "dm-insert-chat-list:$cleanId")) return@launch
                var currentChats = _userChats.value.toMutableList()
                var cleanCurrentUserId = currentUserId.trim().lowercase()
                var idx = currentChats.indexOfFirst { it.chat.id.trim().lowercase() == cleanId }

                if (idx != -1) {
                    if (!isSessionSnapshotValid(sessionSnapshot, "dm-insert-chat-update:$cleanId")) return@launch
                    updateChatInList(currentChats, idx, normalizedMsg, cleanCurrentUserId)
                } else {
                    android.util.Log.d("MessageRepository", "New chat $cleanId discovered. Verifying with server...")
                    val chatRes = getChatById(cleanId)
                    var chat = chatRes.getOrNull()
                    if (!isSessionSnapshotValid(sessionSnapshot, "dm-insert-after-chat-fetch:$cleanId")) return@launch
                    
                    if (chat != null) {
                        // CRITICAL PRIVACY FIX: Only add chat if the current user is a participant.
                        // This prevents "Ghost Chats" from other users leaking into the UI.
                        var participants = chat.participants.map { it.trim().lowercase() }
                        if (!participants.contains(cleanCurrentUserId)) {
                             android.util.Log.e("MessageRepository", "BLOCKED LEAK: Message for chat ${chat.id} received but user is not a participant.")
                             return@launch
                        }

                        // RELAXED: Encouraging discovery of chats even if participant count is unexpected
                        if (!chat.is_group && participants.size < 2) {
                            android.util.Log.w("MessageRepository", "Discovering DM with <2 participants: ${chat.id}")
                        }
                        
                        var otherId = chat.participants.firstOrNull { it.trim().lowercase() != cleanCurrentUserId }
                        android.util.Log.d("MessageRepository", "[DISCOVERY-TRACE] Other participant for DM: $otherId")
                        if (normalizedMsg.sender_id.trim().lowercase() != cleanCurrentUserId) {
                            sessionStateStore.clearLocalReadOverride(cleanId)
                        }
                        
                        var cachedUser = otherId?.let { userDao.getUser(it) }
                        var resolvedName = cachedUser?.full_name?.ifBlank { cachedUser.username } ?: "Usuario"
                        var resolvedImage = cachedUser?.avatar_url
                        
                        val placeholder = ChatWithLastMessage(
                            chat = chat.copy(
                                group_name = if (chat.is_group) chat.group_name else resolvedName,
                                group_image = if (chat.is_group) chat.group_image else resolvedImage,
                                last_message = buildPreviewForContentType(normalizedMsg.content_type, normalizedMsg.content),
                                last_message_at = normalizedMsg.created_at ?: "", // NO injection here
                                participants = chat.participants // Ensure participants preserved
                            ),
                            lastMessage = normalizedMsg,
                            unreadCount = 1,
                            otherUserName = resolvedName,
                            otherUserImage = resolvedImage,
                            otherUserId = otherId,
                            otherUserVerified = cachedUser?.is_verified ?: false,
                            otherUserIsAdmin = cachedUser?.is_admin ?: false
                        )
                        
                        android.util.Log.d("MessageRepository", "[DISCOVERY-TRACE] Resolving chat ${chat.id} as $resolvedName")
                        
                        // Add to in-memory list and notify UI
                        val newList = (currentChats + placeholder).sortedByDescending { 
                            it.chat.safeLastMessageAt 
                        }
                        if (!isSessionSnapshotValid(sessionSnapshot, "dm-insert-before-list-emit:$cleanId")) return@launch
                        _userChats.value = newList
                        
                        // Persist to Room
                        if (!isSessionSnapshotValid(sessionSnapshot, "dm-insert-before-chat-cache:$cleanId")) return@launch
                        chatDao.insertChat(conectate.com.data.local.entity.ChatEntity.fromDomain(
                            chat = chat,
                            displayName = resolvedName,
                            displayImage = resolvedImage,
                            unreadCount = 1,
                            lastMsg = normalizedMsg.content,
                            lastTime = normalizedMsg.created_at,
                            otherUserId = otherId,
                            lastMsgId = normalizedMsg.id,
                            lastSenderId = normalizedMsg.sender_id,
                            nickname = null,
                            clearedAt = null
                        ))
                    }
                }
            }
        }
    }

    private suspend fun handleGlobalMessageUpdate(
        msg: Message,
        sessionUserId: String? = currentSessionUserId()
    ) {
        var sessionSnapshot = captureSessionSnapshot(sessionUserId)
        withContext(Dispatchers.IO) {
            if (!isSessionSnapshotValid(sessionSnapshot, "dm-update-start:${msg.id}")) return@withContext
            try {
                android.util.Log.d("MessageRepository", "🔄 UPDATE received for message ${msg.id}")
                
                // 1. Intentar obtener el mensaje existente de Room
                var existing = messageDao.getMessageById(msg.id)
                
                // --- [GOD MODE] LAZY CONTEXT RECOVERY ---
                if (existing == null) {
                    android.util.Log.d("MessageRepository", "Update for unknown message ${msg.id}. Fetching full context...")
                    val fullMsgRes = getMessageFromSupabase(msg.id)
                    val fullMsg = fullMsgRes.getOrNull()
                    if (fullMsg != null) {
                        messageDao.insertMessage(conectate.com.data.local.entity.MessageEntity.fromDomain(fullMsg, isSynced = true))
                        existing = messageDao.getMessageById(msg.id)
                    }
                }

                if (existing == null) {
                    android.util.Log.w("MessageRepository", "Ignoring update for unknown message ${msg.id}")
                    return@withContext
                }

                // 2. SMART MERGE: Solo aplicar campos que realmente cambiaron
                // El problema anterior era que si un campo no venía en el payload, se reseteaba a su valor por defecto
                val currentDomain = existing.toDomain()
                val mergedReceipt = mergeReceiptState(
                    currentStatus = currentDomain.status,
                    currentDeliveredAt = currentDomain.delivered_at,
                    currentReadAt = currentDomain.read_at,
                    incomingStatus = msg.status,
                    incomingDeliveredAt = msg.delivered_at,
                    incomingReadAt = msg.read_at
                )
                
                // Para campos booleanos críticos, solo actualizamos si el valor es true
                // (porque Supabase solo envía los campos que cambiaron, y si is_edited=true o deleted_for_all=true,
                // esos son los únicos casos que nos importan)
                val finalMsg = currentDomain.copy(
                    chat_id = if (msg.chat_id.isNotEmpty()) msg.chat_id else currentDomain.chat_id,
                    sender_id = if (msg.sender_id.isNotEmpty()) msg.sender_id else currentDomain.sender_id,
                    content = if (msg.content.isNotEmpty()) msg.content else currentDomain.content,
                    // CRITICAL FIX: Solo actualizar is_edited si viene como true (una vez editado, siempre editado)
                    is_edited = msg.is_edited || currentDomain.is_edited,
                    // CRITICAL FIX: Solo actualizar deleted_for_all si viene como true
                    deleted_for_all = msg.deleted_for_all || currentDomain.deleted_for_all,
                    deleted_by = (currentDomain.deleted_by + msg.deleted_by).distinct().map { it.lowercase() },
                    status = mergedReceipt.status,
                    delivered_at = mergedReceipt.deliveredAt,
                    read_at = mergedReceipt.readAt
                )

                var cleanId = finalMsg.chat_id.trim().lowercase()
                if (cleanId.isEmpty()) {
                    android.util.Log.w("MessageRepository", "⚠️ Update ignorado: chat_id vacío en mensaje ${msg.id}")
                    return@withContext
                }

                android.util.Log.d("MessageRepository", "📝 Applying update: content='${finalMsg.content}', is_edited=${finalMsg.is_edited}, deleted_for_all=${finalMsg.deleted_for_all}")

                // 3. Guardar en Room
                if (!isSessionSnapshotValid(sessionSnapshot, "dm-update-before-db:${msg.id}")) return@withContext
                messageDao.insertMessage(conectate.com.data.local.entity.MessageEntity.fromDomain(finalMsg, isSynced = true))
                
                // 4. Actualizar flujo UI (CRÍTICO para que el otro usuario vea el cambio)
                if (!isSessionSnapshotValid(sessionSnapshot, "dm-update-before-flow:${msg.id}")) return@withContext
                updateMessageInFlow(cleanId, finalMsg)
                
                android.util.Log.d("MessageRepository", "✅ Update procesado y aplicado a UI: ${msg.id} en $cleanId")
            } catch (e: Exception) {
                android.util.Log.e("MessageRepository", "❌ Error procesando update global", e)
            }
        }
    }

    private suspend fun getMessageFromSupabase(messageId: String): Result<Message> {
        return try {
            var token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/messages") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("id", "eq.$messageId")
            }
            if (response.status.isSuccess()) {
                var list = response.body<List<Message>>()
                if (list.isNotEmpty()) Result.success(list.first().withNormalizedStatus()) else Result.failure(Exception("Not found"))
            } else Result.failure(Exception("Supabase error"))
        } catch (e: Exception) { Result.failure(e) }
    }

    private suspend fun updateChatInList(currentChats: MutableList<ChatWithLastMessage>, index: Int, msg: Message, currentUserId: String) {
        var existing = currentChats[index]
        
        // Unread Count: Normalize IDs to prevent case-sensitivity issues
        var cleanSenderId = msg.sender_id.trim().lowercase()
        var cleanCurrentUserId = currentUserId.trim().lowercase()
        val shouldIncrementUnread = UnreadPolicy.shouldIncrementUnread(
            senderId = cleanSenderId,
            currentUserId = cleanCurrentUserId,
            isConversationVisible = sessionStateStore.isActiveChatVisible(msg.chat_id)
        )
        if (shouldIncrementUnread) {
            sessionStateStore.clearLocalReadOverride(msg.chat_id)
        }
        val newUnread = when {
            shouldIncrementUnread -> existing.unreadCount + 1
            cleanSenderId == cleanCurrentUserId -> existing.unreadCount
            else -> 0
        }
        
        // Construct preview
        var preview = buildPreviewForContentType(msg.content_type, msg.content)

        currentChats[index] = existing.copy(
            lastMessage = msg,
            unreadCount = newUnread,
            chat = existing.chat.copy(
                last_message = preview,
                last_message_at = msg.created_at
            )
        )
        _userChats.value = sortChatsByPriority(currentChats)

        // Persist immediately so Room emits the reordered list without waiting on a nested launch.
        var chatId = msg.chat_id.trim().lowercase()
        try {
            chatDao.updateLastMessage(chatId, preview, msg.safeCreatedAt)
            if (shouldIncrementUnread) {
                chatDao.incrementUnreadCount(chatId)
            } else if (sessionStateStore.isActiveChatVisible(chatId)) {
                chatDao.markAsRead(chatId)
            }
        } catch (e: Exception) {
            android.util.Log.e("MessageRepository", "Global insert Room update failed", e)
        }
    }

    private fun normalizeDate(date: String): String {
        if (date.isBlank()) return ""
        return try {
            // Standardize to T separator and remove any trailing info after seconds for basic comparison if needed, 
            // but for ISO strings, cleaning is safer.
            date.trim().replace(" ", "T")
        } catch (e: Exception) { "" }
    }

    private data class ChatParticipantState(
        val clearedAt: String?,
        val isMuted: Boolean,
        val isPinned: Boolean,
        val isArchived: Boolean,
        val isFavorite: Boolean
    )

    private fun sortChatsByPriority(chats: List<ChatWithLastMessage>): List<ChatWithLastMessage> {
        return chats.sortedWith(
            compareBy<ChatWithLastMessage> { it.chat.is_archived }
                .thenByDescending { it.chat.is_pinned }
                .thenByDescending { it.chat.is_favorite }
                .thenByDescending { normalizeDate(it.chat.last_message_at ?: it.chat.created_at) }
        )
    }

    private fun isMissingPreferenceColumnError(body: String): Boolean {
        var normalized = body.lowercase()
        val referencesPreferenceColumns = normalized.contains("is_muted") ||
            normalized.contains("is_pinned") ||
            normalized.contains("is_archived") ||
            normalized.contains("is_favorite")
        val looksLikeMissingColumn = normalized.contains("column") || normalized.contains("42703")
        return referencesPreferenceColumns && looksLikeMissingColumn
    }

    private fun ChatEntity.belongsToUser(activeUserId: String): Boolean {
        var cleanUserId = activeUserId.trim().lowercase()
        if (cleanUserId.isBlank()) return false
        val participantIds = runCatching {
            Json.decodeFromString<List<String>>(participantsJson)
        }.getOrDefault(emptyList())
            .map { it.trim().lowercase() }
        return participantIds.contains(cleanUserId)
    }

    // --- CHAT LIST ---

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getUserChatsFlow(): Flow<List<ChatWithLastMessage>> {
        scope.launch { getUserChats() } // Background sync

        return authService.observeSession()
            .map { session ->
                session?.user?.id?.trim()?.lowercase()
                    ?: authService.getCurrentUserId()?.trim()?.lowercase()
            }
            .distinctUntilChanged()
            .flatMapLatest { activeUserId ->
                if (activeUserId.isNullOrBlank()) {
                    flowOf(emptyList())
                } else {
                    chatDao.getAllChatsFlow()
                        .flatMapLatest { entities ->
                            val visibleEntities = entities.filter { it.chat.belongsToUser(activeUserId) }
                            val lastMessageIds = visibleEntities
                                .mapNotNull { it.chat.last_message_id?.trim() }
                                .filter { it.isNotBlank() }
                                .distinct()

                            if (lastMessageIds.isEmpty()) {
                                flowOf(visibleEntities.map { it.toDomainWithDetails() })
                            } else {
                                messageDao.getMessagesByIdsFlow(lastMessageIds).map { messageEntities ->
                                    val messageMap = messageEntities.associateBy { it.id.trim().lowercase() }
                                    visibleEntities.map { entity ->
                                        val overrideMessage = entity.chat.last_message_id
                                            ?.trim()
                                            ?.lowercase()
                                            ?.let(messageMap::get)
                                            ?.toDomain()
                                        entity.toDomainWithDetails(overrideMessage)
                                    }
                                }
                            }
                        }
                }
                }
            .distinctUntilChanged()
    }

    suspend fun getUserChats(): Result<List<ChatWithLastMessage>> {
        return try {
            var userId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))
            var cleanUserId = userId.trim().lowercase()
            var sessionSnapshot = captureSessionSnapshot(userId)
            var accessToken = authService.ensureValidAccessToken()
                .getOrElse { return Result.failure(it) }

            // 0. Fetch Local Chats first (for fallback/optimistic)
            val allLocalEntities = chatDao.getAllChatsSync()
            val visibleLocalEntities = allLocalEntities.filter { it.belongsToUser(cleanUserId) }
            val allLocalChatsDomain = visibleLocalEntities.map { it.toDomain() }
            
            // PRE-LOAD: Ensure UI has local data while we fetch remote
            if (_userChats.value.isEmpty() && isSessionSnapshotValid(sessionSnapshot, "get-user-chats-preload")) {
                val preLoad = visibleLocalEntities
                    .sortedWith(
                        compareBy<ChatEntity> { it.is_archived }
                            .thenByDescending { it.is_pinned }
                            .thenByDescending { normalizeDate(it.last_message_at ?: "") }
                    )
                    .map { it.toDomainWithDetails() }
                _userChats.value = preLoad
                android.util.Log.d("MessageRepository", "📦 Pre-loaded ${preLoad.size} chats from Room")
            }

            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val localInbox = localFallbackApiClient.loadMessagesInbox(accessToken, "chats")
                    .getOrElse { error ->
                        android.util.Log.w("MessageRepository", "Local fallback inbox failed", error)
                        if (allLocalChatsDomain.isEmpty()) {
                            return Result.failure(error)
                        }
                        val localOnly = visibleLocalEntities.map { it.toDomainWithDetails() }
                        if (isSessionSnapshotValid(sessionSnapshot, "local-fallback-inbox-local-only")) {
                            _userChats.value = localOnly
                        }
                        return Result.success(localOnly)
                    }

                val mappedChats = localInbox.items
                    .filter { it.kind == "chat" }
                    .map { item -> mapLocalFallbackInboxItem(item, cleanUserId) }
                    .let(::sortChatsByPriority)

                if (isSessionSnapshotValid(sessionSnapshot, "local-fallback-inbox-result")) {
                    _userChats.value = mappedChats
                    scope.launch {
                        runCatching {
                            chatDao.insertChats(
                                mappedChats.map { chat ->
                                    ChatEntity.fromDomain(
                                        chat = chat.chat,
                                        displayName = chat.otherUserName,
                                        displayImage = chat.otherUserImage,
                                        unreadCount = chat.unreadCount,
                                        lastMsg = chat.chat.last_message,
                                        lastTime = chat.chat.last_message_at,
                                        otherUserId = chat.otherUserId,
                                        lastMsgId = chat.lastMessage?.id,
                                        lastSenderId = chat.lastMessage?.sender_id,
                                        nickname = chat.nickname,
                                        clearedAt = chat.clearedAt
                                    )
                                }
                            )
                        }.onFailure {
                            android.util.Log.w("MessageRepository", "Could not cache local fallback inbox", it)
                        }
                    }
                }
                return Result.success(mappedChats)
            }

            // 1. Fetch Remote Chats (Explicitly lowercased filter)
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/chats") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("participants", "cs.{\"$cleanUserId\"}")
                parameter("order", "last_message_at.desc.nullslast")
            }

            val remoteChats: List<Chat> = if (response.status.isSuccess()) {
                response.body()
            } else {
                val responseBody = runCatching { response.bodyAsText() }.getOrDefault("")
                android.util.Log.w(
                    "MessageRepository",
                    "getUserChats remote fetch failed ${response.status.value}: $responseBody"
                )
                if (allLocalChatsDomain.isEmpty()) {
                    return Result.failure(Exception("No se pudieron cargar tus chats en este momento"))
                }
                emptyList()
            }
            
            // 2. Combine and Normalize
            val allChatsJoined = (remoteChats + allLocalChatsDomain)
                .distinctBy { it.id.trim().lowercase() }
            
            if (allChatsJoined.isEmpty()) {
                if (isSessionSnapshotValid(sessionSnapshot, "get-user-chats-empty")) {
                    _userChats.value = emptyList()
                }
                return Result.success(emptyList())
            }

            // 3. Enrich Metadata in BULK (Chunked to avoid URL limits)
            val activeChatIds = allChatsJoined.map { it.id.trim().lowercase() }
            val otherUserIds = allChatsJoined
                .flatMap { chat -> chat.participants.map { it.trim().lowercase() } }
                .filter { it.isNotBlank() && it != cleanUserId }
                .distinct()
            
            // A. Get Profiles
            val profilesMap = if (otherUserIds.isNotEmpty()) {
                val userRes = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("id", "in.(${otherUserIds.joinToString(",")})")
                    parameter("select", "id,username,display_name,full_name,avatar_url,is_verified,is_admin,email")
                }
                if (userRes.status.isSuccess()) {
                    userRes.body<List<conectate.com.data.model.User>>().associateBy { it.id.trim().lowercase() }
                } else emptyMap()
            } else emptyMap()

            // A2. Get Nicknames
            val nicknamesMap = if (otherUserIds.isNotEmpty()) {
                val nickRes = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/nicknames") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("user_id", "eq.$cleanUserId")
                    parameter("target_user_id", "in.(${otherUserIds.joinToString(",")})")
                }
                if (nickRes.status.isSuccess()) {
                    nickRes.body<List<JsonObject>>()
                        .mapNotNull { nicknameJson ->
                            var targetId = nicknameJson["target_user_id"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?.trim()
                                ?.lowercase()
                                ?: return@mapNotNull null
                            targetId to nicknameJson["nickname"]?.jsonPrimitive?.contentOrNull
                        }
                        .toMap()
                } else emptyMap()
            } else emptyMap()

            // A3. Get per-user participant state (clear, mute, pin, archive)
            val participantStateMap = mutableMapOf<String, ChatParticipantState>()
            if (activeChatIds.isNotEmpty()) {
                activeChatIds.chunked(30).forEach { chunk ->
                    try {
                        var partRes = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/chat_participants") {
                            header("Authorization", "Bearer $accessToken")
                            header("apikey", supabaseConfig.supabaseAnonKey)
                            parameter("chat_id", "in.(${chunk.joinToString(",")})")
                            parameter("user_id", "eq.$cleanUserId")
                            parameter("select", "chat_id,cleared_at,is_muted,is_pinned,is_archived,is_favorite")
                        }
                        val participantRows: List<JsonObject> = if (partRes.status.isSuccess()) {
                            partRes.body()
                        } else {
                            val partBody = runCatching { partRes.bodyAsText() }.getOrDefault("")
                            if (isMissingPreferenceColumnError(partBody)) {
                                val fallbackSelect = if (partBody.contains("is_favorite", ignoreCase = true)) {
                                    "chat_id,cleared_at,is_muted,is_pinned,is_archived"
                                } else {
                                    "chat_id,cleared_at"
                                }
                                val fallbackRes = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/chat_participants") {
                                    header("Authorization", "Bearer $accessToken")
                                    header("apikey", supabaseConfig.supabaseAnonKey)
                                    parameter("chat_id", "in.(${chunk.joinToString(",")})")
                                    parameter("user_id", "eq.$cleanUserId")
                                    parameter("select", fallbackSelect)
                                }
                                if (fallbackRes.status.isSuccess()) fallbackRes.body() else emptyList()
                            } else {
                                emptyList()
                            }
                        }

                        participantRows.forEach chatParticipantLoop@{ participantJson ->
                            var cid = participantJson["chat_id"]?.jsonPrimitive?.content?.trim()?.lowercase()
                                ?: return@chatParticipantLoop
                            var clearedAt = participantJson["cleared_at"]?.jsonPrimitive?.contentOrNull
                            var isMuted = participantJson["is_muted"]?.jsonPrimitive?.booleanOrNull ?: false
                            var isPinned = participantJson["is_pinned"]?.jsonPrimitive?.booleanOrNull ?: false
                            var isArchived = participantJson["is_archived"]?.jsonPrimitive?.booleanOrNull ?: false
                            var isFavorite = participantJson["is_favorite"]?.jsonPrimitive?.booleanOrNull ?: false
                            participantStateMap[cid] = ChatParticipantState(
                                clearedAt = clearedAt,
                                isMuted = isMuted,
                                isPinned = isPinned,
                                isArchived = isArchived,
                                isFavorite = isFavorite
                            )
                        }
                    } catch(e: Exception) {}
                }
            }

            // B. Fetch Last Messages for all chats in CHUNKS (to avoid URL truncation)
            val lastMessagesMap = mutableMapOf<String, Message>()
            if (activeChatIds.isNotEmpty()) {
                activeChatIds.chunked(30).forEach { chunk ->
                    try {
                        val msgRes = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/messages") {
                            header("Authorization", "Bearer $accessToken")
                            header("apikey", supabaseConfig.supabaseAnonKey)
                            parameter("chat_id", "in.(${chunk.joinToString(",")})")
                            parameter("order", "created_at.desc")
                            parameter("limit", "100") // v22.4: Reduced from 1000 to 100 to save resources
                        }
                        if (msgRes.status.isSuccess()) {
                            msgRes.body<List<Message>>()
                                .groupBy { it.chat_id.trim().lowercase() }
                                .forEach { (id, msgs) -> if (!lastMessagesMap.containsKey(id)) lastMessagesMap[id] = msgs.first() }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MessageRepository", "Chunked last msg fetch failed", e)
                    }
                }
            }

            // C. Fetch Unread Counts in CHUNKS
            val unreadCountsMap = mutableMapOf<String, Int>()
            if (activeChatIds.isNotEmpty()) {
                activeChatIds.chunked(30).forEach { chunk ->
                    try {
                        var countRes = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/messages") {
                            header("Authorization", "Bearer $accessToken")
                            header("apikey", supabaseConfig.supabaseAnonKey)
                            parameter("select", "chat_id,read_at,sender_id,status")
                            parameter("chat_id", "in.(${chunk.joinToString(",")})")
                            parameter("sender_id", "neq.$cleanUserId")
                            parameter("read_at", "is.null")
                        }
                        if (countRes.status.isSuccess()) {
                            val unreadMsgs = countRes.body<List<JsonObject>>()
                            unreadMsgs.forEach unreadLoop@{ json ->
                                var cid = json["chat_id"]?.jsonPrimitive?.content?.trim()?.lowercase()
                                    ?: return@unreadLoop
                                var senderId = json["sender_id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                                    ?: return@unreadLoop
                                var status = json["status"]?.jsonPrimitive?.contentOrNull
                                var readAt = json["read_at"]?.jsonPrimitive?.contentOrNull
                                val isUnread = MessageStatusNormalizer.isUnread(status, readAt)
                                if (senderId != cleanUserId && isUnread) {
                                    unreadCountsMap[cid] = (unreadCountsMap[cid] ?: 0) + 1
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MessageRepository", "Chunked unread count fetch failed", e)
                    }
                }
            }

            // 3. Construct Final Objects
            val enrichedChats = allChatsJoined.map { chat ->
                var cleanChatId = chat.id.trim().lowercase()
                var isActuallyGroup = chat.is_group || chat.participants.size != 2
                var participants = chat.participants.map { it.trim().lowercase() }
                
                var otherId = if (!isActuallyGroup) {
                    participants.firstOrNull { it != userId.trim().lowercase() }
                } else null
                
                var profile = otherId?.let { profilesMap[it] }
                var nickname = otherId?.let { nicknamesMap[it] }
                val participantState = participantStateMap[cleanChatId]
                var clearedAt = participantState?.clearedAt
                var isMuted = participantState?.isMuted ?: chat.is_muted
                var isPinned = participantState?.isPinned ?: chat.is_pinned
                var isArchived = participantState?.isArchived ?: chat.is_archived
                var isFavorite = participantState?.isFavorite ?: chat.is_favorite
                
                val realLastMsg = lastMessagesMap[cleanChatId]
                val remoteUnread = unreadCountsMap[cleanChatId] ?: 0
                var unread = if (sessionStateStore.hasLocalReadOverride(cleanChatId)) 0 else remoteUnread

                // NUCLEAR FIX: Always compare Server vs Local to find the TRUE latest message
                // The server might return stale data (2025), but Room has the "hola" we just sent (2026).
                val localLast = messageDao.getLatestMessageSync(cleanChatId)?.toDomain()
                
                var finalLastMsg = realLastMsg
                if (localLast != null) {
                    val localTime = normalizeDate(localLast.created_at ?: "")
                    val serverTime = normalizeDate(realLastMsg?.created_at ?: "")
                    
                    if (serverTime.isEmpty() || localTime >= serverTime) {
                        finalLastMsg = localLast
                    }
                }

                val remoteMetaAt = normalizeDate(chat.last_message_at ?: chat.created_at)
                val realMsgAt = normalizeDate(finalLastMsg?.created_at ?: "")
                
                // If the message (local or remote) is newer than the chat metadata, use the message date.
                // Otherwise fall back to chat metadata (only if we have no message).
                val finalLastAt = if (finalLastMsg != null && realMsgAt >= remoteMetaAt) {
                    finalLastMsg.created_at
                } else {
                    // Try to respect the newest date we have seen anywhere
                    if (realMsgAt > remoteMetaAt) finalLastMsg?.created_at ?: remoteMetaAt else remoteMetaAt
                }
                
                // Content for display
                val finalLastContent = when {
                    finalLastMsg != null -> buildPreviewForContentType(finalLastMsg.content_type, finalLastMsg.content)
                    else -> chat.last_message ?: "Sin mensajes"
                }

                // TRACING
                if (cleanChatId.contains("bonny", ignoreCase = true)) {
                    android.util.Log.d("MessageRepository", "[DATE-TRACE-FINAL] Chat $cleanChatId: DisplayMsg=${finalLastMsg?.content}, Time=$finalLastAt")
                }

                val resolvedDisplayName = if (isActuallyGroup) {
                    chat.group_name ?: "Chat del Sistema"
                } else {
                    profile?.displayName ?: profile?.username ?: "Usuario"
                }
                
                var resolvedImage = if (isActuallyGroup) chat.group_image else profile?.avatar_url

                ChatWithLastMessage(
                    chat = chat.copy(
                        is_group = isActuallyGroup,
                        group_name = resolvedDisplayName,
                        group_image = resolvedImage,
                        last_message = finalLastContent,
                        last_message_at = finalLastAt,
                        nickname = nickname,
                        is_muted = isMuted,
                        is_pinned = isPinned,
                        is_archived = isArchived,
                        is_favorite = isFavorite,
                        cleared_at = clearedAt ?: chat.cleared_at
                    ),
                    lastMessage = finalLastMsg,
                    unreadCount = unread,
                    otherUserName = resolvedDisplayName,
                    otherUserImage = resolvedImage,
                    otherUserId = otherId,
                    otherUserVerified = profile?.is_verified ?: false,
                    otherUserIsAdmin = profile?.is_admin ?: false,
                    nickname = nickname,
                    clearedAt = clearedAt
                )
            }

            // 3. Deduplicate and MERGE (Aggressive fusion of duplicate chats)
            val distinctList = enrichedChats
                .groupBy { 
                    if (it.chat.is_group) "group_${it.chat.id}" 
                    else {
                        var participants = it.chat.participants
                            .map { p -> p.trim().lowercase() }
                            .sorted()
                        if (participants.size == 2) "dm_${participants.joinToString("_")}"
                        else "ghost_dm_${it.chat.id}"
                    }
                }
                .map { (key, entries) -> 
                    // Pick the one with the NEWEST activity (based on effective last_message_at we just set)
                    val best = entries.maxByOrNull { normalizeDate(it.chat.last_message_at ?: it.chat.created_at) }
                        ?: entries.first()
                    val totalUnread = entries.sumOf { it.unreadCount }
                    
                    if (entries.size > 1) {
                         android.util.Log.w("MessageRepository", "Merging ${entries.size} duplicate chats for $key. Winner: ${best.chat.id}")
                    }
                    
                    best.copy(unreadCount = totalUnread)
                }
                .filter { it.chat.participants.isNotEmpty() || it.chat.is_group }
                .let(::sortChatsByPriority)

            if (distinctList.size < enrichedChats.size) {
                 android.util.Log.w("MessageRepository", "Filtered out ${enrichedChats.size - distinctList.size} invalid/ghost chats")
            }

            // 4. Smart Merge (Preserve Live Data + Optimistic Local Chats)
            val currentMap = _userChats.value.associateBy { it.chat.id }
            val remoteMap = distinctList.associateBy { it.chat.id }
            
            // A. Merge Remote with Cache (Prefer Cache if fresher)
            val mergedRemote = distinctList.map { fetched ->
                var cached = currentMap[fetched.chat.id]
                val cachedTime = cached?.lastMessage?.safeCreatedAt
                val fetchedTime = fetched.lastMessage?.safeCreatedAt
                
                val useCached = (cached != null && cached.lastMessage != null) && (
                    fetched.lastMessage == null || 
                    (cachedTime != null && fetchedTime != null && cachedTime > fetchedTime)
                )

                if (useCached) {
                    val safeCached = cached ?: return@map fetched
                    val safeCachedLastMessage = safeCached.lastMessage ?: return@map fetched
                    android.util.Log.d("MessageRepository", "🔥 Smart Merge: PRESERVING fresher cached message for ${fetched.chat.id} (Cached: $cachedTime vs Remote: $fetchedTime)")
                    fetched.copy(
                        lastMessage = safeCachedLastMessage,
                        otherUserName = if (fetched.otherUserName == "Usuario" || fetched.otherUserName == "Cargando...") safeCached.otherUserName else fetched.otherUserName,
                        otherUserImage = if (fetched.otherUserImage == null) safeCached.otherUserImage else fetched.otherUserImage,
                        chat = fetched.chat.copy(
                            last_message = safeCached.chat.last_message,
                            last_message_at = safeCached.chat.last_message_at ?: safeCachedLastMessage.created_at ?: fetched.chat.last_message_at,
                            last_message_id = safeCached.chat.last_message_id,
                            group_name = if (fetched.chat.group_name == "Usuario" || fetched.chat.group_name == "Cargando...") safeCached.chat.group_name else fetched.chat.group_name
                        )
                    )
                } else {
                    if (cached != null) {
                         // Optional: Debug log if needed, or remove for production
                         // android.util.Log.d("MessageRepository", "♻️ Smart Merge: TRUSTING remote post (Cached was null or older)")
                    }
                    fetched
                }
            }
            
            // B. Add Local-Only (Optimistic) Chats
            // These are chats we created locally but Supabase hasn't returned yet (Latency/RLS)
            val optimisticChats = visibleLocalEntities.filter { local -> 
                !remoteMap.containsKey(local.id.trim().lowercase()) 
            }.map { it.toDomainWithDetails() }

            // C. Final List
            var finalMergedList = (mergedRemote + optimisticChats)
                .let(::sortChatsByPriority) // ROBUST SORT (pinned first, then most recent)
                .distinctBy { it.chat.id.trim().lowercase() }

            // 5. UPDATE ROOM CACHE (PRESERVING DELETED CHATS) & AUTO-REPAIR "Usuario"
            scope.launch {
                if (!isSessionSnapshotValid(sessionSnapshot, "get-user-chats-cache-start")) return@launch
                try {
                    // CRITICAL: Get list of locally deleted chats first
                    val allLocalChats = chatDao.getAllChatsSync() // Need sync version
                    val deletedChatIds = allLocalChats
                        .filter { it.is_deleted }
                        .map { it.id.trim().lowercase() }
                        .toSet()
                        
                    // AUTO-RESURRECTION: Only if server data is strictly NEWER than local
                    val chatsToResurrect = allLocalChats.filter { local ->
                         if (!local.is_deleted) return@filter false
                         var remoteChat = remoteMap[local.id.trim().lowercase()]
                         if (remoteChat == null) return@filter false
                         
                         val remoteAt = normalizeDate(remoteChat.lastMessage?.safeCreatedAt ?: remoteChat.chat.safeLastMessageAt)
                         val localAt = normalizeDate(local.last_message_at ?: "")
                         
                         // Resurrection criteria: Server has a message newer than what we had when deleted
                         // String comparison is now safe because normalizeDate handles formats
                         remoteAt > localAt && localAt.isNotEmpty()
                    }
                    
                    if (chatsToResurrect.isNotEmpty()) {
                        android.util.Log.w("MessageRepository", "🧟 RESURRECTING ${chatsToResurrect.size} chats with NEW activity!")
                        chatsToResurrect.forEach { chat ->
                            chatDao.insertChat(chat.copy(is_deleted = false))
                        }
                    }
                    
                    if (deletedChatIds.isNotEmpty()) {
                        android.util.Log.d("MessageRepository", "Found ${deletedChatIds.size} locally deleted chats. Filtering from UI and Cache.")
                        // Filter from the list we are about to display
                        finalMergedList = finalMergedList.filter { 
                            it.chat.id.trim().lowercase() !in deletedChatIds 
                        }
                    }
                    
                    // Update UI ONLY after filtering
                    if (!isSessionSnapshotValid(sessionSnapshot, "get-user-chats-before-emit")) return@launch
                    _userChats.value = finalMergedList
                    
                    // Filter out chats that user has deleted locally (Redundant but safe for DB update list)
                    val chatsToUpdate = finalMergedList // Already filtered above
                    
                    // Update Users first to avoid FK issues
                    if (!isSessionSnapshotValid(sessionSnapshot, "get-user-chats-before-users")) return@launch
                    val userEntities = chatsToUpdate.mapNotNull { chat ->
                        var otherId = chat.chat.participants.firstOrNull { it != userId }
                        if (otherId != null && chat.otherUserName != "Usuario") {
                            conectate.com.data.local.entity.UserEntity(
                                id = otherId,
                                username = chat.otherUserName ?: "",
                                full_name = chat.otherUserName ?: "",
                                avatar_url = chat.otherUserImage,
                                bio = null
                            )
                        } else null
                    }
                    if (userEntities.isNotEmpty()) {
                        userDao.insertUsers(userEntities)
                    }

                    // --- PURGE INVALID CACHE (Ghost Chats / Missing Participant) ---
                    // If a chat exists locally but Supabase says we aren't participants, or it doesn't return it
                    // and it's not a pending local chat, we dump it to protect privacy.
                    val remoteIds = chatsToUpdate.map { it.chat.id.trim().lowercase() }.toSet()
                    val currentCleanUserId = userId.trim().lowercase()
                    
                    // CRITICAL: We only purge if we actually got a SUCCESSFUL non-empty list from the server
                    // OR if we are 100% sure the user is NOT a participant.
                    val shouldPerformCleanup = chatsToUpdate.isNotEmpty()
                    
                    if (shouldPerformCleanup) {
                        allLocalChats.forEach { local: ChatEntity ->
                            val cleanLocalId = local.id.trim().lowercase()
                            var participants = try { Json.decodeFromString<List<String>>(local.participantsJson) } catch(e: Exception) { emptyList<String>() }
                            val isParticipant = participants.map { it.trim().lowercase() }.contains(currentCleanUserId)
                            
                            // We purge if:
                            // 1. User is NOT a participant (Clear Leak)
                            // 2. Chat is NOT in remote and NOT a pending local chat
                            val isInRemote = remoteIds.contains(cleanLocalId)
                            val isPendingCheck = local.last_message_preview == "sending..." // Simple check
                            
                            if (!isParticipant) {
                                // SAFETY: Do NOT delete local data just because server returned weird participant list (RLS/Latency)
                                android.util.Log.e("MessageRepository", "PURGE SKIPPED (SAFE MODE): User not in participants list of $cleanLocalId")
                                // chatDao.deleteChat(cleanLocalId)
                                // messageDao.deleteMessagesByChatId(cleanLocalId)
                            } else if (!isInRemote && !isPendingCheck && !local.is_group) {
                                // If it's a DM, but server doesn't know about it, and it's not pending, it might be stale
                                // but we are careful. Only purge if we have a robust remote set.
                                android.util.Log.w("MessageRepository", "STALE PURGE SKIPPED: Chat $cleanLocalId not in remote sync list")
                                // chatDao.deleteChat(cleanLocalId)
                            }
                        }
                    }

                    // Update Chats (excluding deleted ones)
                    if (!isSessionSnapshotValid(sessionSnapshot, "get-user-chats-before-chats")) return@launch
                    val chatEntities = chatsToUpdate.map { chat ->
                        var otherId = if (!chat.chat.is_group) chat.chat.participants.firstOrNull { it != userId } else null
                        conectate.com.data.local.entity.ChatEntity.fromDomain(
                            chat = chat.chat,
                            displayName = chat.otherUserName,
                            displayImage = chat.otherUserImage,
                            unreadCount = chat.unreadCount,
                            lastMsg = chat.chat.last_message,
                            lastTime = chat.chat.last_message_at,
                            otherUserId = otherId,
                            lastMsgId = chat.lastMessage?.id,
                            lastSenderId = chat.lastMessage?.sender_id,
                            nickname = chat.nickname,
                            clearedAt = chat.clearedAt
                        )
                    }
                    chatDao.insertChats(chatEntities)
                    android.util.Log.d("MessageRepository", "Room cache updated with ${chatEntities.size} chats.")
                    
                    // --- AUTO-REPAIR "Usuario" CHATS ---
                    // If we find any chat named "Usuario", we assume it's broken and try to fix it.
                    val brokenChats = chatsToUpdate.filter { 
                        !it.chat.is_group && (it.otherUserName == "Usuario" || it.otherUserName.isNullOrBlank())
                    }
                    
                    if (brokenChats.isNotEmpty()) {
                        android.util.Log.w("MessageRepository", "Found ${brokenChats.size} broken 'Usuario' chats. Attempting auto-repair...")
                        brokenChats.forEach { broken ->
                            launch repairLaunch@{
                                if (!isSessionSnapshotValid(sessionSnapshot, "get-user-chats-repair:${broken.chat.id}")) return@repairLaunch
                                var otherId = broken.chat.participants.firstOrNull { it != userId }
                                if (otherId != null) {
                                    val profileRes = userRepository.getUserById(otherId)
                                    var profile = profileRes.getOrNull()
                                    if (profile != null) {
                                        val realName = profile.displayName.ifBlank {
                                            profile.username?.ifBlank { "Usuario" } ?: "Usuario"
                                        }
                                         android.util.Log.d("MessageRepository", "🔧 Repaired chat ${broken.chat.id}: Usuario -> $realName")
                                         
                                         // Update User Cache
                                         if (!isSessionSnapshotValid(sessionSnapshot, "get-user-chats-repair-user:${broken.chat.id}")) return@repairLaunch
                                         userDao.insertUser(conectate.com.data.local.entity.UserEntity.fromDomain(profile))
                                         
                                         // Update Chat Entity
                                         val correctedEntity = conectate.com.data.local.entity.ChatEntity.fromDomain(
                                            chat = broken.chat,
                                            displayName = realName,
                                            displayImage = profile.avatar_url,
                                            unreadCount = broken.unreadCount,
                                            lastMsg = broken.chat.last_message,
                                            lastTime = broken.chat.last_message_at,
                                            otherUserId = otherId,
                                            lastMsgId = broken.chat.last_message_id,
                                            lastSenderId = broken.chat.last_message_sender_id,
                                            nickname = broken.nickname,
                                            clearedAt = broken.clearedAt
                                         )
                                         if (!isSessionSnapshotValid(sessionSnapshot, "get-user-chats-repair-chat:${broken.chat.id}")) return@repairLaunch
                                         chatDao.insertChat(correctedEntity)
                                         
                                         // Trigger UI Refresh? No, flow will update on next pulse or we can force it?
                                         // For now, next sync will pick it up or live flow will update if we modify _userChats
                                         // Let's rely on Room Flow or next sync.
                                    }
                                }
                            }
                        }
                    }
                    
                } catch (e: Exception) {
                    android.util.Log.e("MessageRepository", "Error updating Room cache", e)
                }
            }

            if (!isSessionSnapshotValid(sessionSnapshot, "get-user-chats-result")) {
                return Result.success(chatDao.getAllChatsSync().map { it.toDomainWithDetails() })
            }
            Result.success(finalMergedList)
        } catch (e: Exception) {
            android.util.Log.e("MessageRepository", "getUserChats error", e)
            Result.failure(e)
        }
    }


    private fun mapLocalFallbackInboxItem(
        item: LocalFallbackInboxItem,
        currentUserId: String
    ): ChatWithLastMessage {
        val chatId = item.id.trim().lowercase()
        val otherId = item.profileId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val updatedAt = item.updatedAt.takeIf { it.isNotBlank() } ?: "1970-01-01T00:00:00Z"
        val isGroupLike = item.kind != "chat"
        val participants = if (isGroupLike) {
            listOf(currentUserId).filter { it.isNotBlank() }
        } else {
            listOfNotNull(currentUserId.takeIf { it.isNotBlank() }, otherId).distinct()
        }
        val subtitle = item.subtitle.trim().ifBlank { "Sin mensajes" }
        val summaryMessage = subtitle
            .takeUnless { it.equals("Sin actividad reciente", ignoreCase = true) }
            ?.let {
                Message(
                    id = "summary-$chatId",
                    chat_id = chatId,
                    sender_id = otherId ?: "",
                    content = it,
                    content_type = "text",
                    status = "sent",
                    created_at = updatedAt
                )
            }
        val chat = Chat(
            id = chatId,
            participants = participants,
            created_at = updatedAt,
            updated_at = updatedAt,
            last_message = subtitle,
            last_message_at = updatedAt,
            last_message_id = summaryMessage?.id,
            last_message_sender_id = summaryMessage?.sender_id,
            is_group = isGroupLike,
            group_name = item.title.ifBlank { "Chat privado" },
            group_image = item.avatarUrl.takeIf { it.isNotBlank() },
            is_muted = item.isMuted,
            is_pinned = item.isPinned,
            is_archived = item.isArchived,
            is_favorite = item.isFavorite
        )
        return ChatWithLastMessage(
            chat = chat,
            lastMessage = summaryMessage,
            unreadCount = item.unreadCount,
            otherUserName = item.title.ifBlank { "Chat privado" },
            otherUserImage = item.avatarUrl.takeIf { it.isNotBlank() },
            otherUserId = otherId,
            clearedAt = null
        )
    }



    override suspend fun syncChats(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val chatsResult = getUserChats()
            var chats = chatsResult.getOrElse { return@withContext Result.failure(it) }

            // Sync recent conversations first to avoid heavy bursts in large accounts.
            chats.sortedByDescending { it.chat.safeLastMessageAt }
                .take(30)
                .forEach { chatWithLastMessage ->
                    var chatId = chatWithLastMessage.chat.id.trim().lowercase()
                    if (chatId.isNotBlank()) {
                        fetchMessageHistory(chatId)
                    }
                }

            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("MessageRepository", "syncChats failed", e)
            Result.failure(e)
        }
    }



    override suspend fun retryPendingMessages(maxBatch: Int): Result<Int> = withContext(Dispatchers.IO) {
        try {
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.success(0)
            var token = authService.getAccessToken()
                ?: return@withContext Result.success(0)

            val pendingMessages = messageDao.getPendingSendMessages()
                .take(maxBatch.coerceAtLeast(1))
            if (pendingMessages.isEmpty()) {
                return@withContext Result.success(0)
            }

            var syncedCount = 0
            pendingMessages.forEach { pending ->
                if (pending.sender_id.trim().lowercase() != currentUserId) return@forEach
                var synced = syncPendingSendMessage(pending, token)
                if (synced) {
                    syncedCount++
                } else {
                    messageDao.updateSyncStatus(pending.id, "failed", false, "send")
                }
            }

            Result.success(syncedCount)
        } catch (e: Exception) {
            android.util.Log.e("MessageRepository", "retryPendingMessages failed", e)
            Result.failure(e)
        }
    }



    override suspend fun retryPendingDeletes(maxBatch: Int): Result<Int> = withContext(Dispatchers.IO) {
        try {
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.success(0)

            val pendingDeletes = messageDao.getPendingDeleteMessages()
                .take(maxBatch.coerceAtLeast(1))
            if (pendingDeletes.isEmpty()) {
                return@withContext Result.success(0)
            }

            var syncedCount = 0
            pendingDeletes.forEach { pending ->
                var synced = syncPendingDeleteMessage(pending, currentUserId)
                if (synced) {
                    var syncedEntity = pending.copy(
                        status = "sent",
                        is_synced = true,
                        pending_action = null
                    )
                    messageDao.insertMessage(syncedEntity)
                    updateMessageInFlow(syncedEntity.chat_id, syncedEntity.toDomain())
                    syncedCount++
                }
            }

            Result.success(syncedCount)
        } catch (e: Exception) {
            android.util.Log.e("MessageRepository", "retryPendingDeletes failed", e)
            Result.failure(e)
        }
    }

    private suspend fun syncPendingSendMessage(
        pending: conectate.com.data.local.entity.MessageEntity,
        token: String
    ): Boolean {
        return try {
            val pendingMessage = pending.toDomain().copy(
                chat_id = pending.chat_id.trim().lowercase(),
                sender_id = pending.sender_id.trim().lowercase(),
                status = "sending"
            )
            val serverResult = sendMessageToBackend(token = token, msg = pendingMessage)
            val isDuplicate = serverResult.statusCode == 409 ||
                (serverResult.errorBody?.contains("23505") == true)

            if (!serverResult.isSuccess && !isDuplicate) {
                android.util.Log.w(
                    "MessageRepository",
                    "retryPendingMessages server rejected ${pending.id}: ${serverResult.statusCode}"
                )
                return false
            }

            val serverMessage = if (serverResult.isSuccess) serverResult.message else null

            val syncedMessage = (serverMessage ?: pending.toDomain()).copy(
                chat_id = pending.chat_id.trim().lowercase(),
                status = "sent"
            )
            messageDao.insertMessage(
                conectate.com.data.local.entity.MessageEntity.fromDomain(
                    msg = syncedMessage,
                    isSynced = true,
                    pendingAction = null
                )
            )
            updateMessageInFlow(pending.chat_id, syncedMessage)

            var preview = buildPreviewForContentType(syncedMessage.content_type, syncedMessage.content)
            updateChatLastMessageInternal(
                chatId = pending.chat_id.trim().lowercase(),
                preview = preview,
                createdAt = syncedMessage.created_at,
                msgId = syncedMessage.id,
                senderId = syncedMessage.sender_id,
                token = token
            )
            true
        } catch (e: Exception) {
            android.util.Log.e("MessageRepository", "retryPendingMessages failed for ${pending.id}", e)
            false
        }
    }

    private suspend fun syncPendingDeleteMessage(
        pending: conectate.com.data.local.entity.MessageEntity,
        currentUserId: String
    ): Boolean {
        val deleteResult = CollectionDeleteRetrySupport.retryRemoteDelete(
            logTag = "MessageRepository",
            messageId = pending.id,
            remoteDelete = {
                if (pending.deleted_for_all) {
                    supabaseConfig.client.postgrest["messages"].update({
                        set("deleted_for_all", true)
                    }) {
                        filter { eq("id", pending.id) }
                    }
                } else {
                    var deletedBy = parseDeletedBy(pending)
                        .map { it.trim().lowercase() }
                        .toMutableList()
                    if (!deletedBy.contains(currentUserId)) {
                        deletedBy.add(currentUserId)
                    }
                    supabaseConfig.client.postgrest["messages"].update({
                        set("deleted_by", deletedBy.distinct())
                    }) {
                        filter { eq("id", pending.id) }
                    }
                }
            },
            isRetryableNetworkError = { error ->
                var text = error.message?.lowercase().orEmpty()
                transientNetworkRetryCodes.any { code -> text.contains(code) }
            }
        )

        deleteResult.exceptionOrNull()?.let { error ->
            android.util.Log.w("MessageRepository", "retryPendingDeletes failed for ${pending.id}", error)
        }
        return deleteResult.isSuccess
    }

    private fun parseDeletedBy(message: conectate.com.data.local.entity.MessageEntity): List<String> {
        return try {
            Json.decodeFromString(message.deleted_by_json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun countUnreadMessages(chatId: String, userId: String, token: String): Int {
        return try {
            // Get My Last Read
            var partRes = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/chat_participants") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("chat_id", "eq.$chatId")
                parameter("user_id", "eq.$userId")
                parameter("select", "last_read_at")
            }
            if (!partRes.status.isSuccess()) return 0
            var body = partRes.body<JsonArray>()
            val lastReadAt = body.firstOrNull()?.jsonObject?.get("last_read_at")?.jsonPrimitive?.contentOrNull

            // Count messages > last_read_at AND sender != Me
            var countRes = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/messages") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("chat_id", "eq.$chatId")
                parameter("sender_id", "neq.$userId")
                if (lastReadAt != null) {
                    parameter("created_at", "gt.$lastReadAt")
                }
                // If lastReadAt is null, we count ALL messages from other users (logic is correct)
                
                parameter("select", "id") // minimal return
                header("Range", "0-99") // Limit to avoid massive count expenses if not needed? User asked for counter.
                // Actually "count=exact" might be better but REST API returns list.
            }
            if (countRes.status.isSuccess()) countRes.body<JsonArray>().size else 0
        } catch (e: Exception) { 0 }
    }



    suspend fun setNickname(targetUserId: String, nickname: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var currentUserId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("No user"))
            var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            
            var payload = buildJsonObject {
                put("user_id", currentUserId)
                put("target_user_id", targetUserId)
                put("nickname", nickname ?: "")
            }
            
            if (nickname.isNullOrBlank()) {
                var response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/nicknames") {
                    header("Authorization", "Bearer $token")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("user_id", "eq.$currentUserId")
                    parameter("target_user_id", "eq.$targetUserId")
                }
                if (!response.status.isSuccess()) {
                    return@withContext Result.failure(Exception("Error deleting nickname: ${response.status}"))
                }
            } else {
                // UPSERT: Create or Update existing nickname
                supabaseConfig.client.postgrest["nicknames"].upsert(payload, onConflict = "user_id,target_user_id")
            }

            // Update local Room
            var chat = chatDao.getChatByOtherUserId(targetUserId)
            if (chat != null) {
                chatDao.updateNickname(chat.id, nickname)
                
                // Update Memory flow
                var currentChats = _userChats.value.toMutableList()
                var idx = currentChats.indexOfFirst { it.chat.id == chat.id }
                if (idx != -1) {
                    currentChats[idx] = currentChats[idx].copy(nickname = nickname)
                    _userChats.value = currentChats
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("MessageRepository", "setNickname failed", e)
            Result.failure(e)
        }
    }



    suspend fun clearChat(chatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var currentUserId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("No user"))
            var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            var cleanId = chatId.trim().lowercase()
            var now = java.time.Instant.now().toString()
            
            var payload = buildJsonObject {
                put("cleared_at", now)
            }
            
            var response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/chat_participants") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                parameter("chat_id", "eq.$cleanId")
                parameter("user_id", "eq.$currentUserId")
                setBody(payload)
            }
            
            if (response.status.isSuccess()) {
                // Update Local Room
                chatDao.updateClearedAt(cleanId, now)
                
                // Nuclear local message delete for this chat to sync with server's "cleared" state
                messageDao.deleteMessagesByChatId(cleanId)
                
                // Clear In-Memory Flow
                _activeChatMessages[cleanId]?.value = emptyList()
                
                // Update Chat List Metadata
                var currentChats = _userChats.value.toMutableList()
                var idx = currentChats.indexOfFirst { it.chat.id == cleanId }
                if (idx != -1) {
                    currentChats[idx] = currentChats[idx].copy(
                        clearedAt = now,
                        lastMessage = null
                    )
                    _userChats.value = currentChats
                }
                
                Result.success(Unit)
            } else {
                Result.failure(Exception("Error clearing chat: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- MESSAGES FLOW ---



    suspend fun getCachedMessages(chatId: String): List<Message> = withContext(Dispatchers.IO) {
        var cleanId = chatId.trim().lowercase()
        if (cleanId.isBlank()) return@withContext emptyList()

        var activeUserId = currentSessionUserId()
        val localMessages = messageDao.getMessagesSync(cleanId).map { it.toDomain() }
        var unified = unifyMessageList(localMessages)

        if (activeUserId.isNullOrBlank()) {
            unified
        } else {
            unified.filterNot { message ->
                message.deleted_by.any { deletedBy ->
                    deletedBy.trim().lowercase() == activeUserId
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getMessagesFlow(chatId: String): Flow<List<Message>> {
        var cleanId = chatId.trim().lowercase()
        
        return _userChats.flatMapLatest { chats ->
            var currentChat = chats.find { it.chat.id.trim().lowercase() == cleanId }
            
            // Fallback to Room if memory is syncing
            if (currentChat == null) {
                try {
                    var local = chatDao.getChatById(cleanId)
                    if (local != null) currentChat = local.toDomainWithDetails()
                } catch(e: Exception) { /* ignore */ }
            }
            
            var participants = currentChat?.chat?.participants?.map { it.trim().lowercase() }?.sorted() ?: emptyList()
            var isGroup = currentChat?.chat?.is_group == true
            
            // 1. Identify Sibling IDs
            val siblingIds = if (participants.size == 2 && !isGroup) {
                val foundInMem = chats.filter { 
                    it.chat.participants.map { p -> p.trim().lowercase() }.sorted() == participants && !it.chat.is_group 
                }.map { it.chat.id.trim().lowercase() }
                (foundInMem + cleanId).toSet()
            } else {
                setOf(cleanId)
            }
            
            // 2. Combine Room Flow AND Memory State for EACH sibling
            val flows = siblingIds.map { id ->
                val roomFlow = messageDao.getMessagesFlow(id)
                val memFlow = _activeChatMessages.getOrPut(id) { MutableStateFlow(emptyList()) }
                
                combine(roomFlow, memFlow) { room, mem ->
                    room.map { it.toDomain() } + mem
                }
            }
            
            // Background history fetch (read receipts are handled by visible chat screen).
            // This used to relaunch on every chat-list update and caused visible lag.
            if (shouldRefreshChatHistory(cleanId)) {
                scope.launch {
                    fetchMessageHistory(cleanId)
                }
            }

            combine(flows) { lists -> 
                val allMsgs = lists.flatMap { it }
                var unified = unifyMessageList(allMsgs)
                var activeUserId = currentSessionUserId()
                val visibleForUser = if (activeUserId.isNullOrBlank()) {
                    unified
                } else {
                    unified.filterNot { message ->
                        message.deleted_by.any { deletedBy ->
                            deletedBy.trim().lowercase() == activeUserId
                        }
                    }
                }
                
                // Filter by clearedAt if exists
                var clearedAt = currentChat?.clearedAt
                if (clearedAt != null) {
                    visibleForUser.filter { (it.created_at ?: "") > clearedAt }
                } else visibleForUser
            }.distinctUntilChanged()
        }
    }

    private fun shouldRefreshChatHistory(chatId: String, minIntervalMs: Long = 12_000L): Boolean {
        var cleanId = chatId.trim().lowercase()
        if (cleanId.isBlank()) return false
        var now = System.currentTimeMillis()
        val lastFetchAt = chatHistoryFetchTimestamps[cleanId] ?: 0L
        if (now - lastFetchAt < minIntervalMs) {
            return false
        }
        chatHistoryFetchTimestamps[cleanId] = now
        return true
    }

    private suspend fun fetchMessageHistory(chatId: String) {
        try {
            val sessionUserId = currentSessionUserId() ?: return
            var sessionSnapshot = captureSessionSnapshot(sessionUserId)
            if (!isSessionSnapshotValid(sessionSnapshot, "fetch-history-start:$chatId")) return
            var accessToken = authService.ensureValidAccessToken().getOrElse { return }
            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val localChat = localFallbackApiClient.loadChat(accessToken, chatId)
                    .getOrElse { error ->
                        android.util.Log.w("MessageRepository", "Local fallback chat history failed for $chatId", error)
                        return
                    }
                val rawMsgs = localChat.messages
                    .mapNotNull { messageJson ->
                        runCatching { json.decodeFromJsonElement<Message>(messageJson) }.getOrNull()
                    }
                    .map { it.copy(chat_id = chatId).withNormalizedStatus() }

                scope.launch {
                    if (!isSessionSnapshotValid(sessionSnapshot, "local-fallback-history-cache:$chatId")) return@launch
                    runCatching {
                        messageDao.insertMessages(
                            rawMsgs.map { conectate.com.data.local.entity.MessageEntity.fromDomain(it, isSynced = true) }
                        )
                    }.onFailure {
                        android.util.Log.w("MessageRepository", "Could not cache local fallback messages", it)
                    }
                }

                _activeChatMessages[chatId]?.let { flow ->
                    if (!isSessionSnapshotValid(sessionSnapshot, "local-fallback-history-flow:$chatId")) return
                    flow.update { current -> unifyMessageList(current + rawMsgs) }
                }
                return
            }
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/messages") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("chat_id", "eq.$chatId")
                parameter("order", "created_at.desc") // FETCH NEWEST FIRST
                parameter("limit", "100") // Limit to 100 recent messages for speed
            }
            if (response.status.isSuccess()) {
                if (!isSessionSnapshotValid(sessionSnapshot, "fetch-history-after-network:$chatId")) return
                val rawMsgsDesc: List<Message> = response.body()
                val rawMsgs = rawMsgsDesc
                    .reversed() // Reverse back to chronological for correct processing logic
                    .map { it.withNormalizedStatus() }
                
                if (rawMsgs.isEmpty()) {
                     android.util.Log.w("MessageRepository", "⚠️ SERVER RETURNED 0 MESSAGES for chat $chatId. (Check RLS or if chat is really empty)")
                } else {
                     android.util.Log.d("MessageRepository", "📥 SERVER RETURNED ${rawMsgs.size} messages for chat $chatId")
                     // DEBUG TIMESTAMPS:
                     val firstMsg = rawMsgs.first()
                     var lastMsg = rawMsgs.last()
                     android.util.Log.d("MessageRepository", "🕒 DEBUG FIRST MSG (${firstMsg.id}): created_at='${firstMsg.created_at}'")
                     android.util.Log.d("MessageRepository", "🕒 DEBUG LAST MSG (${lastMsg.id}): created_at='${lastMsg.created_at}'")
                }
                
                // HYDRATION: Supabase returns flat messages. We must re-attach reply_to objects.
                // 1. Create lookup from current batch
                val batchMap = rawMsgs.associateBy { it.id }
                
                val hydratedMsgs = rawMsgs.map { msg ->
                    if (msg.reply_to_id != null && msg.reply_to == null) {
                        // Priority 1: Find in this batch
                        var reply = batchMap[msg.reply_to_id]
                        
                        // Priority 2: Find in Room (if replying to older message)
                        if (reply == null) {
                             reply = try {
                                 messageDao.getMessageById(msg.reply_to_id)?.toDomain()
                             } catch(e: Exception) { null }
                        }
                        
                        if (reply != null) msg.copy(reply_to = reply) else msg
                    } else {
                        msg
                    }
                }
                
                // PERSISTENCE: Save all fetched (and hydrated) messages to Room
                scope.launch {
                    if (!isSessionSnapshotValid(sessionSnapshot, "fetch-history-cache:$chatId")) return@launch
                    try {
                        // CRITICAL FIX: Force the message to belong to the Chat ID we are currently looking at.
                        // This prevents messages from being lost in "sibling" silos and ensures Room always returns them.
                        val entities = hydratedMsgs.map { 
                            conectate.com.data.local.entity.MessageEntity.fromDomain(it.copy(chat_id = chatId), isSynced = true) 
                        }
                        messageDao.insertMessages(entities)
                        android.util.Log.d("MessageRepository", "✅ Saved ${entities.size} messages (normalized to $chatId) to Room")
                    } catch (e: Exception) {
                        android.util.Log.e("MessageRepository", "❌ Failed to save history to Room", e)
                    }
                }
                
                // Atomic UI Update
                _activeChatMessages[chatId]?.let { flow ->
                    if (!isSessionSnapshotValid(sessionSnapshot, "fetch-history-flow:$chatId")) return
                    flow.update { current ->
                        unifyMessageList(current + hydratedMsgs)
                    }
                    android.util.Log.d("MessageRepository", "✅ History merge complete for $chatId. Total: ${flow.value.size}")
                }
            }
        } catch (e: Exception) { 
            android.util.Log.e("MessageRepository", "fetchMessageHistory error", e)
        }
    }

    suspend fun addMessageToFlow(chatId: String, msg: Message) {
        var cleanId = chatId.trim().lowercase()
        var mutex = getMutexForChat(cleanId)

        mutex.withLock {
            addMessageToFlowLogic(cleanId, msg)
        }
    }

    private fun addMessageToFlowLogic(cleanId: String, msg: Message) {
        var flow = _activeChatMessages[cleanId] ?: return
        flow.update { prevList ->
            val merged = (prevList + msg)
            unifyMessageList(merged)
        }
    }

    private fun unifyMessageList(messages: List<Message>): List<Message> {
        if (messages.isEmpty()) return emptyList()
        
        // 1. Exact ID Deduplication (Prefer real status over "sending")
        val byId = messages.groupBy { it.id.trim().lowercase() }.map { (_, variations) ->
            variations.find { it.status != "sending" } ?: variations.first()
        }
        
        // 2. Robust Sorting (Ascending for Chat Screen)
        // CRITICAL: We want 'sending' messages at the end.
        // And null dates (broken ones) at the beginning (1970).
        return byId.sortedWith(compareBy<Message> { 
            when {
                it.status == "sending" -> "9999-12-31T23:59:59Z"
                it.created_at.isNullOrBlank() -> "1970-01-01T00:00:00Z"
                else -> it.created_at.replace(" ", "T")
            }
        }.thenBy { it.id })
    }

    private fun updateMessageInFlow(chatId: String, msg: Message) {
        var cleanId = chatId.trim().lowercase()
        var flow = _activeChatMessages[cleanId] ?: return
        
        flow.update { current -> 
            val updatedList = current.toMutableList()
            var idx = updatedList.indexOfFirst { it.id == msg.id }
            if (idx != -1) {
                updatedList[idx] = msg
            } else {
                updatedList.add(msg)
            }
            unifyMessageList(updatedList)
        }
    }

    private fun removeMessageFromFlow(chatId: String, msgId: String) {
        var cleanId = chatId.trim().lowercase()
        var flow = _activeChatMessages[cleanId] ?: return
        flow.value = flow.value.filter { it.id != msgId }
    }

    // --- ACTIONS ---

    suspend fun sendMessage(
        chatId: String, 
        content: String, 
        replyToId: String? = null,
        mediaUri: Uri? = null,
        mediaType: String = "text",
        messageId: String? = null,
        existingMediaUrl: String? = null,
        isForwarded: Boolean = false,
        forwardedFromMessageId: String? = null,
        forwardedFromUserId: String? = null
    ): Result<Message> {
        // Fix: Define ID outside try block so it's visible in catch block
        var id = messageId ?: UUID.randomUUID().toString()
        var cleanId = chatId.trim().lowercase()
        var auditUserId: String? = null
        val startedAt = System.currentTimeMillis()
        
        return try {
            var userId = (authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))).trim().lowercase()
            auditUserId = userId

            val guardDecision = operationGuard.acquire(
                action = GuardedAction.DIRECT_MESSAGE_SEND,
                actorId = userId,
                scopeId = cleanId
            )
            if (!guardDecision.allowed) {
                val retryAfterSeconds = InputSecurityPolicy.retryAfterSeconds(guardDecision.retryAfterMs)
                return Result.failure(Exception("Estas enviando mensajes muy rapido. Intenta en ${retryAfterSeconds}s."))
            }

            var token = authService.ensureValidAccessToken()
                .getOrElse { return Result.failure(it) }
            val baseSanitizedContent = if (mediaType == "text") {
                InputSecurityPolicy.sanitizeMessageText(content)
            } else {
                content.trim().take(InputSecurityPolicy.MAX_TEXT_MESSAGE_LENGTH)
            }
            val moderation = moderationService.moderate(
                text = baseSanitizedContent,
                context = ModerationContext.CHAT_MESSAGE
            )
            if (moderation.verdict == ModerationVerdict.BLOCK) {
                appMetrics.recordFailure(
                    metric = "dm_send",
                    attributes = mapOf("reason" to "moderation_block")
                )
                return Result.failure(Exception("Tu mensaje incumple nuestras politicas"))
            }
            val sanitizedContent = moderation.sanitizedText
            if (mediaType == "text" && mediaUri == null && sanitizedContent.isBlank()) {
                return Result.failure(Exception("El mensaje no puede estar vacio"))
            }
            
            android.util.Log.d(
                "MessageRepository",
                "🚀 Sending message to chat: $cleanId, contentLength=${sanitizedContent.length}, type: $mediaType"
            )

            // --- 0. PROACTIVE INSTANT CHAT & PROFILE PERSISTENCE (FIX FOR VISIBILITY & "Usuario") ---
            // Only for 1-on-1 chats to avoid touching Groups/Communities logic
            scope.launch {
                try {
                    var existingChat = _userChats.value.find { it.chat.id.trim().lowercase() == cleanId }
                    if (existingChat == null) {
                        // Is this a group? We can check cache or assumes it's DM if we are here (usually)
                        // But to be safe, we check if we can interpret the participants from the ID or if it's a new DM
                        // For DMs, we usually don't have the chat object yet, so we must rely on the fact that we are sending to a valid Chat ID.
                        // However, usually we can't easily know if it's a group if we don't have it. 
                        // BUT, if it IS a new DM, the ID might be a UUID.
                        
                        // We will attempt to fetch the OTHER user's profile to resolve the name.
                        // Assuming standard DM creation where we might know the participant.
                        // If we can't find participants, we skip name resolution.
                        
                        // Placeholder intentionally skipped: realtime listener hydrates full DM metadata.
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MessageRepository", "Proactive persistence check failed", e)
                }
            }

            var mediaUrl: String? = existingMediaUrl
            var uploadedMediaSize = 0L
            if (mediaUri != null) {
                var bytes = context.contentResolver.openInputStream(mediaUri)?.use { it.readBytes() }
                    ?: return Result.failure(Exception("No se pudo leer el archivo"))

                val (mimeType, extension) = resolveMediaUploadMeta(mediaType, mediaUri, bytes)
                var filename = "$cleanId/${System.currentTimeMillis()}$extension"
                var path = "chat-media/$filename"
                var upload = storageService.uploadFile("chat-media", filename, bytes, mimeType)
                if (upload.isSuccess) {
                    mediaUrl = if (authService.isLocalFallbackSession()) {
                        storageService.getPublicUrl("chat-media", filename)
                    } else {
                        path
                    }
                    uploadedMediaSize = bytes.size.toLong()
                    android.util.Log.d("MessageRepository", "✅ Media uploaded ($mimeType): $mediaUrl")
                } else {
                    var reason = upload.exceptionOrNull()?.message ?: "No se pudo subir multimedia"
                    android.util.Log.e("MessageRepository", "❌ Media upload failed: $reason")
                    return Result.failure(Exception(reason))
                }
            }

            var msg = Message(
                id = id,
                chat_id = cleanId, // Send clean ID
                sender_id = userId,
                content = sanitizedContent,
                content_type = mediaType,
                media_url = mediaUrl,
                media_size = uploadedMediaSize,
                status = "sending", // EXPLICIT STATUS FOR OPTIMISTIC VIEW
                // SANITIZATION: If replying to a local FCM placeholder, try to resolve it to a Realtime UUID.
                // If not found in map, strip it to prevent HTTP 400.
                reply_to_id = if (replyToId?.startsWith("fcm_") == true) {
                    transientIdMap[replyToId]
                } else {
                    replyToId
                },
                is_forwarded = isForwarded,
                forwarded_from_message_id = forwardedFromMessageId,
                forwarded_from_user_id = forwardedFromUserId,
                created_at = java.time.Instant.now().toString(),
                reply_to = if (replyToId != null) {
                    _activeChatMessages[cleanId]?.value?.find { it.id == replyToId }
                } else null
            )
            
            android.util.Log.d("MessageRepository", "📦 Message object created: id=$id, chat_id=$cleanId, sender_id=$userId")

            // --- 1. PERSISTENCE OPTIMISTIC (IMMEDIATE SAVE) ---
            // Save to Room FIRST to prevent data loss if server call fails or app is killed.
            scope.launch {
                try {
                    val optimisticEntity = conectate.com.data.local.entity.MessageEntity.fromDomain(msg, isSynced = false, pendingAction = "send")
                    messageDao.insertMessage(optimisticEntity)
                    android.util.Log.d("MessageRepository", "✅ Optimistic Message saved to Room: ${msg.id}")
                } catch (e: Exception) {
                    android.util.Log.e("MessageRepository", "❌ Failed to save optimistic message", e)
                }
            }

            // --- 2. UPDATE MEMORY (FLOW) ---
            addMessageToFlow(cleanId, msg)
            android.util.Log.d("MessageRepository", "🔄 Optimistic update applied to flow")

            // --- 3. SEND TO SERVER ---
            android.util.Log.d("MessageRepository", "📡 Sending message to backend (secure RPC with fallback)...")
            val serverSendResult = sendMessageToBackend(
                token = token,
                msg = msg
            )
            android.util.Log.d("MessageRepository", "📨 Backend status: ${serverSendResult.statusCode}")

            // --- 4. HANDLE RESPONSE (UPDATE LOCAL) ---
            if (serverSendResult.isSuccess) {
                val returnedMsg = (serverSendResult.message ?: msg.copy(status = "sent")).copy(
                    reply_to = msg.reply_to,
                    created_at = (serverSendResult.message?.created_at ?: msg.created_at)
                )
                
                android.util.Log.d("MessageRepository", "✅ Message sent successfully: ${returnedMsg.id}")
                
                 // Update Memory Corrected
                addMessageToFlow(cleanId, returnedMsg)
                
                // Update Room (Mark as Synced, Status Sent)
                scope.launch {
                    try {
                        var entity = conectate.com.data.local.entity.MessageEntity.fromDomain(returnedMsg, isSynced = true)
                        messageDao.insertMessage(entity) // Should REPLACE the optimistic one due to Primary Key Match
                        android.util.Log.d("MessageRepository", "✅ Room Updated to SENT: ${returnedMsg.id}")

                        // Update Chat Metadata
                         var preview = buildPreviewForContentType(returnedMsg.content_type, returnedMsg.content)
                        updateChatLastMessageInternal(cleanId, preview, returnedMsg.created_at, returnedMsg.id, userId, token)
                        
                    } catch (e: Exception) {
                        android.util.Log.e("MessageRepository", "❌ Failed to update Room after success", e)
                    }
                }

                 // Proactive Chat List Update (Existing Logic - Optimized)
                 scope.launch {
                     try {
                         var currentChats = _userChats.value.toMutableList()
                         var idx = currentChats.indexOfFirst { it.chat.id.trim().lowercase() == cleanId }
                         if (idx != -1) {
                            // Update existing
                            updateChatInList(currentChats, idx, returnedMsg, userId)
                         } else {
                            // Handle New Chat placeholder (Keep existing logic if needed or simplified)
                            // ... (Omitting complex creation logic for brevity, relying on standard refresh loop for new chats usually)
                            // But for "Instant" feel, we trigger a fast fetch
                            getUserChats() 
                         }
                     } catch(e: Exception) { }
                 }

                runCatching {
                    operationGuard.audit(
                        action = GuardedAction.DIRECT_MESSAGE_SEND,
                        actorId = userId,
                        scopeId = cleanId,
                        outcome = GuardOutcome.SUCCESS,
                        attributes = mapOf("message_id" to returnedMsg.id)
                    )
                }
                appMetrics.incrementCounter(
                    metric = "dm_send_success",
                    attributes = mapOf("media_type" to mediaType)
                )
                appMetrics.recordLatency(
                    metric = "dm_send",
                    durationMs = System.currentTimeMillis() - startedAt,
                    attributes = mapOf("media_type" to mediaType)
                )

                 Result.success(returnedMsg)
            } else {
                var errorBody = serverSendResult.errorBody ?: "Unable to read error body"
                val normalizedError = normalizeBackendSendFailure(errorBody)
                android.util.Log.e(
                    "MessageRepository",
                    "❌ Send FAILED: HTTP ${serverSendResult.statusCode} - $errorBody"
                )
                
                // Update Room to FAILED status
                scope.launch {
                    try {
                        messageDao.updateSyncStatus(id, "failed", false, "send")
                    } catch(e: Exception) { }
                }

                runCatching {
                    operationGuard.audit(
                        action = GuardedAction.DIRECT_MESSAGE_SEND,
                        actorId = userId,
                        scopeId = cleanId,
                        outcome = GuardOutcome.FAILED,
                        attributes = mapOf("http_status" to serverSendResult.statusCode.toString())
                    )
                }
                appMetrics.recordFailure(
                    metric = "dm_send",
                    attributes = mapOf(
                        "media_type" to mediaType,
                        "http_status" to serverSendResult.statusCode.toString()
                    )
                )

                Result.failure(Exception(normalizedError))
            }
        } catch (e: Exception) { 
            android.util.Log.e("MessageRepository", "❌❌ EXCEPTION in sendMessage", e)
             // Update Room to FAILED status
             scope.launch {
                try {
                    messageDao.updateSyncStatus(id, "failed", false, "send")
                } catch(e2: Exception) { }
            }
            var actorId = auditUserId
            if (!actorId.isNullOrBlank()) {
                runCatching {
                    operationGuard.audit(
                        action = GuardedAction.DIRECT_MESSAGE_SEND,
                        actorId = actorId,
                        scopeId = cleanId,
                        outcome = GuardOutcome.FAILED,
                        attributes = mapOf("error_type" to (e::class.simpleName ?: "unknown"))
                    )
                }
            }
            appMetrics.recordFailure(
                metric = "dm_send",
                throwable = e,
                attributes = mapOf("media_type" to mediaType)
            )
            Result.failure(e) 
        }
    }

    suspend fun uploadChatMediaForScheduledMessage(
        chatId: String,
        mediaUri: Uri,
        mediaType: String
    ): Result<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            var cleanChatId = chatId.trim().lowercase()
            if (cleanChatId.isBlank()) {
                Result.failure(Exception("Chat inválido"))
            } else {
                var bytes = context.contentResolver.openInputStream(mediaUri)?.use { it.readBytes() }
                    ?: return@withContext Result.failure(Exception("No se pudo leer archivo"))

                val (mimeType, extension) = resolveMediaUploadMeta(mediaType, mediaUri, bytes)
                var filename = "$cleanChatId/${System.currentTimeMillis()}$extension"
                var upload = storageService.uploadFile("chat-media", filename, bytes, mimeType)
                if (upload.isSuccess) {
                    Result.success(
                        if (authService.isLocalFallbackSession()) {
                            storageService.getPublicUrl("chat-media", filename)
                        } else {
                            "chat-media/$filename"
                        }
                    )
                } else {
                    Result.failure(upload.exceptionOrNull() ?: Exception("No se pudo subir multimedia"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private data class BackendSendResult(
        val isSuccess: Boolean,
        val statusCode: Int,
        val message: Message? = null,
        val errorBody: String? = null
    )

    private suspend fun sendMessageToBackend(
        token: String,
        msg: Message
    ): BackendSendResult {
        if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
            return localFallbackApiClient.sendChatMessage(
                accessToken = token,
                chatId = msg.chat_id,
                content = msg.content,
                contentType = msg.content_type,
                mediaUrl = msg.media_url,
                mediaSize = msg.media_size,
                replyToId = msg.reply_to_id
            ).fold(
                onSuccess = { sent ->
                    BackendSendResult(
                        isSuccess = true,
                        statusCode = 201,
                        message = sent.copy(status = "sent")
                    )
                },
                onFailure = { error ->
                    BackendSendResult(
                        isSuccess = false,
                        statusCode = 500,
                        errorBody = error.message ?: "No se pudo enviar al servidor Ubuntu."
                    )
                }
            )
        }

        var rpcPayload = buildJsonObject {
            put("p_chat_id", msg.chat_id)
            put("p_message_id", msg.id)
            put("p_content", msg.content)
            put("p_content_type", msg.content_type)
            put("p_media_url", msg.media_url)
            put("p_media_size", msg.media_size)
            put("p_reply_to_id", msg.reply_to_id)
            put("p_is_forwarded", msg.is_forwarded)
            put("p_forwarded_from_message_id", msg.forwarded_from_message_id)
            put("p_forwarded_from_user_id", msg.forwarded_from_user_id)
        }

        return try {
            var rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_send_message") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                setBody(rpcPayload)
            }

            if (rpcResponse.status.isSuccess()) {
                var raw = runCatching { rpcResponse.bodyAsText() }.getOrNull().orEmpty()
                val parsed = parseMessageResponse(raw)
                return BackendSendResult(
                    isSuccess = true,
                    statusCode = rpcResponse.status.value,
                    message = parsed?.copy(status = "sent") ?: msg.copy(status = "sent")
                )
            }

            val rpcError = runCatching { rpcResponse.bodyAsText() }.getOrNull().orEmpty()
            if (isMissingRpcFunction(rpcResponse.status.value, rpcError, "secure_send_message")) {
                return BackendSendResult(
                    isSuccess = false,
                    statusCode = rpcResponse.status.value,
                    errorBody = "secure_send_message RPC missing. Refusing insecure fallback for DM sends."
                )
            }

            BackendSendResult(
                isSuccess = false,
                statusCode = rpcResponse.status.value,
                errorBody = rpcError
            )
        } catch (e: Exception) {
            BackendSendResult(
                isSuccess = false,
                statusCode = 500,
                errorBody = e.message ?: "Unexpected error calling secure_send_message"
            )
        }
    }

    private fun parseMessageResponse(raw: String): Message? {
        if (raw.isBlank()) return null
        return runCatching { json.decodeFromString<Message>(raw) }.getOrNull()
            ?: runCatching { json.decodeFromString<List<Message>>(raw).firstOrNull() }.getOrNull()
    }

    private fun isMissingRpcFunction(statusCode: Int, body: String, functionName: String): Boolean {
        if (statusCode != 400 && statusCode != 404) return false
        val cleanBody = body.lowercase()
        return cleanBody.contains(functionName.lowercase()) &&
            (cleanBody.contains("could not find the function") || cleanBody.contains("undefined function"))
    }

    private fun isBlockedByRecipientError(body: String): Boolean {
        var normalized = body.lowercase()
        return normalized.contains("chat_blocked_by_recipient")
    }

    private fun normalizeBackendSendFailure(errorBody: String): String {
        val clean = errorBody.trim()
        return when {
            isBlockedByRecipientError(clean) -> "CHAT_BLOCKED_BY_RECIPIENT"
            clean.isBlank() -> "SEND_FAILED"
            else -> clean
        }
    }

    // Helper extracted to avoid code duplication
    private suspend fun updateChatLastMessageInternal(chatId: String, preview: String, createdAt: String?, msgId: String, senderId: String, token: String) {
          try {
            var update = buildJsonObject {
                put("last_message", preview)
                put("last_message_at", createdAt)
                put("last_message_id", msgId)
                put("last_message_sender_id", senderId)
            }
            supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/chats") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                parameter("id", "eq.$chatId")
                setBody(update)
            }
        } catch (e: Exception) { }
    }

    private suspend fun updateChatLastMessage(chatId: String, msg: Message, token: String) {
        try {
            var update = buildJsonObject {
                put("last_message", buildPreviewForContentType(msg.content_type, msg.content))
                put("last_message_at", msg.created_at)
                put("last_message_id", msg.id)
            }
            var response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/chats") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                parameter("id", "eq.$chatId")
                setBody(update)
            }
            if (!response.status.isSuccess()) {
                android.util.Log.e("MessageRepository", "Failed to update last message: ${response.status}")
            }
        } catch (e: Exception) { 
             android.util.Log.e("MessageRepository", "Error updating last message", e)
        }
    }

    suspend fun markChatAsRead(chatId: String): Result<Unit> {
        return try {
            var userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return Result.failure(Exception("No user"))
            var token = authService.getAccessToken()
            var cleanId = chatId.trim().lowercase()
            
            var now = java.time.Instant.now().toString()
            
            // 1. Sync global state in 'messages' table (prefer secure RPC, fallback to PATCH)
            if (!token.isNullOrBlank()) {
                var remoteReadSynced = false
                runCatching {
                    supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_mark_messages_read") {
                        header("Authorization", "Bearer $token")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Content-Type", "application/json")
                        setBody(buildJsonObject { put("p_chat_id", cleanId) })
                    }
                }.onSuccess { response ->
                    remoteReadSynced = response.status.isSuccess()
                    if (!remoteReadSynced) {
                        android.util.Log.w("MessageRepository", "secure_mark_messages_read failed for $cleanId: ${response.status}")
                    }
                }.onFailure { e ->
                    android.util.Log.w("MessageRepository", "secure_mark_messages_read exception for $cleanId", e)
                }

                if (!remoteReadSynced) {
                    val messageUpdate = buildJsonObject {
                        put("status", "read")
                        put("read_at", now)
                        put("delivered_at", now)
                        put("updated_at", now)
                    }
                    runCatching {
                        supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/messages") {
                            header("Authorization", "Bearer $token")
                            header("apikey", supabaseConfig.supabaseAnonKey)
                            header("Content-Type", "application/json")
                            parameter("chat_id", "eq.$cleanId")
                            parameter("sender_id", "neq.$userId") // Only mark others' messages as read
                            parameter("read_at", "is.null")
                            setBody(messageUpdate)
                        }
                    }.onFailure { e ->
                        android.util.Log.w("MessageRepository", "markChatAsRead fallback patch failed for $cleanId", e)
                    }
                }
                
                // 2. Sync 'chat_participants' for general metadata
                val participantUpdate = buildJsonObject { put("last_read_at", now) }
                runCatching {
                    supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/chat_participants") {
                        header("Authorization", "Bearer $token")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Content-Type", "application/json")
                        parameter("chat_id", "eq.$cleanId")
                        parameter("user_id", "eq.$userId")
                        setBody(participantUpdate)
                    }
                }.onFailure { e ->
                    android.util.Log.w("MessageRepository", "chat_participants last_read_at sync failed for $cleanId", e)
                }
            } else {
                android.util.Log.w("MessageRepository", "markChatAsRead: no token available, skipping remote sync for $cleanId")
            }

            // Instant local convergence for read state (viewport/read receipt UX).
            messageDao.markAllAsRead(cleanId, userId, now)
            
            // 3. Update Local State for Unread Count
             var currentChats = _userChats.value.toMutableList()
             val index = currentChats.indexOfFirst { it.chat.id.trim().lowercase() == cleanId }
             if (index != -1) {
                 currentChats[index] = currentChats[index].copy(unreadCount = 0)
                 _userChats.value = currentChats
             }

             // --- ADDED: Clear Android Notifications ---
             try {
                 var notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                 notificationManager.cancel(cleanId.hashCode())
             } catch (e: Exception) {
                 android.util.Log.e("MessageRepository", "Error clearing notification", e)
             }
             
             // 4. Update Room
             chatDao.markAsRead(cleanId)
             sessionStateStore.rememberLocalReadOverride(cleanId)
             
            Result.success(Unit)
        } catch (e: Exception) { 
            android.util.Log.e("MessageRepository", "markChatAsRead failed", e)
            Result.failure(e) 
        }
    }



    suspend fun markMessageAsDelivered(chatId: String, messageId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("No user"))
            var cleanId = chatId.trim().lowercase()
            var cleanMessageId = messageId.trim().lowercase()
            if (cleanId.isBlank() || cleanMessageId.isBlank()) return@withContext Result.success(Unit)

            var now = java.time.Instant.now().toString()
            var updatePayload = buildJsonObject {
                put("status", "delivered")
                put("delivered_at", now)
                put("updated_at", now)
            }

            var remoteSynced = false
            runCatching {
                supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/messages") {
                    header("Authorization", "Bearer $token")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Content-Type", "application/json")
                    header("Prefer", "return=representation")
                    parameter("id", "eq.$cleanMessageId")
                    parameter("chat_id", "eq.$cleanId")
                    parameter("sender_id", "neq.$currentUserId")
                    parameter("status", "eq.sent")
                    parameter("select", "id")
                    setBody(updatePayload)
                }
            }.onSuccess { response ->
                if (response.status.isSuccess()) {
                    var body = runCatching { response.bodyAsText() }.getOrDefault("")
                    remoteSynced = extractUpdatedIdsFromPatchBody(body).contains(cleanMessageId)
                    if (!remoteSynced) {
                        remoteSynced = fetchRemoteMessageIdsByStatus(
                            chatId = cleanId,
                            messageIds = listOf(cleanMessageId),
                            currentUserId = currentUserId,
                            token = token,
                            statuses = listOf(Constants.MESSAGE_STATUS_DELIVERED, Constants.MESSAGE_STATUS_READ, "received")
                        ).contains(cleanMessageId)
                    }
                }
            }.onFailure { e ->
                android.util.Log.w("MessageRepository", "markMessageAsDelivered patch failed for $cleanMessageId", e)
            }

            if (!remoteSynced) {
                // Legacy fallback for old deployments without granular patch support.
                runCatching {
                    var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_mark_messages_delivered") {
                        header("Authorization", "Bearer $token")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Content-Type", "application/json")
                        setBody(buildJsonObject { put("p_chat_id", cleanId) })
                    }
                    if (response.status.isSuccess()) {
                        remoteSynced = fetchRemoteMessageIdsByStatus(
                            chatId = cleanId,
                            messageIds = listOf(cleanMessageId),
                            currentUserId = currentUserId,
                            token = token,
                            statuses = listOf(Constants.MESSAGE_STATUS_DELIVERED, Constants.MESSAGE_STATUS_READ, "received")
                        ).contains(cleanMessageId)
                    }
                }
            }

            messageDao.markMessageAsDelivered(cleanMessageId, cleanId, currentUserId, now)
            if (!remoteSynced) {
                android.util.Log.w(
                    "MessageRepository",
                    "markMessageAsDelivered could not confirm remote transition for $cleanMessageId in $cleanId"
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    suspend fun markMessagesAsDelivered(chatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("No user"))
            var cleanId = chatId.trim().lowercase()

            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_mark_messages_delivered") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(buildJsonObject { put("p_chat_id", cleanId) })
            }

            if (response.status.isSuccess()) {
                messageDao.markAllAsDelivered(cleanId, currentUserId, java.time.Instant.now().toString())
                Result.success(Unit)
            } else {
                Result.failure(Exception("Error marking delivered: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    suspend fun markMessagesAsRead(chatId: String, messageIds: Collection<String>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("No user"))
            var cleanId = chatId.trim().lowercase()
            val cleanMessageIds = messageIds
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
            if (cleanId.isBlank() || cleanMessageIds.isEmpty()) return@withContext Result.success(Unit)

            var now = java.time.Instant.now().toString()
            var updatePayload = buildJsonObject {
                put("status", "read")
                put("read_at", now)
                put("delivered_at", now)
                put("updated_at", now)
            }
            var idsFilter = cleanMessageIds.joinToString(",")
            val remotelyReadIds = mutableSetOf<String>()

            runCatching {
                supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/messages") {
                    header("Authorization", "Bearer $token")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Content-Type", "application/json")
                    header("Prefer", "return=representation")
                    parameter("chat_id", "eq.$cleanId")
                    parameter("sender_id", "neq.$currentUserId")
                    parameter("id", "in.($idsFilter)")
                    parameter("read_at", "is.null")
                    parameter("select", "id")
                    setBody(updatePayload)
                }
            }.onSuccess { response ->
                if (response.status.isSuccess()) {
                    var body = runCatching { response.bodyAsText() }.getOrDefault("")
                    remotelyReadIds.addAll(extractUpdatedIdsFromPatchBody(body))
                } else {
                    android.util.Log.w("MessageRepository", "markMessagesAsRead patch failed for $cleanId: ${response.status}")
                }
            }.onFailure { e ->
                android.util.Log.w("MessageRepository", "markMessagesAsRead patch exception for $cleanId", e)
            }

            if (remotelyReadIds.isEmpty()) {
                remotelyReadIds.addAll(
                    fetchRemoteMessageIdsByStatus(
                        chatId = cleanId,
                        messageIds = cleanMessageIds,
                        currentUserId = currentUserId,
                        token = token,
                        statuses = listOf(Constants.MESSAGE_STATUS_READ)
                    )
                )
            }

            // Keep local UX responsive even if remote confirmation lags.
            val idsToMark = if (remotelyReadIds.isEmpty()) {
                cleanMessageIds
            } else {
                cleanMessageIds.filter { remotelyReadIds.contains(it) }
            }
            if (idsToMark.isEmpty()) return@withContext Result.success(Unit)

            messageDao.markMessagesAsReadByIds(cleanId, currentUserId, now, idsToMark)
            val localUnread = messageDao.countUnreadIncoming(cleanId, currentUserId)
            if (localUnread <= 0) {
                chatDao.markAsRead(cleanId)
                sessionStateStore.rememberLocalReadOverride(cleanId)
            }
            try {
                var notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.cancel(cleanId.hashCode())
            } catch (_: Exception) {
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractUpdatedIdsFromPatchBody(rawBody: String): Set<String> {
        if (rawBody.isBlank()) return emptySet()
        return runCatching {
            var element = Json.parseToJsonElement(rawBody)
            if (element !is JsonArray) return@runCatching emptySet<String>()
            element.mapNotNull { row ->
                var id = row.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                id?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            }.toSet()
        }.getOrDefault(emptySet())
    }

    private suspend fun fetchRemoteMessageIdsByStatus(
        chatId: String,
        messageIds: Collection<String>,
        currentUserId: String,
        token: String,
        statuses: List<String>
    ): Set<String> {
        var cleanChatId = chatId.trim().lowercase()
        val cleanIds = messageIds.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
        val cleanStatuses = statuses
            .map { MessageStatusNormalizer.normalize(it) }
            .filter { it.isNotBlank() }
            .distinct()
        if (cleanChatId.isBlank() || cleanIds.isEmpty() || cleanStatuses.isEmpty()) return emptySet()

        return runCatching {
            var idsFilter = cleanIds.joinToString(",")
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/messages") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("select", "id,status")
                parameter("chat_id", "eq.$cleanChatId")
                parameter("sender_id", "neq.$currentUserId")
                parameter("id", "in.($idsFilter)")
            }
            if (!response.status.isSuccess()) return@runCatching emptySet<String>()
            var rows = runCatching { response.body<List<JsonObject>>() }.getOrDefault(emptyList())
            rows.mapNotNull { row ->
                var status = MessageStatusNormalizer.normalize(row["status"]?.jsonPrimitive?.contentOrNull)
                if (!cleanStatuses.contains(status)) return@mapNotNull null
                row["id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            }.toSet()
        }.getOrElse {
            emptySet()
        }
    }



    suspend fun markMessagesAsRead(chatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("No user"))
            var cleanId = chatId.trim().lowercase()

            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_mark_messages_read") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(buildJsonObject { put("p_chat_id", cleanId) })
            }

            if (response.status.isSuccess()) {
                messageDao.markAllAsRead(cleanId, currentUserId, java.time.Instant.now().toString())
                chatDao.markAsRead(cleanId)
                sessionStateStore.rememberLocalReadOverride(cleanId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Error marking read: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendPollMessage(
        chatId: String,
        question: String,
        options: List<String>,
        allowsMultiple: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var cleanId = chatId.trim().lowercase()
        var userId = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: return@withContext Result.failure(Exception("No user"))

        val sanitizedQuestion = question.trim().take(300)
        val sanitizedOptions = options.map { it.trim().take(120) }.filter { it.isNotBlank() }.distinct()
        if (sanitizedQuestion.isBlank()) return@withContext Result.failure(Exception("La pregunta es obligatoria"))
        if (sanitizedOptions.size < 2) return@withContext Result.failure(Exception("La encuesta requiere al menos 2 opciones"))

        var msgId = UUID.randomUUID().toString()
        var now = java.time.Instant.now().toString()
        val optimistic = Message(
            id = msgId,
            chat_id = cleanId,
            sender_id = userId,
            content = sanitizedQuestion,
            content_type = "poll",
            created_at = now,
            status = "sending"
        )

        messageDao.insertMessage(conectate.com.data.local.entity.MessageEntity.fromDomain(optimistic, isSynced = false, pendingAction = "send"))
        addMessageToFlow(cleanId, optimistic)
        chatDao.updateLastMessage(cleanId, "📊 $sanitizedQuestion", now)

        var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
        
        try {
            var payload = buildJsonObject {
                put("p_chat_id", cleanId)
                put("p_message_id", msgId)
                put("p_question", sanitizedQuestion)
                put("p_options", JsonArray(sanitizedOptions.map { JsonPrimitive(it) }))
                put("p_allows_multiple", allowsMultiple)
            }
            
            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_create_chat_poll_message") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(payload)
            }
            
            if (response.status.isSuccess()) {
                var synced = optimistic.copy(status = "sent")
                messageDao.insertMessage(conectate.com.data.local.entity.MessageEntity.fromDomain(synced, isSynced = true))
                updateMessageInFlow(cleanId, synced)
                Result.success(Unit)
            } else {
                val error = response.bodyAsText()
                Result.failure(Exception("Error al crear encuesta: $error"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    suspend fun findChatByParticipants(normalizedParticipants: List<String>): Result<Chat> {
        return try {
            var token = authService.getAccessToken() 
                ?: return Result.failure(Exception("No token"))
            
            // Query for chats that contain these exact 2 participants
            // Query for chats that contain these exact 2 participants (using contains filter)
            // Use curly braces for Postgres array contains filter (Standard)
            val participantsFilter = "{${normalizedParticipants.joinToString(",")}}"
            
            var response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/chats"
            ) {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("is_group", "eq.false")
                parameter("participants", "cs.$participantsFilter")
            }
            
            if (response.status.isSuccess()) {
                var chats = response.body<List<Chat>>()
                
                // Find all chats with EXACTLY these participants (sorted)
                val matchingChat = chats.filter { chat ->
                    // MUST be exactly 2 participants for a DM
                    var chatParticipants = chat.participants
                        .map { it.trim().lowercase() }
                        .sorted()
                    chatParticipants.size == 2 && chatParticipants == normalizedParticipants
                }.maxByOrNull { it.safeLastMessageAt } // Pick the most active one to avoid ghost IDs
                
                if (matchingChat != null) {
                    android.util.Log.d("MessageRepository", 
                        "findChatByParticipants: Found newest matching chat ${matchingChat.id}")
                    return Result.success(matchingChat)
                }
            }
            
            Result.failure(Exception("No matching chat found"))
        } catch (e: Exception) {
            android.util.Log.e("MessageRepository", "findChatByParticipants error", e)
            Result.failure(e)
        }
    }

    /**
     * Cache a chat locally after finding it remotely
     */
    private suspend fun cacheChat(chat: Chat) {
        scope.launch {
            try {
                var userId = authService.getCurrentUserId() ?: return@launch
                var otherId = chat.participants.firstOrNull { 
                    it.trim().lowercase() != userId.trim().lowercase() 
                }
                
                // Resolve display name
                var displayName = "Usuario"
                var displayImage: String? = null
                
                if (otherId != null) {
                    var cachedUser = userDao.getUser(otherId)
                    if (cachedUser != null) {
                        displayName = cachedUser.full_name.ifBlank { cachedUser.username }
                        displayImage = cachedUser.avatar_url
                    } else {
                        var profileResult = userRepository.getUserById(otherId)
                        var fetchedProfile = profileResult.getOrNull()
                        if (fetchedProfile != null) {
                            displayName = fetchedProfile.displayName.ifBlank { "Usuario" }
                            displayImage = fetchedProfile.avatar_url
                            try {
                                userDao.insertUser(
                                    conectate.com.data.local.entity.UserEntity.fromDomain(fetchedProfile)
                                )
                            } catch (e: Exception) { }
                        }
                    }
                }
                
                var entity = conectate.com.data.local.entity.ChatEntity.fromDomain(
                    chat = chat,
                    displayName = displayName,
                    displayImage = displayImage,
                    unreadCount = 0,
                    lastMsg = chat.last_message,
                    lastTime = chat.last_message_at ?: "", // NO injection here
                    otherUserId = otherId,
                    lastMsgId = chat.last_message_id,
                    lastSenderId = chat.last_message_sender_id,
                    nickname = chat.nickname,
                    clearedAt = chat.cleared_at
                )
                chatDao.insertChat(entity)
                android.util.Log.d("MessageRepository", "Cached chat locally: ${chat.id}")
            } catch (e: Exception) {
                android.util.Log.e("MessageRepository", "Failed to cache chat", e)
            }
        }
    }
    
    
    suspend fun getOrCreateChat(targetUserId: String): Result<Chat> {
        var userId = authService.getCurrentUserId() 
            ?: return Result.failure(Exception("No user"))
        
        // CRITICAL: Normalize participants to ensure same chat is found regardless of who initiates
        val normalizedParticipants = listOf(userId, targetUserId)
            .map { it.trim().lowercase() }
            .sorted()  // Canonical order
        
        android.util.Log.d("MessageRepository", 
            "getOrCreateChat: Looking for chat with participants: $normalizedParticipants")
        
        // 1. Try to find existing chat with these exact participants (remote source of truth)
        var existingChat = findChatByParticipants(normalizedParticipants)
        if (existingChat.isSuccess) {
            var chat = existingChat.getOrThrow()
            android.util.Log.d("MessageRepository", "Found existing chat: ${chat.id}")
            
            // Cache it locally
            cacheChat(chat)
            return existingChat
        }
        
        // 2. NO FALLBACK TO LOCAL CACHE FOR 1-1 CHATS
        // We MUST trust the server as the unique source of truth to avoid 'zombie' IDs
        // from Room interfering with new legit chats.
        // If findChatByParticipants failed, we proceed to create a new, clean one.
        
        // 3. Create new chat (constraint will prevent duplicates at DB level)
        android.util.Log.d("MessageRepository", "Creating new chat (none found on server)")
        return createNewChat(listOf(targetUserId))
    }
    
    suspend fun createGroup(name: String, participants: List<String>): Result<Chat> {
        return createNewChat(participants, isGroup = true, name = name)
    }

    private suspend fun createNewChat(participants: List<String>, isGroup: Boolean = false, name: String? = null): Result<Chat> {
        return try {
             var userId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))
             var token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
             
             // Ensure unique and inclusive (Strict normalization for filtering)
             val allParticipants = (participants + userId)
                 .map { it.trim().lowercase() }
                 .distinct()
                 .sorted() // CRITICAL: Canonical order for uniqueness constraint

             val chatData = buildJsonObject {
                 put("is_group", isGroup)
                 if (name != null) put("group_name", name)
                 // put("created_by", userId) // REMOVED: Column does not exist
                 put("participants", JsonArray(allParticipants.map { JsonPrimitive(it) }))
             }
             
             val res = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/chats") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                setBody(chatData)
             }
             
             if (res.status.isSuccess()) {
                 var chat = res.body<List<Chat>>().first()
                  var cleanChatId = chat.id.trim().lowercase()
                  var cleanUserId = userId.trim().lowercase()
                  var now = java.time.Instant.now().toString()
                  
                  // 3. Persist to Room Immediately (Visibility Insurance)
                  scope.launch {
                      try {
                          var otherId = allParticipants.firstOrNull { it.trim().lowercase() != cleanUserId }
                          
                          // Resolve Name for DM
                          var displayName = "Usuario"
                          var displayImage: String? = null
                          
                          if (otherId != null) {
                              // Try quick cache lookup
                              var cachedUser = userDao.getUser(otherId)
                              if (cachedUser != null) {
                                  displayName = cachedUser.full_name.ifBlank { cachedUser.username }
                                  displayImage = cachedUser.avatar_url
                              } else {
                                  // Quick Fetch
                                  var profileResult = userRepository.getUserById(otherId)
                                  var fetchedProfile = profileResult.getOrNull()
                                  if (fetchedProfile != null) {
                                      displayName = fetchedProfile.displayName.ifBlank {
                                          fetchedProfile.username?.ifBlank { "Usuario" } ?: "Usuario"
                                      }
                                      displayImage = fetchedProfile.avatar_url
                                      // Cache User
                                      try {
                                          userDao.insertUser(conectate.com.data.local.entity.UserEntity.fromDomain(fetchedProfile))
                                      } catch(e: Exception) {
                                           // Ignore entity insert error
                                      }
                                  }
                              }
                          }
                          
                          var entity = conectate.com.data.local.entity.ChatEntity.fromDomain(
                              chat = chat,
                              displayName = displayName,
                              displayImage = displayImage,
                              unreadCount = 0,
                              lastMsg = null,
                              lastTime = now, // Valid now only for BRAND NEW chats
                              otherUserId = otherId,
                              lastMsgId = null,
                              lastSenderId = null
                          )
                          chatDao.insertChat(entity)
                          android.util.Log.d("MessageRepository", "✅ New chat persisted to Room with timestamp: $cleanChatId")
                      } catch (e: Exception) {
                          android.util.Log.e("MessageRepository", "Failed to cache new chat", e)
                      }
                  }

                 // 4. Insert Participants (Critical)
                 // We launch this in parallel but wait for it to ensure consistency before returning
                 coroutineScope {
                     allParticipants.map { pid ->
                         launch {
                             val pData = buildJsonObject {
                                 put("chat_id", chat.id)
                                 put("user_id", pid)
                             }
                             // We ignore individual failures (duplicate key) but log them
                             try {
                                 supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/chat_participants") {
                                    header("Authorization", "Bearer $token")
                                    header("apikey", supabaseConfig.supabaseAnonKey)
                                    header("Content-Type", "application/json")
                                    setBody(pData)
                                 }
                             } catch(e: Exception) {
                                 android.util.Log.e("MessageRepository", "Failed to add participant $pid", e)
                             }
                         }
                     }
                 }
                 
                 getUserChats() // Refresh list
                 Result.success(chat)
             } else {
                 var errorBody = try { res.body<String>() } catch(e: Exception) { "Unknown error body" }
                 android.util.Log.e("MessageRepository", "Create chat failed: ${res.status} - $errorBody")
                 
                 // RECOVERY: If 409 Conflict, it means it exists!
                 if (res.status.value == 409 || errorBody.contains("23505")) {
                     android.util.Log.w("MessageRepository", "⚠️ Conflict detected! Chat already exists. Attempting recovery...")
                     // We know participants, let's FORCE find it
                     delay(500)
                     
                     // Use the participants list to find the chat again but heavily relying on remote
                     val recovery = findChatByParticipants(allParticipants)
                     if (recovery.isSuccess) {
                         android.util.Log.d("MessageRepository", "✅ RECOVERY SUCCESS: Found chat ${recovery.getOrNull()?.id}")
                         val recoveredChat = recovery.getOrThrow()
                         
                         // Ensure it's cached
                         cacheChat(recoveredChat)
                         
                         return Result.success(recoveredChat)
                     } else {
                         // Fallback: Fetch ALL chats for self and filter locally as last resort
                         android.util.Log.w("MessageRepository", "⚠️ Direct recovery failed. Trying brute force recovery...")
                         val allMyChats = getUserChats()
                         if (allMyChats.isSuccess) {
                             var match = allMyChats.getOrNull()?.find { c -> 
                                 !c.chat.is_group && 
                                 c.chat.participants.size == 2 && 
                                 c.chat.participants.map { it.trim().lowercase() }.sorted() == allParticipants
                             }
                             if (match != null) {
                                  android.util.Log.d("MessageRepository", "✅ BRUTE FORCE RECOVERY SUCCESS")
                                  return Result.success(match.chat)
                             }
                         }
                     }
                 }
                 
                 Result.failure(Exception("Create failed: ${res.status} - $errorBody"))
             }
        } catch (e: Exception) { 
            android.util.Log.e("MessageRepository", "Create chat exception", e)
            Result.failure(e) 
        }
    }

    suspend fun getChatByUserId(targetUserId: String): Result<Chat> {
        val cleanTargetId = targetUserId.trim().lowercase()
        // 1. Check DAO (Source of Truth for "known" chats)
        val localEntity = chatDao.getChatByOtherUserId(cleanTargetId)
        if (localEntity != null) {
            return Result.success(localEntity.toDomainWithDetails().chat)
        }

        // 2. Check Memory Cache
        val memoryMatch = _userChats.value.firstOrNull { 
            !it.chat.is_group && 
            it.chat.participants.size == 2 && // Strict size check
            it.chat.participants.any { p -> p.trim().lowercase() == cleanTargetId }
        }?.chat
        
        if (memoryMatch != null) return Result.success(memoryMatch)
        
        // 3. Refresh & Check Remote
        // This fetches latest list from Supabase, so if it exists there, we find it.
        return getUserChats().mapCatching { list ->
             list.firstOrNull { 
                 !it.chat.is_group && 
                 it.chat.participants.size == 2 && // Strict size check
                 it.chat.participants.any { p -> p.trim().lowercase() == cleanTargetId }
             }?.chat ?: throw Exception("No chat found")
        }
    }

     private fun parseMessageFromJson(jsonElement: JsonObject): Message {
        return try {
            // [ROBUST FIX] Use the official serializer to handle nulls and missing fields safely
            val baseMsg = json.decodeFromJsonElement<Message>(jsonElement).withNormalizedStatus()
            
            // Augment with reply_to object if possible (lazy lookup in active flow)
            val replyToId = baseMsg.reply_to_id
            var chatId = baseMsg.chat_id.trim().lowercase()
            
            val augmentedMsg = if (!replyToId.isNullOrBlank()) {
                 baseMsg.copy(
                     reply_to = _activeChatMessages[chatId]?.value?.find { it.id == replyToId }
                 )
            } else {
                baseMsg
            }
            
            return augmentedMsg
        } catch (e: Exception) {
            android.util.Log.e("MessageRepository", "CRITICAL: Failed to parse message JSON", e)
            android.util.Log.e("MessageRepository", "JSON was: $jsonElement")
            
            // Safe fallback to keep the app alive
            Message(
                id = (jsonElement["id"] as? JsonPrimitive)?.contentOrNull ?: UUID.randomUUID().toString(),
                chat_id = (jsonElement["chat_id"] as? JsonPrimitive)?.contentOrNull ?: "",
                sender_id = (jsonElement["sender_id"] as? JsonPrimitive)?.contentOrNull ?: "",
                content = "Error decoding message",
                content_type = "text",
                created_at = java.time.Instant.now().toString()
            )
        }
    }

    private data class ReceiptMergeResult(
        val status: String,
        val deliveredAt: String?,
        val readAt: String?
    )

    private fun normalizedReceiptStatus(status: String?): String {
        return MessageStatusNormalizer.normalize(status)
    }

    private fun receiptStatusRank(status: String): Int {
        return when (normalizedReceiptStatus(status)) {
            "failed" -> 0
            "sending" -> 1
            Constants.MESSAGE_STATUS_SENT -> 2
            Constants.MESSAGE_STATUS_DELIVERED -> 3
            Constants.MESSAGE_STATUS_READ -> 4
            else -> 2
        }
    }

    private fun mergeReceiptState(
        currentStatus: String,
        currentDeliveredAt: String?,
        currentReadAt: String?,
        incomingStatus: String,
        incomingDeliveredAt: String?,
        incomingReadAt: String?
    ): ReceiptMergeResult {
        val current = normalizedReceiptStatus(currentStatus)
        var incoming = normalizedReceiptStatus(incomingStatus)

        val mergedReadAt = incomingReadAt ?: currentReadAt
        var mergedDeliveredAt = incomingDeliveredAt ?: currentDeliveredAt

        val candidate = when {
            !mergedReadAt.isNullOrBlank() || incoming == Constants.MESSAGE_STATUS_READ -> Constants.MESSAGE_STATUS_READ
            !mergedDeliveredAt.isNullOrBlank() || incoming == Constants.MESSAGE_STATUS_DELIVERED -> Constants.MESSAGE_STATUS_DELIVERED
            else -> incoming
        }

        var finalStatus = if (receiptStatusRank(candidate) >= receiptStatusRank(current)) candidate else current
        if (finalStatus == Constants.MESSAGE_STATUS_READ && mergedDeliveredAt.isNullOrBlank()) {
            mergedDeliveredAt = mergedReadAt
        }

        return ReceiptMergeResult(
            status = finalStatus,
            deliveredAt = mergedDeliveredAt,
            readAt = mergedReadAt
        )
    }

    private fun deriveMessageStatus(msg: Message): String {
        return MessageStatusNormalizer.canonical(
            status = msg.status,
            deliveredAt = msg.delivered_at,
            readAt = msg.read_at
        )
    }

    private fun Message.withNormalizedStatus(): Message {
        return copy(status = deriveMessageStatus(this))
    }
    
    suspend fun getChatById(chatId: String): Result<Chat> {
          var cleanId = chatId.trim().lowercase()
          // 1. Try local DAO (Enriched with nicknames/cleared_at)
          var entity = chatDao.getChatById(cleanId)
          if (entity != null) return Result.success(entity.toDomain())

          // 2. Try Memory Cache
          var cached = _userChats.value.find { it.chat.id.trim().lowercase() == cleanId }?.chat
          if (cached != null) return Result.success(cached)

          // 3. Network Fallback
          authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))
          var token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
          
          return try {
              var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/chats") {
                    header("Authorization", "Bearer $token")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("id", "eq.$cleanId")
              }
              if (response.status.isSuccess()) {
                  var c = response.body<List<Chat>>().firstOrNull()
                  if (c != null) Result.success(c) else Result.failure(Exception("Not found"))
              } else Result.failure(Exception("Error HTTP ${response.status}"))
          } catch(e: Exception) {
              Result.failure(e)
          }
    }

    fun getChatFlow(id: String): Flow<Chat?> {
        var cleanId = id.trim().lowercase()
        return chatDao.getChatByIdFlow(cleanId).map { it?.toDomain() }
    }
    


    suspend fun deleteMessage(messageId: String, forEveryone: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var currentUserId = authService.getCurrentUserId()
                ?: return@withContext Result.failure(Exception("No hay usuario"))
            var cleanUserId = currentUserId.trim().lowercase()

            var localMsg = messageDao.getMessageById(messageId)
                ?: return@withContext Result.failure(Exception("Mensaje no encontrado localmente"))

            val pendingDelete = if (forEveryone) {
                localMsg.copy(
                    deleted_for_all = true,
                    is_synced = false,
                    pending_action = "delete"
                )
            } else {
                val currentDeletedBy = parseDeletedBy(localMsg).map { it.trim().lowercase() }
                val newDeletedBy = (currentDeletedBy + cleanUserId).distinct()
                localMsg.copy(
                    deleted_by_json = Json.encodeToString(newDeletedBy),
                    is_synced = false,
                    pending_action = "delete"
                )
            }

            messageDao.insertMessage(pendingDelete)
            updateMessageInFlow(pendingDelete.chat_id.trim().lowercase(), pendingDelete.toDomain())

            var remoteSynced = syncPendingDeleteMessage(pendingDelete, cleanUserId)
            if (remoteSynced) {
                var syncedEntity = pendingDelete.copy(
                    status = "sent",
                    is_synced = true,
                    pending_action = null
                )
                messageDao.insertMessage(syncedEntity)
                updateMessageInFlow(syncedEntity.chat_id.trim().lowercase(), syncedEntity.toDomain())
            } else {
                android.util.Log.w(
                    "MessageRepository",
                    "Delete queued offline for message=$messageId, forEveryone=$forEveryone"
                )
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    suspend fun editMessage(msg: Message, newContent: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No hay sesión"))
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("No hay usuario"))
            var cleanMessageId = msg.id.trim()
            val normalizedContent = newContent.trim()
            if (cleanMessageId.isBlank()) {
                return@withContext Result.failure(Exception("Mensaje inválido"))
            }
            if (normalizedContent.isBlank()) {
                return@withContext Result.failure(Exception("El mensaje no puede estar vacío"))
            }
            if (normalizedContent.length > 4096) {
                return@withContext Result.failure(Exception("El mensaje es demasiado largo"))
            }

            var localMsg = messageDao.getMessageById(cleanMessageId)
            val source = localMsg?.toDomain() ?: msg
            if (source.content_type != "text") {
                return@withContext Result.failure(Exception("Solo se pueden editar mensajes de texto"))
            }
            if (source.deleted_for_all) {
                return@withContext Result.failure(Exception("No se puede editar un mensaje eliminado"))
            }
            if (source.sender_id.trim().lowercase() != currentUserId) {
                return@withContext Result.failure(Exception("Solo puedes editar tus propios mensajes"))
            }

            var patchPayload = buildJsonObject {
                put("content", normalizedContent)
                put("is_edited", true)
            }

            var response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/messages") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                parameter("id", "eq.$cleanMessageId")
                parameter("sender_id", "eq.$currentUserId")
                parameter("deleted_for_all", "eq.false")
                setBody(patchPayload)
            }

            if (!response.status.isSuccess()) {
                var body = runCatching { response.bodyAsText() }.getOrDefault("")
                return@withContext Result.failure(
                    Exception("No se pudo editar el mensaje (${response.status.value}). $body".trim())
                )
            }

            val updatedRows = runCatching { response.body<List<JsonObject>>() }.getOrDefault(emptyList())
            if (updatedRows.isEmpty()) {
                return@withContext Result.failure(
                    Exception("No se pudo editar el mensaje (sin cambios o sin permisos)")
                )
            }

            var updated = (localMsg ?: conectate.com.data.local.entity.MessageEntity.fromDomain(source, isSynced = true)).copy(
                content = normalizedContent,
                is_edited = true,
                is_synced = true,
                pending_action = null
            )
            messageDao.insertMessage(updated)
            updateMessageInFlow(updated.chat_id.trim().lowercase(), updated.toDomain())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    data class MessageReactionToggleResult(
        val messageId: String,
        val reactions: Map<String, List<String>>
    )



    suspend fun toggleMessageReaction(messageId: String, emoji: String): Result<MessageReactionToggleResult> = withContext(Dispatchers.IO) {
        try {
            var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            var userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("No user"))
            var local = messageDao.getMessageById(messageId)
                ?: return@withContext Result.failure(Exception("Mensaje no encontrado"))

            val normalizedEmoji = emoji.trim()
            if (normalizedEmoji.isBlank()) {
                return@withContext Result.failure(Exception("Reacción inválida"))
            }

            var rpcPayload = buildJsonObject {
                put("p_message_id", messageId)
                put("p_emoji", normalizedEmoji.take(16))
            }

            var rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_toggle_message_reaction") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(rpcPayload)
            }

            val updatedReactions = if (rpcResponse.status.isSuccess()) {
                var raw = runCatching { rpcResponse.bodyAsText() }.getOrNull().orEmpty()
                parseReactionToggleResponse(raw, messageId)?.reactions
                    ?: toggleReactionMap(local.toDomain().reactions, normalizedEmoji, userId)
            } else {
                val rpcBody = runCatching { rpcResponse.bodyAsText() }.getOrNull().orEmpty()
                if (isMissingRpcFunction(rpcResponse.status.value, rpcBody, "secure_toggle_message_reaction")) {
                    val patched = toggleReactionMap(local.toDomain().reactions, normalizedEmoji, userId)
                    var patchPayload = buildJsonObject {
                        put("reactions", reactionsToJsonObject(patched))
                    }
                    var patchResponse = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/messages") {
                        header("Authorization", "Bearer $token")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Content-Type", "application/json")
                        parameter("id", "eq.$messageId")
                        setBody(patchPayload)
                    }
                    if (!patchResponse.status.isSuccess()) {
                        return@withContext Result.failure(Exception("No se pudo guardar reacción"))
                    }
                    patched
                } else {
                    return@withContext Result.failure(Exception("No se pudo reaccionar: $rpcBody"))
                }
            }

            var updated = local.toDomain().copy(reactions = updatedReactions)
            messageDao.insertMessage(
                conectate.com.data.local.entity.MessageEntity.fromDomain(
                    msg = updated,
                    isSynced = local.is_synced,
                    pendingAction = local.pending_action
                )
            )
            updateMessageInFlow(updated.chat_id.trim().lowercase(), updated)

            Result.success(MessageReactionToggleResult(messageId = updated.id, reactions = updatedReactions))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun forwardMessageToChats(
        source: Message,
        targetChatIds: List<String>
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (source.deleted_for_all) {
            return@withContext Result.failure(Exception("No se puede reenviar un mensaje eliminado"))
        }

        val uniqueTargets = targetChatIds
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()

        if (uniqueTargets.isEmpty()) {
            return@withContext Result.failure(Exception("Selecciona al menos un chat"))
        }
        if (uniqueTargets.size > MAX_FORWARD_TARGETS_PER_BATCH) {
            return@withContext Result.failure(Exception("Puedes reenviar a un máximo de $MAX_FORWARD_TARGETS_PER_BATCH chats por vez"))
        }

        var successCount = 0
        val errors = mutableListOf<String>()

        uniqueTargets.forEach { targetChatId ->
            var content = buildPreviewForContentType(
                contentType = source.content_type,
                fallbackContent = source.content.ifBlank { "Mensaje reenviado" }
            )

            val sendResult = sendMessage(
                chatId = targetChatId,
                content = content,
                mediaType = source.content_type,
                existingMediaUrl = source.media_url,
                isForwarded = true,
                forwardedFromMessageId = source.id,
                forwardedFromUserId = source.sender_id
            )

            if (sendResult.isSuccess) {
                successCount += 1
            } else {
                errors += (sendResult.exceptionOrNull()?.message ?: "Error desconocido")
            }
        }

        if (successCount == 0) {
            return@withContext Result.failure(Exception(errors.firstOrNull() ?: "No se pudo reenviar"))
        }

        Result.success(successCount)
    }

    private fun toggleReactionMap(
        current: Map<String, List<String>>,
        emoji: String,
        userId: String
    ): Map<String, List<String>> {
        var normalized = current.mapValues { (_, users) ->
            users.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().toMutableList()
        }.toMutableMap()

        var hadSameReaction = false
        normalized.keys.toList().forEach { key ->
            var users = normalized[key] ?: mutableListOf()
            val removed = users.remove(userId)
            if (key == emoji && removed) {
                hadSameReaction = true
            }
            if (users.isEmpty()) normalized.remove(key) else normalized[key] = users
        }

        if (!hadSameReaction) {
            val targetUsers = normalized[emoji]?.toMutableList() ?: mutableListOf()
            if (!targetUsers.contains(userId)) targetUsers.add(userId)
            normalized[emoji] = targetUsers
        }

        return normalized.mapValues { (_, users) -> users.distinct() }.filterValues { it.isNotEmpty() }
    }

    private fun reactionsToJsonObject(reactions: Map<String, List<String>>): JsonObject {
        return buildJsonObject {
            reactions.forEach { (emoji, users) ->
                val cleanedUsers = users.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
                if (emoji.isNotBlank() && cleanedUsers.isNotEmpty()) {
                    put(emoji, JsonArray(cleanedUsers.map { JsonPrimitive(it) }))
                }
            }
        }
    }

    private fun parseReactionToggleResponse(raw: String, fallbackMessageId: String): MessageReactionToggleResult? {
        if (raw.isBlank()) return null
        return runCatching {
            var element = json.parseToJsonElement(raw)
            when (element) {
                is JsonObject -> {
                    var msgId = element["message_id"]?.jsonPrimitive?.contentOrNull ?: fallbackMessageId
                    var reactionsElement = element["reactions"] ?: element
                    MessageReactionToggleResult(msgId, parseReactionMap(reactionsElement))
                }
                is JsonArray -> {
                    val first = element.firstOrNull() as? JsonObject ?: return@runCatching null
                    var msgId = first["message_id"]?.jsonPrimitive?.contentOrNull ?: fallbackMessageId
                    var reactionsElement = first["reactions"] ?: first
                    MessageReactionToggleResult(msgId, parseReactionMap(reactionsElement))
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun parseReactionMap(element: JsonElement?): Map<String, List<String>> {
        val obj = element as? JsonObject ?: return emptyMap()
        return obj.mapNotNull { (emoji, usersElement) ->
            val usersArray = usersElement as? JsonArray ?: return@mapNotNull null
            var users = usersArray.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
            if (emoji.isBlank() || users.isEmpty()) null else emoji to users
        }.toMap()
    }
    
    suspend fun getPlayableUrl(path: String): Result<String> = mediaResolver.getPlayableUrl(path)

    fun getShareableUrl(path: String): Result<String> = mediaResolver.getShareableUrl(path)

    suspend fun consumeViewOnceMedia(
        scopeType: String,
        messageId: String,
        mediaPath: String,
        deviceId: String = android.os.Build.MODEL ?: "android"
    ): Result<String> = mediaResolver.consumeViewOnceMedia(
        scopeType = scopeType,
        messageId = messageId,
        mediaPath = mediaPath,
        deviceId = deviceId
    )

    private suspend fun syncChatPreferencesRemote(
        chatId: String,
        token: String,
        userId: String,
        isMuted: Boolean?,
        isPinned: Boolean?,
        isArchived: Boolean?,
        isFavorite: Boolean?
    ) {
        var rpcPayload = buildJsonObject {
            put("p_chat_id", chatId)
            if (isMuted != null) put("p_is_muted", isMuted)
            if (isPinned != null) put("p_is_pinned", isPinned)
            if (isArchived != null) put("p_is_archived", isArchived)
            if (isFavorite != null) put("p_is_favorite", isFavorite)
        }

        var rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_set_chat_preferences") {
            header("Authorization", "Bearer $token")
            header("apikey", supabaseConfig.supabaseAnonKey)
            header("Content-Type", "application/json")
            setBody(rpcPayload)
        }

        if (rpcResponse.status.isSuccess()) return

        val rpcFailedBody = runCatching { rpcResponse.bodyAsText() }.getOrDefault("")
        android.util.Log.w(
            "MessageRepository",
            "secure_set_chat_preferences failed (${rpcResponse.status.value}). Trying REST fallback. Body=$rpcFailedBody"
        )
        if (isMissingPreferenceColumnError(rpcFailedBody)) {
            throw Exception("Supabase chat_participants no tiene columnas de preferencias de chat. Ejecuta el SQL final.")
        }

        var patchPayload = buildJsonObject {
            if (isMuted != null) put("is_muted", isMuted)
            if (isPinned != null) put("is_pinned", isPinned)
            if (isArchived != null) put("is_archived", isArchived)
            if (isFavorite != null) put("is_favorite", isFavorite)
        }

        var patchResponse = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/chat_participants") {
            header("Authorization", "Bearer $token")
            header("apikey", supabaseConfig.supabaseAnonKey)
            header("Content-Type", "application/json")
            header("Prefer", "return=representation")
            parameter("chat_id", "eq.$chatId")
            parameter("user_id", "eq.$userId")
            setBody(patchPayload)
        }

        if (patchResponse.status.isSuccess()) return

        val patchBody = runCatching { patchResponse.bodyAsText() }.getOrDefault("")
        if (isMissingPreferenceColumnError(patchBody)) {
            throw Exception("Supabase chat_participants no tiene columnas de preferencias de chat. Ejecuta el SQL final.")
        }
        android.util.Log.w(
            "MessageRepository",
            "chat_participants PATCH fallback failed (${patchResponse.status.value}). Trying UPSERT. Body=$patchBody"
        )

        val upsertPayload = buildJsonObject {
            put("chat_id", chatId)
            put("user_id", userId)
            if (isMuted != null) put("is_muted", isMuted)
            if (isPinned != null) put("is_pinned", isPinned)
            if (isArchived != null) put("is_archived", isArchived)
            if (isFavorite != null) put("is_favorite", isFavorite)
        }

        val upsertResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/chat_participants") {
            header("Authorization", "Bearer $token")
            header("apikey", supabaseConfig.supabaseAnonKey)
            header("Content-Type", "application/json")
            header("Prefer", "resolution=merge-duplicates,return=representation")
            parameter("on_conflict", "chat_id,user_id")
            setBody(upsertPayload)
        }

        if (!upsertResponse.status.isSuccess()) {
            val upsertBody = runCatching { upsertResponse.bodyAsText() }.getOrDefault("")
            if (isMissingPreferenceColumnError(upsertBody)) {
                throw Exception("Supabase chat_participants no tiene columnas de preferencias de chat. Ejecuta el SQL final.")
            }
            if (rpcFailedBody.isNotBlank()) {
                throw Exception("Error setting chat preferences: ${rpcResponse.status.value}")
            }
            throw Exception("Error creating chat preferences: ${upsertResponse.status.value}")
        }
    }

    private suspend fun applyLocalChatPreferences(
        chatId: String,
        isMuted: Boolean?,
        isPinned: Boolean?,
        isArchived: Boolean?,
        isFavorite: Boolean?
    ) {
        isMuted?.let { chatDao.updateMuteState(chatId, it) }
        isPinned?.let { chatDao.updatePinState(chatId, it) }
        isArchived?.let { chatDao.updateArchiveState(chatId, it) }
        isFavorite?.let { chatDao.updateFavoriteState(chatId, it) }

        _userChats.value = sortChatsByPriority(
            _userChats.value.map { conversation ->
                if (conversation.chat.id.trim().lowercase() != chatId) {
                    conversation
                } else {
                    conversation.copy(
                        chat = conversation.chat.copy(
                            is_muted = isMuted ?: conversation.chat.is_muted,
                            is_pinned = isPinned ?: conversation.chat.is_pinned,
                            is_archived = isArchived ?: conversation.chat.is_archived,
                            is_favorite = isFavorite ?: conversation.chat.is_favorite
                        )
                    )
                }
            }
        )
    }

    private suspend fun updateChatPreferences(
        chatId: String,
        isMuted: Boolean?,
        isPinned: Boolean?,
        isArchived: Boolean?,
        isFavorite: Boolean?
    ): Result<Unit> {
        return try {
            var currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))
            var token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            var cleanId = chatId.trim().lowercase()
            var cleanUserId = currentUserId.trim().lowercase()

            syncChatPreferencesRemote(
                chatId = cleanId,
                token = token,
                userId = cleanUserId,
                isMuted = isMuted,
                isPinned = isPinned,
                isArchived = isArchived,
                isFavorite = isFavorite
            )
            applyLocalChatPreferences(
                chatId = cleanId,
                isMuted = isMuted,
                isPinned = isPinned,
                isArchived = isArchived,
                isFavorite = isFavorite
            )
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("MessageRepository", "updateChatPreferences failed", e)
            Result.failure(e)
        }
    }



    suspend fun muteChat(chatId: String, isMuted: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        updateChatPreferences(chatId = chatId, isMuted = isMuted, isPinned = null, isArchived = null, isFavorite = null)
    }



    suspend fun pinChat(chatId: String, isPinned: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        updateChatPreferences(chatId = chatId, isMuted = null, isPinned = isPinned, isArchived = null, isFavorite = null)
    }

    suspend fun archiveChat(chatId: String, isArchived: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        updateChatPreferences(chatId = chatId, isMuted = null, isPinned = null, isArchived = isArchived, isFavorite = null)
    }

    suspend fun favoriteChat(chatId: String, isFavorite: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        updateChatPreferences(chatId = chatId, isMuted = null, isPinned = null, isArchived = null, isFavorite = isFavorite)
    }

    fun isChatMutedLocally(chatId: String): Boolean {
        var cleanId = chatId.trim().lowercase()
        if (cleanId.isBlank()) return false
        return runCatching {
            chatDao.getChatByIdSync(cleanId)?.is_muted == true
        }.getOrDefault(false)
    }
    
    suspend fun deleteChat(chatId: String): Result<Unit> {
        return try {
            var cleanId = chatId.trim().lowercase()
            sessionStateStore.clearLocalReadOverride(cleanId)
            // Soft delete from Room (marks as deleted, hidden from UI)
            chatDao.deleteChat(cleanId)
            
            // Remove from in-memory cache
            var currentChats = _userChats.value.toMutableList()
            currentChats.removeAll { it.chat.id.trim().lowercase() == cleanId }
            _userChats.value = currentChats
            
            android.util.Log.d("MessageRepository", "Chat deleted successfully: $chatId")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("MessageRepository", "Error deleting chat", e)
            Result.failure(e)
        }
    }
    
    suspend fun reportContent(chatId: String, reason: String): Result<Unit> {
        var cleanChatId = chatId.trim().lowercase()
        if (cleanChatId.isBlank()) return Result.failure(Exception("Chat inválido"))

        var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: return Result.failure(Exception("No user logged in"))
        var chat = getChatById(cleanChatId).getOrNull()
            ?: return Result.failure(Exception("No se encontró la conversación"))

        val reportedUserId = chat.participants
            .map { it.trim().lowercase() }
            .firstOrNull { it.isNotBlank() && it != currentUserId }
            ?: return Result.failure(Exception("No se pudo identificar al usuario reportado"))
        val reportedUser = userRepository.getUserById(reportedUserId).getOrNull()
        val reportedName = reportedUser?.displayName ?: reportedUser?.username ?: "Usuario"

        return reportStore.submitReport(
            targetId = reportedUserId,
            name = reportedName,
            reason = reason,
            desc = "Reporte generado desde la conversación $cleanChatId.",
            uri = null
        )
    }
    suspend fun submitReport(targetId: String, name: String, reason: String, desc: String, uri: Uri?): Result<Unit> =
        reportStore.submitReport(
            targetId = targetId,
            name = name,
            reason = reason,
            desc = desc,
            uri = uri
        )

    suspend fun getMyReports(): Result<List<conectate.com.data.model.Report>> = reportStore.getMyReports()

    private fun resolveMediaUploadMeta(mediaType: String, mediaUri: Uri?, bytes: ByteArray? = null): Pair<String, String> {
        var declaredMime = mediaUri?.let { context.contentResolver.getType(it) }
        var guessedMime = guessMimeTypeFromUri(mediaUri)
        var inferredMime = bytes?.let(::inferMimeTypeFromBytes)
        val resolved = resolveMediaMeta(
            preferredMediaType = mediaType,
            declaredMime = declaredMime,
            guessedMime = guessedMime,
            inferredMime = inferredMime
        )
        return resolved.mimeType to resolved.extensionWithDot
    }

    private fun buildPreviewForContentType(contentType: String, fallbackContent: String): String {
        buildConversationBackgroundPreview(contentType, fallbackContent)?.let { return it }
        buildCallEventPreview(contentType, fallbackContent)?.let { return it }
        return when (contentType.trim().lowercase()) {
            "image" -> "📷 Foto"
            "video" -> "🎥 Video"
            "audio" -> "🎤 Audio"
            "view_once_image" -> "👁️ Foto ver una vez"
            "view_once_video" -> "👁️ Video ver una vez"
            "gif" -> "🌀 GIF"
            "sticker" -> "🧩 Sticker"
            "document", "file" -> "📎 Archivo"
            "location" -> "📍 Ubicación"
            "contact" -> "👤 Contacto"
            "poll" -> "📊 Encuesta"
            else -> fallbackContent.ifBlank { "Archivo" }
        }
    }


    // --- COMPATIBILITY & ADMIN ---

    suspend fun handleIncomingMessage(msg: Message) {
        // Manually inject into flow for instant update if Realtime hasn't caught it yet
        android.util.Log.d("MessageRepository", "FCM Message received: ${msg.content}, chat_id: ${msg.chat_id}")
        handleGlobalMessageInsert(msg, currentSessionUserId())
    }

    suspend fun getAllReports(): Result<List<conectate.com.data.model.Report>> = reportStore.getAllReports()

    // --- FALLBACK PROFILE RESOLUTION ---
    fun getCachedUserProfile(chatId: String): conectate.com.data.model.User? {
        var cleanId = chatId.trim().lowercase()
        // 1. Try In-Memory Cache
        var cached = _userChats.value.find { it.chat.id.trim().lowercase() == cleanId }
        if (cached != null) return userFromCachedChat(cached)
        
        // 2. Try Local Room Sync
        return runCatching {
            var entity = chatDao.getChatByIdSync(cleanId) // Need to add this to DAO or use a blocking call
            if (entity != null) {
                var cachedUser = entity.other_user_id?.let { userDao.getUserSync(it) }
                var resolvedName = entity.display_name
                    ?.takeIf { it.isNotBlank() }
                    ?: cachedUser?.full_name?.takeIf { it.isNotBlank() }
                    ?: cachedUser?.username
                conectate.com.data.model.User(
                    id = entity.other_user_id ?: cachedUser?.id.orEmpty(),
                    display_name = resolvedName,
                    avatar_url = entity.display_image ?: cachedUser?.avatar_url,
                    username = resolvedName ?: cachedUser?.username ?: "Usuario"
                )
            } else null
        }.getOrNull()
    }

    fun getCachedUserProfileByUserId(userId: String): conectate.com.data.model.User? {
        var cleanUserId = userId.trim().lowercase()
        var cached = _userChats.value.find {
            !it.chat.is_group && it.chat.participants.any { participant ->
                participant.trim().lowercase() == cleanUserId
            }
        } ?: return null
        return userFromCachedChat(cached)
    }



    suspend fun refreshDirectChatIdentity(chatId: String, otherUserId: String): conectate.com.data.model.User? = withContext(Dispatchers.IO) {
        var cleanChatId = chatId.trim().lowercase()
        var cleanUserId = otherUserId.trim().lowercase()
        if (cleanChatId.isBlank() || cleanUserId.isBlank()) return@withContext null

        var profile = userRepository.getUserById(cleanUserId).getOrNull()
            ?: getCachedUserProfile(cleanChatId)
            ?: getCachedUserProfileByUserId(cleanUserId)
            ?: return@withContext null

        runCatching {
            userDao.insertUser(conectate.com.data.local.entity.UserEntity.fromDomain(profile))
        }

        var resolvedName = profile.displayName.ifBlank { profile.username ?: "Usuario" }
        var resolvedImage = profile.avatar_url

        runCatching {
            chatDao.getChatById(cleanChatId)?.let { entity ->
                chatDao.insertChat(
                    entity.copy(
                        display_name = resolvedName,
                        display_image = resolvedImage,
                        other_user_id = cleanUserId
                    )
                )
            }
        }.onFailure {
            android.util.Log.w("MessageRepository", "Failed to refresh DM identity for $cleanChatId", it)
        }

        _userChats.update { chats ->
            chats.map { item ->
                if (item.chat.id.trim().lowercase() != cleanChatId || item.chat.is_group) {
                    item
                } else {
                    item.copy(
                        chat = item.chat.copy(
                            group_name = resolvedName,
                            group_image = resolvedImage
                        ),
                        otherUserName = resolvedName,
                        otherUserImage = resolvedImage,
                        otherUserId = cleanUserId
                    )
                }
            }
        }

        profile
    }

    private fun userFromCachedChat(cached: ChatWithLastMessage): conectate.com.data.model.User {
        // Construct a partial User object from display info
        // This ensures that if the list shows a name, the chat header shows it too.
        return conectate.com.data.model.User(
            id = cached.otherUserId ?: "", 
            display_name = cached.otherUserName,
            avatar_url = cached.otherUserImage,
            username = cached.otherUserName ?: ""
        )
    }

    suspend fun updateReportStatus(reportId: String, newStatus: String): Result<Unit> =
        reportStore.updateReportStatus(reportId, newStatus)

    suspend fun deleteReport(reportId: String): Result<Unit> = reportStore.deleteReport(reportId)
}





