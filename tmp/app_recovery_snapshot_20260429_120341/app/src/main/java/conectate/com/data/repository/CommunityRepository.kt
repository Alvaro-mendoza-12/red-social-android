package conectate.com.data.repository

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.app.NotificationManager
import android.app.NotificationChannel
import conectate.com.BuildConfig
import conectate.com.core.concurrency.newSafeScope
import conectate.com.core.moderation.ContentModerationService
import conectate.com.core.moderation.ModerationContext
import conectate.com.core.moderation.ModerationVerdict
import conectate.com.core.observability.AppMetrics
import conectate.com.core.security.GuardedAction
import conectate.com.core.security.GuardOutcome
import conectate.com.core.security.InputSecurityPolicy
import conectate.com.core.security.OperationGuard
import conectate.com.data.repository.collection.CollectionAdminRemoteStore
import conectate.com.data.repository.collection.CollectionCreationRemoteStore
import conectate.com.data.repository.collection.CollectionDeleteRetrySupport
import conectate.com.data.repository.collection.CollectionGovernanceSupport
import conectate.com.data.repository.collection.CollectionMediaStore
import conectate.com.data.repository.collection.CollectionMessageEngagementStore
import conectate.com.data.repository.collection.CollectionMemberProfileStore
import conectate.com.data.repository.collection.CollectionMessageForwardingSupport
import conectate.com.data.repository.collection.CollectionMembershipMutationSupport
import conectate.com.data.repository.collection.CollectionMessageSyncSupport
import conectate.com.data.repository.collection.CollectionPendingMessageRemoteStore
import conectate.com.data.repository.collection.CollectionPendingMessageRetrySupport
import conectate.com.data.repository.collection.CollectionMessageReceiptRemoteStore
import conectate.com.data.repository.collection.CollectionPollMessageRemoteStore
import conectate.com.data.repository.collection.CollectionPollStore
import conectate.com.data.repository.collection.CollectionRepositorySupport
import conectate.com.data.repository.collection.CollectionSyncSupport
import conectate.com.data.repository.collection.bindCollectionRealtimeAuthLifecycle
import conectate.com.data.repository.collection.launchCollectionRealtimeLoop
import conectate.com.data.local.dao.CommunityDao
import conectate.com.data.local.entity.CommunityEntity
import conectate.com.data.local.entity.CommunityMemberEntity
import conectate.com.data.local.entity.CommunityMessageEntity
import conectate.com.data.model.Community
import conectate.com.data.model.CommunityMember
import conectate.com.data.model.CommunityMessage
import conectate.com.data.model.CommunityWithLastMessage
import conectate.com.data.model.MessagePollOptionState
import conectate.com.data.model.PollVoteUser
import conectate.com.data.model.MessageReadReceipt
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.domain.sync.CommunitySyncDataSource
import conectate.com.util.ChatSoundPlayer
import conectate.com.util.buildCallEventPreview
import conectate.com.util.buildConversationBackgroundPreview
import conectate.com.util.ConversationNotificationPrefs
import conectate.com.util.isCallEventContentType
import conectate.com.util.MessageNotificationFactory
import conectate.com.util.MessageNotificationPayload
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.*
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.flow.*
import android.os.Handler
import android.os.Looper
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommunityRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val communityDao: CommunityDao,
    private val userRepository: UserRepository,
    private val storageService: conectate.com.data.supabase.StorageService,
    private val moderationService: ContentModerationService,
    private val appMetrics: AppMetrics,
    private val operationGuard: OperationGuard,
    private val chatSoundPlayer: ChatSoundPlayer,
    @ApplicationContext private val context: Context
) : CommunitySyncDataSource {
    private val maxForwardTargetsPerBatch = 20
    private var scope = newSafeScope("CommunityRepository", Dispatchers.IO)
    private var json = Json { ignoreUnknownKeys = true }
    private val transientNetworkRetryCodes = listOf("timeout", "timed out", "connection", "network", "502", "503", "504")
    private val collectionAdminRemoteStore = CollectionAdminRemoteStore(supabaseConfig)
    private val collectionCreationRemoteStore = CollectionCreationRemoteStore(supabaseConfig)
    private val collectionMediaStore = CollectionMediaStore(storageService)
    private val collectionMessageEngagementStore = CollectionMessageEngagementStore(supabaseConfig, json)
    private val collectionMemberProfileStore = CollectionMemberProfileStore(supabaseConfig)
    private val collectionMessageReceiptRemoteStore = CollectionMessageReceiptRemoteStore(supabaseConfig)
    private val collectionPollMessageRemoteStore = CollectionPollMessageRemoteStore(supabaseConfig, authService, json)
    private val collectionPollStore = CollectionPollStore(supabaseConfig, authService, json)
    private val collectionPendingMessageRemoteStore = CollectionPendingMessageRemoteStore(supabaseConfig, authService)
    private val sessionWriteGuard = SessionWriteGuard { authService.getCurrentUserId() }

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
                "CommunityRepository",
                "Discarding stale session write from $source. expected=${snapshot.accountId}, active=${currentSessionUserId()}"
            )
        }
        return valid
    }

    init {
        bindCollectionRealtimeAuthLifecycle(
            scope = scope,
            supabaseConfig = supabaseConfig,
            logTag = "CommunityRepository",
            logPrefix = "[COMM]",
            readyLog = "🟢 [C-REALTIME] Sistema Iniciado",
            onAuthenticated = ::startRealtimeSubscriptions,
            onNotAuthenticated = ::stopRealtimeSubscriptions
        )
    }

    private var realtimeJob: Job? = null
    private var communityListenerJob: Job? = null
    private var subscribedCommunityIds: Set<String> = emptySet()
    private val activeCommunityMessageJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private fun startRealtimeSubscriptions(userId: String) {
        if (userId.isBlank()) return
        
        realtimeJob?.cancel()
        realtimeJob = scope.launchCollectionRealtimeLoop(
            userId = userId,
            logTag = "CommunityRepository",
            collectionLabelPlural = "communities",
            syncAction = ::syncCommunities,
            retryPendingAction = { retryPendingCommunityMessages() },
            loadSubscribedIds = {
                communityDao.getAllCommunitiesSync()
                    .filter { it.is_member && !it.is_deleted }
                    .map { it.id.trim().lowercase() }
                    .toSet()
            },
            onSubscribedIdsChanged = { subscribedCommunityIds = it },
            subscribeAction = ::subscribeToCommunityChanges
        )
    }

    private fun stopRealtimeSubscriptions() {
        realtimeJob?.cancel()
        realtimeJob = null
        communityListenerJob?.cancel()
        communityListenerJob = null
        android.util.Log.d("CommunityRepository", "[COMM] Realtime subscriptions stopped")
    }

    fun clearData() {
        android.util.Log.w("CommunityRepository", "CLEAR DATA: Purging in-memory community state...")
        sessionWriteGuard.invalidate()
        stopRealtimeSubscriptions()
        subscribedCommunityIds = emptySet()
        currentOpenedCommunityId = null
        activeCommunityMessageJobs.values.forEach { it.cancel() }
        activeCommunityMessageJobs.clear()
        _activeCommunityMessages.clear()
    }

    // --- STATE ---
    private val _activeCommunityMessages = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<List<CommunityMessage>>>()
    private var currentOpenedCommunityId: String? = null

    fun setActiveCommunity(communityId: String?) {
        currentOpenedCommunityId = communityId?.trim()?.lowercase()
        if (communityId != null) {
            var cleanId = communityId.trim().lowercase()
            // Clear system notifications for this community
            try {
                var notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(cleanId.hashCode())
            } catch (e: Exception) {
                android.util.Log.e("CommunityRepository", "Error clearing notification", e)
            }
            scope.launch {
                runCatching { communityDao.resetUnreadCount(cleanId) }
            }
        }
    }

    fun getUserRoleFlow(communityId: String, userId: String): Flow<String?> {
        return communityDao.getUserRoleFlow(communityId.trim().lowercase(), userId.trim().lowercase())
    }

    // --- REALTIME SUBSCRIPTION (ROBUST) ---

    // Single channel for all community events for this user
    
    private suspend fun subscribeToCommunityChanges(userId: String, communityIds: List<String>) {
        communityListenerJob?.cancel()
        communityListenerJob = scope.launch {
            // 1. MESSAGES CHANNEL LOOP
            if (communityIds.isNotEmpty()) {
                launch {
                    var retryCount = 0
                    while (isActive) {
                        try {
                            var client = supabaseConfig.client
                            var cleanUserId = userId.trim().lowercase()
                            var channelName = "communities-messages-$cleanUserId"
                            var channel = client.channel(channelName)
                            
                            // [REALTIME FIX] Remove 'in.' filter as it might be unsupported and causing delivery failures.
                            // RLS already restricts messages to those the user should see.
                            var changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                                table = "community_messages"
                            }

                            coroutineScope {
                                val msgScope = this
                                changes.onEach { change: PostgresAction ->
                                    android.util.Log.d("CommunityRepository", "📡 [REALTIME-MSG] Change received: $change")
                                    when (change) {
                                        is PostgresAction.Insert -> {
                                            var msg = json.decodeFromJsonElement<CommunityMessage>(change.record)
                                            handleIncomingMessage(msg, cleanUserId)
                                        }
                                        is PostgresAction.Update -> {
                                            var msg = json.decodeFromJsonElement<CommunityMessage>(change.record)
                                            handleIncomingCommunityUpdate(msg, cleanUserId)
                                        }
                                        else -> {}
                                    }
                                }.launchIn(this)
                                launch {
                                    supabaseConfig.subscribeRealtimeChannel(
                                        channel = channel,
                                        reason = "community-messages-$userId"
                                    )
                                    android.util.Log.d("CommunityRepository", "🔥 [REALTIME] Subscribed to COMM MESSAGES Flow for ${communityIds.size} communities")
                                    appMetrics.incrementCounter(
                                        metric = "community_realtime_subscribe_success",
                                        attributes = mapOf("scope" to "messages", "community_count" to communityIds.size.toString())
                                    )
                                    
                                    if (BuildConfig.DEBUG) {
                                        launch {
                                            while (isActive) {
                                                delay(30000)
                                                android.util.Log.d("CommunityRepository", "💓 [HEARTBEAT] COMM MESSAGES Channel Status: ${channel.status.value}")
                                            }
                                        }
                                    }

                                    retryCount = 0
                                    channel.status.collect { status ->
                                        if (status == RealtimeChannel.Status.UNSUBSCRIBED || status.name == "DISCONNECTED" || status.name == "CLOSED" || status.name == "TIMED_OUT") {
                                            android.util.Log.w("CommunityRepository", "⚠️ Comm Messages Channel Status: $status. Restarting...")
                                            msgScope.cancel()
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            if (e !is CancellationException) {
                                retryCount++
                                val delayMs = 3000L * (1 shl minOf(retryCount, 4))
                                appMetrics.recordFailure(
                                    metric = "community_realtime_messages_loop",
                                    throwable = e,
                                    attributes = mapOf("retry_count" to retryCount.toString())
                                )
                                android.util.Log.e("CommunityRepository", "❌ Comm Messages Loop Error. Retry in ${delayMs}ms", e)
                                delay(delayMs)
                            }
                        }
                    }
                }
            } else {
                android.util.Log.w("CommunityRepository", "⚠️ [REALTIME] No communities to subscribe to (Messages Loop skipped).")
            }
            
            // 2. MEMBERS
            launch {
                var retryCount = 0
                while (isActive) {
                    try {
                        var client = supabaseConfig.client
                        var cleanUserId = userId.trim().lowercase()
                        var channelName = "communities-members-$cleanUserId"
                        var channel = client.channel(channelName)
                        
                        var changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                            table = "community_members"
                            this.filter = "user_id=eq.$cleanUserId"
                        }
                        
                        coroutineScope {
                            val memberScope = this
                            changes.onEach { change: PostgresAction ->
                                android.util.Log.d("CommunityRepository", "👥 Membership changed ($change). Syncing and Refreshing Realtime...")
                                
                                val userIdToClean = userId.trim().lowercase()
                                when (change) {
                                    is PostgresAction.Insert -> {
                                        try {
                                            var member = json.decodeFromJsonElement<CommunityMember>(change.record)
                                            communityDao.insertMembers(listOf(CommunityMemberEntity(member.community_id.trim().lowercase(), member.user_id.trim().lowercase(), member.role, member.joined_at)))
                                        } catch (e: Exception) {
                                            android.util.Log.e("CommunityRepository", "Error decoding member insert", e)
                                        }
                                    }
                                    is PostgresAction.Update -> {
                                        try {
                                            var member = json.decodeFromJsonElement<CommunityMember>(change.record)
                                            val cleanCId = member.community_id.trim().lowercase()
                                            val cleanUId = member.user_id.trim().lowercase()
                                            android.util.Log.i("CommunityRepository", "🔄 [ROLE-UPDATE] User $cleanUId in $cleanCId is now ${member.role}")
                                            communityDao.insertMembers(listOf(CommunityMemberEntity(cleanCId, cleanUId, member.role, member.joined_at)))
                                        } catch (e: Exception) {
                                             android.util.Log.e("CommunityRepository", "Error decoding member update. Check REPLICA IDENTITY FULL.", e)
                                        }
                                    }
                                    is PostgresAction.Delete -> {
                                        val oldData = change.oldRecord
                                        val cId = oldData["community_id"]?.jsonPrimitive?.contentOrNull
                                        if (cId != null) {
                                            communityDao.removeMember(cId.trim().lowercase(), userIdToClean)
                                        }
                                    }
                                    else -> {}
                                }

                                syncCommunities()
                            }.launchIn(this)
                            launch {
                                supabaseConfig.subscribeRealtimeChannel(
                                    channel = channel,
                                    reason = "community-members-$userId"
                                )
                                android.util.Log.d("CommunityRepository", "🔥 [REALTIME] Subscribed to COMM MEMBERS updates")
                                retryCount = 0
                                channel.status.collect { status ->
                                    if (status == RealtimeChannel.Status.UNSUBSCRIBED || status.name == "DISCONNECTED" || status.name == "CLOSED" || status.name == "TIMED_OUT") {
                                        android.util.Log.w("CommunityRepository", "⚠️ Comm Members Channel Status: $status. Restarting...")
                                        memberScope.cancel()
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                         if (e !is CancellationException) {
                            retryCount++
                            delay(5000)
                         }
                    }
                }
            }

            // 3. GLOBAL COMMUNITIES DISCOVERY (Listen for new public communities)
            launch {
                var retryCount = 0
                while (isActive) {
                    try {
                        var client = supabaseConfig.client
                        var cleanUserId = userId.trim().lowercase()
                        var channelName = "communities-discovery-$cleanUserId"
                        var channel = client.channel(channelName)
                        
                        var changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                            table = "communities"
                        }
                        
                        coroutineScope {
                            val discoveryScope = this
                            changes.onEach { change: PostgresAction ->
                                android.util.Log.d("CommunityRepository", "🌍 [REALTIME-DISCOVERY] Community table change: $change")
                                when (change) {
                                    is PostgresAction.Insert -> {
                                        try {
                                            var community = json.decodeFromJsonElement<conectate.com.data.model.Community>(change.record)
                                            if (community.is_public) {
                                                android.util.Log.i("CommunityRepository", "✨ New public community discovered: ${community.name}")
                                                communityDao.insertCommunities(listOf(conectate.com.data.local.entity.CommunityEntity.fromDomain(community, 0)))
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("CommunityRepository", "Error decoding community insert", e)
                                        }
                                    }
                                    is PostgresAction.Update -> {
                                        try {
                                            var community = json.decodeFromJsonElement<conectate.com.data.model.Community>(change.record)
                                            var cleanCommunityId = community.id.trim().lowercase()
                                            var local = communityDao.getCommunityById(cleanCommunityId)
                                            android.util.Log.i("CommunityRepository", "📝 [META-UPDATE] Community ${community.name} updated. OnlyAdmins=${community.only_admins_can_talk}, deleted=${community.is_deleted}")
                                            if (community.is_deleted) {
                                                communityDao.deleteCommunity(cleanCommunityId)
                                            } else if (local != null || community.is_public) {
                                                communityDao.insertCommunities(
                                                    listOf(
                                                        conectate.com.data.local.entity.CommunityEntity.fromDomain(
                                                            community.copy(id = cleanCommunityId),
                                                            local?.unread_count ?: 0
                                                        )
                                                    )
                                                )
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("CommunityRepository", "Error decoding community update. Check REPLICA IDENTITY FULL.", e)
                                        }
                                    }
                                    else -> {}
                                }
                            }.launchIn(this)
                            launch {
                                supabaseConfig.subscribeRealtimeChannel(
                                    channel = channel,
                                    reason = "community-discovery-$userId"
                                )
                                android.util.Log.d("CommunityRepository", "🔥 [REALTIME] Subscribed to GLOBAL COMMUNITIES discovery")
                                retryCount = 0
                                channel.status.collect { status ->
                                    if (status == RealtimeChannel.Status.UNSUBSCRIBED || status.name == "DISCONNECTED" || status.name == "CLOSED" || status.name == "TIMED_OUT") {
                                        discoveryScope.cancel()
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (e !is CancellationException) {
                            retryCount++
                            delay(5000)
                        }
                    }
                }
            }
        }
    }

    // --- MESSAGE HANDLING ---

    // Note: No explicit locking needed - Dispatcher.IO coroutines are single-threaded per context
    
    suspend fun handleIncomingMessage(
        msg: CommunityMessage,
        sessionUserId: String? = currentSessionUserId()
    ) {
        var sessionSnapshot = captureSessionSnapshot(sessionUserId)
        withContext(Dispatchers.IO) {
            if (!isSessionSnapshotValid(sessionSnapshot, "community-insert-start:${msg.community_id}")) return@withContext
            var cleanId = msg.community_id.trim().lowercase()
            android.util.Log.i("CommunityRepository", "📡 [REALTIME-INCOMING] Received msg=${msg.id} content='${msg.content.take(20)}' comm=$cleanId")
            
            try {
                    
                    // 1. Validar existencia local
                    var localCommunity = communityDao.getCommunityById(cleanId)
                    if (localCommunity == null) {
                        android.util.Log.w("CommunityRepository", "⚠️ Recibido mensaje de comunidad desconocida $cleanId. Sincronizando...")
                        syncCommunities()
                        localCommunity = communityDao.getCommunityById(cleanId)
                    }

                    // 2. Si no existe, ignorar
                    if (localCommunity == null) {
                        android.util.Log.e("CommunityRepository", "⛔ Mensaje BLOQUEADO para $cleanId (No existe)")
                        return@withContext
                    }

                    // 3. DUPLICATE DETECTION: Check if message already exists in local DB
                    val existingMessage = communityDao.getMessageById(msg.id)
                    if (existingMessage != null) {
                        android.util.Log.d("CommunityRepository", "♻️ [DUPLICATE] Mensaje ${msg.id} ya existe en DB. Ignorando.")
                        return@withContext
                    }

                    android.util.Log.d("CommunityRepository", "📩 [MSG-VALID] Content='${msg.content.take(50)}...', Sender=${msg.sender_id}, Community=$cleanId")

                    // 4. Persistir Mensaje (Room)
                    var currentUserId = sessionSnapshot.accountId
                    
                    if (currentUserId != null && msg.sender_id.trim().lowercase() != currentUserId) {
                        scope.launch { markMessageAsDelivered(cleanId, msg.id) }
                    }

                    // Hydrate reply_to manually since Realtime payload allows it to be null/missing joins
                    val normalizedMsg = if (msg.reply_to_id != null && msg.reply_to == null) {
                        val reply = communityDao.getMessageById(msg.reply_to_id)?.toDomain()
                        msg.copy(reply_to = reply)
                    } else msg.copy(community_id = cleanId) // Ensure clean ID

                    val hydratedMsg = hydrateCommunityMessage(normalizedMsg)

                    if (!isSessionSnapshotValid(sessionSnapshot, "community-insert-before-db:$cleanId")) return@withContext
                    communityDao.insertMessages(listOf(conectate.com.data.local.entity.CommunityMessageEntity.fromDomain(hydratedMsg)))
                    android.util.Log.d("CommunityRepository", "💾 [DB-SAVE] Mensaje ${msg.id} guardado en Room DB")
                    
                    // 5. Actualizar Flow Activo (Chat Screen)
                    if (!isSessionSnapshotValid(sessionSnapshot, "community-insert-before-flow:$cleanId")) return@withContext
                    updateCommunityMessageInFlow(cleanId, hydratedMsg)
                    android.util.Log.d("CommunityRepository", "🔄 [FLOW-UPDATE] Flow actualizado para $cleanId")
                    
                    // 6. Actualizar Metadatos de la Comunidad (Lista de Chats) y Notificar
                    var cleanSenderId = msg.sender_id.trim().lowercase()
                    val isMyMessage = cleanSenderId == currentUserId
                    
                    if (!isMyMessage) {
                        // CRITICAL FIX: Only increment unread & notify if this is a JOINED community
                        var isMember = if (currentUserId != null) communityDao.isUserMemberSync(cleanId, currentUserId) else false
                        
                        // Also check if this COMMUNITY is NOT currently open
                        val isCommunityCurrentlyOpen = (cleanId == currentOpenedCommunityId)
                        
                        if (isMember && !isCommunityCurrentlyOpen) {
                            android.util.Log.d("CommunityRepository", "🔔 [UNREAD+1] Incrementando contador para $cleanId (comunidad cerrada y soy miembro)")
                            if (!isSessionSnapshotValid(sessionSnapshot, "community-insert-before-unread:$cleanId")) return@withContext
                            communityDao.incrementUnreadCount(cleanId)
                            if (!isCallEventContentType(msg.content_type)) {
                                showNotification(msg, localCommunity.name)
                            }
                        } else {
                            android.util.Log.d("CommunityRepository", "👁️ [MSG-IGNORE-NOTIF] Member=$isMember, Open=$isCommunityCurrentlyOpen. Skipping notification.")
                            if (isMember && isCommunityCurrentlyOpen) {
                                if (!isCallEventContentType(msg.content_type)) {
                                    chatSoundPlayer.playReceive()
                                }
                            }
                        }
                    } else {
                        android.util.Log.d("CommunityRepository", "✍️ [MY-MSG] Mensaje enviado por mí. No incrementar unread.")
                    }
                    
                    // Actualizar last_message siempre (para todos los mensajes, incluso los míos)
                    if (!isSessionSnapshotValid(sessionSnapshot, "community-insert-before-metadata:$cleanId")) return@withContext
                    communityDao.updateMetadata(cleanId, 
                        buildConversationBackgroundPreview(msg.content_type, msg.content)
                            ?: buildCallEventPreview(msg.content_type, msg.content)
                            ?: when(msg.content_type) {
                            "text" -> msg.content
                            "poll" -> "📊 ${msg.content}"
                            else -> "Archivo multimedia"
                        },
                        msg.created_at
                    )
                    
                    android.util.Log.d("CommunityRepository", "✅ [INCOMING-COMPLETE] Mensaje ${msg.id} procesado exitosamente")

                } catch (e: Exception) {
                    android.util.Log.e("CommunityRepository", "❌ [INCOMING-ERROR] Error crítico procesando mensaje ${msg.id}", e)
                }
        }
    }

    private suspend fun handleIncomingCommunityUpdate(
        msg: CommunityMessage,
        sessionUserId: String? = currentSessionUserId()
    ) {
        var sessionSnapshot = captureSessionSnapshot(sessionUserId)
        withContext(Dispatchers.IO) {
            if (!isSessionSnapshotValid(sessionSnapshot, "community-update-start:${msg.id}")) return@withContext
            try {
                var existing = communityDao.getMessageById(msg.id)
                if (existing != null) {
                    val currentDomain = existing.toDomain()
                    var mergedReceipt = mergeCommunityReceiptState(
                        currentStatus = currentDomain.status,
                        currentDeliveredAt = currentDomain.delivered_at,
                        currentReadAt = currentDomain.read_at,
                        incomingStatus = msg.status,
                        incomingDeliveredAt = msg.delivered_at,
                        incomingReadAt = msg.read_at
                    )
                    val finalMsg = currentDomain.copy(
                        content = if (msg.content.isNotEmpty()) msg.content else currentDomain.content,
                        is_edited = msg.is_edited || currentDomain.is_edited,
                        deleted_for_all = msg.deleted_for_all || currentDomain.deleted_for_all,
                        deleted_by = (currentDomain.deleted_by + msg.deleted_by).distinct().map { it.lowercase() },
                        reactions = msg.reactions,
                        is_forwarded = msg.is_forwarded,
                        forwarded_from_message_id = msg.forwarded_from_message_id,
                        forwarded_from_user_id = msg.forwarded_from_user_id,
                        status = mergedReceipt.status,
                        delivered_at = mergedReceipt.deliveredAt,
                        read_at = mergedReceipt.readAt
                    )
                    
                    if (!isSessionSnapshotValid(sessionSnapshot, "community-update-before-db:${msg.id}")) return@withContext
                    communityDao.insertMessages(listOf(conectate.com.data.local.entity.CommunityMessageEntity.fromDomain(finalMsg)))
                    if (!isSessionSnapshotValid(sessionSnapshot, "community-update-before-flow:${msg.id}")) return@withContext
                    updateCommunityMessageInFlow(finalMsg.community_id, finalMsg)
                }
                Unit
                Unit

            } catch (e: Exception) {
                android.util.Log.e("CommunityRepository", "Error handling update", e)
            }
        }
    }

    private data class CommunityReceiptMergeResult(
        val status: String,
        val deliveredAt: String?,
        val readAt: String?
    )

    private fun normalizeCommunityReceiptStatus(status: String?): String {
        var normalized = status?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            "read" -> "read"
            "delivered", "received" -> "delivered"
            "sending" -> "sending"
            "failed" -> "failed"
            "" -> "sent"
            else -> normalized
        }
    }

    private fun communityReceiptRank(status: String): Int {
        return when (normalizeCommunityReceiptStatus(status)) {
            "failed" -> 0
            "sending" -> 1
            "sent" -> 2
            "delivered" -> 3
            "read" -> 4
            else -> 2
        }
    }

    private fun mergeCommunityReceiptState(
        currentStatus: String,
        currentDeliveredAt: String?,
        currentReadAt: String?,
        incomingStatus: String,
        incomingDeliveredAt: String?,
        incomingReadAt: String?
    ): CommunityReceiptMergeResult {
        var current = normalizeCommunityReceiptStatus(currentStatus)
        var incoming = normalizeCommunityReceiptStatus(incomingStatus)
        val mergedReadAt = incomingReadAt ?: currentReadAt
        var mergedDeliveredAt = incomingDeliveredAt ?: currentDeliveredAt

        val candidate = when {
            !mergedReadAt.isNullOrBlank() || incoming == "read" -> "read"
            !mergedDeliveredAt.isNullOrBlank() || incoming == "delivered" -> "delivered"
            else -> incoming
        }
        var finalStatus = if (communityReceiptRank(candidate) >= communityReceiptRank(current)) candidate else current
        if (finalStatus == "read" && mergedDeliveredAt.isNullOrBlank()) {
            mergedDeliveredAt = mergedReadAt
        }
        return CommunityReceiptMergeResult(finalStatus, mergedDeliveredAt, mergedReadAt)
    }

    // --- NOTIFICATIONS (SYSTEM) ---
    
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
@Composable
    private suspend fun showNotification(msg: CommunityMessage, communityName: String) {
        withContext(Dispatchers.Main) {
            try {
                var cleanId = msg.community_id.trim().lowercase()
                if (ConversationNotificationPrefs(context).isMuted("community", cleanId)) {
                    android.util.Log.d("CommunityRepository", "🔕 [NOTIFICATION] Community $cleanId muted locally. Skipping notification.")
                    return@withContext
                }

                var notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                var channelId = "community_messages_channel_v2"
                val previewText = buildConversationBackgroundPreview(msg.content_type, msg.content)
                    ?: buildCallEventPreview(msg.content_type, msg.content)
                    ?: if (msg.content_type == "text") msg.content else "📎 Multimedia"
                val notification = MessageNotificationFactory.build(
                    context = context,
                    payload = MessageNotificationPayload(
                        channelId = channelId,
                        channelName = "Mensajes de Comunidades",
                        channelDescription = "Notificaciones de mensajes en comunidades",
                        conversationId = cleanId,
                        targetType = "community",
                        conversationTitle = communityName,
                        senderName = msg.sender_name ?: "Alguien",
                        messageBody = previewText,
                        notificationId = cleanId.hashCode(),
                        senderId = msg.sender_id,
                        messageId = msg.id,
                        isGroupConversation = true
                    )
                )

                notificationManager.notify(cleanId.hashCode(), notification)
                android.util.Log.d("CommunityRepository", "🔔 [NOTIFICATION] Sent notification for community $communityName, messageId=${msg.id}")
            } catch (e: Exception) {
                android.util.Log.e("CommunityRepository", "❌ [NOTIFICATION] Failed to show notification", e)
            }
        }
    }

    // --- FLOWS ---

@Composable
@Composable
    suspend fun getCachedMessages(communityId: String): List<CommunityMessage> = withContext(Dispatchers.IO) {
        var cleanId = communityId.trim().lowercase()
        if (cleanId.isBlank()) return@withContext emptyList()
        var localMessages = communityDao.getMessagesSync(cleanId).map { it.toDomain() }
        unifyCommunityMessages(localMessages, currentSessionUserId())
    }

    fun getCommunityMessagesFlow(communityId: String): Flow<List<CommunityMessage>> {
        var cleanId = communityId.trim().lowercase()
        
        return _activeCommunityMessages.getOrPut(cleanId) {
            val state = MutableStateFlow<List<CommunityMessage>>(emptyList())
            
            // 1. Room Collection
            activeCommunityMessageJobs[cleanId]?.cancel()
            activeCommunityMessageJobs[cleanId] = scope.launch {
                communityDao.getMessagesFlow(cleanId)
                    .distinctUntilChanged()
                    .collect { entities ->
                        var localMessages = entities.map { it.toDomain() }
                        val activeUserId = currentSessionUserId()
                        state.update { current -> unifyCommunityMessages(localMessages + current, activeUserId) }
                    }
            }
            // 2. Initial Sync
            scope.launch { syncCommunityMessages(cleanId, currentSessionUserId()) }
            
            state
        }
    }

    private fun sanitizeMemberSenderName(value: String?): String? =
        value?.trim()?.takeIf { it.isNotBlank() && !it.equals("Usuario", ignoreCase = true) }

    private fun sanitizeMemberSenderImage(value: String?): String? =
        value?.trim()?.takeIf { it.isNotBlank() }

    private suspend fun hydrateCommunityMessages(messages: List<CommunityMessage>): List<CommunityMessage> {
        if (messages.isEmpty()) return emptyList()

        val normalizedMessages = messages.map { message ->
            message.copy(
                community_id = message.community_id.trim().lowercase(),
                sender_id = message.sender_id.trim().lowercase()
            )
        }
        var profileMap = collectionMemberProfileStore.loadProfiles(normalizedMessages.map { it.sender_id })

        return normalizedMessages.map { message ->
            var profile = profileMap[message.sender_id]
            val senderName = sanitizeMemberSenderName(message.sender_name)
                ?: sanitizeMemberSenderName(profile?.userName)
            val senderImage = sanitizeMemberSenderImage(message.sender_image)
                ?: sanitizeMemberSenderImage(profile?.userImage)

            message.copy(
                sender_name = senderName ?: message.sender_name,
                sender_image = senderImage ?: message.sender_image
            )
        }
    }

    private suspend fun hydrateCommunityMessage(message: CommunityMessage): CommunityMessage =
        hydrateCommunityMessages(listOf(message)).firstOrNull() ?: message

    private data class CommunityReceiptState(
        val deliveredAt: String?,
        val readAt: String?
    )

    private suspend fun fetchCommunityReceiptStates(
        communityId: String,
        currentUserId: String
    ): Map<String, CommunityReceiptState> {
        var token = authService.getAccessToken()?.trim().orEmpty()
        var cleanCommunityId = communityId.trim().lowercase()
        var cleanUserId = currentUserId.trim().lowercase()
        if (token.isBlank() || cleanCommunityId.isBlank() || cleanUserId.isBlank()) {
            return emptyMap()
        }

        return runCatching {
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/community_message_receipts") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("select", "message_id,delivered_at,read_at")
                parameter("community_id", "eq.$cleanCommunityId")
                parameter("user_id", "eq.$cleanUserId")
            }
            if (!response.status.isSuccess()) {
                return@runCatching emptyMap()
            }
            response.body<List<JsonObject>>().mapNotNull { row ->
                var messageId = row["message_id"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                messageId to CommunityReceiptState(
                    deliveredAt = row["delivered_at"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null },
                    readAt = row["read_at"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
                )
            }.toMap()
        }.onFailure {
            android.util.Log.w("CommunityRepository", "Failed to fetch receipt states for community $cleanCommunityId", it)
        }.getOrDefault(emptyMap())
    }

    private fun applyCommunityReceiptState(
        message: CommunityMessage,
        receipt: CommunityReceiptState?,
        currentUserId: String
    ): CommunityMessage {
        if (receipt == null) return message
        if (message.sender_id.trim().lowercase() == currentUserId.trim().lowercase()) return message

        var mergedReceipt = mergeCommunityReceiptState(
            currentStatus = message.status,
            currentDeliveredAt = message.delivered_at,
            currentReadAt = message.read_at,
            incomingStatus = when {
                !receipt.readAt.isNullOrBlank() -> "read"
                !receipt.deliveredAt.isNullOrBlank() -> "delivered"
                else -> message.status
            },
            incomingDeliveredAt = receipt.deliveredAt,
            incomingReadAt = receipt.readAt
        )

        return message.copy(
            status = mergedReceipt.status,
            delivered_at = mergedReceipt.deliveredAt,
            read_at = mergedReceipt.readAt
        )
    }

    private fun mergeSyncedCommunityMessageWithLocalState(
        incoming: CommunityMessage,
        local: CommunityMessage?
    ): CommunityMessage {
        local ?: return incoming
        var mergedReceipt = mergeCommunityReceiptState(
            currentStatus = local.status,
            currentDeliveredAt = local.delivered_at,
            currentReadAt = local.read_at,
            incomingStatus = incoming.status,
            incomingDeliveredAt = incoming.delivered_at,
            incomingReadAt = incoming.read_at
        )
        return incoming.copy(
            sender_name = sanitizeMemberSenderName(incoming.sender_name)
                ?: sanitizeMemberSenderName(local.sender_name)
                ?: incoming.sender_name,
            sender_image = sanitizeMemberSenderImage(incoming.sender_image)
                ?: sanitizeMemberSenderImage(local.sender_image)
                ?: incoming.sender_image,
            reply_to = incoming.reply_to ?: local.reply_to,
            is_edited = incoming.is_edited || local.is_edited,
            deleted_for_all = incoming.deleted_for_all || local.deleted_for_all,
            deleted_by = (local.deleted_by + incoming.deleted_by).distinct().map { it.trim().lowercase() },
            reactions = if (incoming.reactions.isNotEmpty()) incoming.reactions else local.reactions,
            is_forwarded = incoming.is_forwarded || local.is_forwarded,
            forwarded_from_message_id = incoming.forwarded_from_message_id ?: local.forwarded_from_message_id,
            forwarded_from_user_id = incoming.forwarded_from_user_id ?: local.forwarded_from_user_id,
            status = mergedReceipt.status,
            delivered_at = mergedReceipt.deliveredAt,
            read_at = mergedReceipt.readAt
        )
    }

    private fun unifyCommunityMessages(messages: List<CommunityMessage>, currentUserId: String?): List<CommunityMessage> {
        if (messages.isEmpty()) return emptyList()
        val filtered = messages.filter { msg ->
            if (currentUserId != null) {
                !msg.deleted_by.contains(currentUserId.trim().lowercase())
            } else true
        }
        
        // [FIX] Hydrate reply_to relations
        val msgMap = filtered.associateBy { it.id }
        val hydrated = filtered.map { msg ->
            if (msg.reply_to_id != null && msg.reply_to == null) {
                msg.copy(reply_to = msgMap[msg.reply_to_id])
            } else msg
        }

        return hydrated.distinctBy { it.id }.sortedBy { it.created_at }
    }

    private fun updateCommunityMessageInFlow(communityId: String, msg: CommunityMessage) {
        var cleanId = communityId.trim().lowercase()
        val flow = _activeCommunityMessages[cleanId] ?: return
        var currentUserId = authService.getCurrentUserId()
        
        flow.update { current ->
            val list = current.toMutableList()
            val index = list.indexOfFirst { it.id == msg.id }
            if (index != -1) {
                list[index] = msg
            } else {
                list.add(msg)
            }
            unifyCommunityMessages(list, currentUserId)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getCommunitiesFlow(): Flow<List<CommunityWithLastMessage>> {
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
                    communityDao.getCommunitiesForUserFlow(activeUserId)
                        .distinctUntilChanged()
                        .map { entities ->
                            entities
                                .filter { !it.is_deleted && it.is_member }
                                .map { entity ->
                                    CommunityWithLastMessage(
                                        community = entity.toDomain(),
                                        unreadCount = entity.unread_count
                                    )
                                }
                                .sortedByDescending { it.community.last_message_at }
                        }
                }
            }
            .flowOn(Dispatchers.IO)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getDiscoverableCommunitiesFlow(): Flow<List<CommunityWithLastMessage>> {
        return authService.observeSession()
            .map { session ->
                session?.user?.id?.trim()?.lowercase()
                    ?: authService.getCurrentUserId()?.trim()?.lowercase()
            }
            .distinctUntilChanged()
            .flatMapLatest { activeUserId ->
                communityDao.getAllCommunitiesFlow()
                    .distinctUntilChanged()
                    .map { entities ->
                        entities
                            .filter { entity ->
                                !entity.is_deleted && (
                                    entity.is_public ||
                                        entity.is_member ||
                                        entity.creator_id.trim().lowercase() == activeUserId
                                    )
                            }
                            .map { entity ->
                                CommunityWithLastMessage(
                                    community = entity.toDomain(),
                                    unreadCount = entity.unread_count
                                )
                            }
                            .sortedByDescending { it.community.last_message_at }
                    }
            }
            .flowOn(Dispatchers.IO)
    }

    // --- SYNC ---

@Composable
@Composable
    override suspend fun syncCommunities(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var userId = (authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))).trim().lowercase()
            var sessionSnapshot = captureSessionSnapshot(userId)
            
            android.util.Log.d("CommunityRepository", "🔄 [SYNC] Sync Communities for $userId")

            // 1. Fetch memberships
            var memberships = supabaseConfig.client.postgrest["community_members"].select {
                filter { eq("user_id", userId) }
            }.decodeList<CommunityMember>()
            val joinedIds = memberships.map { it.community_id.trim().lowercase() }

            // 2. Fetch Communities (Public + Joined)
            var communities = supabaseConfig.client.postgrest["communities"].select {
                filter {
                    or {
                        eq("is_public", true)
                        isIn("id", joinedIds)
                    }
                }
            }.decodeList<Community>()
            val activeCommunities = communities.filter { !it.is_deleted }

            // 3. Batch Sync Room
            val allLocal = communityDao.getAllCommunitiesSync()
            val localMap = allLocal.associateBy { it.id.trim().lowercase() }
            val localUnreadCounts = communityDao.getUnreadCountRows(userId)
                .associate { row ->
                    row.communityId.trim().lowercase() to row.unreadCount.coerceAtLeast(0)
                }

            if (!isSessionSnapshotValid(sessionSnapshot, "community-sync-before-upsert:$userId")) {
                return@withContext Result.success(Unit)
            }
            communityDao.insertCommunities(communities.map { 
                var cleanCommId = it.id.trim().lowercase()
                var localComm = localMap[cleanCommId]
                val isJoined = joinedIds.contains(cleanCommId) && !it.is_deleted
                var snapshot = CollectionSyncSupport.resolveLastMessageSnapshot(
                    serverMessage = it.last_message,
                    serverTime = it.last_message_at,
                    localMessage = localComm?.last_message,
                    localTime = localComm?.last_message_at
                )

                CommunityEntity.fromDomain(
                    community = it.copy(
                        id = cleanCommId,
                        last_message = snapshot.lastMessage,
                        last_message_at = snapshot.lastMessageAt,
                        is_member = isJoined
                    ),
                    unreadCount = if (isJoined) {
                        CollectionMessageSyncSupport.resolveDisplayedUnreadCount(
                            isConversationOpen = cleanCommId == currentOpenedCommunityId,
                            exactUnread = localUnreadCounts[cleanCommId] ?: 0
                        )
                    } else {
                        0
                    }
                ) 
            })
            
            // 4. Update Memberships
            val validMemberships = memberships.filter { 
                // Ensure community exists locally or remotely
                activeCommunities.any { c -> c.id.trim().lowercase() == it.community_id.trim().lowercase() } 
            }
            // Upsert (Replace) current valid memberships
            if (!isSessionSnapshotValid(sessionSnapshot, "community-sync-before-members:$userId")) {
                return@withContext Result.success(Unit)
            }
            communityDao.insertMembers(validMemberships.map {
                CommunityMemberEntity(it.community_id.trim().lowercase(), it.user_id.trim().lowercase(), it.role, it.joined_at)
            })

            // Remove memberships that are NO LONGER in the fetched list
            val validCommunityIds = validMemberships.map { it.community_id.trim().lowercase() }
            if (validCommunityIds.isEmpty()) {
                communityDao.deleteAllMembershipsForUser(userId)
            } else {
                communityDao.deleteUserMembershipsNotIn(userId, validCommunityIds)
            }

            // 5. Cleanup: If server returned list, assume it's authoritative for what we should know about
            val activeIds = activeCommunities.map { it.id.trim().lowercase() }
            if (!isSessionSnapshotValid(sessionSnapshot, "community-sync-before-cleanup:$userId")) {
                return@withContext Result.success(Unit)
            }
            if (activeIds.isNotEmpty()) {
                communityDao.markOthersAsNotJoined(activeIds)
            } else if (memberships.isEmpty() && communities.isEmpty()) {
                 communityDao.markOthersAsNotJoined(emptyList())
            }

            // [SMART SYNC] Check for Ghost Messages
            try {
                activeCommunities.forEach { comm ->
                    var cleanId = comm.id.trim().lowercase()
                    var localComm = localMap[cleanId]
                    if (CollectionSyncSupport.hasServerNewerContent(comm.last_message_at, localComm?.last_message_at)) {
                        android.util.Log.i("CommunityRepository", "👻 [GHOST-CHECK] Comm $cleanId has newer content. Fetching...")
                        launch { syncCommunityMessages(cleanId, userId) }
                    }
                }
            } catch (e: Exception) {
               android.util.Log.e("CommunityRepository", "Smart Sync Warning", e)
            }
            
            android.util.Log.d("CommunityRepository", "✅ [SYNC] Completed. ${activeIds.size} communities.")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("CommunityRepository", "Error en syncCommunities", e)
            Result.failure(e)
        }
    }

    suspend fun uploadCommunityImage(communityId: String, bytes: ByteArray): Result<String> =
        collectionMediaStore.uploadImage(
            ownerId = communityId,
            bytes = bytes,
            bucketName = "communities",
            filePrefix = "community"
        )

    suspend fun createCommunity(
        name: String, 
        description: String?, 
        imageBytes: ByteArray?, 
        category: String?, 
        isPublic: Boolean
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            var creatorId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            
            var communityId = UUID.randomUUID().toString().lowercase()
            
            var imageUrl: String? = null
            if (imageBytes != null) {
                var uploadResult = uploadCommunityImage(communityId, imageBytes)
                if (uploadResult.isSuccess) {
                    imageUrl = uploadResult.getOrNull()
                }
            }

            var now = java.time.Instant.now().toString()
            // Crear objeto JSON explícito para evitar fallos por campos extra o nulos
            val communityJson = buildJsonObject {
                put("id", communityId)
                put("name", name)
                put("description", description)
                put("image", imageUrl)
                put("category", category)
                put("is_public", isPublic)
                put("created_at", now)
                put("creator_id", creatorId)
                put("is_deleted", false)
            }

            var membershipPayloads = listOf(
                buildJsonObject {
                    put("community_id", communityId)
                    put("user_id", creatorId.trim().lowercase())
                    put("role", "admin")
                    put("joined_at", now)
                }
            )
            collectionCreationRemoteStore.createCollectionWithMemberships(
                collectionTable = "communities",
                collectionPayload = communityJson,
                membershipTable = "community_members",
                membershipPayloads = membershipPayloads
            ).getOrThrow()

            // Optimistic Local Insert (Instant UI)
            try {
                val newCommunity = Community(
                    id = communityId,
                    name = name,
                    description = description,
                    image = imageUrl,
                    category = category,
                    is_public = isPublic,
                    created_at = now,
                    creator_id = creatorId
                )
                communityDao.insertCommunities(listOf(CommunityEntity.fromDomain(newCommunity)))
                communityDao.insertMembers(listOf(
                    CommunityMemberEntity(
                        community_id = communityId,
                        user_id = creatorId,
                        role = "admin",
                        joined_at = now
                    )
                ))
            } catch (e: Exception) {
                android.util.Log.e("CommunityRepository", "Error local insert", e)
            }

            Result.success(communityId)
        } catch (e: Exception) {
            android.util.Log.e("CommunityRepository", "Error en createCommunity", e)
            Result.failure(e)
        }
    }


    private suspend fun getMemberRole(communityId: String, userId: String): String? {
        return collectionAdminRemoteStore.getMemberRole(
            membershipTable = "community_members",
            scopeColumn = "community_id",
            scopeId = communityId,
            userId = userId
        )
    }

    suspend fun deleteCommunityLocally(communityId: String) {
        communityDao.deleteCommunity(communityId)
    }

@Composable
@Composable
    suspend fun markAsRead(communityId: String, messageIds: Collection<String>) {
        var cleanId = communityId.trim().lowercase()
        withContext(Dispatchers.IO) {
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return@withContext
            var token = authService.getAccessToken()
            var now = java.time.Instant.now().toString()
            val cleanMessageIds = messageIds
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
            if (cleanMessageIds.isEmpty()) return@withContext
            var remotelyReadIds = runCatching {
                collectionMessageReceiptRemoteStore.markMessagesReadByIds(
                    logTag = "CommunityRepository",
                    messageTable = "community_messages",
                    scopeColumn = "community_id",
                    scopeId = cleanId,
                    messageIds = cleanMessageIds,
                    currentUserId = currentUserId,
                    token = token,
                    eligibleSourceStatuses = listOf("sent", "delivered", "received"),
                    requireReadAtNull = false,
                    confirmationStatuses = listOf("read")
                )
            }.onFailure {
                android.util.Log.w("CommunityRepository", "markAsRead by ids fallback to local state for $cleanId", it)
            }.getOrDefault(emptySet())
            val idsToMark = CollectionMessageSyncSupport.resolveLocallyReadableIds(
                requestedIds = cleanMessageIds,
                remotelyReadIds = remotelyReadIds
            )
            if (idsToMark.isNotEmpty()) {
                communityDao.markMessagesAsReadByIds(cleanId, now, currentUserId, idsToMark)
            }
            val remainingUnread = runCatching { communityDao.countUnreadIncoming(cleanId, currentUserId) }.getOrDefault(0)
            val displayedUnread = CollectionMessageSyncSupport.resolveDisplayedUnreadCount(
                isConversationOpen = currentOpenedCommunityId == cleanId,
                exactUnread = remainingUnread
            )
            communityDao.resetUnreadCount(cleanId)
            if (displayedUnread > 0) {
                communityDao.incrementUnreadCount(cleanId, displayedUnread)
            }
        }
    }

@Composable
@Composable
    suspend fun markAsRead(communityId: String) {
        var cleanId = communityId.trim().lowercase()
        withContext(Dispatchers.IO) {
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return@withContext
            var token = authService.getAccessToken()
            var now = java.time.Instant.now().toString()
            runCatching {
                collectionMessageReceiptRemoteStore.markAllMessagesRead(
                    logTag = "CommunityRepository",
                    messageTable = "community_messages",
                    scopeColumn = "community_id",
                    scopeId = cleanId,
                    currentUserId = currentUserId,
                    token = token,
                    rpcFunctionName = "secure_mark_community_messages_read",
                    rpcScopeKey = "p_community_id",
                    eligibleSourceStatuses = listOf("sent", "delivered", "received"),
                    requireReadAtNull = true
                )
            }.onFailure {
                android.util.Log.w("CommunityRepository", "markAsRead fallback to local state for $cleanId", it)
            }
            communityDao.markAllAsRead(cleanId, now, currentUserId)
            communityDao.resetUnreadCount(cleanId)
            runCatching {
                var notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(cleanId.hashCode())
            }
        }
    }

@Composable
@Composable
    suspend fun markMessageAsDelivered(communityId: String, messageId: String) {
        var cleanId = communityId.trim().lowercase()
        val cleanMessageId = messageId.trim().lowercase()
        if (cleanId.isBlank() || cleanMessageId.isBlank()) return
        withContext(Dispatchers.IO) {
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return@withContext
            var token = authService.getAccessToken()
            var now = java.time.Instant.now().toString()
            var remoteSynced = collectionMessageReceiptRemoteStore.markMessageDelivered(
                logTag = "CommunityRepository",
                messageTable = "community_messages",
                scopeColumn = "community_id",
                scopeId = cleanId,
                messageId = cleanMessageId,
                currentUserId = currentUserId,
                token = token,
                confirmationStatuses = listOf("delivered", "read", "received")
            )
            communityDao.markMessageAsDelivered(cleanId, cleanMessageId, now, currentUserId)
            if (!remoteSynced) {
                android.util.Log.w("CommunityRepository", "markMessageAsDelivered could not confirm remote transition for $cleanMessageId in $cleanId")
            }
        }
    }

@Composable
@Composable
    suspend fun markMessagesAsDelivered(communityId: String) {
        var cleanId = communityId.trim().lowercase()
        withContext(Dispatchers.IO) {
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return@withContext
            var token = authService.getAccessToken()
            var now = java.time.Instant.now().toString()
            var remoteSynced = collectionMessageReceiptRemoteStore.markAllMessagesDelivered(
                logTag = "CommunityRepository",
                scopeId = cleanId,
                token = token,
                rpcFunctionName = "secure_mark_community_messages_delivered",
                rpcScopeKey = "p_community_id"
            )
            if (remoteSynced) {
                communityDao.markAllAsDelivered(cleanId, now, currentUserId)
            }
        }
    }

    suspend fun isUserMember(communityId: String, userId: String): Boolean {
        var cleanId = communityId.trim().lowercase()
        return try {
            var response = supabaseConfig.client.postgrest["community_members"].select {
                filter {
                    eq("community_id", cleanId)
                    eq("user_id", userId)
                }
            }.decodeSingleOrNull<CommunityMember>()
            response != null
        } catch (e: Exception) {
            false
        }
    }

    fun isUserMemberFlow(communityId: String, userId: String): Flow<Boolean> {
        return communityDao.isUserMemberFlow(communityId.trim().lowercase(), userId.trim().lowercase())
    }



    fun getCommunityFlow(id: String): Flow<Community?> {
        var cleanId = id.trim().lowercase()
        return communityDao.getCommunityByIdFlow(cleanId).map { it?.toDomain() }
    }



@Composable
@Composable
    suspend fun searchCommunities(query: String): Result<List<Community>> = withContext(Dispatchers.IO) {
        try {
            var response = supabaseConfig.client.postgrest["communities"].select {
                filter { 
                    ilike("name", "%$query%")
                    eq("is_public", true)
                    eq("is_deleted", false)
                }
            }.decodeList<Community>()
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    suspend fun updateCommunityInfo(
        communityId: String,
        name: String,
        description: String?,
        imageBytes: ByteArray?,
        isPublic: Boolean,
        onlyAdminsCanTalk: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var me = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            var cleanId = communityId.trim().lowercase()
            var community = getCommunityById(cleanId) ?: return@withContext Result.failure(Exception("Community not found"))
            var privileges = resolveCommunityGovernancePrivileges(community, me)
            CollectionGovernanceSupport.requireCanManageCollection(
                privileges = privileges,
                errorMessage = "No tienes permisos para editar la comunidad."
            )
            var imageUrl: String? = null
            
            if (imageBytes != null) {
                var uploadResult = uploadCommunityImage(cleanId, imageBytes)
                if (uploadResult.isSuccess) {
                    imageUrl = uploadResult.getOrNull()
                }
            }

            val updatePayload = buildJsonObject {
                put("name", name)
                put("description", description ?: "")
                put("is_public", isPublic)
                put("only_admins_can_talk", onlyAdminsCanTalk)
                if (imageUrl != null) put("image", imageUrl)
            }
            collectionAdminRemoteStore.updateCollectionInfo(
                collectionTable = "communities",
                scopeId = cleanId,
                payload = updatePayload
            ).getOrThrow()
            
            syncCommunities()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    suspend fun getCommunityById(id: String): Community? {
        var cleanId = id.trim().lowercase()
        return try {
            val entity = communityDao.getCommunityById(cleanId)
            if (entity != null) return entity.toDomain()
            
            var response = supabaseConfig.client.postgrest["communities"].select {
                filter { eq("id", cleanId) }
            }.decodeSingleOrNull<Community>()
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
            var normalized = response?.copy(
                id = response.id.trim().lowercase(),
                creator_id = response.creator_id.trim().lowercase(),
                is_member = if (!currentUserId.isNullOrBlank()) {
                    response.creator_id.trim().equals(currentUserId, ignoreCase = true) ||
                        isUserMember(cleanId, currentUserId)
                } else {
                    false
                }
            )
            if (normalized != null) {
                communityDao.insertCommunities(
                    listOf(
                        CommunityEntity.fromDomain(
                            community = normalized,
                            unreadCount = 0
                        )
                    )
                )
            }
            normalized
        } catch (e: Exception) {
            null
        }
    }

@Composable
@Composable
    suspend fun leaveCommunity(communityId: String): Result<Unit> = withContext(Dispatchers.IO) {
        var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
        CollectionMembershipMutationSupport.leaveCurrentUserCollection(
            scopeId = communityId,
            currentUserId = userId,
            applyLocalOptimistic = { cleanId, cleanUserId ->
                communityDao.removeMember(cleanId, cleanUserId)
                communityDao.updateMembership(cleanId, false)
            },
            remoteDelete = { cleanId, cleanUserId ->
                collectionAdminRemoteStore.deleteMember(
                    membershipTable = "community_members",
                    scopeColumn = "community_id",
                    scopeId = cleanId,
                    userId = cleanUserId
                )
            }
        )
    }

    suspend fun uploadCommunityAudio(communityId: String, bytes: ByteArray): Result<String> =
        collectionMediaStore.uploadAudio(
            ownerId = communityId,
            bytes = bytes,
            primaryBucket = "community-media",
            fallbackBucket = "communities",
            filePrefix = "audio_comm",
            extension = "m4a",
            contentType = "audio/m4a"
        )

@Composable
@Composable
    suspend fun removeMember(communityId: String, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var me = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            var cleanId = communityId.trim().lowercase()
            var targetUserId = userId.trim().lowercase()
            
            var community = getCommunityById(cleanId) ?: return@withContext Result.failure(Exception("Community not found"))
            CollectionGovernanceSupport.requireTargetIsNotCreator(
                targetUserId = targetUserId,
                creatorId = community.creator_id,
                errorMessage = "No se puede eliminar al creador de la comunidad."
            )
            var privileges = resolveCommunityGovernancePrivileges(community, me)
            CollectionGovernanceSupport.requireCanManageMembers(
                privileges = privileges,
                errorMessage = "No tienes permisos para eliminar miembros."
            )

            // Execute delete
            collectionAdminRemoteStore.deleteMember(
                membershipTable = "community_members",
                scopeColumn = "community_id",
                scopeId = cleanId,
                userId = targetUserId
            ).getOrThrow()
            
            // Remove locally
            communityDao.removeMember(cleanId, targetUserId)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

@Composable
@Composable
    suspend fun updateMemberRole(communityId: String, userId: String, newRole: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var me = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            var cleanId = communityId.trim().lowercase()
            var targetUserId = userId.trim().lowercase()
            
            var community = getCommunityById(cleanId) ?: return@withContext Result.failure(Exception("Community not found"))
            CollectionGovernanceSupport.requireTargetIsNotCreator(
                targetUserId = targetUserId,
                creatorId = community.creator_id,
                errorMessage = "No se puede cambiar el rol del creador."
            )
            var privileges = resolveCommunityGovernancePrivileges(community, me)
            CollectionGovernanceSupport.requireCanManageMembers(
                privileges = privileges,
                errorMessage = "No tienes permisos para gestionar roles."
            )

            collectionAdminRemoteStore.updateMemberRole(
                membershipTable = "community_members",
                scopeColumn = "community_id",
                scopeId = cleanId,
                userId = targetUserId,
                newRole = newRole
            ).getOrThrow()
            // Update local member cache
            getCommunityMembers(cleanId)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

@Composable
@Composable
    suspend fun joinCommunity(communityId: String): Result<Unit> = withContext(Dispatchers.IO) {
        var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
        var joinedAt = java.time.Instant.now().toString()
        CollectionMembershipMutationSupport.joinCurrentUserCollection(
            scopeId = communityId,
            currentUserId = userId,
            joinedAt = joinedAt,
            applyLocalOptimistic = { cleanId, cleanUserId, at ->
                communityDao.insertMembers(listOf(CommunityMemberEntity(cleanId, cleanUserId, "member", at)))
                communityDao.updateMembership(cleanId, true)
            },
            remoteInsert = { cleanId, cleanUserId, at ->
                collectionAdminRemoteStore.insertMember(
                    membershipTable = "community_members",
                    scopeColumn = "community_id",
                    scopeId = cleanId,
                    userId = cleanUserId,
                    joinedAt = at
                )
            },
            afterRemote = { syncCommunities() }
        )
    }

@Composable
@Composable
    suspend fun deleteCommunity(communityId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("Not authenticated"))
            var cleanId = communityId.trim().lowercase()
            var community = getCommunityById(cleanId)
            var privileges = community?.let { resolveCommunityGovernancePrivileges(it, userId) }

            if (privileges?.canManageCollection == true) {
                collectionAdminRemoteStore.softDeleteCollection(
                    collectionTable = "communities",
                    scopeId = cleanId
                ).getOrThrow()
            } else {
                leaveCommunity(cleanId)
            }
            
            communityDao.deleteCommunity(cleanId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun resolveCommunityGovernancePrivileges(
        community: Community,
        currentUserId: String
    ) = CollectionGovernanceSupport.resolvePrivileges(
        currentUserId = currentUserId,
        creatorId = community.creator_id,
        loadMemberRole = { normalizedUserId -> getMemberRole(community.id, normalizedUserId) },
        loadIsGlobalAdmin = { normalizedUserId ->
            userRepository.getUserById(normalizedUserId).getOrNull()?.is_admin == true
        }
    )

    suspend fun sendCommunityMessage(
        communityId: String,
        content: String,
        contentType: String = "text",
        mediaUrl: String? = null,
        replyToId: String? = null,
        pollId: String? = null,
        isForwarded: Boolean = false,
        forwardedFromMessageId: String? = null,
        forwardedFromUserId: String? = null,
        messageId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            var senderId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("Not authenticated"))
            var cleanId = communityId.trim().lowercase()
            var community = getCommunityById(cleanId)
                ?: return@withContext Result.failure(Exception("La comunidad ya no está disponible."))
            val isCreator = community.creator_id.trim().lowercase() == senderId
            var isMember = community.is_member || isCreator || isUserMember(cleanId, senderId)
            if (!isMember) {
                return@withContext Result.failure(
                    Exception(
                        if (community.is_public) {
                            "Únete a esta comunidad para poder escribir."
                        } else {
                            "Ya no perteneces a esta comunidad."
                        }
                    )
                )
            }

            val guardDecision = operationGuard.acquire(
                action = GuardedAction.COMMUNITY_MESSAGE_SEND,
                actorId = senderId,
                scopeId = cleanId
            )
            if (!guardDecision.allowed) {
                val retryAfterSeconds = InputSecurityPolicy.retryAfterSeconds(guardDecision.retryAfterMs)
                return@withContext Result.failure(Exception("Estas enviando mensajes muy rapido. Intenta en ${retryAfterSeconds}s."))
            }

            val sanitizedContent = if (contentType == "text") {
                InputSecurityPolicy.sanitizeMessageText(content)
            } else {
                content.trim().take(InputSecurityPolicy.MAX_TEXT_MESSAGE_LENGTH)
            }
            val moderation = moderationService.moderate(
                text = sanitizedContent,
                context = ModerationContext.COMMUNITY_MESSAGE
            )
            if (moderation.verdict == ModerationVerdict.BLOCK) {
                appMetrics.recordFailure(
                    metric = "community_message_send",
                    attributes = mapOf("reason" to "moderation_block")
                )
                return@withContext Result.failure(Exception("Tu mensaje incumple nuestras politicas"))
            }
            val finalContent = moderation.sanitizedText
            if (contentType == "text" && mediaUrl.isNullOrBlank() && finalContent.isBlank()) {
                return@withContext Result.failure(Exception("El mensaje no puede estar vacio"))
            }
            
            val msgId = messageId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().lowercase()
            var now = java.time.Instant.now().toString()

            var msg = CommunityMessage(
                id = msgId,
                community_id = cleanId,
                sender_id = senderId,
                content = finalContent,
                content_type = contentType,
                media_url = mediaUrl,
                poll_id = pollId,
                created_at = now,
                reply_to_id = replyToId,
                is_forwarded = isForwarded,
                forwarded_from_message_id = forwardedFromMessageId,
                forwarded_from_user_id = forwardedFromUserId
            )

            // Optimistic local insert
            communityDao.insertMessages(
                listOf(
                    CommunityMessageEntity.fromDomain(
                        msg = msg,
                        isSynced = false,
                        pendingAction = "send",
                        retryCount = 0,
                        lastRetryAt = null
                    )
                )
            )
            updateCommunityMessageInFlow(cleanId, msg)
            val preview = buildConversationBackgroundPreview(contentType, finalContent)
                ?: buildCallEventPreview(contentType, finalContent)
                ?: if (contentType == "text") finalContent else "Multimedia"
            communityDao.updateMetadata(cleanId, preview, now)

            try {
                operationGuard.audit(
                    action = GuardedAction.COMMUNITY_MESSAGE_SEND,
                    actorId = senderId,
                    scopeId = cleanId,
                    outcome = GuardOutcome.QUEUED,
                    attributes = mapOf("message_id" to msgId)
                )
            } catch (_: Exception) {
                // Best effort audit only.
            }

            val msgJson = buildJsonObject {
                put("id", msgId)
                put("community_id", cleanId)
                put("sender_id", senderId)
                put("content", finalContent)
                put("content_type", contentType)
                put("media_url", mediaUrl)
                put("reply_to_id", replyToId)
                put("poll_id", pollId)
                put("created_at", now)
                put("is_forwarded", isForwarded)
                put("forwarded_from_message_id", forwardedFromMessageId)
                put("forwarded_from_user_id", forwardedFromUserId)
            }
            var result = insertCommunityMessageWithRetry(msgId, msgJson)
            if (result.isFailure) {
                val syncError = result.exceptionOrNull()
                android.util.Log.e("CommunityRepository", "❌ [SERVER-FAIL] Falló subida de mensaje comunidad $msgId", syncError)
                try {
                    operationGuard.audit(
                        action = GuardedAction.COMMUNITY_MESSAGE_SEND,
                        actorId = senderId,
                        scopeId = cleanId,
                        outcome = GuardOutcome.FAILED,
                        attributes = mapOf(
                            "message_id" to msgId,
                            "error_type" to (syncError?.javaClass?.simpleName ?: "unknown")
                        )
                    )
                } catch (_: Exception) {
                    // Best effort audit only.
                }
                appMetrics.recordFailure(
                    metric = "community_message_send",
                    throwable = syncError,
                    attributes = mapOf("content_type" to contentType)
                )
                Result.failure(syncError ?: Exception("No se pudo sincronizar mensaje de comunidad"))
            } else {
                try {
                    operationGuard.audit(
                        action = GuardedAction.COMMUNITY_MESSAGE_SEND,
                        actorId = senderId,
                        scopeId = cleanId,
                        outcome = GuardOutcome.SUCCESS,
                        attributes = mapOf("message_id" to msgId)
                    )
                } catch (_: Exception) {
                    // Best effort audit only.
                }
                appMetrics.incrementCounter(
                    metric = "community_message_send_success",
                    attributes = mapOf("content_type" to contentType)
                )
                appMetrics.recordLatency(
                    metric = "community_message_send",
                    durationMs = System.currentTimeMillis() - startedAt,
                    attributes = mapOf("content_type" to contentType)
                )
                Result.success(Unit)
            }
        } catch (e: Exception) {
            appMetrics.recordFailure("community_message_send", e)
            Result.failure(e)
        }
    }

    suspend fun sendCommunityPollMessage(
        communityId: String,
        question: String,
        options: List<String>,
        allowsMultiple: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var cleanId = communityId.trim().lowercase()
        var senderId = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: return@withContext Result.failure(Exception("Not authenticated"))

        val sanitizedQuestion = question.trim().take(300)
        val sanitizedOptions = options.map { it.trim().take(120) }.filter { it.isNotBlank() }.distinct()
        if (sanitizedQuestion.isBlank()) return@withContext Result.failure(Exception("La pregunta es obligatoria"))
        if (sanitizedOptions.size < 2) return@withContext Result.failure(Exception("La encuesta requiere al menos 2 opciones"))

        var messageId = UUID.randomUUID().toString().lowercase()
        var now = java.time.Instant.now().toString()
        val optimistic = CommunityMessage(
            id = messageId,
            community_id = cleanId,
            sender_id = senderId,
            content = sanitizedQuestion,
            content_type = "poll",
            poll_id = null,
            created_at = now
        )

        communityDao.insertMessages(
            listOf(
                CommunityMessageEntity.fromDomain(
                    msg = optimistic,
                    isSynced = false,
                    pendingAction = "send",
                    retryCount = 0,
                    lastRetryAt = null
                )
            )
        )
        updateCommunityMessageInFlow(cleanId, optimistic)
        communityDao.updateMetadata(cleanId, "📊 $sanitizedQuestion", now)

        val remote = createCommunityPollMessageSecure(
            communityId = cleanId,
            messageId = messageId,
            question = sanitizedQuestion,
            options = sanitizedOptions,
            allowsMultiple = allowsMultiple
        )

        if (remote.isSuccess) {
            val inserted = remote.getOrThrow().copy(community_id = cleanId)
            communityDao.insertMessages(
                listOf(
                    CommunityMessageEntity.fromDomain(
                        msg = inserted,
                        isSynced = true,
                        pendingAction = null
                    )
                )
            )
            communityDao.updateMessageSyncStatus(messageId, isSynced = true, pendingAction = null)
            updateCommunityMessageInFlow(cleanId, inserted)
            Result.success(Unit)
        } else {
            var error = remote.exceptionOrNull() ?: Exception("No se pudo crear la encuesta")
            communityDao.markRetryAttempt(messageId, java.time.Instant.now().toString())
            communityDao.updateMessageSyncStatus(messageId, isSynced = false, pendingAction = "send")
            Result.failure(error)
        }
    }

    private suspend fun createCommunityPollMessageSecure(
        communityId: String,
        messageId: String,
        question: String,
        options: List<String>,
        allowsMultiple: Boolean
    ): Result<CommunityMessage> = collectionPollMessageRemoteStore.createPollMessage(
        scopeType = "community",
        scopeId = communityId,
        messageId = messageId,
        question = question,
        options = options,
        allowsMultiple = allowsMultiple,
        rpcFunctionName = "secure_create_community_poll_message",
        rpcScopeKey = "p_community_id",
        messageTable = "community_messages",
        messageScopeColumn = "community_id",
        includeStatusSentInFallbackPayload = false,
        parseMessage = ::parseRpcCommunityMessage,
        buildFallbackMessage = { pollId, senderId, createdAt ->
            CommunityMessage(
                id = messageId,
                community_id = communityId,
                sender_id = senderId,
                content = question,
                content_type = "poll",
                poll_id = pollId,
                created_at = createdAt
            )
        }
    )

    private fun parseRpcCommunityMessage(body: String): CommunityMessage? {
        if (body.isBlank()) return null
        return try {
            json.decodeFromString<CommunityMessage>(body)
        } catch (_: Exception) {
            runCatching { json.decodeFromString<List<CommunityMessage>>(body).firstOrNull() }.getOrNull()
        }
    }

    private suspend fun insertCommunityMessageWithRetry(
        messageId: String,
        payload: JsonObject,
        maxAttempts: Int = 4
    ): Result<Unit> {
        return CollectionPendingMessageRetrySupport.retryPendingMessageSend(
            messageId = messageId,
            maxAttempts = maxAttempts,
            sendAction = { sendCommunityMessageSecureOrFallback(messageId = messageId, payload = payload) },
            isDuplicateKeyError = CollectionRepositorySupport::isDuplicateKeyError,
            isRetryableNetworkError = { error -> CollectionRepositorySupport.isRetryableNetworkError(error, transientNetworkRetryCodes) },
            onConfirmedSuccess = {
                communityDao.updateMessageSyncStatus(messageId, isSynced = true, pendingAction = null)
            },
            onDuplicateTreatedAsSuccess = {
                communityDao.updateMessageSyncStatus(messageId, isSynced = true, pendingAction = null)
            },
            onTerminalFailure = {
                communityDao.markRetryAttempt(messageId, java.time.Instant.now().toString())
                communityDao.updateMessageSyncStatus(messageId, isSynced = false, pendingAction = "send")
            },
            failureMessage = "No se pudo sincronizar mensaje de comunidad"
        )
    }

    private suspend fun sendCommunityMessageSecureOrFallback(
        messageId: String,
        payload: JsonObject
    ): Result<Unit> = collectionPendingMessageRemoteStore.sendSecureOrFallback(
        rpcFunctionName = "secure_send_community_message",
        scopePayloadKey = "community_id",
        scopeRpcKey = "p_community_id",
        tableName = "community_messages",
        messageId = messageId,
        payload = payload,
        includeCreatedAtInLegacyPayload = true
    )

    private fun isMissingCommunityRpcFunction(statusCode: Int, responseBody: String): Boolean {
        if (statusCode != 400 && statusCode != 404) return false
        val cleanBody = responseBody.lowercase()
        return cleanBody.contains("secure_send_community_message") &&
            (cleanBody.contains("could not find the function") || cleanBody.contains("undefined function"))
    }

    private suspend fun retryPendingCommunityMessages(maxBatch: Int = 40) {
        var userId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return
        val pending = communityDao.getPendingOutgoingMessages(maxBatch)
        if (pending.isEmpty()) return

        pending.forEach { pendingEntity ->
            if (pendingEntity.sender_id.trim().lowercase() != userId) return@forEach

            val payload = buildJsonObject {
                put("id", pendingEntity.id)
                put("community_id", pendingEntity.community_id)
                put("sender_id", pendingEntity.sender_id)
                put("content", pendingEntity.content)
                put("content_type", pendingEntity.content_type)
                put("media_url", pendingEntity.media_url)
                put("reply_to_id", pendingEntity.reply_to_id)
                put("poll_id", pendingEntity.poll_id)
                put("created_at", pendingEntity.created_at)
                put("is_forwarded", pendingEntity.is_forwarded)
                put("forwarded_from_message_id", pendingEntity.forwarded_from_message_id)
                put("forwarded_from_user_id", pendingEntity.forwarded_from_user_id)
            }
            insertCommunityMessageWithRetry(pendingEntity.id, payload)
        }
    }

    suspend fun uploadCommunityMedia(communityId: String, bytes: ByteArray, contentType: String): Result<String> =
        collectionMediaStore.uploadMessageMedia(
            ownerId = communityId,
            bytes = bytes,
            contentType = contentType,
            primaryBucket = "community-media",
            fallbackBucket = "communities",
            filePrefix = "media_comm"
        )

@Composable
@Composable
    suspend fun editCommunityMessage(messageId: String, newContent: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var updateJson = buildJsonObject {
                put("content", newContent)
                put("is_edited", true)
            }
            supabaseConfig.client.postgrest["community_messages"].update(updateJson) {
                filter { eq("id", messageId) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

@Composable
@Composable
    suspend fun deleteCommunityMessage(messageId: String, forEveryone: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            var cleanUserId = userId.trim().lowercase()
            var existing = communityDao.getMessageById(messageId)?.toDomain()
            val optimisticMessage = existing?.let { current ->
                if (forEveryone) {
                    current.copy(deleted_for_all = true)
                } else {
                    current.copy(
                        deleted_by = (current.deleted_by + cleanUserId)
                            .map { it.trim().lowercase() }
                            .filter { it.isNotBlank() }
                            .distinct()
                    )
                }
            }

            if (optimisticMessage != null) {
                communityDao.insertMessages(
                    listOf(conectate.com.data.local.entity.CommunityMessageEntity.fromDomain(optimisticMessage))
                )
                updateCommunityMessageInFlow(optimisticMessage.community_id, optimisticMessage)
            }

            val remoteDeleteResult = CollectionDeleteRetrySupport.retryRemoteDelete(
                logTag = "CommunityRepository",
                messageId = messageId,
                remoteDelete = {
                    if (forEveryone) {
                        var updateJson = buildJsonObject {
                            put("deleted_for_all", true)
                        }
                        supabaseConfig.client.postgrest["community_messages"].update(updateJson) {
                            filter { eq("id", messageId) }
                        }
                    } else {
                        val updatedDeletedBy = optimisticMessage?.deleted_by
                            ?: existing?.deleted_by
                                ?.map { it.trim().lowercase() }
                                ?.filter { it.isNotBlank() }
                                ?.distinct()
                            ?: emptyList()
                        var updateJson = buildJsonObject {
                            put("deleted_by", JsonArray(updatedDeletedBy.map { JsonPrimitive(it) }))
                        }
                        supabaseConfig.client.postgrest["community_messages"].update(updateJson) {
                            filter { eq("id", messageId) }
                        }
                    }
                },
                isRetryableNetworkError = { error ->
                    CollectionRepositorySupport.isRetryableNetworkError(error, transientNetworkRetryCodes)
                }
            )

            try {
                remoteDeleteResult.getOrThrow()
                Result.success(Unit)
            } catch (remoteError: Exception) {
                if (optimisticMessage != null) {
                    android.util.Log.w(
                        "CommunityRepository",
                        "Remote delete sync failed after optimistic local delete for $messageId",
                        remoteError
                    )
                    Result.success(Unit)
                } else {
                    Result.failure(remoteError)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

@Composable
@Composable
    suspend fun getCommunityMessageReadReceipts(messageId: String): Result<List<MessageReadReceipt>> = withContext(Dispatchers.IO) {
        var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
        collectionMessageEngagementStore.getMessageReadReceipts(
            messageId = messageId,
            token = token,
            rpcFunctionName = "secure_get_community_message_receipts"
        )
    }

    suspend fun getPollState(pollId: String): Result<List<MessagePollOptionState>> =
        collectionPollStore.getPollState(pollId)

    suspend fun votePollOption(
        pollId: String,
        optionId: String,
        selected: Boolean = true
    ): Result<List<MessagePollOptionState>> =
        collectionPollStore.votePollOption(pollId = pollId, optionId = optionId, selected = selected)

    suspend fun getPollOptionVoters(
        pollId: String,
        optionId: String
    ): Result<List<PollVoteUser>> =
        collectionPollStore.getPollOptionVoters(pollId = pollId, optionId = optionId)

    data class CommunityMessageReactionToggleResult(
        val messageId: String,
        val reactions: Map<String, List<String>>
    )

@Composable
@Composable
    suspend fun toggleCommunityMessageReaction(messageId: String, emoji: String): Result<CommunityMessageReactionToggleResult> = withContext(Dispatchers.IO) {
        try {
            var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            var userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("No user"))
            var local = communityDao.getMessageById(messageId)
                ?: return@withContext Result.failure(Exception("Mensaje no encontrado"))
            val reactionResult = collectionMessageEngagementStore.toggleReaction(
                messageId = messageId,
                emoji = emoji,
                currentReactions = local.toDomain().reactions,
                userId = userId,
                token = token,
                rpcFunctionName = "secure_toggle_community_message_reaction",
                tableName = "community_messages",
                missingRpcPredicate = ::isMissingCommunityRpcFunction
            ).getOrElse { return@withContext Result.failure(it) }
            val updatedReactions = reactionResult.reactions

            val updated = local.toDomain().copy(reactions = updatedReactions)
            communityDao.insertMessages(listOf(CommunityMessageEntity.fromDomain(updated, isSynced = local.is_synced, pendingAction = local.pending_action, retryCount = local.retry_count, lastRetryAt = local.last_retry_at)))
            updateCommunityMessageInFlow(updated.community_id, updated)

            Result.success(CommunityMessageReactionToggleResult(messageId = updated.id, reactions = updatedReactions))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun forwardCommunityMessageToCommunities(
        source: CommunityMessage,
        targetCommunityIds: List<String>
    ): Result<Int> = withContext(Dispatchers.IO) {
        CollectionMessageForwardingSupport.forwardMessage(
            sourceDeletedForAll = source.deleted_for_all,
            sourceScopeId = source.community_id,
            targetScopeIds = targetCommunityIds,
            maxTargetsPerBatch = maxForwardTargetsPerBatch,
            emptySelectionMessage = "Selecciona al menos una comunidad",
            batchLimitMessage = "Puedes reenviar a un máximo de $maxForwardTargetsPerBatch comunidades por vez",
            totalFailureMessage = "No se pudo reenviar"
        ) { targetId ->
            sendCommunityMessage(
                communityId = targetId,
                content = if (source.content.isNotBlank()) source.content else "Mensaje reenviado",
                contentType = source.content_type,
                mediaUrl = source.media_url,
                isForwarded = true,
                forwardedFromMessageId = source.id,
                forwardedFromUserId = source.sender_id
            )
        }
    }

@Composable
@Composable
    suspend fun getCommunityMembers(communityId: String): Result<List<CommunityMember>> = withContext(Dispatchers.IO) {
        try {
            var response = supabaseConfig.client.postgrest["community_members"].select {
                filter { eq("community_id", communityId) }
            }.decodeList<CommunityMember>()
            
            if (response.isEmpty()) return@withContext Result.success(emptyList())

            var profileMap = collectionMemberProfileStore.loadProfiles(response.map { it.user_id })
            val enriched = response.map { member ->
                var profile = profileMap[member.user_id.trim().lowercase()]
                member.copy(
                    userName = profile?.userName ?: member.userName ?: "Usuario",
                    userImage = profile?.userImage ?: member.userImage
                )
            }
            
            communityDao.insertMembers(enriched.map { 
                CommunityMemberEntity(it.community_id.trim().lowercase(), it.user_id.trim().lowercase(), it.role, it.joined_at) 
            })
            
            Result.success(enriched)
        } catch (e: Exception) {
            var local = communityDao.getMembersFlow(communityId.trim().lowercase()).first().map {
                CommunityMember(it.community_id, it.user_id, it.role, it.joined_at)
            }
            val localProfiles = collectionMemberProfileStore.loadProfiles(local.map { it.user_id })
            val enrichedLocal = local.map { member ->
                var profile = localProfiles[member.user_id.trim().lowercase()]
                member.copy(
                    userName = profile?.userName ?: member.userName,
                    userImage = profile?.userImage ?: member.userImage
                )
            }
            if (enrichedLocal.isNotEmpty()) Result.success(enrichedLocal)
            else Result.failure(e)
        }
    }

    fun getMembersFlow(communityId: String): Flow<List<CommunityMember>> {
        var cleanId = communityId.trim().lowercase()
        return communityDao.getMembersFlow(cleanId).map { list ->
            list.map { CommunityMember(it.community_id, it.user_id, it.role, it.joined_at) }
        }
    }

    private suspend fun syncCommunityMessages(
        communityId: String,
        sessionUserId: String? = currentSessionUserId()
    ) {
        var cleanId = communityId.trim().lowercase()
        try {
            var currentUserId = SessionWriteGuard.normalizeAccountId(sessionUserId) ?: return
            var sessionSnapshot = captureSessionSnapshot(currentUserId)
            if (!isSessionSnapshotValid(sessionSnapshot, "community-sync-messages-start:$cleanId")) return
            var response = supabaseConfig.client.postgrest["community_messages"].select {
                filter { eq("community_id", cleanId) }
                order("created_at", Order.ASCENDING)
            }.decodeList<CommunityMessage>()
            
            if (response.isNotEmpty()) {
                val receiptStates = fetchCommunityReceiptStates(cleanId, currentUserId)
                val localMessagesById = communityDao.getMessagesSync(cleanId)
                    .associateBy { it.id.trim().lowercase() }
                    .mapValues { (_, entity) -> entity.toDomain() }
                val hydratedResponse = hydrateCommunityMessages(
                    response.map { it.copy(community_id = cleanId) }
                ).map { message ->
                    var messageId = message.id.trim().lowercase()
                    applyCommunityReceiptState(
                        message = message,
                        receipt = receiptStates[messageId],
                        currentUserId = currentUserId
                    )
                }
                val mergedResponse = hydratedResponse.map { incoming ->
                    mergeSyncedCommunityMessageWithLocalState(
                        incoming = incoming,
                        local = localMessagesById[incoming.id.trim().lowercase()]
                    )
                }
                if (!isSessionSnapshotValid(sessionSnapshot, "community-sync-messages-after-network:$cleanId")) return
                if (_activeCommunityMessages.containsKey(cleanId)) {
                    var current = _activeCommunityMessages[cleanId]?.value ?: emptyList()
                    _activeCommunityMessages[cleanId]?.value = CollectionMessageSyncSupport.mergeActiveMessages(
                        current = current,
                        incoming = mergedResponse,
                        idSelector = { it.id },
                        createdAtSelector = { it.created_at }
                    )
                }

                if (!isSessionSnapshotValid(sessionSnapshot, "community-sync-messages-before-db:$cleanId")) return
                communityDao.insertMessages(mergedResponse.map {
                    conectate.com.data.local.entity.CommunityMessageEntity.fromDomain(it) 
                })

                CollectionMessageSyncSupport.reconcileUnreadCount(
                    isConversationOpen = (cleanId == currentOpenedCommunityId),
                    countUnread = { communityDao.countUnreadIncoming(cleanId, currentUserId) },
                    resetUnread = { communityDao.resetUnreadCount(cleanId) },
                    incrementUnread = { unread -> communityDao.incrementUnreadCount(cleanId, unread) },
                    onUnreadReconciled = { unread ->
                        android.util.Log.d("CommunityRepository", "🔔 [SYNC-UNREAD] Reconciled unread=$unread for community $cleanId")
                    }
                )

                mergedResponse.maxByOrNull { it.created_at }?.let { lastMsg ->
                    if (!isSessionSnapshotValid(sessionSnapshot, "community-sync-messages-before-metadata:$cleanId")) return
                    communityDao.updateMetadata(
                        cleanId,
                        CollectionMessageSyncSupport.buildMetadataPreview(lastMsg.content_type, lastMsg.content),
                        lastMsg.created_at
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CommunityRepository", "Error syncing messages", e)
        }
    }

}





