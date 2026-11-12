package conectate.com.data.repository

import conectate.com.core.media.readMediaBytesWithLimit

import androidx.room.withTransaction
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import conectate.com.BuildConfig
import conectate.com.core.concurrency.newSafeScope
import conectate.com.core.media.ChatMediaOfflineCache
import conectate.com.core.messaging.MessageStatusNormalizer
import conectate.com.core.messaging.PendingMessageSyncPolicy
import conectate.com.data.local.prefs.MessagesPreferences
import conectate.com.data.model.Chat
import conectate.com.data.model.ChatParticipant
import conectate.com.data.model.ChatWithLastMessage
import conectate.com.data.model.CommunityMessage
import conectate.com.data.model.GroupMessage
import conectate.com.data.model.Message
import conectate.com.data.repository.message.MessageCacheManager
import conectate.com.data.repository.message.MessageRealtimeSync
import conectate.com.data.repository.message.MessageNotificationService
import conectate.com.data.repository.message.MessageSessionStateStore
import conectate.com.data.repository.message.MessageMediaResolver
import conectate.com.data.repository.message.InboxSyncPolicy
import conectate.com.data.repository.message.InboxSyncState
import conectate.com.data.repository.message.InboxIdentityCache
import conectate.com.data.repository.message.mergeInboxConversationIdentity
import conectate.com.data.repository.message.matchesExactDirectParticipants
import conectate.com.data.repository.message.normalizedParticipantIds
import conectate.com.data.repository.message.selectCachedDirectChat
import conectate.com.data.repository.message.PendingMessageBatchRunner
import conectate.com.data.repository.message.PendingSendOutcome
import conectate.com.data.repository.message.MessageSendCoordinator
import conectate.com.data.repository.message.AccountChatReader
import conectate.com.data.repository.message.accountScopedChatFlow
import conectate.com.data.repository.message.isAccessibleTo
import conectate.com.data.repository.message.SessionListenerBinding
import conectate.com.data.repository.message.MessageDeletionQueue
import conectate.com.data.repository.message.MessageDeletionTransport
import conectate.com.data.repository.message.applyMessageDeletions
import conectate.com.domain.sync.PendingSyncRetryException
import conectate.com.data.repository.message.ChatMediaPrefetchCandidate
import conectate.com.data.repository.message.selectRecentChatMediaForPrefetch
import conectate.com.data.repository.message.MessageReportStore
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.StorageService
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.data.sync.PendingMessageSyncScheduler
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.MessageSendHttpException
import conectate.com.data.localfallback.isRetryableMessageHttpStatus
import conectate.com.data.repository.message.executeMessageSend
import conectate.com.data.repository.message.distinctHistoryAccess
import conectate.com.data.localfallback.LocalFallbackInboxItem
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.local.entity.ChatEntity
import conectate.com.core.messaging.UnreadPolicy
import conectate.com.core.network.InteractiveReadDeadlines
import conectate.com.core.network.runReadWithDeadline
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
import conectate.com.util.fileExtensionFromName
import conectate.com.util.inferMimeTypeFromBytes
import conectate.com.util.isCallEventContentType
import conectate.com.util.resolveMediaMeta
import conectate.com.util.VideoUtils
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
import java.io.File
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
    private val database: conectate.com.data.local.AppDatabase,
    private val moderationService: ContentModerationService,
    private val appMetrics: AppMetrics,
    private val operationGuard: OperationGuard,
    private val chatSoundPlayer: ChatSoundPlayer,
    @param:ApplicationContext private val context: Context,
    // Nuevos stores inyectados
    private val messageCacheManager: MessageCacheManager,
    private val messageRealtimeSync: MessageRealtimeSync,
    private val messageNotificationService: MessageNotificationService,
    private val sessionStateStore: MessageSessionStateStore,
    private val inboxIdentityCache: InboxIdentityCache,
    private val readJournal: conectate.com.data.repository.message.VisibleReadJournal = conectate.com.data.repository.message.VisibleReadJournal(context),
    private val readSynchronizer: conectate.com.data.repository.message.VisibleReadSynchronizer = conectate.com.data.repository.message.VisibleReadSynchronizer(readJournal, localFallbackApiClient, authService),
) : ChatSyncDataSource {
    companion object {
        private const val MAX_FORWARD_TARGETS_PER_BATCH = 20
    }

    private val deletedMessageStore by lazy { conectate.com.util.DeletedMessageStore(context) }

    suspend fun handleDeletedMessage(identity: conectate.com.util.DeletedMessageIdentity, createdAt: String? = null, channelId: String? = null) {
        if (identity.targetType != "chat" || currentSessionUserId()?.trim()?.lowercase() != identity.accountId) return
        val session = captureSessionSnapshot(identity.accountId)
        if (!isSessionSnapshotValid(session, "deleted-message-start")) return
        conectate.com.util.MessageNotificationFactory.redactMessage(context, identity, createdAt, channelContextId = channelId)
        messageDao.redactDeletedMessage(identity.conversationId, identity.messageId)
        if (!isSessionSnapshotValid(session, "deleted-message-preview")) return
        chatDao.redactDeletedPreview(identity.conversationId, identity.messageId)
        val local = messageDao.getMessageById(identity.messageId)?.toDomain()
        if (local != null && local.chat_id == identity.conversationId && isSessionSnapshotValid(session, "deleted-message-flow")) {
            updateMessageInFlow(identity.conversationId, deletedMessageStore.redact(local, identity.accountId))
        }
    }

    private val scope = newSafeScope("MessageRepository", Dispatchers.IO)
    private val mediaPrefetchGate = conectate.com.data.repository.message.ChatMediaPrefetchGate(scope)
    private var json = Json { 
        ignoreUnknownKeys = true 
        coerceInputValues = true
        isLenient = true
    }
    private val transientNetworkRetryCodes = listOf("timeout", "timed out", "connection", "network", "502", "503", "504")

    // --- STATE ---
    
    // 1. User Chats List (Global)
    private val _userChats = MutableStateFlow<List<ChatWithLastMessage>>(emptyList())
    val userChats: Flow<List<ChatWithLastMessage>> get() = observeVisibleInbox()

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
    private val inboxSyncMutex = Mutex()
    private val messageSendCoordinator = MessageSendCoordinator()
    private val notificationMutePreferences = conectate.com.util.ConversationNotificationPrefs(context)
    private val inboxPreviewReader = conectate.com.data.repository.message.InboxPreviewReader(
        database, ::currentSessionUserId, deletedMessageStore.changes,
        preferenceChanges = notificationMutePreferences.observeChanges(),
        isChatMuted = { account, chatId, remote -> remote || notificationMutePreferences.isMuted("chat", chatId, account) },
    ) { message, account -> deletedMessageStore.redact(message, account) }
    private val deletionTransport = MessageDeletionTransport(localFallbackApiClient, supabaseConfig, ::shouldUseUbuntuBackend)
    private val deletionQueue = MessageDeletionQueue(database, messageSendCoordinator,
        { authService.ensureValidAccessToken() }, deletionTransport::delete)
    private val _inboxSyncState = MutableStateFlow(InboxSyncState())
    val inboxSyncState: StateFlow<InboxSyncState> = _inboxSyncState.asStateFlow()

    // 6. Transient ID Mapper (FCM -> UUID) allows replies to work even during race conditions
    private val transientIdMap = ConcurrentHashMap<String, String>()
    private val sessionWriteGuard = SessionWriteGuard { authService.getCurrentUserId() }
    private fun automaticMediaAllowed(): Boolean = MessagesPreferences.isMediaAutoDownloadEnabled(context) &&
        conectate.com.data.local.prefs.AppSettingsPreferences.isAutomaticMediaDownloadAllowed(context)
    private val receivedImageGallery = conectate.com.core.media.ReceivedChatImageGallery(
        context, { authService.getCurrentUserId() },
        { MessagesPreferences.areReceivedImagesSavedToGallery(context) }
    )
    private val offlineMediaCache = ChatMediaOfflineCache.from(
        context = context,
        accountIdProvider = { authService.getCurrentUserId() },
        automaticMediaEnabled = ::automaticMediaAllowed,
        onCachedMedia = receivedImageGallery::onCachedMedia
    )
    private val mediaResolver = MessageMediaResolver(
        storageService = storageService,
        authService = authService,
        supabaseConfig = supabaseConfig,
        sessionStateStore = sessionStateStore,
        offlineMediaCache = offlineMediaCache,
        context = context
    )
    private val reportStore = MessageReportStore(
        context = context,
        supabaseConfig = supabaseConfig,
        authService = authService,
        storageService = storageService,
        localFallbackApiClient = localFallbackApiClient,
        userRepository = userRepository,
        moderationService = moderationService,
        appMetrics = appMetrics,
        operationGuard = operationGuard
    )
    private val sessionListeners: SessionListenerBinding

    private fun shouldUseUbuntuBackend(): Boolean =
        localFallbackApiClient.isConfigured &&
            (supabaseConfig.isLocalFallbackPrimary || authService.isLocalFallbackSession())

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
        // Room history and the durable outbox survive process/session restarts.
        // Identity repairs must be scoped to individual rows, never a startup wipe.
        // Start Global Realtime Listener using new MessageRealtimeSync component
        val userIdFlow: Flow<String?> = if (supabaseConfig.isLocalFallbackPrimary) {
                authService.currentSession.map { session ->
                    session?.user?.id?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                }
            } else {
                supabaseConfig.client.auth.sessionStatus.map { status ->
                    if (status is SessionStatus.Authenticated) {
                        (status.session.user?.id ?: "").trim().lowercase().takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }
            }
        sessionListeners = SessionListenerBinding(scope, userIdFlow, messageRealtimeSync::clearAll) { userId ->
                    val session = captureSessionSnapshot(userId)
                    android.util.Log.d(
                        "MessageRepository",
                        "[RECEIVE-FIX] Session active ($userId): Starting MessageRealtimeSync",
                    )
                    messageRealtimeSync.start(userId)

                    launch {
                        messageRealtimeSync.messageEvents.collect { msg ->
                            if (isSessionSnapshotValid(session, "session-message-event")) messageEvents.emit(msg)
                        }
                    }

                    launch {
                        messageCacheManager.cachedChats.collect { chats ->
                            if (isSessionSnapshotValid(session, "session-chat-cache")) {
                                _userChats.value = chats.filter { it.chat.isAccessibleTo(userId) }
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
        _inboxSyncState.value = InboxSyncState()
        _userChats.value = emptyList()
        _activeChatMessages.clear()
        chatHistoryFetchTimestamps.clear()
        transientIdMap.clear()
        chatMutexes.clear()
        sessionStateStore.clearAll()
        // Nuevos stores
        messageCacheManager.clearAll()
        sessionListeners.stopActive()
    }

    fun setActiveChatSession(chatId: String?, isVisible: Boolean) {
        sessionStateStore.setActiveChatSession(chatId, isVisible)
        if (isVisible) {
            chatId
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotBlank() && !it.startsWith("placeholder_") }
                ?.let(messageNotificationService::cancelNotificationForChat)
        }
    }

    // --- REALTIME: Delegado a MessageRealtimeSync ---
    // Los métodos de realtime se manejan ahora en MessageRealtimeSync
    // Esta clase actúa como facade/orquestador

    private suspend fun handleGlobalMessageInsert(
        msg: Message,
        lease: conectate.com.data.supabase.AuthSessionLease,
    ) {
        lease.requireCurrent()
        val normalizedMsg = deletedMessageStore.redact(msg.withNormalizedStatus(), lease.accountId)
        val cleanId = normalizedMsg.chat_id.trim().lowercase()
        val sessionSnapshot = sessionWriteGuard.capture(lease, operationJob = null)
        if (!isSessionSnapshotValid(sessionSnapshot, "dm-insert-start:$cleanId")) return
        android.util.Log.d("MessageRepository", "Incoming message received for the active account")
        
        var currentUserId = sessionSnapshot.accountId
        var wasAlreadyStored = false
        var mediaMessageToPrefetch: Message? = null
        
        // 1. ATOMIC PROCESSING (Room + UI Flow)
        // We lock for the specific chat to ensure Realtime and FCM events happen sequentially.
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
                        wasAlreadyStored = true
                        android.util.Log.d("MessageRepository", "♻️ Merging duplicate/partial message with authoritative payload: ${normalizedMsg.id}")
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
                    val mergedMsg = mergeIncomingMessageWithLocal(
                        incoming = normalizedMsg,
                        local = existingReal?.toDomain()
                    )
                    var hydratedMsg = mergedMsg
                    if (mergedMsg.reply_to_id != null && mergedMsg.reply_to == null) {
                        val replyMsgEntity = messageDao.getMessageById(mergedMsg.reply_to_id)
                        if (replyMsgEntity != null) {
                            hydratedMsg = mergedMsg.copy(reply_to = replyMsgEntity.toDomain())
                            android.util.Log.d("MessageRepository", "💧 Hydrated reply_to for incoming message")
                        }
                    }
                    val finalizedMsg = deletedMessageStore.redact(hydratedMsg, currentUserId)

                    if (!isSessionSnapshotValid(sessionSnapshot, "dm-insert-before-db:$cleanId")) return@withLock
                    // Save to Room
                    var entity = conectate.com.data.local.entity.MessageEntity.fromDomain(finalizedMsg, isSynced = true)
                    messageDao.insertMessage(entity)
                    mediaMessageToPrefetch = finalizedMsg
                    
                    // 2. FLOW UPDATES (Atomic update inside Mutex)
                    if (!isSessionSnapshotValid(sessionSnapshot, "dm-insert-before-flow:$cleanId")) return@withLock
                    addMessageToFlowLogic(cleanId, finalizedMsg)

                    // 3. RE-ROUTE TO PULSE
                    if (!isSessionSnapshotValid(sessionSnapshot, "dm-insert-before-pulse:$cleanId")) return@withLock
                    messageEvents.emit(finalizedMsg)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (e: Exception) {
                    android.util.Log.e("MessageRepository", "Could not store an incoming message", e)
                }
            }

        if (normalizedMsg.deleted_for_all) {
            conectate.com.util.DeletedMessageIdentity.create(currentUserId, "chat", cleanId, normalizedMsg.id)?.let {
                handleDeletedMessage(it, normalizedMsg.created_at)
            }
            return
        }
        mediaMessageToPrefetch?.let { prefetchRecentChatMedia(listOf(it)) }

        // Un FCM parcial seguido por el evento completo solo debe enriquecer la
        // fila; no debe volver a sumar no leídos, reproducir sonido ni notificar.
        if (wasAlreadyStored) return
        
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
                    markMessageAsDelivered(lease, cleanId, normalizedMsg.id)
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
                if (msg.deleted_for_all) conectate.com.util.DeletedMessageIdentity.create(sessionSnapshot.accountId, "chat", msg.chat_id, msg.id)?.let {
                    handleDeletedMessage(it, msg.created_at)
                }
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
                val finalMsg = deletedMessageStore.redact(currentDomain.copy(
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
                ), sessionSnapshot.accountId)

                var cleanId = finalMsg.chat_id.trim().lowercase()
                if (cleanId.isEmpty()) {
                    android.util.Log.w("MessageRepository", "⚠️ Update ignorado: chat_id vacío en mensaje ${msg.id}")
                    return@withContext
                }

                android.util.Log.d("MessageRepository", "📝 Applying update: content='${finalMsg.content}', is_edited=${finalMsg.is_edited}, deleted_for_all=${finalMsg.deleted_for_all}")

                // 3. Guardar en Room
                if (!isSessionSnapshotValid(sessionSnapshot, "dm-update-before-db:${msg.id}")) return@withContext
                messageDao.insertMessage(conectate.com.data.local.entity.MessageEntity.fromDomain(finalMsg, isSynced = true))
                
                if (finalMsg.deleted_for_all) {
                    conectate.com.util.DeletedMessageIdentity.create(sessionSnapshot.accountId, "chat", cleanId, finalMsg.id)?.let {
                        handleDeletedMessage(it, finalMsg.created_at)
                    }
                }

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

    private suspend fun updateChatInList(currentChats: MutableList<ChatWithLastMessage>, index: Int, msg: Message, currentUserId: String, requireCurrent: () -> Unit = {}) {
        requireCurrent()
        var existing = currentChats[index]
        
        // Unread Count: Normalize IDs to prevent case-sensitivity issues
        var cleanSenderId = msg.sender_id.trim().lowercase()
        var cleanCurrentUserId = currentUserId.trim().lowercase()
        val shouldIncrementUnread = UnreadPolicy.shouldIncrementUnread(
            senderId = cleanSenderId,
            currentUserId = cleanCurrentUserId,
            // Opening a chat does not mean its newest message is actually in the viewport.
            isConversationVisible = false
        ) && msg.id !in readJournal.seenIds(cleanCurrentUserId, "chat", msg.chat_id) &&
            UnreadPolicy.isEligibleStatusForReadTransition(msg.status)
        if (shouldIncrementUnread) {
            sessionStateStore.clearLocalReadOverride(msg.chat_id)
        }
        val newUnread = when {
            shouldIncrementUnread -> existing.unreadCount + 1
            cleanSenderId == cleanCurrentUserId -> existing.unreadCount
            else -> existing.unreadCount
        }
        
        // A slow acknowledgement must not replace a newer queued message in the inbox.
        val previewMessage = conectate.com.data.repository.message.newestInboxMessage(msg, existing.lastMessage) ?: msg
        val preview = if (previewMessage.deleted_for_all) "Mensaje eliminado" else buildPreviewForContentType(previewMessage.content_type, previewMessage.content)

        currentChats[index] = existing.copy(
            lastMessage = previewMessage,
            unreadCount = newUnread,
            chat = existing.chat.copy(
                last_message = preview,
                last_message_at = previewMessage.created_at
            )
        )
        requireCurrent()
        _userChats.value = sortChatsByPriority(currentChats)

        // Persist immediately so Room emits the reordered list without waiting on a nested launch.
        var chatId = msg.chat_id.trim().lowercase()
        try {
            chatDao.updateLastMessageOwned(chatId, preview, previewMessage.safeCreatedAt, previewMessage.id, previewMessage.sender_id) { requireCurrent() }
            if (shouldIncrementUnread) {
                chatDao.incrementUnreadCount(chatId)
            }
        } catch (e: CancellationException) {
            throw e
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

    fun getUserChatsFlow(): Flow<List<ChatWithLastMessage>> {
        scope.launch { getUserChats() } // Optional network sync; presentation stays local.
        return observeVisibleInbox()
    }

    private fun observeVisibleInbox(): Flow<List<ChatWithLastMessage>> = inboxPreviewReader.observe(
        authService.observeSession().map { session ->
            session?.user?.id?.trim()?.lowercase() ?: currentSessionUserId()
        },
        onSnapshotLoaded = { account ->
            if (currentSessionUserId() == account) {
                _inboxSyncState.update { it.copy(hasLoadedLocalSnapshot = true) }
            }
        },
    )

    suspend fun getUserChats(force: Boolean = false): Result<List<ChatWithLastMessage>> {
        return inboxSyncMutex.withLock {
            val sessionSnapshot = captureSessionSnapshot()
            val now = System.currentTimeMillis()
            val currentState = _inboxSyncState.value
            if (!InboxSyncPolicy.shouldStart(
                    force = force,
                    isRefreshing = currentState.isRefreshing,
                    nowMillis = now,
                    lastCompletedAtMillis = currentState.lastCompletedAtMillis,
                )
            ) {
                return@withLock Result.success(_userChats.value)
            }

            _inboxSyncState.update { it.copy(isRefreshing = true, lastError = null) }
            try {
                val result = runReadWithDeadline(InteractiveReadDeadlines.InboxMillis) {
                    syncUserChats().getOrThrow()
                }
                if (!isSessionSnapshotValid(sessionSnapshot, "inbox-refresh-state")) {
                    return@withLock Result.failure(CancellationException("La cuenta de la bandeja cambió"))
                }
                val completedAt = System.currentTimeMillis()
                _inboxSyncState.update { it.copy(
                    isRefreshing = false,
                    hasCompletedInitialSync = true,
                    lastError = result.exceptionOrNull()?.message,
                    lastCompletedAtMillis = completedAt,
                ) }
                result
            } catch (cancelled: CancellationException) {
                if (isSessionSnapshotValid(sessionSnapshot, "inbox-refresh-cancelled")) {
                    _inboxSyncState.update { it.copy(isRefreshing = false) }
                }
                throw cancelled
            }
        }
    }

    private suspend fun syncUserChats(): Result<List<ChatWithLastMessage>> {
        return try {
            var userId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))
            var cleanUserId = userId.trim().lowercase()
            val identityGeneration = conectate.com.core.media.ProfileIdentityImageStore.generation(cleanUserId)
            var sessionSnapshot = captureSessionSnapshot(userId)

            // 0. Fetch Local Chats first (for fallback/optimistic)
            val allLocalEntities = chatDao.getAllChatsSync()
            val visibleLocalEntities = allLocalEntities.filter { it.belongsToUser(cleanUserId) }
            val allLocalChatsDomain = visibleLocalEntities.map { it.toDomain() }
            val localOnly = visibleLocalEntities
                .sortedWith(
                    compareBy<ChatEntity> { it.is_archived }
                        .thenByDescending { it.is_pinned }
                        .thenByDescending { normalizeDate(it.last_message_at ?: "") }
                )
                .map { it.toDomainWithDetails() }
            
            // PRE-LOAD: Ensure UI has local data while we fetch remote
            if (_userChats.value.isEmpty() && isSessionSnapshotValid(sessionSnapshot, "get-user-chats-preload")) {
                _userChats.value = localOnly
                android.util.Log.d("MessageRepository", "📦 Pre-loaded ${localOnly.size} chats from Room")
            }

            // Token refresh can require the network. It must happen only after
            // Room has supplied the account-scoped inbox; otherwise an expired
            // token makes an already-cached inbox look unavailable offline.
            var accessToken = authService.ensureValidAccessToken().getOrElse { error ->
                if (localOnly.isNotEmpty() && isSessionSnapshotValid(sessionSnapshot, "get-user-chats-auth-offline")) {
                    _userChats.value = localOnly
                    android.util.Log.w("MessageRepository", "Remote inbox auth unavailable; using Room cache", error)
                }
                return InboxSyncPolicy.cachedOrFailure(localOnly, error)
            }

            if (shouldUseUbuntuBackend()) {
                scope.launch { readSynchronizer.flush(cleanUserId, "chat", accessToken) }
                val localInbox = localFallbackApiClient.loadMessagesInbox(accessToken, "chats")
                    .getOrElse { error ->
                        android.util.Log.w("MessageRepository", "Local fallback inbox failed", error)
                        if (allLocalChatsDomain.isEmpty()) {
                            return Result.failure(error)
                        }
                        if (isSessionSnapshotValid(sessionSnapshot, "local-fallback-inbox-local-only")) {
                            _userChats.value = localOnly
                        }
                        return Result.success(localOnly)
                    }

                val existingLocalChats = allLocalChatsDomain.associateBy { it.id }
                val existingLocalSnapshots = visibleLocalEntities
                    .map(ChatEntity::toDomainWithDetails)
                    .associateBy { it.chat.id.trim().lowercase() }
                val mappedChats = localInbox.items
                    .filter { it.kind == "chat" }
                    .map { item -> 
                        val mapped = mapLocalFallbackInboxItem(item, cleanUserId).copy(
                            unreadCount = readJournal.resolveUnread(cleanUserId, "chat", item.id, item.unreadCount, item.unreadMessageIds)
                        )
                        val existing = existingLocalChats[mapped.chat.id]
                        val mappedTime = mapped.chat.last_message_at ?: ""
                        val existingTime = existing?.last_message_at ?: ""
                        val effectiveClearedAt = mapped.clearedAt ?: mapped.chat.cleared_at
                        val normalizedClearedAt = normalizeDate(effectiveClearedAt.orEmpty())
                        val normalizedExistingTime = normalizeDate(existingTime)
                        
                        val mergedMessages = if (
                            existing != null &&
                            existingTime > mappedTime &&
                            (normalizedClearedAt.isEmpty() || normalizedExistingTime > normalizedClearedAt)
                        ) {
                            mapped.copy(
                                clearedAt = effectiveClearedAt,
                                chat = mapped.chat.copy(
                                    last_message = existing.last_message,
                                    last_message_at = existing.last_message_at,
                                    last_message_id = existing.last_message_id,
                                    last_message_sender_id = existing.last_message_sender_id,
                                    cleared_at = effectiveClearedAt
                                )
                            )
                        } else {
                            mapped.copy(
                                clearedAt = effectiveClearedAt,
                                lastMessage = mapped.lastMessage?.takeIf { message ->
                                    val messageAt = normalizeDate(message.created_at.orEmpty())
                                    normalizedClearedAt.isEmpty() || messageAt.isEmpty() || messageAt > normalizedClearedAt
                                },
                                chat = mapped.chat.copy(cleared_at = effectiveClearedAt)
                            )
                        }
                        mergeInboxConversationIdentity(
                            existing = existingLocalSnapshots[mapped.chat.id.trim().lowercase()],
                            incoming = mergedMessages,
                        )
                    }
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
                            inboxIdentityCache.persist(mappedChats, cleanUserId, identityGeneration)
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
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        android.util.Log.w("MessageRepository", "Could not read chat participant state", e)
                    }
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
                                .forEach { (id, msgs) ->
                                    val clearedAtKey = normalizeDate(participantStateMap[id]?.clearedAt.orEmpty())
                                    val visibleLatest = msgs.firstOrNull { message ->
                                        val messageAt = normalizeDate(message.created_at ?: "")
                                        clearedAtKey.isEmpty() || messageAt.isEmpty() || messageAt > clearedAtKey
                                    }
                                    if (visibleLatest != null && !lastMessagesMap.containsKey(id)) {
                                        lastMessagesMap[id] = visibleLatest
                                    }
                                }
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
                            parameter("select", "chat_id,read_at,sender_id,status,created_at")
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
                                val createdAt = normalizeDate(json["created_at"]?.jsonPrimitive?.contentOrNull.orEmpty())
                                val clearedAt = normalizeDate(participantStateMap[cid]?.clearedAt.orEmpty())
                                if (clearedAt.isNotEmpty() && createdAt.isNotEmpty() && createdAt <= clearedAt) {
                                    return@unreadLoop
                                }
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
                
                val clearedAtKey = normalizeDate(clearedAt ?: chat.cleared_at ?: "")
                val realLastMsg = lastMessagesMap[cleanChatId]?.takeIf { message ->
                    val messageAt = normalizeDate(message.created_at ?: "")
                    clearedAtKey.isEmpty() || messageAt.isEmpty() || messageAt > clearedAtKey
                }
                val remoteUnread = unreadCountsMap[cleanChatId] ?: 0
                var unread = if (sessionStateStore.hasLocalReadOverride(cleanChatId)) 0 else remoteUnread

                // NUCLEAR FIX: Always compare Server vs Local to find the TRUE latest message
                // The server might return stale data (2025), but Room has the "hola" we just sent (2026).
                val localLast = messageDao.getLatestMessageSync(cleanChatId)?.toDomain()?.takeIf { message ->
                    val messageAt = normalizeDate(message.created_at ?: "")
                    clearedAtKey.isEmpty() || messageAt.isEmpty() || messageAt > clearedAtKey
                }
                
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
                } else if (finalLastMsg == null && clearedAtKey.isNotEmpty()) {
                    clearedAt ?: chat.cleared_at ?: remoteMetaAt
                } else {
                    // Try to respect the newest date we have seen anywhere
                    if (realMsgAt > remoteMetaAt) finalLastMsg?.created_at ?: remoteMetaAt else remoteMetaAt
                }
                
                // Content for display
                val finalLastContent = when {
                    finalLastMsg != null -> buildPreviewForContentType(finalLastMsg.content_type, finalLastMsg.content)
                    clearedAtKey.isNotEmpty() -> "Sin mensajes"
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
            val mergedRemote = distinctList.map { remote ->
                var cached = currentMap[remote.chat.id]
                val fetched = mergeInboxConversationIdentity(cached, remote)
                val cachedTime = cached?.lastMessage?.safeCreatedAt
                val fetchedTime = fetched.lastMessage?.safeCreatedAt
                val cachedClearedAt = cached?.clearedAt

                if (cachedClearedAt != null && fetchedTime != null && cachedClearedAt > fetchedTime) {
                    return@map fetched.copy(
                        lastMessage = null,
                        chat = fetched.chat.copy(
                            last_message = null,
                            last_message_at = cachedClearedAt,
                            last_message_id = null
                        )
                    )
                }
                
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
                         val localAt = normalizeDate(local.cleared_at ?: local.last_message_at ?: "")
                         
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
                    
                    // Preserve a durable identity snapshot before replacing chat rows.
                    if (!isSessionSnapshotValid(sessionSnapshot, "get-user-chats-before-users")) return@launch
                    inboxIdentityCache.persist(chatsToUpdate, cleanUserId, identityGeneration)

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
        } catch (cancelled: CancellationException) {
            throw cancelled
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
        val lastMessageId = item.lastMessageId?.trim().orEmpty()
        val lastMessageSenderId = item.lastMessageSenderId?.trim().orEmpty()
        val lastMessageContentType = item.lastMessageContentType?.trim()?.ifBlank { null } ?: "text"
        val summaryMessage = subtitle
            .takeIf {
                lastMessageId.isNotBlank() &&
                    !it.equals("Sin actividad reciente", ignoreCase = true) &&
                    !it.equals("Sin mensajes", ignoreCase = true) &&
                    !it.equals("No hay mensajes recientes", ignoreCase = true)
            }
            ?.let {
                Message(
                    id = lastMessageId,
                    chat_id = chatId,
                    sender_id = lastMessageSenderId,
                    content = it,
                    content_type = lastMessageContentType,
                    status = "sent",
                    created_at = updatedAt
                )
            }
        val clearedAt = item.clearedAt?.takeIf { it.isNotBlank() }
            ?: item.clearedAtSnake?.takeIf { it.isNotBlank() }
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
            is_favorite = item.isFavorite,
            cleared_at = clearedAt
        )
        return ChatWithLastMessage(
            chat = chat,
            lastMessage = summaryMessage,
            unreadCount = item.unreadCount,
            otherUserName = item.title.ifBlank { "Chat privado" },
            otherUserImage = item.avatarUrl.takeIf { it.isNotBlank() },
            otherUserId = otherId,
            clearedAt = clearedAt
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
        val accountId = currentSessionUserId() ?: return@withContext Result.success(0)
        val session = captureSessionSnapshot(accountId)
        PendingMessageBatchRunner(
            loadPending = { messageDao.getPendingSendMessages() },
            accessToken = { authService.ensureValidAccessToken() },
            send = { message, token ->
                messageSendCoordinator.withMessage(message.id) {
                    // A foreground send may have finished while this worker waited.
                    val latest = messageDao.getMessageById(message.id)
                    val deletion = database.messageDeletionDao().get(message.id, accountId, true)
                    if (latest == null || latest.is_synced || latest.pending_action != "send" ||
                        (deletion != null && deletion.state != "failed")) {
                        PendingSendOutcome.SKIPPED
                    } else {
                        syncPendingSendMessage(latest, token, session)
                    }
                }
            },
            markTerminalFailure = { message ->
                messageDao.markPendingSendFailed(message.id)
                updateMessageInFlow(message.chat_id, message.toDomain().copy(status = "failed"))
            }
        ).run(accountId, maxBatch) { isSessionSnapshotValid(session, "retryPendingMessages") }
    }



    override suspend fun retryPendingDeletes(maxBatch: Int): Result<Int> = withContext(Dispatchers.IO) {
        val actor = currentSessionUserId() ?: return@withContext Result.success(0)
        val session = captureSessionSnapshot(actor)
        deletionQueue.retry(actor, maxBatch) { isSessionSnapshotValid(session, "retryPendingDeletes") }
    }

    /** Reuses the durable outbox item, its attachment and ID after any send failure. */
    suspend fun retryMessage(messageId: String, chatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val accountId = currentSessionUserId() ?: return@withContext Result.failure(Exception("Inicia sesión de nuevo."))
        val session = captureSessionSnapshot(accountId)
        messageSendCoordinator.withMessage(messageId) {
            runCatching {
                check(isSessionSnapshotValid(session, "manual-retry")) { "La cuenta cambió." }
                val pending = messageDao.getMessageById(messageId) ?: error("El mensaje ya no está disponible.")
                check(pending.sender_id.equals(accountId, true) && pending.chat_id.equals(chatId, true)) {
                    "El mensaje pertenece a otra cuenta o conversación."
                }
                check(!pending.deleted_for_all && !isSendCancelledByDeletion(messageId, accountId, session)) {
                    "No se puede reenviar un mensaje eliminado."
                }
                if (pending.is_synced) return@runCatching
                check(isSessionSnapshotValid(session, "manual-retry-loaded")) { "La cuenta cambió." }
                messageDao.updateSyncStatus(messageId, "sending", false, "send")
                if (!isSessionSnapshotValid(session, "manual-retry-persisted")) return@runCatching
                updateMessageInFlow(chatId, pending.toDomain().copy(status = "sending"))
                PendingMessageSyncScheduler.enqueue(context, "manual_message_retry")
                if (!hasValidatedNetwork()) return@runCatching
                val token = authService.ensureValidAccessToken().getOrThrow()
                when (syncPendingSendMessage(pending.copy(status = "sending", pending_action = "send"), token, session)) {
                    PendingSendOutcome.TERMINAL_FAILURE -> {
                        if (!isSessionSnapshotValid(session, "manual-retry-failed")) return@runCatching
                        messageDao.markPendingSendFailed(messageId)
                        updateMessageInFlow(chatId, pending.toDomain().copy(status = "failed"))
                        error("No se pudo enviar. Puedes intentarlo de nuevo.")
                    }
                    else -> Unit
                }
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    private suspend fun isSendCancelledByDeletion(
        messageId: String,
        actor: String,
        session: SessionWriteGuard.Snapshot
    ): Boolean {
        val deletion = database.messageDeletionDao().get(messageId, actor.trim().lowercase(), true)
        if (!isSessionSnapshotValid(session, "send-deletion-check")) {
            throw PendingSyncRetryException("Send session changed")
        }
        return deletion?.let { it.state != "failed" } == true
    }

    private suspend fun syncPendingSendMessage(
        pending: conectate.com.data.local.entity.MessageEntity,
        token: String,
        session: SessionWriteGuard.Snapshot
    ): PendingSendOutcome {
        fun requireCurrentSession() {
            if (!isSessionSnapshotValid(session, "syncPendingSendMessage")) {
                throw PendingSyncRetryException("Outbox session changed")
            }
        }
        return try {
            requireCurrentSession()
            if (isSendCancelledByDeletion(pending.id, pending.sender_id, session)) return PendingSendOutcome.SKIPPED
            var pendingMessage = pending.toDomain().copy(
                chat_id = pending.chat_id.trim().lowercase(),
                sender_id = pending.sender_id.trim().lowercase(),
                status = "sending"
            )
            val localMediaUri = pendingChatMediaUri(pendingMessage.media_url)
            if (localMediaUri != null) {
                val upload = uploadChatMedia(
                    cleanChatId = pendingMessage.chat_id,
                    mediaUri = localMediaUri,
                    mediaType = pendingMessage.content_type,
                    messageId = pendingMessage.id,
                    accountId = pendingMessage.sender_id
                ).getOrElse { error ->
                    if (error is CancellationException) throw error
                    android.util.Log.w("MessageRepository", "Pending media upload still unavailable for ${pending.id}", error)
                    return if (isRetryableNetworkFailure(error.message)) {
                        PendingSendOutcome.RETRY_LATER
                    } else {
                        PendingSendOutcome.TERMINAL_FAILURE
                    }
                }
                requireCurrentSession()
                if (isSendCancelledByDeletion(pending.id, pending.sender_id, session)) return PendingSendOutcome.SKIPPED
                pendingMessage = pendingMessage.copy(
                    media_url = upload.url,
                    thumbnail_url = upload.thumbnailUrl,
                    media_size = upload.sizeBytes
                )
                messageDao.insertMessage(
                    conectate.com.data.local.entity.MessageEntity.fromDomain(
                        msg = pendingMessage,
                        isSynced = false,
                        pendingAction = "send"
                    )
                )
                requireCurrentSession()
                updateMessageInFlow(pending.chat_id, pendingMessage)
                deletePendingChatMedia(localMediaUri)
            }
            requireCurrentSession()
            if (isSendCancelledByDeletion(pending.id, pending.sender_id, session)) return PendingSendOutcome.SKIPPED
            val serverResult = sendMessageToBackend(token = token, msg = pendingMessage)
            requireCurrentSession()
            // Policy rejection and idempotency conflict both use 409. Only an
            // explicit successful acknowledgement may turn the local clock into a check.
            if (!serverResult.isSuccess) {
                android.util.Log.w(
                    "MessageRepository",
                    "retryPendingMessages server rejected ${pending.id}: ${serverResult.statusCode}"
                )
                return if (isRetryableBackendFailure(serverResult.statusCode, serverResult.errorBody)) {
                    PendingSendOutcome.RETRY_LATER
                } else {
                    PendingSendOutcome.TERMINAL_FAILURE
                }
            }

            val serverMessage = serverResult.message

            val syncedMessage = PendingMessageSyncPolicy.acknowledgedMessage(
                serverMessage = serverMessage,
                queuedMessage = pendingMessage,
                chatId = pending.chat_id
            )
            messageDao.insertMessage(
                conectate.com.data.local.entity.MessageEntity.fromDomain(
                    msg = syncedMessage,
                    isSynced = true,
                    pendingAction = null
                )
            )
            requireCurrentSession()
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
            PendingSendOutcome.SENT
        } catch (error: CancellationException) {
            throw error
        } catch (error: PendingSyncRetryException) {
            throw error
        } catch (e: Exception) {
            android.util.Log.e("MessageRepository", "retryPendingMessages failed for ${pending.id}", e)
            if (isRetryableNetworkFailure(e.message)) {
                PendingSendOutcome.RETRY_LATER
            } else {
                PendingSendOutcome.TERMINAL_FAILURE
            }
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
            val session = captureSessionSnapshot(currentUserId)
            var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))

            if (shouldUseUbuntuBackend()) {
                val result = localFallbackApiClient.setNickname(token, targetUserId, nickname)
                if (result.isFailure) return@withContext result

                if (!isSessionSnapshotValid(session, "setNickname")) return@withContext Result.failure(CancellationException("Session changed"))
                val chat = findLocalDirectChat(currentUserId, targetUserId)
                if (chat != null) {
                    chatDao.updateNickname(chat.id, nickname)
                    val currentChats = _userChats.value.toMutableList()
                    val idx = currentChats.indexOfFirst { it.chat.id == chat.id }
                    if (idx != -1) {
                        currentChats[idx] = currentChats[idx].copy(nickname = nickname)
                        _userChats.value = currentChats
                    }
                }
                return@withContext Result.success(Unit)
            }
            
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
            if (!isSessionSnapshotValid(session, "setNickname")) return@withContext Result.failure(CancellationException("Session changed"))
            var chat = findLocalDirectChat(currentUserId, targetUserId)
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



    suspend fun clearChat(chatId: String): Result<Unit> =
        clearChat(authService.captureAccountLease(), chatId)

    internal suspend fun clearChat(
        owner: conectate.com.data.supabase.AuthSessionLease,
        chatId: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanId = chatId.trim().lowercase()
        if (cleanId.isBlank()) return@withContext Result.failure(IllegalArgumentException("Chat inválido"))
        owner.requireCurrent()
        val now = java.time.Instant.now().toString()
        val ubuntu = shouldUseUbuntuBackend()
        executeOwnedChatVisibility(
            owner = owner,
            token = {
                authService.getAccessTokenForAccountAwait(requireNotNull(owner.accountId))
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Tu sesión venció. Inicia sesión de nuevo.")
            },
            hide = null,
            clear = { token -> clearChatRemotely(token, requireNotNull(owner.accountId), cleanId, now, ubuntu) },
            commit = {
                authService.withAccountLocalStorage(owner) {
                    database.withTransaction {
                        currentCoroutineContext().ensureActive()
                        owner.requireCurrent()
                        // A per-user cutoff retains the conversation and persisted messages.
                        chatDao.updateClearedAt(cleanId, now)
                        currentCoroutineContext().ensureActive()
                        owner.requireCurrent()
                    }
                }
                if (!authService.commitForAccountLease(owner) {
                    _activeChatMessages[cleanId]?.value = emptyList()
                    _userChats.value = _userChats.value.map { entry ->
                        if (entry.chat.id.trim().lowercase() != cleanId) entry
                        else entry.copy(
                            chat = entry.chat.copy(
                                last_message = "Sin mensajes",
                                last_message_at = now,
                                last_message_sender_id = null,
                                last_message_id = null,
                                cleared_at = now,
                            ),
                            clearedAt = now,
                            lastMessage = null,
                            unreadCount = 0,
                        )
                    }
                }) throw CancellationException("La cuenta cambió. Vuelve a abrir el chat.")
            },
            operation = ChatVisibilityOperation.CLEAR,
        )
    }

    /** Remote cutoff only: credentials and participant identity belong to the captured lease. */
    private suspend fun clearChatRemotely(
        token: String,
        accountId: String,
        chatId: String,
        clearedAt: String,
        ubuntu: Boolean,
    ): Result<Unit> {
        if (ubuntu) return localFallbackApiClient.clearChat(token, chatId)
        val response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/chat_participants") {
            header("Authorization", "Bearer $token")
            header("apikey", supabaseConfig.supabaseAnonKey)
            header("Content-Type", "application/json")
            parameter("chat_id", "eq.$chatId")
            parameter("user_id", "eq.$accountId")
            setBody(buildJsonObject { put("cleared_at", clearedAt) })
        }
        return if (response.status.isSuccess()) Result.success(Unit)
        else Result.failure(Exception("Error clearing chat: ${response.status}"))
    }

    // --- MESSAGES FLOW ---



    suspend fun getCachedMessages(chatId: String): List<Message> = withContext(Dispatchers.IO) {
        var cleanId = chatId.trim().lowercase()
        if (cleanId.isBlank()) return@withContext emptyList()

        val activeUserId = currentSessionUserId() ?: return@withContext emptyList()
        val session = captureSessionSnapshot(activeUserId)
        val chat = chatDao.getChatById(cleanId)
        if (chat?.toDomain()?.isAccessibleTo(activeUserId) != true) return@withContext emptyList()
        val localMessages = messageDao.getMessagesSync(cleanId).map { it.toDomain() }
        val deletionIntents = database.messageDeletionDao().forChat(cleanId)
        if (!isSessionSnapshotValid(session, "getCachedMessages")) return@withContext emptyList()
        applyMessageDeletions(unifyMessageList(localMessages), deletionIntents, activeUserId)
            .filter { chat.cleared_at == null || (it.created_at ?: "") > chat.cleared_at }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getMessagesFlow(chatId: String): Flow<List<Message>> {
        val cleanId = chatId.trim().lowercase()
        return accountScopedChatFlow(
            accounts = authService.observeSession().map { it?.user?.id },
            // Delivery/read timestamps update this row frequently. Only a change in
            // access should replace the history subscription; metadata has its own flow.
            chats = chatDao.getChatByIdFlow(cleanId).map { it?.toDomain() }.distinctHistoryAccess(),
            currentAccount = ::currentSessionUserId,
            empty = emptyList()
        ) { _, accountId -> getMessagesForAccountFlow(cleanId, accountId) }
            .flowOn(Dispatchers.IO)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun getMessagesForAccountFlow(cleanId: String, accountId: String): Flow<List<Message>> {
        data class HistorySource(val siblings: Set<String>, val clearedAt: String?)
        return _userChats.map { chats ->
            val currentDetails = chats.find { it.chat.id.trim().lowercase() == cleanId }
            var currentChat = currentDetails?.chat
            var clearedAt = currentDetails?.clearedAt
            
            // Fallback to Room if memory is syncing
            if (currentChat == null) {
                try {
                    var local = chatDao.getChatById(cleanId)
                    if (local != null) {
                        currentChat = local.toDomain()
                        clearedAt = local.cleared_at
                    }
                } catch(e: Exception) { /* ignore */ }
            }
            
            if (currentChat?.isAccessibleTo(accountId) != true || currentSessionUserId() != accountId) {
                return@map null
            }
            val participants = currentChat.participants.map { it.trim().lowercase() }.sorted()
            val isGroup = currentChat.is_group
            
            // 1. Identify Sibling IDs
            val siblingIds = if (participants.size == 2 && !isGroup) {
                val foundInMem = chats.filter { 
                    it.chat.participants.map { p -> p.trim().lowercase() }.sorted() == participants && !it.chat.is_group 
                }.map { it.chat.id.trim().lowercase() }
                (foundInMem + cleanId).toSet()
            } else {
                setOf(cleanId)
            }
            
            HistorySource(siblingIds, clearedAt)
        }.distinctUntilChanged().flatMapLatest { source ->
            if (source == null) return@flatMapLatest flowOf(emptyList())
            val siblingIds = source.siblings
            // Inbox previews and unrelated chats must not resubscribe every history row.
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

            val reconciled = combine(flows) { lists ->
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
                val clearedAt = source.clearedAt
                if (clearedAt != null) {
                    visibleForUser.filter { (it.created_at ?: "") > clearedAt }
                } else visibleForUser
            }
            val deletionFlows = siblingIds.map { database.messageDeletionDao().observeChat(it) }
            combine(reconciled, combine(deletionFlows) { it.toList().flatten() }) { messages, intents ->
                applyMessageDeletions(messages, intents, accountId)
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
            val localChatEntity = chatDao.getChatById(chatId)
            val clearedAt = localChatEntity?.cleared_at
            
            if (shouldUseUbuntuBackend()) {
                val localChat = localFallbackApiClient.loadChat(accessToken, chatId)
                    .getOrElse { error ->
                        android.util.Log.w("MessageRepository", "Local fallback chat history failed for $chatId", error)
                        return
                    }
                val seenIds = readJournal.seenIds(sessionUserId, "chat", chatId)
                val rawMsgs = localChat.messages
                    .mapNotNull { messageJson ->
                        runCatching { json.decodeFromJsonElement<Message>(messageJson) }.getOrNull()
                    }
                    .map { it.copy(chat_id = chatId).withNormalizedStatus() }
                    .map { message ->
                        if (message.id in seenIds && !message.deleted_for_all &&
                            conectate.com.core.messaging.UnreadPolicy.isEligibleStatusForReadTransition(message.status))
                            message.copy(status = "read") else message
                    }

                val currentById = _activeChatMessages[chatId]
                    ?.value
                    .orEmpty()
                    .associateBy { it.id.trim().lowercase() }
                val historyById = rawMsgs.associateBy { it.id.trim().lowercase() }
                val hydratedRawMsgs = rawMsgs.map { message ->
                    val replyId = message.reply_to_id?.trim()?.lowercase()
                    if (replyId.isNullOrBlank() || message.reply_to != null) {
                        message
                    } else {
                        val reply = currentById[replyId]
                            ?: historyById[replyId]
                            ?: runCatching { messageDao.getMessageById(replyId)?.toDomain() }.getOrNull()
                        message.copy(reply_to = reply)
                    }
                }

                val filteredRawMsgs = if (clearedAt != null) {
                    hydratedRawMsgs.filter { (it.created_at ?: "") > clearedAt }
                } else hydratedRawMsgs

                scope.launch {
                    if (!isSessionSnapshotValid(sessionSnapshot, "local-fallback-history-cache:$chatId")) return@launch
                    runCatching {
                        messageDao.insertMessages(
                            filteredRawMsgs.map { conectate.com.data.local.entity.MessageEntity.fromDomain(it, isSynced = true) }
                        )
                    }.onFailure {
                        android.util.Log.w("MessageRepository", "Could not cache local fallback messages", it)
                    }
                }

                _activeChatMessages[chatId]?.let { flow ->
                    if (!isSessionSnapshotValid(sessionSnapshot, "local-fallback-history-flow:$chatId")) return
                    flow.update { current -> unifyMessageList(current + filteredRawMsgs) }
                }
                prefetchRecentChatMedia(filteredRawMsgs)
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
                
                val filteredHydratedMsgs = if (clearedAt != null) {
                    hydratedMsgs.filter { (it.created_at ?: "") > clearedAt }
                } else hydratedMsgs

                // PERSISTENCE: Save all fetched (and hydrated) messages to Room
                scope.launch {
                    if (!isSessionSnapshotValid(sessionSnapshot, "fetch-history-cache:$chatId")) return@launch
                    try {
                        // CRITICAL FIX: Force the message to belong to the Chat ID we are currently looking at.
                        // This prevents messages from being lost in "sibling" silos and ensures Room always returns them.
                        val entities = filteredHydratedMsgs.map { 
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
                        unifyMessageList(current + filteredHydratedMsgs)
                    }
                    android.util.Log.d("MessageRepository", "✅ History merge complete for $chatId. Total: ${flow.value.size}")
                }
                prefetchRecentChatMedia(filteredHydratedMsgs)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) { 
            android.util.Log.e("MessageRepository", "fetchMessageHistory error", e)
        }
    }

    private fun prefetchRecentChatMedia(messages: List<Message>) {
        val account = currentSessionUserId() ?: return
        val session = captureSessionSnapshot(account)
        if (!isSessionSnapshotValid(session, "prefetch-chat-start")) return
        val visible = messages.map { deletedMessageStore.redact(it, account) }.filter { message ->
            !message.deleted_for_all && message.deleted_by.none { it.trim().lowercase() == account }
        }
        visible.takeLast(12).forEach { message ->
            if (isSessionSnapshotValid(session, "prefetch-chat-register")) receivedImageGallery.register(
                message.media_url, message.sender_id, message.content_type, message.deleted_for_all, message.deleted_by)
        }
        prefetchChatMedia(
            visible.map { message ->
                ChatMediaPrefetchCandidate(
                    contentType = message.content_type,
                    mediaUrl = message.media_url,
                    mediaSizeBytes = message.media_size.takeIf { it > 0L }
                )
            }
        ) { mediaPath ->
            // Recheck both generation and tombstone inside the deferred prefetch coroutine.
            isSessionSnapshotValid(session, "prefetch-chat-deferred") && visible.any { message ->
                message.media_url == mediaPath && !deletedMessageStore.redact(message, account).deleted_for_all
            }
        }
    }

    internal fun prefetchRecentGroupMedia(messages: List<GroupMessage>) {
        val account = currentSessionUserId() ?: return
        val session = captureSessionSnapshot(account)
        if (!isSessionSnapshotValid(session, "prefetch-group-start")) return
        val visible = messages.map { deletedMessageStore.redact(it, account) }.filter { message ->
            !message.deleted_for_all && message.deleted_by.none { it.trim().lowercase() == account }
        }
        visible.takeLast(12).forEach { message ->
            if (isSessionSnapshotValid(session, "prefetch-group-register")) receivedImageGallery.register(
                message.media_url, message.sender_id, message.content_type, message.deleted_for_all, message.deleted_by)
        }
        prefetchChatMedia(
            visible.map { message ->
                ChatMediaPrefetchCandidate(message.content_type, message.media_url)
            }
        ) { mediaPath ->
            // Recheck both generation and tombstone inside the deferred prefetch coroutine.
            isSessionSnapshotValid(session, "prefetch-group-deferred") && visible.any { message ->
                message.media_url == mediaPath && !deletedMessageStore.redact(message, account).deleted_for_all
            }
        }
    }

    internal fun prefetchRecentCommunityMedia(messages: List<CommunityMessage>) {
        val account = currentSessionUserId() ?: return
        val session = captureSessionSnapshot(account)
        if (!isSessionSnapshotValid(session, "prefetch-community-start")) return
        val visible = messages.map { deletedMessageStore.redact(it, account) }.filter { message ->
            !message.deleted_for_all && message.deleted_by.none { it.trim().lowercase() == account }
        }
        visible.takeLast(12).forEach { message ->
            if (isSessionSnapshotValid(session, "prefetch-community-register")) receivedImageGallery.register(
                message.media_url, message.sender_id, message.content_type, message.deleted_for_all, message.deleted_by)
        }
        prefetchChatMedia(
            visible.map { message ->
                ChatMediaPrefetchCandidate(message.content_type, message.media_url)
            }
        ) { mediaPath ->
            // Recheck both generation and tombstone inside the deferred prefetch coroutine.
            isSessionSnapshotValid(session, "prefetch-community-deferred") && visible.any { message ->
                message.media_url == mediaPath && !deletedMessageStore.redact(message, account).deleted_for_all
            }
        }
    }

    private fun prefetchChatMedia(candidates: Iterable<ChatMediaPrefetchCandidate>, isAllowed: (String) -> Boolean) {
        if (!automaticMediaAllowed()) return
        val session = captureSessionSnapshot()
        val account = session.accountId ?: return
        val recent = candidates.toList()
        val typeByUrl = recent.associate { it.mediaUrl?.trim() to it.contentType }
        selectRecentChatMediaForPrefetch(recent, maxItems = 24).asReversed()
            .sortedBy { if (typeByUrl[it] in setOf("image", "sticker", "gif")) 0 else 1 }.forEach { mediaPath ->
            if (!isAllowed(mediaPath)) return@forEach
            val sourceKey = conectate.com.data.localfallback.UbuntuStorageUrl.stableCacheKey(mediaPath) ?: mediaPath
            val key = conectate.com.data.repository.message.ChatMediaPrefetchGate.Key(
                account, session.epoch,
                "${typeByUrl[mediaPath]}:$sourceKey",
            )
            mediaPrefetchGate.enqueue(key, isAllowed = {
                isSessionSnapshotValid(session, "prefetch-slot") && automaticMediaAllowed() && isAllowed(mediaPath)
            }) {
                val image = when (typeByUrl[mediaPath]) {
                    "sticker", "gif" -> conectate.com.core.media.MediaImageRequestFactory.animated(context, mediaPath)
                    "image" -> conectate.com.core.media.MediaImageRequestFactory.preview(context, mediaPath)
                    else -> null
                }
                if (image != null) coil.Coil.imageLoader(context).execute(image.newBuilder()
                    .setParameter(conectate.com.core.media.MediaImageRequestFactory.PREFETCH_CHAT_IMAGE, true).build())
                mediaResolver.getPlayableUrl(
                    path = mediaPath,
                    allowOfflineMediaCache = true,
                    isPrefetch = true
                )
            }
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
        
        // 1. Exact ID deduplication. Realtime/FCM acknowledgements may contain
        // only a subset of a structured message. Choosing the first non-sending
        // variation could therefore turn a contact into an empty text bubble
        // until the chat was reopened. Keep the richest payload and merge the
        // authoritative delivery/deletion state independently.
        val byId = messages.groupBy { it.id.trim().lowercase() }.map { (_, variations) ->
            val preferredPayload = variations.maxWithOrNull(
                compareBy<Message> { messagePayloadScore(it) }
                    .thenBy { it.updated_at.orEmpty() }
            ) ?: variations.first()
            val preferredStatus = variations.maxByOrNull { messageStatusRank(it.status) }
                ?: preferredPayload
            val mergedReactions = variations
                .flatMap { it.reactions.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, values) -> values.flatten().distinct() }
            preferredPayload.copy(
                status = preferredStatus.status,
                delivered_at = variations.firstNotNullOfOrNull { it.delivered_at },
                read_at = variations.firstNotNullOfOrNull { it.read_at },
                deleted_for_all = variations.any { it.deleted_for_all },
                deleted_by = variations.flatMap { it.deleted_by }.distinct(),
                is_edited = variations.any { it.is_edited },
                reactions = mergedReactions,
                reply_to_id = preferredPayload.reply_to_id
                    ?: variations.firstNotNullOfOrNull { it.reply_to_id },
                reply_to = preferredPayload.reply_to
                    ?: variations.firstNotNullOfOrNull { it.reply_to }
            )
        }
        
        // Keep creation order through uploads and acknowledgements. Grouping by
        // delivery state makes a faster second upload jump before the first.
        val redactionAccount = currentSessionUserId()
        return conectate.com.core.messaging.orderMessageTimeline(byId.map { deletedMessageStore.redact(it, redactionAccount) })
    }

    private fun messagePayloadScore(message: Message): Int {
        val contentType = message.content_type.trim().lowercase()
        return (if (message.deleted_for_all) 10_000 else 0) +
            (if (contentType != "text") 2_000 else 0) +
            (if (message.content.isNotBlank()) 1_000 else 0) +
            (if (!message.media_url.isNullOrBlank()) 500 else 0) +
            (if (!message.thumbnail_url.isNullOrBlank()) 100 else 0) +
            message.content.length.coerceAtMost(300)
    }

    private fun messageStatusRank(status: String): Int = when (status.trim().lowercase()) {
        "read" -> 5
        "delivered" -> 4
        "sent" -> 3
        "sending" -> 2
        "failed" -> 1
        else -> 0
    }

    private fun updateMessageInFlow(chatId: String, msg: Message) {
        var cleanId = chatId.trim().lowercase()
        var flow = _activeChatMessages[cleanId] ?: return
        
        flow.update { current -> 
            val updatedList = current.toMutableList()
            var idx = updatedList.indexOfFirst { it.id == msg.id }
            if (idx != -1) {
                val local = current[idx]
                val receipt = conectate.com.core.messaging.mergeMessageReceiptState(
                    msg.status, msg.delivered_at, msg.read_at, local.status, local.delivered_at, local.read_at,
                )
                updatedList[idx] = msg.copy(status = receipt.status, delivered_at = receipt.deliveredAt, read_at = receipt.readAt)
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
        forwardedFromUserId: String? = null,
        allowRapidMediaBatch: Boolean = false,
        onQueued: suspend () -> Unit = {}
    ): Result<Message> = sendMessage(
        accountLease = authService.captureAccountLease(),
        chatId = chatId,
        content = content,
        replyToId = replyToId,
        mediaUri = mediaUri,
        mediaType = mediaType,
        messageId = messageId,
        existingMediaUrl = existingMediaUrl,
        isForwarded = isForwarded,
        forwardedFromMessageId = forwardedFromMessageId,
        forwardedFromUserId = forwardedFromUserId,
        allowRapidMediaBatch = allowRapidMediaBatch,
        onQueued = onQueued,
    )

    internal suspend fun sendMessage(
        accountLease: conectate.com.data.supabase.AuthSessionLease,
        chatId: String,
        content: String,
        replyToId: String? = null,
        mediaUri: Uri? = null,
        mediaType: String = "text",
        messageId: String? = null,
        existingMediaUrl: String? = null,
        isForwarded: Boolean = false,
        forwardedFromMessageId: String? = null,
        forwardedFromUserId: String? = null,
        allowRapidMediaBatch: Boolean = false,
        onQueued: suspend () -> Unit = {}
    ): Result<Message> = conectate.com.data.repository.message.executeOwnedMessageSend(accountLease, sessionWriteGuard, onQueued) { session, queued ->
        val id = messageId ?: UUID.randomUUID().toString()
        messageSendCoordinator.withMessage(id) {
            if (!isSessionSnapshotValid(session, "sendMessage:lock")) {
                return@withMessage Result.failure(CancellationException("Session changed"))
            }
            val cached = if (messageId != null) messageDao.getMessageById(id) else null
            if (!isSessionSnapshotValid(session, "sendMessage:cached")) {
                return@withMessage Result.failure(CancellationException("Session changed"))
            }
            if (cached != null && (cached.sender_id.trim().lowercase() != session.accountId ||
                    cached.chat_id.trim().lowercase() != chatId.trim().lowercase())) {
                return@withMessage Result.failure(IllegalArgumentException("Message belongs to another conversation"))
            }
            val deletion = database.messageDeletionDao().get(id, session.accountId.orEmpty(), true)
            if (cached?.deleted_for_all == true || (deletion != null && deletion.state != "failed")) {
                return@withMessage Result.failure(IllegalStateException("No se puede reenviar un mensaje eliminado"))
            }
            if (cached != null && cached.is_synced && cached.sender_id.trim().lowercase() == session.accountId &&
                cached.chat_id.trim().lowercase() == chatId.trim().lowercase()) {
                return@withMessage Result.success(cached.toDomain())
            }
            sendMessageInternal(session, chatId, content, replyToId, mediaUri, mediaType, id, existingMediaUrl,
                isForwarded, forwardedFromMessageId, forwardedFromUserId, allowRapidMediaBatch,
                existingMessage = cached?.toDomain(), onQueued = queued)
        }
    }

    private suspend fun sendMessageInternal(
        session: SessionWriteGuard.Snapshot,
        chatId: String, 
        content: String, 
        replyToId: String? = null,
        mediaUri: Uri? = null,
        mediaType: String = "text",
        messageId: String? = null,
        existingMediaUrl: String? = null,
        isForwarded: Boolean = false,
        forwardedFromMessageId: String? = null,
        forwardedFromUserId: String? = null,
        allowRapidMediaBatch: Boolean = false,
        existingMessage: Message? = null,
        onQueued: suspend () -> Unit = {}
    ): Result<Message> {
        // Fix: Define ID outside try block so it's visible in catch block
        var id = messageId ?: UUID.randomUUID().toString()
        var cleanId = chatId.trim().lowercase()
        var auditUserId: String? = null
        var queuedMessage: Message? = null
        val startedAt = System.currentTimeMillis()
        val startedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
        fun recordSendStage(stage: String) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAtElapsedMs
            android.util.Log.i("MessageRepository", "Message send: ref=${id.hashCode().toUInt().toString(16)} " +
                "stage=$stage elapsed_ms=$elapsed on_main_thread=${android.os.Looper.myLooper() == android.os.Looper.getMainLooper()}")
        }
        fun requireCurrentSession() {
            if (!isSessionSnapshotValid(session, "sendMessage")) throw CancellationException("Session changed")
        }
        
        return try {
            val userId = checkNotNull(session.accountId)
            requireCurrentSession()
            auditUserId = userId

            val guardDecision = operationGuard.acquire(
                action = GuardedAction.DIRECT_MESSAGE_SEND,
                actorId = userId,
                scopeId = cleanId,
                enforceMinInterval = !allowRapidMediaBatch
            )
            if (!guardDecision.allowed) {
                val retryAfterSeconds = InputSecurityPolicy.retryAfterSeconds(guardDecision.retryAfterMs)
                return Result.failure(Exception("Estas enviando mensajes muy rapido. Intenta en ${retryAfterSeconds}s."))
            }

            val baseSanitizedContent = if (mediaType == "text") {
                InputSecurityPolicy.sanitizeMessageText(content)
            } else {
                content.trim().take(InputSecurityPolicy.MAX_TEXT_MESSAGE_LENGTH)
            }
            val sanitizedContent = if (mediaType == "contact") {
                // Contactos usan JSON estructurado. Moderar o reescribir ese JSON
                // puede invalidarlo y hacer que el receptor no pueda abrirlo.
                baseSanitizedContent
            } else {
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
                moderation.sanitizedText
            }
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

            val uploaded = conectate.com.data.repository.message.uploadedMessageForRetry(existingMessage, mediaType)
            var mediaUrl: String? = uploaded?.media_url ?: existingMediaUrl
            var uploadedMediaSize = uploaded?.media_size ?: 0L
            var pendingMediaUri: Uri? = null
            if (mediaUri != null && uploaded == null) {
                val pendingMedia = persistPendingChatMedia(
                    accountId = userId,
                    requireCurrent = ::requireCurrentSession,
                    sourceUri = mediaUri,
                    messageId = id,
                    mediaType = mediaType
                ).getOrElse { error ->
                    return Result.failure(error)
                }
                pendingMediaUri = pendingMedia.uri
                mediaUrl = pendingMedia.uri.toString()
                uploadedMediaSize = pendingMedia.sizeBytes
            }

            var msg = Message(
                id = id,
                chat_id = cleanId, // Send clean ID
                sender_id = userId,
                content = sanitizedContent,
                content_type = mediaType,
                media_url = mediaUrl,
                media_size = uploadedMediaSize,
                thumbnail_url = uploaded?.thumbnail_url,
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
                created_at = existingMessage?.created_at?.takeIf { it.isNotBlank() } ?: java.time.Instant.now().toString(),
                reply_to = if (replyToId != null) {
                    _activeChatMessages[cleanId]?.value?.find { it.id == replyToId }
                } else null
            )
            queuedMessage = msg
            requireCurrentSession()
            recordSendStage("created")
            
            android.util.Log.d("MessageRepository", "📦 Message object created: id=$id, chat_id=$cleanId, sender_id=$userId")

            // --- 1. PERSISTENCE OPTIMISTIC (IMMEDIATE SAVE) ---
            // Save to Room FIRST to prevent data loss if server call fails or app is killed.
            try {
                val optimisticEntity = conectate.com.data.local.entity.MessageEntity.fromDomain(
                    msg,
                    isSynced = false,
                    pendingAction = "send"
                )
                messageDao.insertMessageOwned(optimisticEntity) { requireCurrentSession() }
                recordSendStage("persisted")
                PendingMessageSyncScheduler.enqueue(context, "direct_message_pending")
                android.util.Log.d("MessageRepository", "✅ Optimistic Message saved to Room: ${msg.id}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                pendingMediaUri?.let(::deletePendingChatMedia)
                return Result.failure(IllegalStateException("No se pudo guardar el mensaje en el dispositivo", error))
            }

            // Durable acceptance releases the composer before any delivery/network work.
            onQueued()

            // --- 2. UPDATE MEMORY (FLOW) ---
            requireCurrentSession()
            addMessageToFlow(cleanId, msg)
            val optimisticChats = _userChats.value.toMutableList()
            val optimisticChatIndex = optimisticChats.indexOfFirst {
                it.chat.id.trim().lowercase() == cleanId
            }
            if (optimisticChatIndex >= 0) {
                updateChatInList(optimisticChats, optimisticChatIndex, msg, userId, ::requireCurrentSession)
            } else {
                // Room may already know this chat even if the in-memory inbox is
                // still hydrating after launch.
                val preview = buildPreviewForContentType(msg.content_type, msg.content)
                runCatching {
                    chatDao.updateLastMessageOwned(cleanId, preview, msg.safeCreatedAt, msg.id, userId) { requireCurrentSession() }
                }
            }
            android.util.Log.d("MessageRepository", "🔄 Optimistic update applied to flow")
            recordSendStage("optimistic_ready")

            // Offline-first: once the message (and any selected media) is safely
            // persisted, do not make the composer wait for an HTTP timeout. The
            // connectivity observer/WorkManager will retry this same id later.
            if (!hasValidatedNetwork()) {
                android.util.Log.d("MessageRepository", "Message queued locally until connectivity returns")
                return Result.success(msg)
            }

            recordSendStage("token_requested")
            val token = runCatching {
                authService.getAccessTokenForAccountAwait(userId) ?: error("No se pudo renovar la sesión.")
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                requireCurrentSession()
                android.util.Log.w("MessageRepository", "Message queued until the session can reconnect", error)
                runCatching {
                    operationGuard.audit(
                        action = GuardedAction.DIRECT_MESSAGE_SEND,
                        actorId = userId,
                        scopeId = cleanId,
                        outcome = GuardOutcome.QUEUED,
                        attributes = mapOf("message_id" to msg.id, "reason" to "token_unavailable")
                    )
                }
                return Result.success(msg)
            }
            requireCurrentSession()
            recordSendStage("token_ready")

            if (isSendCancelledByDeletion(id, userId, session)) return Result.success(msg.copy(deleted_for_all = true))
            if (pendingMediaUri != null) {
                val mediaUpload = uploadChatMedia(cleanId, pendingMediaUri, mediaType, id, userId)
                    .getOrElse { error ->
                        if (error is CancellationException) throw error
                        requireCurrentSession()
                        val reason = error.message ?: "No se pudo subir multimedia"
                        android.util.Log.w("MessageRepository", "Media upload queued: $reason", error)
                        if (isRetryableNetworkFailure(error.message)) {
                            return Result.success(msg)
                        }
                        messageDao.markPendingSendFailedOwned(id) { requireCurrentSession() }
                        updateMessageInFlow(cleanId, msg.copy(status = "failed"))
                        return Result.failure(Exception(reason))
                    }
                requireCurrentSession()
                if (isSendCancelledByDeletion(id, userId, session)) return Result.success(msg.copy(deleted_for_all = true))
                msg = msg.copy(
                    media_url = mediaUpload.url,
                    thumbnail_url = mediaUpload.thumbnailUrl,
                    media_size = mediaUpload.sizeBytes
                )
                queuedMessage = msg
                messageDao.insertMessageOwned(
                    conectate.com.data.local.entity.MessageEntity.fromDomain(
                        msg = msg,
                        isSynced = false,
                        pendingAction = "send"
                    )
                ) { requireCurrentSession() }
                requireCurrentSession()
                addMessageToFlow(cleanId, msg)
                deletePendingChatMedia(pendingMediaUri)
                android.util.Log.d("MessageRepository", "✅ Media uploaded (${mediaUpload.contentType}): ${mediaUpload.url}")
            }

            // --- 3. SEND TO SERVER ---
            requireCurrentSession()
            if (isSendCancelledByDeletion(id, userId, session)) return Result.success(msg.copy(deleted_for_all = true))
            android.util.Log.d("MessageRepository", "📡 Sending message to backend (secure RPC with fallback)...")
            recordSendStage("backend_started")
            val serverSendResult = sendMessageToBackend(
                token = token,
                msg = msg
            )
            requireCurrentSession()
            recordSendStage("backend_returned")
            android.util.Log.d("MessageRepository", "📨 Backend status: ${serverSendResult.statusCode}")

            // --- 4. HANDLE RESPONSE (UPDATE LOCAL) ---
            if (serverSendResult.isSuccess) {
                var returnedMsg = (serverSendResult.message ?: msg.copy(status = "sent")).copy(
                    reply_to = msg.reply_to,
                    created_at = (serverSendResult.message?.created_at ?: msg.created_at)
                )
                
                android.util.Log.d("MessageRepository", "✅ Message sent successfully: ${returnedMsg.id}")

                // Persist the acknowledged state before returning so an immediate process death
                // cannot restore an already accepted message as still pending.
                try {
                    val entity = conectate.com.data.local.entity.MessageEntity.fromDomain(
                        returnedMsg,
                        isSynced = true
                    )
                    val stored = messageDao.persistMessageOwned(entity) { requireCurrentSession() }
                    returnedMsg = returnedMsg.copy(status = stored.status, delivered_at = stored.delivered_at, read_at = stored.read_at)
                    android.util.Log.d("MessageRepository", "✅ Room Updated to SENT: ${returnedMsg.id}")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    android.util.Log.e("MessageRepository", "❌ Failed to cache acknowledged message", error)
                }

                requireCurrentSession()
                addMessageToFlow(cleanId, returnedMsg)
                recordSendStage("acknowledged")

                val followUpSession = session.copy(operationJob = null)
                scope.launch {
                    try {
                        sessionWriteGuard.requireValid(followUpSession)
                        val preview = buildPreviewForContentType(returnedMsg.content_type, returnedMsg.content)
                        updateChatLastMessageInternal(
                            cleanId,
                            preview,
                            returnedMsg.created_at,
                            returnedMsg.id,
                            userId,
                            token
                        )
                    } catch (error: Exception) {
                        android.util.Log.e("MessageRepository", "Failed to update chat metadata after send", error)
                    }
                }

                 // Proactive Chat List Update (Existing Logic - Optimized)
                 scope.launch {
                     try {
                         sessionWriteGuard.requireValid(followUpSession)
                         var currentChats = _userChats.value.toMutableList()
                         var idx = currentChats.indexOfFirst { it.chat.id.trim().lowercase() == cleanId }
                         if (idx != -1) {
                            // Update existing
                            updateChatInList(currentChats, idx, returnedMsg, userId) { sessionWriteGuard.requireValid(followUpSession) }
                         } else {
                            // Handle New Chat placeholder (Keep existing logic if needed or simplified)
                            // ... (Omitting complex creation logic for brevity, relying on standard refresh loop for new chats usually)
                            // But for "Instant" feel, we trigger a fast fetch
                            getUserChats() 
                         }
                     } catch (e: Exception) {
                         if (e is CancellationException) throw e
                         android.util.Log.w("MessageRepository", "Could not refresh chat list after send", e)
                     }
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
                
                if (isRetryableBackendFailure(serverSendResult.statusCode, errorBody)) {
                    runCatching {
                        operationGuard.audit(
                            action = GuardedAction.DIRECT_MESSAGE_SEND,
                            actorId = userId,
                            scopeId = cleanId,
                            outcome = GuardOutcome.QUEUED,
                            attributes = mapOf("message_id" to id, "reason" to "network_unavailable")
                        )
                    }
                    return Result.success(msg)
                }

                // A server-side validation or permission error remains visible, but
                // must not be auto-retried as if it were a connectivity failure.
                messageDao.markPendingSendFailedOwned(id) { requireCurrentSession() }
                updateMessageInFlow(cleanId, msg.copy(status = "failed"))

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
        } catch (error: CancellationException) {
            // The durable row already records pending/sent. Cancellation must not
            // revert a server/realtime acknowledgement back into an unsent message.
            throw error
        } catch (e: Exception) {
            android.util.Log.e("MessageRepository", "❌❌ EXCEPTION in sendMessage", e)
            val pending = queuedMessage
            if (pending != null && isRetryableNetworkFailure(e.message)) {
                return Result.success(pending)
            }
             // Update Room to FAILED status
            runCatching { messageDao.markPendingSendFailedOwned(id) { requireCurrentSession() } }
            if (pending != null && isSessionSnapshotValid(session, "send-failed-flow")) {
                updateMessageInFlow(cleanId, pending.copy(status = "failed"))
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
                uploadChatMedia(cleanChatId, mediaUri, mediaType).map { it.url }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private data class ChatMediaUpload(
        val url: String,
        val thumbnailUrl: String? = null,
        val sizeBytes: Long,
        val contentType: String
    )

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
        if (shouldUseUbuntuBackend()) {
            return localFallbackApiClient.sendChatMessage(
                accessToken = token,
                chatId = msg.chat_id,
                content = msg.content,
                contentType = msg.content_type,
                mediaUrl = UbuntuStorageUrl.rewrite(msg.media_url),
                thumbnailUrl = UbuntuStorageUrl.rewrite(msg.thumbnail_url),
                mediaSize = msg.media_size,
                replyToId = msg.reply_to_id,
                messageId = msg.id,
                isForwarded = msg.is_forwarded,
                forwardedFromMessageId = msg.forwarded_from_message_id,
                forwardedFromUserId = msg.forwarded_from_user_id
            ).fold(
                onSuccess = { sent ->
                    BackendSendResult(
                        isSuccess = true,
                        statusCode = 201,
                        message = PendingMessageSyncPolicy.acknowledgedMessage(
                            serverMessage = sent,
                            queuedMessage = msg,
                            chatId = msg.chat_id
                        )
                    )
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    BackendSendResult(
                        isSuccess = false,
                        statusCode = (error as? MessageSendHttpException)?.statusCode ?: 0,
                        errorBody = (error as? MessageSendHttpException)?.errorCode?.takeIf(String::isNotBlank)
                            ?: error.message ?: "No se pudo enviar al servidor Ubuntu."
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
                val acknowledged = parsed ?: msg.copy(status = "sent")
                return BackendSendResult(
                    isSuccess = true,
                    statusCode = rpcResponse.status.value,
                    message = PendingMessageSyncPolicy.acknowledgedMessage(
                        serverMessage = acknowledged,
                        queuedMessage = msg,
                        chatId = msg.chat_id
                    )
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BackendSendResult(
                isSuccess = false,
                statusCode = 0,
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

    suspend fun loadDirectMessageRequest(chatId: String?, targetUserId: String): Result<conectate.com.data.model.DirectMessageRequestState> {
        if (!shouldUseUbuntuBackend()) return Result.failure(IllegalStateException("Actualiza la conexión al servidor para enviar una solicitud"))
        val token = authService.ensureValidAccessToken().getOrElse { return Result.failure(it) }
        return localFallbackApiClient.loadDirectMessageRequest(token, chatId, targetUserId)
    }

    suspend fun decideDirectMessageRequest(chatId: String, accept: Boolean): Result<conectate.com.data.model.DirectMessageRequestState> {
        val token = authService.ensureValidAccessToken().getOrElse { return Result.failure(it) }
        return localFallbackApiClient.decideDirectMessageRequest(token, chatId, accept)
    }

    private fun normalizeBackendSendFailure(errorBody: String): String {
        val clean = errorBody.trim()
        return when {
            isBlockedByRecipientError(clean) -> "CHAT_BLOCKED_BY_RECIPIENT"
            clean.contains("MESSAGE_REQUEST_PENDING", ignoreCase = true) -> "MESSAGE_REQUEST_PENDING"
            clean.contains("MESSAGE_REQUEST_REJECTED", ignoreCase = true) -> "MESSAGE_REQUEST_REJECTED"
            clean.contains("FRIEND_REQUEST_PENDING", ignoreCase = true) ->
                "Ya enviaste un primer mensaje. Podrás escribir de nuevo cuando acepte tu solicitud de amistad."
            clean.contains("FRIEND_REQUEST_REJECTED", ignoreCase = true) ->
                "La solicitud de amistad fue rechazada."
            clean.isBlank() -> "SEND_FAILED"
            else -> clean
        }
    }

    // Helper extracted to avoid code duplication
    private suspend fun updateChatLastMessageInternal(chatId: String, preview: String, createdAt: String?, msgId: String, senderId: String, token: String) {
          // Ubuntu confirms the message only after updating its authoritative chat preview.
          // A compatibility PATCH here repeats the write and stalls durable queue draining.
          if (shouldUseUbuntuBackend()) return
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
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            android.util.Log.w("MessageRepository", "Could not update remote chat preview", e)
        }
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

    fun markChatAsReadInBackground(chatId: String) {
        scope.launch {
            markChatAsRead(chatId)
        }
    }

    suspend fun markChatAsRead(chatId: String): Result<Unit> {
        return try {
            var userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return Result.failure(Exception("No user"))
            var token = authService.getAccessToken()
            var cleanId = chatId.trim().lowercase()
            
            var now = java.time.Instant.now().toString()
            
            // 1. Sync global read state. Ubuntu is authoritative in local fallback sessions.
            if (shouldUseUbuntuBackend()) {
                if (!token.isNullOrBlank()) {
                    localFallbackApiClient.markChatAsRead(token, cleanId)
                        .onFailure { e ->
                            android.util.Log.w("MessageRepository", "Ubuntu markChatAsRead failed for $cleanId", e)
                        }
                } else {
                    android.util.Log.w("MessageRepository", "markChatAsRead: no Ubuntu session token")
                }
            } else if (!token.isNullOrBlank()) {
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
                android.util.Log.w("MessageRepository", "markChatAsRead: no session token; remote sync skipped")
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



    suspend fun markMessageAsDelivered(chatId: String, messageId: String): Result<Unit> =
        markMessageAsDelivered(authService.captureAccountLease(), chatId, messageId)

    internal suspend fun markMessageAsDelivered(
        lease: conectate.com.data.supabase.AuthSessionLease, chatId: String, messageId: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            lease.requireCurrent()
            val currentUserId = lease.accountId ?: return@withContext Result.failure(IllegalStateException("Inicia sesión para continuar."))
            val token = authService.getAccessTokenForAccountAwait(currentUserId)
            lease.requireCurrent()
            if (token.isNullOrBlank()) return@withContext Result.failure(IllegalStateException("Tu sesión venció. Inicia sesión de nuevo."))
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

            lease.requireCurrent()
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

            lease.requireCurrent()
            messageDao.markMessageAsDelivered(cleanMessageId, cleanId, currentUserId, now)
            if (!remoteSynced) {
                android.util.Log.w(
                    "MessageRepository",
                    "markMessageAsDelivered could not confirm remote transition for $cleanMessageId in $cleanId"
                )
            }
            Result.success(Unit)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (e: Exception) {
            lease.requireCurrent()
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
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("No user"))
            val session = captureSessionSnapshot(currentUserId)
            if (!isSessionSnapshotValid(session, "markMessagesAsRead")) return@withContext Result.failure(Exception("La cuenta cambió."))
            var cleanId = chatId.trim().lowercase()
            val cleanMessageIds = messageIds
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
            if (cleanId.isBlank() || cleanMessageIds.isEmpty()) return@withContext Result.success(Unit)

            var now = java.time.Instant.now().toString()
            if (shouldUseUbuntuBackend()) {
                val incomingIds = messageDao.getMessagesForChat(cleanId).filter {
                    it.id in cleanMessageIds && it.sender_id.trim().lowercase() != currentUserId &&
                        conectate.com.core.messaging.UnreadPolicy.isEligibleStatusForReadTransition(it.status)
                }.map { it.id }
                if (!isSessionSnapshotValid(session, "visible-read-local")) return@withContext Result.failure(Exception("La cuenta cambió."))
                val newlySeen = readJournal.record(currentUserId, "chat", cleanId, incomingIds)
                messageDao.markMessagesAsReadByIds(cleanId, currentUserId, now, incomingIds)
                chatDao.subtractReadMessages(cleanId, newlySeen.size)
                _userChats.update { chats -> chats.map {
                    if (it.chat.id == cleanId) it.copy(unreadCount = (it.unreadCount - newlySeen.size).coerceAtLeast(0)) else it
                } }
                _activeChatMessages[cleanId]?.update { messages -> messages.map {
                    if (it.id in incomingIds) it.copy(status = "read", read_at = now) else it
                } }
                authService.getAccessToken()?.let { readSynchronizer.flush(currentUserId, "chat", it) }
                return@withContext Result.success(Unit)
            }
            val token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
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

            if (!isSessionSnapshotValid(session, "markMessagesAsRead.persist")) return@withContext Result.failure(Exception("La cuenta cambió."))
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
        } catch (error: CancellationException) {
            throw error
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
        PendingMessageSyncScheduler.enqueue(context, "direct_poll_pending")
        addMessageToFlow(cleanId, optimistic)
        chatDao.updateLastMessage(
            chatId = cleanId,
            content = "📊 $sanitizedQuestion",
            timestamp = now,
            messageId = msgId,
            senderId = userId
        )

        val pollSession = captureSessionSnapshot(userId)
        return@withContext messageSendCoordinator.withMessage(msgId) {
            if (!isSessionSnapshotValid(pollSession, "sendPollMessage:lock")) throw CancellationException("Session changed")
            val latest = messageDao.getMessageById(msgId)
            if (latest == null || latest.pending_action != "send" || isSendCancelledByDeletion(msgId, userId, pollSession)) {
                return@withMessage Result.success(Unit)
            }
            val token = authService.getAccessToken() ?: return@withMessage Result.failure(Exception("No token"))

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

                if (!isSessionSnapshotValid(pollSession, "sendPollMessage:response")) throw CancellationException("Session changed")
                if (response.status.isSuccess()) {
                    var synced = optimistic.copy(status = "sent")
                    messageDao.insertMessage(conectate.com.data.local.entity.MessageEntity.fromDomain(synced, isSynced = true))
                    updateMessageInFlow(cleanId, synced)
                    Result.success(Unit)
                } else {
                    val error = response.bodyAsText()
                    Result.failure(Exception("Error al crear encuesta: $error"))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }


    private suspend fun findLocalDirectChat(accountId: String, targetUserId: String): ChatEntity? =
        selectCachedDirectChat(
            candidates = chatDao.getChatsByOtherUserId(targetUserId.trim().lowercase()),
            accountId = accountId,
            targetUserId = targetUserId
        )

    suspend fun findChatByParticipants(normalizedParticipants: List<String>): Result<Chat> {
        return try {
            val expectedParticipants = normalizedParticipantIds(normalizedParticipants)
            val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return Result.failure(Exception("No user"))
            val session = captureSessionSnapshot(currentUserId)
            if (expectedParticipants.size != 2 || currentUserId !in expectedParticipants) {
                return Result.failure(IllegalArgumentException("Direct chat requires the active account and one other participant"))
            }
            val targetUserId = expectedParticipants.firstOrNull { it != currentUserId }
                ?: return Result.failure(Exception("No target user"))

            // Opening a known conversation from a profile is an offline read.
            // Never require a token refresh before checking the Room index.
            val localChat = findLocalDirectChat(currentUserId, targetUserId)?.toDomain()
            if (!isSessionSnapshotValid(session, "findChatByParticipants")) throw CancellationException("Session changed")
            if (localChat != null) {
                return Result.success(localChat)
            }

            var token = authService.ensureValidAccessToken()
                .getOrElse { return Result.failure(it) }
            if (!isSessionSnapshotValid(session, "findChatByParticipants:token")) throw CancellationException("Session changed")

            if (shouldUseUbuntuBackend()) {
                val result = localFallbackApiClient.createDirectChat(token, targetUserId)
                if (!isSessionSnapshotValid(session, "findChatByParticipants:remote")) throw CancellationException("Session changed")
                result.getOrNull()?.let { chat ->
                    cacheChat(chat)
                    getUserChats()
                }
                return result
            }
            
            // Query for chats that contain these exact 2 participants
            // Query for chats that contain these exact 2 participants (using contains filter)
            // Use curly braces for Postgres array contains filter (Standard)
            val participantsFilter = "{${expectedParticipants.joinToString(",")}}"
            
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
                if (!isSessionSnapshotValid(session, "findChatByParticipants:remote")) throw CancellationException("Session changed")
                
                // Find all chats with EXACTLY these participants (sorted)
                val matchingChat = chats
                    .filter { it.matchesExactDirectParticipants(expectedParticipants) }
                    .maxByOrNull { it.safeLastMessageAt } // Pick the most active one to avoid ghost IDs
                
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
    private suspend fun cacheChat(chat: Chat, origin: SessionWriteGuard.Snapshot = captureSessionSnapshot()) {
        sessionWriteGuard.requireValid(origin)
        val session = origin.copy(operationJob = null)
        cacheChatMembership(chat, session)
        scope.launch {
            try {
                if (!isSessionSnapshotValid(session, "cacheChat:start")) return@launch
                val userId = session.accountId ?: return@launch
                if (chat.participants.none { it.trim().lowercase() == userId.trim().lowercase() }) return@launch
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
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                android.util.Log.w("MessageRepository", "Could not cache chat participant profile", e)
                            }
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
                if (!isSessionSnapshotValid(session, "cacheChat:write")) return@launch
                chatDao.insertChatOwned(entity) { sessionWriteGuard.requireValid(session) }
                android.util.Log.d("MessageRepository", "Cached chat locally: ${chat.id}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("MessageRepository", "Failed to cache chat", e)
            }
        }
    }
    
    
    suspend fun getOrCreateChat(targetUserId: String): Result<Chat> =
        getOrCreateChat(authService.captureAccountLease(), targetUserId)

    internal suspend fun getOrCreateChat(
        accountLease: conectate.com.data.supabase.AuthSessionLease, targetUserId: String,
    ): Result<Chat> {
        val userId = accountLease.accountId ?: return Result.failure(Exception("Inicia sesión para conversar."))
        val session = sessionWriteGuard.capture(accountLease, kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
        sessionWriteGuard.requireValid(session)
        val cleanTargetUserId = targetUserId.trim().lowercase()
        if (cleanTargetUserId.isBlank() || cleanTargetUserId.startsWith("placeholder_")) {
            return Result.failure(Exception("Usuario destino invalido"))
        }
        if (cleanTargetUserId == userId) return Result.failure(Exception("No puedes crear un chat contigo mismo"))
        val cached = findLocalDirectChat(userId, cleanTargetUserId)
        if (!isSessionSnapshotValid(session, "getOrCreateChat:local")) throw CancellationException("Session changed")
        if (cached != null) return Result.success(cached.toDomain())

        if (shouldUseUbuntuBackend()) {
            val token = authService.getAccessTokenForAccountAwait(userId) ?: return Result.failure(Exception("Vuelve a iniciar sesión para conversar."))
            if (!isSessionSnapshotValid(session, "getOrCreateChat:token")) throw CancellationException("Session changed")
            val canonicalTargetId = userRepository.getUserById(cleanTargetUserId)
                .getOrNull()
                ?.id
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
                ?: cleanTargetUserId
            if (!isSessionSnapshotValid(session, "getOrCreateChat:profile")) throw CancellationException("Session changed")
            if (canonicalTargetId == userId.trim().lowercase()) {
                return Result.failure(Exception("No puedes crear un chat contigo mismo"))
            }
            val result = localFallbackApiClient.createDirectChat(token, canonicalTargetId)
            if (!isSessionSnapshotValid(session, "getOrCreateChat:remote")) throw CancellationException("Session changed")
            result.getOrNull()?.let { chat ->
                cacheChat(chat, session)
                getUserChats()
            }
            return result
        }
        
        // CRITICAL: Normalize participants to ensure same chat is found regardless of who initiates
        val normalizedParticipants = listOf(userId, targetUserId)
            .map { it.trim().lowercase() }
            .sorted()  // Canonical order
        
        android.util.Log.d("MessageRepository", 
            "getOrCreateChat: Looking for chat with participants: $normalizedParticipants")
        
        // 1. Try to find existing chat with these exact participants (remote source of truth)
        var existingChat = findChatByParticipants(normalizedParticipants)
        if (!isSessionSnapshotValid(session, "getOrCreateChat:lookup")) throw CancellationException("Session changed")
        existingChat.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        if (existingChat.isSuccess) {
            var chat = existingChat.getOrThrow()
            android.util.Log.d("MessageRepository", "Found existing chat: ${chat.id}")
            
            // Cache it locally
            cacheChat(chat, session)
            return existingChat
        }
        
        // No exact local/remote match. Creation remains server-authoritative;
        // never fabricate an ID that could receive unsendable offline messages.
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

             if (!isGroup && shouldUseUbuntuBackend()) {
                 val targetUserId = participants
                     .map { it.trim().lowercase() }
                     .firstOrNull { it.isNotBlank() && it != userId.trim().lowercase() }
                     ?: return Result.failure(Exception("No target user"))
                 val result = localFallbackApiClient.createDirectChat(token, targetUserId)
                 result.getOrNull()?.let { chat ->
                     cacheChat(chat)
                     getUserChats()
                 }
                 return result
             }
             
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
        val accountId = currentSessionUserId() ?: return Result.failure(Exception("No user"))
        val session = captureSessionSnapshot(accountId)
        val participants = listOf(accountId, cleanTargetId)
        // 1. Check DAO (Source of Truth for "known" chats)
        val localEntity = findLocalDirectChat(accountId, cleanTargetId)
        if (!isSessionSnapshotValid(session, "getChatByUserId:local")) return Result.failure(CancellationException("Session changed"))
        if (localEntity != null) {
            return Result.success(localEntity.toDomainWithDetails().chat)
        }

        // 2. Check Memory Cache
        val memoryMatch = _userChats.value.firstOrNull { 
            it.chat.matchesExactDirectParticipants(participants)
        }?.chat
        
        if (memoryMatch != null) return Result.success(memoryMatch)
        
        // 3. Refresh & Check Remote
        // This fetches latest list from Supabase, so if it exists there, we find it.
        return getUserChats().mapCatching { list ->
             if (!isSessionSnapshotValid(session, "getChatByUserId:remote")) throw CancellationException("Session changed")
             list.firstOrNull { 
                 it.chat.matchesExactDirectParticipants(participants)
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

    private fun mergeIncomingMessageWithLocal(incoming: Message, local: Message?): Message {
        local ?: return deletedMessageStore.redact(incoming, currentSessionUserId())
        val receipt = mergeReceiptState(
            currentStatus = local.status,
            currentDeliveredAt = local.delivered_at,
            currentReadAt = local.read_at,
            incomingStatus = incoming.status,
            incomingDeliveredAt = incoming.delivered_at,
            incomingReadAt = incoming.read_at
        )
        return deletedMessageStore.redact(incoming.copy(
            chat_id = incoming.chat_id.ifBlank { local.chat_id },
            sender_id = incoming.sender_id.ifBlank { local.sender_id },
            content = incoming.content.ifBlank { local.content },
            media_url = incoming.media_url ?: local.media_url,
            poll_id = incoming.poll_id ?: local.poll_id,
            media_size = incoming.media_size.takeIf { it > 0 } ?: local.media_size,
            status = receipt.status,
            delivered_at = receipt.deliveredAt,
            read_at = receipt.readAt,
            reply_to_id = incoming.reply_to_id ?: local.reply_to_id,
            reply_to = incoming.reply_to ?: local.reply_to,
            is_edited = incoming.is_edited || local.is_edited,
            deleted_for_all = incoming.deleted_for_all || local.deleted_for_all,
            deleted_by = (local.deleted_by + incoming.deleted_by).distinct(),
            reactions = if (incoming.reactions.isNotEmpty()) incoming.reactions else local.reactions,
            is_forwarded = incoming.is_forwarded || local.is_forwarded,
            forwarded_from_message_id = incoming.forwarded_from_message_id ?: local.forwarded_from_message_id,
            forwarded_from_user_id = incoming.forwarded_from_user_id ?: local.forwarded_from_user_id,
            sender_name = incoming.sender_name ?: local.sender_name,
            created_at = incoming.created_at ?: local.created_at,
            updated_at = incoming.updated_at ?: local.updated_at
        ), currentSessionUserId())
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
        val accountId = currentSessionUserId()
        val session = captureSessionSnapshot(accountId)
        val result = AccountChatReader(
            local = { id -> chatDao.getChatById(id)?.toDomain() },
            memory = { id -> _userChats.value.find { it.chat.id.trim().lowercase() == id }?.chat },
            remote = { id -> runReadWithDeadline(InteractiveReadDeadlines.ConversationHistoryMillis) {
                val token = authService.ensureValidAccessToken().getOrThrow()
                if (!isSessionSnapshotValid(session, "getChatById:token")) throw CancellationException("Session changed")
                if (shouldUseUbuntuBackend()) {
                    localFallbackApiClient.loadChatMetadata(token, id).getOrThrow()
                } else {
                    val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/chats") {
                        header("Authorization", "Bearer $token")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        parameter("id", "eq.$id")
                    }
                    if (!response.status.isSuccess()) throw IllegalStateException("Error HTTP ${response.status}")
                    response.body<List<Chat>>().firstOrNull() ?: throw IllegalStateException("Chat no encontrado")
                }
            } }
        ).read(chatId, accountId) { isSessionSnapshotValid(session, "getChatById") }
        result.getOrNull()?.let { cacheChatMembership(it, session) }
        if (!isSessionSnapshotValid(session, "getChatById:return")) throw CancellationException("Session changed")
        return result
    }

    // Membership is persisted before observers subscribe, using local identity
    // only. Optional remote profile hydration must never gate cached messages.
    private suspend fun cacheChatMembership(chat: Chat, session: SessionWriteGuard.Snapshot) {
        if (!chat.isAccessibleTo(session.accountId)) return
        val existing = chatDao.getChatById(chat.id)
        if (existing?.toDomain()?.isAccessibleTo(session.accountId) == true) return
        val otherId = chat.participants.firstOrNull { it.trim().lowercase() != session.accountId }
        val identity = otherId?.let { userDao.getUser(it.trim().lowercase()) }
        if (!isSessionSnapshotValid(session, "cacheChatMembership")) throw CancellationException("Session changed")
        chatDao.insertChatOwned(ChatEntity.fromDomain(
            chat, displayName = identity?.full_name ?: chat.group_name,
            displayImage = identity?.avatar_url ?: chat.group_image, unreadCount = 0,
            lastMsg = chat.last_message, lastTime = chat.last_message_at, otherUserId = otherId
        )) { sessionWriteGuard.requireValid(session) }
    }

    fun getChatFlow(id: String): Flow<Chat?> {
        val cleanId = id.trim().lowercase()
        return accountScopedChatFlow(
            accounts = authService.observeSession().map { it?.user?.id },
            chats = chatDao.getChatByIdFlow(cleanId).map { it?.toDomain() },
            currentAccount = ::currentSessionUserId,
            empty = null as Chat?
        ) { chat, _ -> flowOf(chat) }.distinctUntilChanged()
    }
    


    suspend fun deleteMessage(messageId: String, forEveryone: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val actor = currentSessionUserId()
            ?: return@withContext Result.failure(IllegalStateException("No hay una cuenta activa"))
        val session = captureSessionSnapshot(actor)
        val result = deletionQueue.enqueue(messageId.trim(), actor, forEveryone) {
            isSessionSnapshotValid(session, "queueMessageDeletion")
        }
        if (result.isSuccess) {
            messageDao.getMessageById(messageId.trim())?.toDomain()?.let { local ->
                conectate.com.util.DeletedMessageIdentity.create(actor, "chat", local.chat_id, local.id)?.let {
                    // This user's notification hides immediately; the Room deletion journal owns rollback/status.
                    conectate.com.util.MessageNotificationFactory.redactMessage(context, it, local.created_at, forEveryone = false)
                }
            }
            PendingMessageSyncScheduler.enqueue(context, "direct_message_delete_pending")
            // The screen is driven by the persisted intent immediately, without
            // waiting for a token, connectivity, transport retries or a worker.
            scope.launch { retryPendingDeletes(30) }
        }
        result
    }

    fun getMessageDeletionFailures(chatId: String): Flow<List<conectate.com.data.local.entity.MessageDeletionEntity>> =
        accountScopedChatFlow(
            accounts = authService.observeSession().map { it?.user?.id },
            chats = chatDao.getChatByIdFlow(chatId).map { it?.toDomain() },
            currentAccount = ::currentSessionUserId,
            empty = emptyList()
        ) { _, actor ->
            database.messageDeletionDao().observeChat(chatId).map { intents ->
                intents.filter { it.actor_id == actor && it.state == "failed" }
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

            if (shouldUseUbuntuBackend()) {
                val serverMessage = localFallbackApiClient.editChatMessage(
                    accessToken = token,
                    chatId = source.chat_id.trim().lowercase(),
                    messageId = cleanMessageId,
                    content = normalizedContent
                ).getOrElse { error ->
                    return@withContext Result.failure(error)
                }
                val updatedEntity = conectate.com.data.local.entity.MessageEntity.fromDomain(
                    msg = serverMessage,
                    isSynced = true,
                    pendingAction = null
                )
                messageDao.insertMessage(updatedEntity)
                updateMessageInFlow(serverMessage.chat_id.trim().lowercase(), serverMessage)
                return@withContext Result.success(Unit)
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

            if (shouldUseUbuntuBackend()) {
                val serverMessage = localFallbackApiClient.toggleChatMessageReaction(
                    accessToken = token,
                    chatId = local.chat_id,
                    messageId = messageId,
                    emoji = normalizedEmoji
                ).getOrElse { error ->
                    return@withContext Result.failure(error)
                }
                messageDao.insertMessage(
                    conectate.com.data.local.entity.MessageEntity.fromDomain(
                        msg = serverMessage,
                        isSynced = true,
                        pendingAction = null
                    )
                )
                updateMessageInFlow(serverMessage.chat_id.trim().lowercase(), serverMessage)
                return@withContext Result.success(
                    MessageReactionToggleResult(
                        messageId = serverMessage.id,
                        reactions = serverMessage.reactions
                    )
                )
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
    ): Result<Int> {
        val accountLease = authService.captureAccountLease()
        return withContext(Dispatchers.IO) {
            accountLease.requireCurrent()
            conectate.com.core.messaging.messageForwardingError(source.deleted_for_all, source.content_type, source.status, source.media_url)
                ?.let { return@withContext Result.failure(IllegalStateException(it)) }

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
                val content = source.content.ifBlank {
                    buildPreviewForContentType(source.content_type, "Mensaje reenviado")
                }

                val sendResult = sendMessage(
                    accountLease = accountLease,
                    chatId = targetChatId,
                    content = content,
                    mediaType = source.content_type,
                    existingMediaUrl = source.media_url,
                    isForwarded = true,
                    forwardedFromMessageId = source.id,
                    forwardedFromUserId = source.sender_id
                )

                sendResult.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                accountLease.requireCurrent()
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
    
    internal suspend fun cachedChatImage(source: String, identity: conectate.com.core.media.StorageReadIdentity): String? {
        conectate.com.core.media.StorageReadSession.requireCurrent(identity)
        if (currentSessionUserId() != identity.accountId.trim().lowercase()) return null
        // The retained original also serves its preview variant offline. Thumbnail
        // parameters intentionally have different cache keys, so try both explicitly.
        val original = UbuntuStorageUrl.originalObjectUrl(source)
        val cached = offlineMediaCache.resolveCachedUri(source)
            ?: original?.takeIf { it != source }?.let {
                offlineMediaCache.resolveCachedUri(it)
            }
        return cached.also {
            conectate.com.core.media.StorageReadSession.requireCurrent(identity)
        }
    }

    internal fun retainLoadedChatImage(source: String, identity: conectate.com.core.media.StorageReadIdentity) {
        val original = UbuntuStorageUrl.originalObjectUrl(source) ?: return
        scope.launch {
            if (conectate.com.core.media.StorageReadSession.snapshot()?.identity != identity ||
                currentSessionUserId() != identity.accountId.trim().lowercase()) return@launch
            mediaResolver.getPlayableUrl(original, allowOfflineMediaCache = true)
        }
    }

    internal fun retainLoadedChatPreview(
        source: String,
        identity: conectate.com.core.media.StorageReadIdentity,
        drawable: android.graphics.drawable.Drawable
    ) {
        val original = UbuntuStorageUrl.originalObjectUrl(source) ?: return
        // Flatten only transformed previews; retain original animated WebP/GIF bytes.
        if (UbuntuStorageUrl.stableCacheKey(source) == UbuntuStorageUrl.stableCacheKey(original)) return
        val bitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        if (bitmap.width > 1920 || bitmap.height > 1920) return
        scope.launch {
            if (conectate.com.core.media.StorageReadSession.snapshot()?.identity != identity ||
                currentSessionUserId() != identity.accountId.trim().lowercase()) return@launch
            if (offlineMediaCache.resolveCachedUri(source, retain = true) != null) return@launch
            val bytes = java.io.ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) return@launch
                output.toByteArray()
            }
            if (conectate.com.core.media.StorageReadSession.snapshot()?.identity != identity) return@launch
            offlineMediaCache.storeLoadedPreview(source, bytes, identity.accountId)
        }
    }

    suspend fun getPlayableUrl(
        path: String,
        allowOfflineMediaCache: Boolean = true
    ): Result<String> = mediaResolver.getPlayableUrl(path, allowOfflineMediaCache)

    private val nativeAudioDelivery by lazy {
        conectate.com.util.ChatAudioPlaybackCache(File(context.cacheDir, "native-conversation-audio"))
    }

    internal suspend fun prepareNativeAudio(path: String): conectate.com.util.NativeAudioDelivery {
        val expected = conectate.com.core.media.StorageReadSession.snapshot()?.identity
        val resolved = getPlayableUrl(path).getOrThrow()
        conectate.com.core.media.StorageReadSession.requireCurrent(expected)
        val delivery = conectate.com.util.NativeAudioDelivery.prepare(context, resolved)
        try { conectate.com.core.media.StorageReadSession.requireCurrent(expected); return delivery }
        catch (error: Throwable) { delivery.close(); throw error }
    }

    internal suspend fun localAudioDurationMs(source: String): Long? {
        val identity = conectate.com.core.media.StorageReadSession.snapshot()?.identity
        val duration = conectate.com.util.localAudioDurationMs(context, source)
        conectate.com.core.media.StorageReadSession.requireCurrent(identity)
        return duration
    }

    /** MediaPlayer's native redirect stack cannot safely transport a private JWT. */
    suspend fun getNativeAudioPlayableUrl(path: String): Result<String> = runCatching {
        val identity = conectate.com.core.media.StorageReadSession.snapshot()?.identity
        val resolved = getPlayableUrl(path).getOrThrow()
        val playable = if (conectate.com.core.media.StorageReadSession.isStorageRequest(resolved))
            withContext(Dispatchers.IO) { nativeAudioDelivery.download(resolved).absolutePath } else resolved
        conectate.com.core.media.StorageReadSession.requireCurrent(identity)
        playable
    }

    suspend fun getOfflineChatMediaUsage(): conectate.com.core.media.ChatOfflineMediaUsage? =
        offlineMediaCache.currentAccountUsage()

    suspend fun clearOfflineChatMedia(expectedAccountKey: String): Boolean =
        offlineMediaCache.clearCurrentAccount(expectedAccountKey)

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
        isMuted?.let {
            // Explicit inbox choices replace the device's timed notification mute.
            if (it) notificationMutePreferences.muteConversation("chat", chatId, currentSessionUserId())
            else notificationMutePreferences.unmuteConversation("chat", chatId, currentSessionUserId())
            chatDao.updateMuteState(chatId, it)
        }
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
            val preferenceSession = captureSessionSnapshot(currentUserId)

            syncChatPreferencesRemote(
                chatId = cleanId,
                token = token,
                userId = cleanUserId,
                isMuted = isMuted,
                isPinned = isPinned,
                isArchived = isArchived,
                isFavorite = isFavorite
            )
            if (!isSessionSnapshotValid(preferenceSession, "chat-preferences-result")) {
                return Result.failure(CancellationException("La cuenta cambió. Vuelve a intentarlo."))
            }
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
            chatDao.getChatByIdSync(cleanId)?.is_muted == true ||
                notificationMutePreferences.isMuted("chat", cleanId, currentSessionUserId())
        }.getOrDefault(false)
    }
    
    suspend fun deleteChat(chatId: String): Result<Unit> =
        deleteChat(authService.captureAccountLease(), chatId)

    internal suspend fun deleteChat(
        owner: conectate.com.data.supabase.AuthSessionLease,
        chatId: String,
    ): Result<Unit> {
        val cleanId = chatId.trim().lowercase()
        if (cleanId.isBlank()) return Result.failure(IllegalArgumentException("Chat inválido"))
        owner.requireCurrent()
        val ubuntu = shouldUseUbuntuBackend()
        val now = java.time.Instant.now().toString()
        return executeOwnedChatVisibility(
            owner = owner,
            token = {
                authService.getAccessTokenForAccountAwait(requireNotNull(owner.accountId))
                    ?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Tu sesión venció. Inicia sesión de nuevo.")
            },
            hide = { token ->
                if (ubuntu) localFallbackApiClient.hideChat(token, cleanId)
                else clearChatRemotely(token, requireNotNull(owner.accountId), cleanId, now, ubuntu = false)
            },
            clear = if (ubuntu) ({ token -> localFallbackApiClient.clearChat(token, cleanId) }) else null,
            commit = {
                authService.withAccountLocalStorage(owner) {
                    database.withTransaction {
                        currentCoroutineContext().ensureActive()
                        owner.requireCurrent()
                        if (!ubuntu) chatDao.updateClearedAt(cleanId, now)
                        chatDao.deleteChat(cleanId, now)
                        currentCoroutineContext().ensureActive()
                        owner.requireCurrent() // A stale write rolls back inside Room.
                    }
                }
                if (!authService.commitForAccountLease(owner) {
                    sessionStateStore.clearLocalReadOverride(cleanId)
                    if (!ubuntu) _activeChatMessages[cleanId]?.value = emptyList()
                    _userChats.value = _userChats.value.filterNot { it.chat.id.trim().lowercase() == cleanId }
                }) throw CancellationException("La cuenta cambió. Vuelve a abrir el chat.")
            },
        )
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
            inferredMime = inferredMime,
            fallbackExtension = fileExtensionFromName(mediaUri?.lastPathSegment) ?: "bin"
        )
        return resolved.mimeType to resolved.extensionWithDot
    }

    private suspend fun uploadChatMedia(
        cleanChatId: String,
        mediaUri: Uri,
        mediaType: String,
        messageId: String = "",
        accountId: String = ""
    ): Result<ChatMediaUpload> = withContext(Dispatchers.IO) {
        conectate.com.core.messaging.MessageUploadProgress.update(accountId, messageId, 0f)
        try {
            val headerBytes = readUriHeaderBytes(mediaUri)
                ?: return@withContext Result.failure(Exception("No se pudo leer archivo"))
            val (mimeType, extension) = resolveMediaUploadMeta(mediaType, mediaUri, headerBytes)
            // Two attachments queued in the same millisecond must never replace each other.
            val filename = "$cleanChatId/${System.currentTimeMillis()}_${UUID.randomUUID()}$extension"
            val sizeBytes = queryUriSizeBytes(mediaUri)
            val upload = if (sizeBytes > 0L && shouldStreamChatMediaUpload(mimeType, sizeBytes)) {
                storageService.uploadFileFromUri(
                    bucketName = "chat-media",
                    fileName = filename,
                    sourceUri = mediaUri,
                    sizeBytes = sizeBytes,
                    contentType = mimeType,
                    allowOverwrite = false,
                    onProgress = { conectate.com.core.messaging.MessageUploadProgress.update(accountId, messageId, it) }
                )
            } else {
                val bytes = context.contentResolver.openInputStream(mediaUri)?.use { it.readMediaBytesWithLimit(60L * 1024 * 1024) }
                    ?: return@withContext Result.failure(Exception("No se pudo leer archivo"))
                storageService.uploadFile("chat-media", filename, bytes, mimeType, allowOverwrite = false)
            }

            if (upload.isFailure) {
                return@withContext Result.failure(upload.exceptionOrNull() ?: Exception("No se pudo subir multimedia"))
            }

            val remoteKey = upload.getOrThrow().Key ?: filename
            val url = if (shouldUseUbuntuBackend()) {
                UbuntuStorageUrl.rewrite(storageService.getPublicUrl("chat-media", remoteKey)).orEmpty()
            } else {
                "chat-media/$filename"
            }
            val uploadedSize = sizeBytes.takeIf { it > 0L } ?: upload.getOrThrow().Size?.toLong() ?: 0L
            val thumbnailUrl = if (mimeType.startsWith("video/", ignoreCase = true)) {
                uploadChatVideoThumbnail(cleanChatId, mediaUri)
            } else {
                null
            }
            Result.success(
                ChatMediaUpload(
                    url = url,
                    thumbnailUrl = thumbnailUrl,
                    sizeBytes = uploadedSize,
                    contentType = mimeType
                )
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Result.failure(error)
        } finally {
            conectate.com.core.messaging.MessageUploadProgress.finish(accountId, messageId)
        }
    }

    private suspend fun uploadChatVideoThumbnail(cleanChatId: String, mediaUri: Uri): String? {
        val bytes = VideoUtils.generateVideoThumbnail(context, mediaUri) ?: return null
        val filename = "$cleanChatId/${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg"
        val upload = storageService.uploadFile(
            bucketName = "thumbnails",
            fileName = filename,
            bytes = bytes,
            contentType = "image/jpeg"
        ).getOrNull() ?: return null
        val remoteKey = upload.Key ?: filename
        return if (shouldUseUbuntuBackend()) {
            UbuntuStorageUrl.rewrite(storageService.getPublicUrl("thumbnails", remoteKey))
        } else {
            "thumbnails/$remoteKey"
        }
    }

    private fun shouldStreamChatMediaUpload(contentType: String, sizeBytes: Long): Boolean {
        if (sizeBytes >= 2L * 1024 * 1024) return true
        val canonical = contentType.substringBefore(";").trim().lowercase()
        return canonical.startsWith("video/") ||
            canonical.startsWith("audio/") ||
            canonical == "application/pdf" ||
            canonical == "application/zip" ||
            canonical.startsWith("application/vnd.openxmlformats")
    }

    private fun readUriHeaderBytes(uri: Uri, maxBytes: Int = 512): ByteArray? {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(maxBytes)
            val read = stream.read(buffer)
            if (read > 0) buffer.copyOf(read) else null
        }
    }

    private fun queryUriSizeBytes(uri: Uri): Long {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) {
                    val value = cursor.getLong(index)
                    if (value > 0L) return value
                }
            }
        }
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length > 0L) return descriptor.length
            }
        }
        return 0L
    }

    private data class PendingChatMedia(
        val uri: Uri,
        val sizeBytes: Long
    )

    private suspend fun persistPendingChatMedia(
        accountId: String,
        requireCurrent: () -> Unit,
        sourceUri: Uri,
        messageId: String,
        mediaType: String,
    ): Result<PendingChatMedia> = conectate.com.util.PendingOutgoingMediaStore.persist(
        context = context, accountId = accountId, namespace = "chat", messageId = messageId,
        sourceUri = sourceUri, preferredMediaType = mediaType, requireCurrent = requireCurrent,
    ).map { PendingChatMedia(it.uri, it.sizeBytes) }

    private fun pendingChatMediaUri(value: String?): Uri? {
        conectate.com.util.PendingOutgoingMediaStore.resolve(context, value)?.let { return it }
        // Pending rows from installed versions still refer to this original directory.
        val uri = value?.trim()?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
        if (!uri.scheme.equals("file", ignoreCase = true)) return null
        val path = uri.path ?: return null
        val root = File(context.filesDir, "pending-message-media").canonicalFile
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        return if (file.isFile && file.path.startsWith(root.path + File.separator)) Uri.fromFile(file) else null
    }

    private fun deletePendingChatMedia(uri: Uri) {
        pendingChatMediaUri(uri.toString())?.path?.let { path -> runCatching { File(path).delete() } }
    }

    private fun isRetryableNetworkFailure(message: String?): Boolean {
        val normalized = message?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return false
        return transientNetworkRetryCodes.any(normalized::contains) ||
            normalized.contains("unable to resolve") ||
            normalized.contains("unknown host") ||
            normalized.contains("failed to connect") ||
            normalized.contains("socket") ||
            normalized.contains("broken pipe") ||
            normalized.contains("connection reset")
    }

    private fun hasValidatedNetwork(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun isRetryableBackendFailure(statusCode: Int, body: String?): Boolean {
        if (conectate.com.data.localfallback.isPendingMessagePermission(body)) return true
        if (statusCode in 100..599) return isRetryableMessageHttpStatus(statusCode)
        val normalized = body?.trim()?.lowercase().orEmpty()
        val terminalHints = listOf(
            "http 400", "http 401", "http 403", "http 404",
            "unauthorized", "forbidden", "not a participant", "blocked",
            "invalid message", "validation"
        )
        if (terminalHints.any(normalized::contains)) return false
        return statusCode == 0 || statusCode == 408 || statusCode == 425 || statusCode == 429 ||
            statusCode in 500..599 || isRetryableNetworkFailure(normalized)
    }

    private fun buildPreviewForContentType(contentType: String, fallbackContent: String): String {
        buildConversationBackgroundPreview(contentType, fallbackContent)?.let { return it }
        buildCallEventPreview(contentType, fallbackContent)?.let { return it }
        return when (contentType.trim().lowercase()) {
            "birthday_card" -> "🎂 Tarjeta de cumpleaños"
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

    suspend fun handleIncomingMessage(msg: Message) = handleIncomingMessage(authService.captureAccountLease(), msg)

    internal suspend fun handleIncomingMessage(lease: conectate.com.data.supabase.AuthSessionLease, msg: Message) {
        handleGlobalMessageInsert(msg, lease)
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
                (cachedUser?.toDomain() ?: conectate.com.data.model.User()).copy(
                    id = entity.other_user_id ?: cachedUser?.id.orEmpty(),
                    display_name = resolvedName,
                    avatar_url = entity.display_image ?: cachedUser?.avatar_url,
                    username = cachedUser?.username ?: "Usuario"
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
        val stored = cached.otherUserId?.let { id ->
            runCatching { userDao.getUserSync(id.trim().lowercase())?.toDomain() }.getOrNull()
        }
        return (stored ?: conectate.com.data.model.User()).copy(
            id = cached.otherUserId ?: "", 
            display_name = cached.otherUserName,
            avatar_url = cached.otherUserImage,
            username = stored?.username,
            is_admin = stored?.is_admin ?: cached.otherUserIsAdmin,
            is_verified = stored?.is_verified ?: cached.otherUserVerified
        )
    }

    suspend fun updateReportStatus(reportId: String, newStatus: String): Result<Unit> =
        reportStore.updateReportStatus(reportId, newStatus)

    suspend fun deleteReport(reportId: String): Result<Unit> = reportStore.deleteReport(reportId)
}





