package conectate.com.data.repository

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.app.NotificationManager
import android.app.NotificationChannel
import conectate.com.BuildConfig
import conectate.com.core.concurrency.newSafeScope
import conectate.com.core.messaging.MessageStatusNormalizer
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
import conectate.com.data.local.dao.GroupDao
import conectate.com.data.local.entity.GroupEntity
import conectate.com.data.local.entity.GroupMemberEntity
import conectate.com.data.local.entity.GroupMessageEntity
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.LocalFallbackInboxItem
import conectate.com.data.model.Group
import conectate.com.data.model.GroupMember
import conectate.com.data.model.GroupMessage
import conectate.com.data.model.MessagePollOptionState
import conectate.com.data.model.PollVoteUser
import conectate.com.data.model.MessageReadReceipt

import conectate.com.data.model.GroupWithLastMessage
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.domain.sync.GroupSyncDataSource
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
import kotlinx.coroutines.isActive

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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import android.os.Handler
import android.os.Looper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val groupDao: GroupDao,
    private val userRepository: UserRepository,
    private val localFallbackApiClient: LocalFallbackApiClient,
    private val storageService: conectate.com.data.supabase.StorageService,
    private val moderationService: ContentModerationService,
    private val appMetrics: AppMetrics,
    private val operationGuard: OperationGuard,
    private val chatSoundPlayer: ChatSoundPlayer,
    @ApplicationContext private val context: Context
) : GroupSyncDataSource {
    private val maxForwardTargetsPerBatch = 20
    private var scope = newSafeScope("GroupRepository", Dispatchers.IO)
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
                "GroupRepository",
                "Discarding stale session write from $source. expected=${snapshot.accountId}, active=${currentSessionUserId()}"
            )
        }
        return valid
    }

    init {
        bindCollectionRealtimeAuthLifecycle(
            scope = scope,
            supabaseConfig = supabaseConfig,
            authService = authService,
            logTag = "GroupRepository",
            logPrefix = "[GOD]",
            readyLog = "🟢 [G-REALTIME] Sistema Iniciado",
            onAuthenticated = ::startRealtimeSubscriptions,
            onNotAuthenticated = ::stopRealtimeSubscriptions
        )
    }

    private var realtimeJob: Job? = null
    private var groupListenerJob: Job? = null
    private var subscribedGroupIds: Set<String> = emptySet()
    private val activeGroupMessageJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private fun startRealtimeSubscriptions(userId: String) {
        if (authService.isLocalFallbackModeEnabled()) {
            android.util.Log.d("GroupRepository", "Skipping Supabase realtime in local fallback mode")
            return
        }
        if (userId.isBlank()) return
        
        realtimeJob?.cancel()
        realtimeJob = scope.launchCollectionRealtimeLoop(
            userId = userId,
            logTag = "GroupRepository",
            collectionLabelPlural = "groups",
            syncAction = ::syncGroups,
            retryPendingAction = { retryPendingGroupMessages() },
            loadSubscribedIds = {
                groupDao.getAllGroupsSync()
                    .filter { it.is_member }
                    .map { it.id.trim().lowercase() }
                    .toSet()
            },
            onSubscribedIdsChanged = { subscribedGroupIds = it },
            subscribeAction = ::subscribeToGroupChanges
        )
    }

    private fun stopRealtimeSubscriptions() {
        realtimeJob?.cancel()
        realtimeJob = null
        groupListenerJob?.cancel()
        groupListenerJob = null
        android.util.Log.d("GroupRepository", "[GOD] Realtime subscriptions stopped")

    }

    fun clearData() {
        android.util.Log.w("GroupRepository", "CLEAR DATA: Purging in-memory group state...")
        sessionWriteGuard.invalidate()
        stopRealtimeSubscriptions()
        subscribedGroupIds = emptySet()
        currentOpenedGroupId = null
        activeGroupMessageJobs.values.forEach { it.cancel() }
        activeGroupMessageJobs.clear()
        _activeGroupMessages.clear()
        _lastDirectUpdate.value = 0L
    }

    // --- STATE ---
    private val _activeGroupMessages = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<List<GroupMessage>>>()
    @Volatile
    private var currentOpenedGroupId: String? = null

    // Maintains an in-memory status of the last group list update timestamp to force UI refresh if needed
    private val _lastDirectUpdate = MutableStateFlow(0L)

    fun getUserRoleFlow(groupId: String, userId: String): Flow<String?> {
        return groupDao.getUserRoleFlow(groupId.trim().lowercase(), userId.trim().lowercase())
    }

    fun setActiveGroup(groupId: String?) {
        currentOpenedGroupId = groupId?.trim()?.lowercase()
        if (groupId != null) {
            var cleanId = groupId.trim().lowercase()
            // Clear system notifications for this group
            try {
                var notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(cleanId.hashCode())
            } catch (e: Exception) {
                android.util.Log.e("GroupRepository", "Error clearing notification", e)
            }
            scope.launch {
                runCatching { groupDao.resetUnreadCount(cleanId) }
            }
        }
    }

    // --- REALTIME SUBSCRIPTION (ROBUST) ---
    
    // We use a SINGLE channel for all group events for this user to avoid connection limits and race conditions.
    // The channel name is crucial: MUST be unique per user but stable across app restarts if possible, 
    // though session-based is safer for auth.
    private suspend fun subscribeToGroupChanges(userId: String, groupIds: List<String>) {
        groupListenerJob?.cancel()
        groupListenerJob = scope.launch {
            // 1. MESSAGES CHANNEL LOOP
            if (groupIds.isNotEmpty()) {
                launch {
                    var retryCount = 0
                    while (isActive) {
                        try {
                            var client = supabaseConfig.client
                            var cleanUserId = userId.trim().lowercase()
                            var channelName = "groups-messages-$cleanUserId"
                            var channel = client.channel(channelName)
                            
                            // [REALTIME FIX] Filter by current joined IDs if possible to reduce noise, 
                            // but RLS should handle it.
                            var changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                                table = "group_messages"
                            }

                            coroutineScope {
                                val msgScope = this
                                changes.onEach { change: PostgresAction ->
                                    android.util.Log.d("GroupRepository", "📡 [REALTIME-MSG] Change received: $change")
                                    when (change) {
                                        is PostgresAction.Insert -> {
                                            var msg = json.decodeFromJsonElement<GroupMessage>(change.record)
                                            handleIncomingMessage(msg, cleanUserId)
                                        }
                                        is PostgresAction.Update -> {
                                            var msg = json.decodeFromJsonElement<GroupMessage>(change.record)
                                            handleIncomingGroupUpdate(msg, cleanUserId)
                                        }
                                        else -> {}
                                    }
                                }.launchIn(this)
                                
                                launch {
                                    supabaseConfig.subscribeRealtimeChannel(
                                        channel = channel,
                                        reason = "group-messages-$userId"
                                    )
                                    android.util.Log.d("GroupRepository", "🔥 [REALTIME] Subscribed to MESSAGES Flow for ${groupIds.size} groups")
                                    appMetrics.incrementCounter(
                                        metric = "group_realtime_subscribe_success",
                                        attributes = mapOf("scope" to "messages", "group_count" to groupIds.size.toString())
                                    )
                                    
                                    if (BuildConfig.DEBUG) {
                                        launch {
                                            while (isActive) {
                                                delay(30000)
                                                android.util.Log.d("GroupRepository", "💓 [HEARTBEAT] MESSAGES Channel Status: ${channel.status.value}")
                                            }
                                        }
                                    }

                                    retryCount = 0
                                    channel.status.collect { status ->
                                        if (status == RealtimeChannel.Status.UNSUBSCRIBED || status.name == "DISCONNECTED" || status.name == "CLOSED" || status.name == "TIMED_OUT") {
                                            android.util.Log.w("GroupRepository", "⚠️ Messages Channel Status: $status. Restarting...")
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
                                    metric = "group_realtime_messages_loop",
                                    throwable = e,
                                    attributes = mapOf("retry_count" to retryCount.toString())
                                )
                                android.util.Log.e("GroupRepository", "❌ Messages Loop Error. Retry in ${delayMs}ms", e)
                                delay(delayMs)
                            }
                        }
                    }
                }
            } else {
                android.util.Log.w("GroupRepository", "⚠️ [REALTIME] No groups to subscribe to (Messages Loop skipped).")
            }

            // 2. MEMBERS CHANNEL LOOP
            launch {
                var retryCount = 0
                while (isActive) {
                    try {
                        var client = supabaseConfig.client
                        var cleanUserId = userId.trim().lowercase()
                        var channelName = "groups-members-$cleanUserId"
                        var channel = client.channel(channelName)
                        
                        var changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                            table = "group_members"
                            this.filter = "user_id=eq.$cleanUserId"
                        }

                        coroutineScope {
                            val memberScope = this
                            changes.onEach { change: PostgresAction ->
                                android.util.Log.d("GroupRepository", "👥 Membership changed ($change). Syncing and Refreshing Realtime...")
                                
                                var normalizedUserId = userId.trim().lowercase()
                                when (change) {
                                    is PostgresAction.Insert -> {
                                        try {
                                            var member = json.decodeFromJsonElement<GroupMember>(change.record)
                                            groupDao.insertMembers(listOf(GroupMemberEntity(member.group_id.trim().lowercase(), member.user_id.trim().lowercase(), member.role, member.joined_at)))
                                        } catch (e: Exception) {
                                            android.util.Log.e("GroupRepository", "Error decoding member insert", e)
                                        }
                                    }
                                    is PostgresAction.Update -> {
                                        try {
                                            var member = json.decodeFromJsonElement<GroupMember>(change.record)
                                            val cleanGId = member.group_id.trim().lowercase()
                                            val cleanUId = member.user_id.trim().lowercase()
                                            android.util.Log.i("GroupRepository", "🔄 [ROLE-UPDATE] User $cleanUId in $cleanGId is now ${member.role}")
                                            groupDao.insertMembers(listOf(GroupMemberEntity(cleanGId, cleanUId, member.role, member.joined_at)))
                                        } catch (e: Exception) {
                                            android.util.Log.e("GroupRepository", "Error decoding member update. Check REPLICA IDENTITY FULL.", e)
                                        }
                                    }
                                    is PostgresAction.Delete -> {
                                        var oldData = change.oldRecord
                                        var gId = oldData["group_id"]?.jsonPrimitive?.contentOrNull
                                        if (gId != null) {
                                            groupDao.removeMember(gId.trim().lowercase(), normalizedUserId)
                                        }
                                    }
                                    else -> {}
                                }

                                var result = syncGroups()
                                if (result.isSuccess) {
                                    val newIds = groupDao.getAllGroupsSync().filter { it.is_member }.map { it.id.trim().lowercase() }.toSet()
                                    if (newIds != subscribedGroupIds) {
                                        android.util.Log.i("GroupRepository", "🔄 Group List changed from ${subscribedGroupIds.size} to ${newIds.size}. Full Restart.")
                                        startRealtimeSubscriptions(userId)
                                    }
                                }
                            }.launchIn(this)
                            
                            launch {
                                supabaseConfig.subscribeRealtimeChannel(
                                    channel = channel,
                                    reason = "group-members-$userId"
                                )
                                android.util.Log.d("GroupRepository", "🔥 [REALTIME] Subscribed to MEMBERS updates")
                                retryCount = 0
                                channel.status.collect { status ->
                                    if (status == RealtimeChannel.Status.UNSUBSCRIBED || status.name == "DISCONNECTED" || status.name == "CLOSED" || status.name == "TIMED_OUT") {
                                         android.util.Log.w("GroupRepository", "⚠️ Members Channel Status: $status. Restarting...")
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

            // 3. GROUPS META CHANNEL LOOP (Real-time settings/names/images)
            launch {
                var retryCount = 0
                while (isActive) {
                    try {
                        var client = supabaseConfig.client
                        var cleanUserId = userId.trim().lowercase()
                        var channelName = "groups-meta-$cleanUserId"
                        var channel = client.channel(channelName)
                        
                        var changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                            table = "groups"
                        }

                        coroutineScope {
                            val metaScope = this
                            changes.onEach { change: PostgresAction ->
                                android.util.Log.d("GroupRepository", "📝 [REALTIME-META] Group table change: $change")
                                when (change) {
                                    is PostgresAction.Update -> {
                                        try {
                                            var group = json.decodeFromJsonElement<conectate.com.data.model.Group>(change.record)
                                            var cleanGroupId = group.id.trim().lowercase()
                                            
                                            // Only update if we are members or have it locally
                                            var local = groupDao.getGroupById(cleanGroupId)
                                            android.util.Log.i("GroupRepository", "📝 [META-UPDATE] Group ${group.name} updated. OnlyAdmins=${group.only_admins_can_talk}")
                                            if (local != null) {
                                                groupDao.upsertGroup(GroupEntity.fromDomain(
                                                    group = group.copy(id = cleanGroupId),
                                                    unreadCount = local.unread_count
                                                ))
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("GroupRepository", "Error decoding group update. Check REPLICA IDENTITY FULL.", e)
                                        }
                                    }
                                    is PostgresAction.Delete -> {
                                        var oldData = change.oldRecord
                                        var gId = oldData["id"]?.jsonPrimitive?.contentOrNull
                                        if (gId != null) {
                                            groupDao.deleteGroup(gId.trim().lowercase())
                                        }
                                    }
                                    else -> {}
                                }
                            }.launchIn(this)
                            launch {
                                supabaseConfig.subscribeRealtimeChannel(
                                    channel = channel,
                                    reason = "group-meta-$userId"
                                )
                                android.util.Log.d("GroupRepository", "🔥 [REALTIME] Subscribed to GROUPS META updates")
                                retryCount = 0
                                channel.status.collect { status ->
                                    if (status == RealtimeChannel.Status.UNSUBSCRIBED || status.name == "DISCONNECTED" || status.name == "CLOSED" || status.name == "TIMED_OUT") {
                                        metaScope.cancel()
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
        msg: GroupMessage,
        sessionUserId: String? = currentSessionUserId()
    ) {
        var sessionSnapshot = captureSessionSnapshot(sessionUserId)
        withContext(Dispatchers.IO) {
            if (!isSessionSnapshotValid(sessionSnapshot, "group-insert-start:${msg.group_id}")) return@withContext
            var cleanId = msg.group_id.trim().lowercase()
            android.util.Log.i("GroupRepository", "📡 [REALTIME-INCOMING] Received msg=${msg.id} content='${msg.content.take(20)}' group=$cleanId")
            
            try {
                    
                    // 1. Validar si el grupo existe localmente, si no, sync rápido
                    var localGroup = groupDao.getGroupById(cleanId)
                    if (localGroup == null) {
                        android.util.Log.w("GroupRepository", "⚠️ Recibido mensaje de grupo desconocido $cleanId. Sincronizando...")
                        syncGroups()
                        localGroup = groupDao.getGroupById(cleanId)
                    }

                    // 2. Si aún no somos miembros o no existe, ignorar (seguridad)
                    if (localGroup == null || !localGroup.is_member) {
                         android.util.Log.e("GroupRepository", "⛔ Mensaje BLOQUEADO para $cleanId (No miembro/No existe)")
                         return@withContext 
                    }

                    // 3. DUPLICATE DETECTION: Check if message already exists in local DB
                    val existingMessage = groupDao.getMessageById(msg.id)
                    if (existingMessage != null) {
                        android.util.Log.d("GroupRepository", "♻️ [DUPLICATE] Mensaje ${msg.id} ya existe en DB. Ignorando.")
                        return@withContext
                    }

                    android.util.Log.d("GroupRepository", "📩 [MSG-VALID] Content='${msg.content.take(50)}...', Sender=${msg.sender_id}, Group=$cleanId")

                    // 4. Persistir Mensaje (Room)
                    var currentUserId = sessionSnapshot.accountId
                    
                    if (currentUserId != null && msg.sender_id.trim().lowercase() != currentUserId) {
                        scope.launch { markMessageAsDelivered(cleanId, msg.id) }
                    }

                    // Hydrate reply_to manually since Realtime payload allows it to be null/missing joins
                    val normalizedMsg = if (msg.reply_to_id != null && msg.reply_to == null) {
                        val reply = groupDao.getMessageById(msg.reply_to_id)?.toDomain()
                        msg.copy(reply_to = reply)
                    } else msg.copy(group_id = cleanId) // Ensure clean ID

                    val hydratedMsg = hydrateGroupMessage(normalizedMsg)

                    if (!isSessionSnapshotValid(sessionSnapshot, "group-insert-before-db:$cleanId")) return@withContext
                    groupDao.insertMessages(listOf(conectate.com.data.local.entity.GroupMessageEntity.fromDomain(hydratedMsg)))
                    android.util.Log.d("GroupRepository", "💾 [DB-SAVE] Mensaje ${msg.id} guardado en Room DB")
                    
                    // 5. Actualizar Flow Activo (Chat Screen)
                    if (!isSessionSnapshotValid(sessionSnapshot, "group-insert-before-flow:$cleanId")) return@withContext
                    updateGroupMessageInFlow(cleanId, hydratedMsg)
                    android.util.Log.d("GroupRepository", "🔄 [FLOW-UPDATE] Flow actualizado para $cleanId")
                    
                    // 6. Actualizar Metadatos del Grupo (Lista de Chats) y Notificar
                    var cleanSenderId = msg.sender_id.trim().lowercase()
                    val isMyMessage = cleanSenderId == currentUserId
                    
                    if (!isMyMessage) {
                        // CRITICAL FIX: Only increment unread if this GROUP is NOT currently open
                        val isGroupCurrentlyOpen = (cleanId == currentOpenedGroupId)
                        
                        if (!isGroupCurrentlyOpen) {
                            android.util.Log.d("GroupRepository", "🔔 [UNREAD+1] Incrementando contador para $cleanId (grupo cerrado)")
                            if (!isSessionSnapshotValid(sessionSnapshot, "group-insert-before-unread:$cleanId")) return@withContext
                            groupDao.incrementUnreadCount(cleanId)
                            if (!isCallEventContentType(msg.content_type)) {
                                showNotification(msg, localGroup.name)
                            }
                        } else {
                            android.util.Log.d("GroupRepository", "👁️ [UNREAD-SKIP] Grupo $cleanId está abierto. NO incrementar contador.")
                            if (!isCallEventContentType(msg.content_type)) {
                                chatSoundPlayer.playReceive()
                            }
                        }
                    } else {
                        android.util.Log.d("GroupRepository", "✍️ [MY-MSG] Mensaje enviado por mí. No incrementar unread.")
                    }
                    
                    // Actualizar last_message siempre (para todos los mensajes, incluso los míos)
                    if (!isSessionSnapshotValid(sessionSnapshot, "group-insert-before-metadata:$cleanId")) return@withContext
                    groupDao.updateMetadata(cleanId, 
                        buildConversationBackgroundPreview(msg.content_type, msg.content)
                            ?: buildCallEventPreview(msg.content_type, msg.content)
                            ?: when(msg.content_type) {
                            "text" -> msg.content
                            "poll" -> "📊 ${msg.content}"
                            else -> "Archivo multimedia"
                        },
                        msg.created_at
                    )
                    
                    android.util.Log.d("GroupRepository", "✅ [INCOMING-COMPLETE] Mensaje ${msg.id} procesado exitosamente")

                } catch (e: Exception) {
                    android.util.Log.e("GroupRepository", "❌ [INCOMING-ERROR] Error crítico procesando mensaje ${msg.id}", e)
                }
        }
    }

    private suspend fun handleIncomingGroupUpdate(
        msg: GroupMessage,
        sessionUserId: String? = currentSessionUserId()
    ) {
        var sessionSnapshot = captureSessionSnapshot(sessionUserId)
        withContext(Dispatchers.IO) {
            if (!isSessionSnapshotValid(sessionSnapshot, "group-update-start:${msg.id}")) return@withContext
            try {
                var existing = groupDao.getMessageById(msg.id)
                if (existing != null) {
                    val currentDomain = existing.toDomain()
                    var mergedReceipt = mergeGroupReceiptState(
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
                    
                    if (!isSessionSnapshotValid(sessionSnapshot, "group-update-before-db:${msg.id}")) return@withContext
                    groupDao.insertMessages(listOf(conectate.com.data.local.entity.GroupMessageEntity.fromDomain(finalMsg)))
                    if (!isSessionSnapshotValid(sessionSnapshot, "group-update-before-flow:${msg.id}")) return@withContext
                    updateGroupMessageInFlow(finalMsg.group_id, finalMsg)
                    android.util.Log.d("GroupRepository", "✏️ [UPDATE] Mensaje actualizado: ${msg.id}")
                }
                Unit

            } catch (e: Exception) {
                android.util.Log.e("GroupRepository", "Error handling update", e)
            }
        }
    }

    private data class GroupReceiptMergeResult(
        val status: String,
        val deliveredAt: String?,
        val readAt: String?
    )

    private fun normalizeGroupReceiptStatus(status: String?): String {
        val normalized = status?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            "read" -> "read"
            "delivered", "received" -> "delivered"
            "sending" -> "sending"
            "failed" -> "failed"
            "" -> "sent"
            else -> normalized
        }
    }

    private fun groupReceiptRank(status: String): Int {
        return when (normalizeGroupReceiptStatus(status)) {
            "failed" -> 0
            "sending" -> 1
            "sent" -> 2
            "delivered" -> 3
            "read" -> 4
            else -> 2
        }
    }

    private fun mergeGroupReceiptState(
        currentStatus: String,
        currentDeliveredAt: String?,
        currentReadAt: String?,
        incomingStatus: String,
        incomingDeliveredAt: String?,
        incomingReadAt: String?
    ): GroupReceiptMergeResult {
        var current = normalizeGroupReceiptStatus(currentStatus)
        var incoming = normalizeGroupReceiptStatus(incomingStatus)
        val mergedReadAt = incomingReadAt ?: currentReadAt
        var mergedDeliveredAt = incomingDeliveredAt ?: currentDeliveredAt

        val candidate = when {
            !mergedReadAt.isNullOrBlank() || incoming == "read" -> "read"
            !mergedDeliveredAt.isNullOrBlank() || incoming == "delivered" -> "delivered"
            else -> incoming
        }
        var finalStatus = if (groupReceiptRank(candidate) >= groupReceiptRank(current)) candidate else current
        if (finalStatus == "read" && mergedDeliveredAt.isNullOrBlank()) {
            mergedDeliveredAt = mergedReadAt
        }
        return GroupReceiptMergeResult(finalStatus, mergedDeliveredAt, mergedReadAt)
    }

    // --- NOTIFICATIONS (SYSTEM) ---
    
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)


    private suspend fun showNotification(msg: GroupMessage, groupName: String) {
        withContext(Dispatchers.Main) {
            try {
                var cleanId = msg.group_id.trim().lowercase()
                if (ConversationNotificationPrefs(context).isMuted("group", cleanId)) {
                    android.util.Log.d("GroupRepository", "🔕 [NOTIFICATION] Group $cleanId muted locally. Skipping notification.")
                    return@withContext
                }

                var notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                var channelId = "group_messages_channel_v2"
                val previewText = buildConversationBackgroundPreview(msg.content_type, msg.content)
                    ?: buildCallEventPreview(msg.content_type, msg.content)
                    ?: if (msg.content_type == "text") msg.content else "📎 Multimedia"
                val notification = MessageNotificationFactory.build(
                    context = context,
                    payload = MessageNotificationPayload(
                        channelId = channelId,
                        channelName = "Mensajes de Grupos",
                        channelDescription = "Notificaciones de mensajes en grupos",
                        conversationId = cleanId,
                        targetType = "group",
                        conversationTitle = groupName,
                        senderName = msg.sender_name ?: "Alguien",
                        messageBody = previewText,
                        notificationId = cleanId.hashCode(),
                        senderId = msg.sender_id,
                        messageId = msg.id,
                        isGroupConversation = true
                    )
                )

                notificationManager.notify(cleanId.hashCode(), notification)
                android.util.Log.d("GroupRepository", "🔔 [NOTIFICATION] Sent notification for group $groupName, messageId=${msg.id}")
            } catch (e: Exception) {
                android.util.Log.e("GroupRepository", "❌ [NOTIFICATION] Failed to show notification", e)
            }
        }
    }

    // --- FLOWS ---
    
    /**
     * Devuelve el flujo de grupos locales, ordenados por actividad reciente.
     * Combina Room con un trigger manual si es necesario.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getGroupsFlow(): Flow<List<GroupWithLastMessage>> {
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
                    groupDao.getGroupsForUserFlow(activeUserId)
                        .distinctUntilChanged()
                        .map { entities ->
                            entities
                                .filter { it.is_member && !it.is_deleted }
                                .map { entity ->
                                    GroupWithLastMessage(
                                        group = entity.toDomain(),
                                        unreadCount = entity.unread_count
                                    )
                                }
                                .sortedByDescending { it.group.last_message_at }
                        }
                }
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * Obtiene un grupo por su ID (Optimizado).
     */


    suspend fun getGroupById(id: String): Group? = withContext(Dispatchers.IO) {
        var cleanId = id.trim().lowercase()
        var group = groupDao.getGroupById(cleanId)?.toDomain() ?: try {
            var remote = supabaseConfig.client.postgrest["groups"].select {
                filter { 
                    eq("id", cleanId)
                    eq("is_deleted", false)
                }
            }.decodeSingleOrNull<Group>()
            
            // Si lo encontramos remoto pero no local, insertarlo?
            // Dejemos que syncGroups maneje la consistencia principal
            remote
        } catch (e: Exception) {
            null
        }
        
        return@withContext if (group?.is_deleted == true) null else group
    }

    fun getGroupFlow(id: String): Flow<Group?> {
        var cleanId = id.trim().lowercase()
        return groupDao.getGroupByIdFlow(cleanId).map { it?.toDomain() }
    }

    fun getGroupByIdFlow(groupId: String): Flow<conectate.com.data.local.entity.GroupEntity?> {
        var cleanId = groupId.trim().lowercase()
        return groupDao.getGroupByIdFlow(cleanId)
    }

    
    fun isUserMemberFlow(groupId: String, userId: String): Flow<Boolean> {
        return groupDao.isUserMemberFlow(groupId.trim().lowercase(), userId.trim().lowercase())
    }


    // --- MESSAGES FLOW ---
    


    suspend fun getCachedMessages(groupId: String): List<GroupMessage> = withContext(Dispatchers.IO) {
        var cleanId = groupId.trim().lowercase()
        if (cleanId.isBlank()) return@withContext emptyList()
        var localMessages = groupDao.getMessagesSync(cleanId).map { it.toDomain() }
        unifyGroupMessages(localMessages, currentSessionUserId())
    }

    fun getMessagesFlow(groupId: String): Flow<List<GroupMessage>> {
        var cleanId = groupId.trim().lowercase()
        
        return _activeGroupMessages.getOrPut(cleanId) {
            val state = MutableStateFlow<List<GroupMessage>>(emptyList())
            
            // 1. Room Collection
            activeGroupMessageJobs[cleanId]?.cancel()
            activeGroupMessageJobs[cleanId] = scope.launch {
                groupDao.getMessagesFlow(cleanId)
                    .distinctUntilChanged()
                    .collect { entities ->
                        var localMessages = entities.map { it.toDomain() }
                        val activeUserId = currentSessionUserId()
                        state.update { current -> unifyGroupMessages(localMessages + current, activeUserId) }
                    }
            }
            // 2. Initial Sync
            scope.launch { syncGroupMessages(cleanId, currentSessionUserId()) }
            
            state
        }
    }

    private fun sanitizeMemberSenderName(value: String?): String? =
        value?.trim()?.takeIf { it.isNotBlank() && !it.equals("Usuario", ignoreCase = true) }

    private fun sanitizeMemberSenderImage(value: String?): String? =
        value?.trim()?.takeIf { it.isNotBlank() }

    private suspend fun hydrateGroupMessages(messages: List<GroupMessage>): List<GroupMessage> {
        if (messages.isEmpty()) return emptyList()

        val normalizedMessages = messages.map { message ->
            message.copy(
                group_id = message.group_id.trim().lowercase(),
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

    private suspend fun hydrateGroupMessage(message: GroupMessage): GroupMessage =
        hydrateGroupMessages(listOf(message)).firstOrNull() ?: message

    private data class GroupReceiptState(
        val deliveredAt: String?,
        val readAt: String?
    )

    private suspend fun fetchGroupReceiptStates(
        groupId: String,
        currentUserId: String
    ): Map<String, GroupReceiptState> {
        var token = authService.getAccessToken()?.trim().orEmpty()
        var cleanGroupId = groupId.trim().lowercase()
        var cleanUserId = currentUserId.trim().lowercase()
        if (token.isBlank() || cleanGroupId.isBlank() || cleanUserId.isBlank()) {
            return emptyMap()
        }

        return runCatching {
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/group_message_receipts") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("select", "message_id,delivered_at,read_at")
                parameter("group_id", "eq.$cleanGroupId")
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
                messageId to GroupReceiptState(
                    deliveredAt = row["delivered_at"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null },
                    readAt = row["read_at"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
                )
            }.toMap()
        }.onFailure {
            android.util.Log.w("GroupRepository", "Failed to fetch receipt states for group $cleanGroupId", it)
        }.getOrDefault(emptyMap())
    }

    private fun applyGroupReceiptState(
        message: GroupMessage,
        receipt: GroupReceiptState?,
        currentUserId: String
    ): GroupMessage {
        if (receipt == null) return message
        if (message.sender_id.trim().lowercase() == currentUserId.trim().lowercase()) return message

        var mergedReceipt = mergeGroupReceiptState(
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

    private fun mergeSyncedGroupMessageWithLocalState(
        incoming: GroupMessage,
        local: GroupMessage?
    ): GroupMessage {
        local ?: return incoming
        var mergedReceipt = mergeGroupReceiptState(
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

    private fun unifyGroupMessages(messages: List<GroupMessage>, currentUserId: String?): List<GroupMessage> {
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

        return hydrated.distinctBy { it.id }.sortedBy { it.created_at } // Sort by created_at explicitly as backup
    }

    private fun updateGroupMessageInFlow(groupId: String, msg: GroupMessage) {
        var cleanId = groupId.trim().lowercase()
        val flow = _activeGroupMessages[cleanId] ?: return
        var currentUserId = authService.getCurrentUserId()
        
        flow.update { current ->
            val list = current.toMutableList()
            val index = list.indexOfFirst { it.id == msg.id }
            if (index != -1) {
                list[index] = msg
            } else {
                list.add(msg)
            }
            unifyGroupMessages(list, currentUserId)
        }
    }

    // --- ACTIONS ---



    suspend fun markAsRead(groupId: String, messageIds: Collection<String>) {
        var cleanId = groupId.trim().lowercase()
        withContext(Dispatchers.IO) {
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return@withContext
            var token = authService.getAccessToken()
            var now = java.time.Instant.now().toString()
            val cleanMessageIds = messageIds
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
            if (cleanMessageIds.isEmpty()) return@withContext
            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured && !token.isNullOrBlank()) {
                localFallbackApiClient.markCollectionAsRead(token, "groups", cleanId)
                    .onFailure { android.util.Log.w("GroupRepository", "Ubuntu mark group read failed for $cleanId", it) }
            }
            var remotelyReadIds = if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                cleanMessageIds.toSet()
            } else runCatching {
                collectionMessageReceiptRemoteStore.markMessagesReadByIds(
                    logTag = "GroupRepository",
                    messageTable = "group_messages",
                    scopeColumn = "group_id",
                    scopeId = cleanId,
                    messageIds = cleanMessageIds,
                    currentUserId = currentUserId,
                    token = token,
                    requireReadAtNull = true,
                    confirmationStatuses = listOf("read")
                )
            }.onFailure {
                android.util.Log.w("GroupRepository", "markAsRead by ids fallback to local state for $cleanId", it)
            }.getOrDefault(emptySet())
            val idsToMark = CollectionMessageSyncSupport.resolveLocallyReadableIds(
                requestedIds = cleanMessageIds,
                remotelyReadIds = remotelyReadIds
            )
            if (idsToMark.isNotEmpty()) {
                groupDao.markMessagesAsReadByIds(cleanId, now, currentUserId, idsToMark)
            }
            val remainingUnread = runCatching { groupDao.countUnreadIncoming(cleanId, currentUserId) }.getOrDefault(0)
            val displayedUnread = CollectionMessageSyncSupport.resolveDisplayedUnreadCount(
                isConversationOpen = currentOpenedGroupId == cleanId,
                exactUnread = remainingUnread
            )
            groupDao.resetUnreadCount(cleanId)
            if (displayedUnread > 0) {
                groupDao.incrementUnreadCount(cleanId, displayedUnread)
            }
        }
    }



    suspend fun markAsRead(groupId: String) {
        var cleanId = groupId.trim().lowercase()
        withContext(Dispatchers.IO) {
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return@withContext
            var token = authService.getAccessToken()
            var now = java.time.Instant.now().toString()
            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured && !token.isNullOrBlank()) {
                localFallbackApiClient.markCollectionAsRead(token, "groups", cleanId)
                    .onFailure { android.util.Log.w("GroupRepository", "Ubuntu mark group read failed for $cleanId", it) }
            } else runCatching {
                collectionMessageReceiptRemoteStore.markAllMessagesRead(
                    logTag = "GroupRepository",
                    messageTable = "group_messages",
                    scopeColumn = "group_id",
                    scopeId = cleanId,
                    currentUserId = currentUserId,
                    token = token,
                    rpcFunctionName = "secure_mark_group_messages_read",
                    rpcScopeKey = "p_group_id",
                    requireReadAtNull = true
                )
            }.onFailure {
                android.util.Log.w("GroupRepository", "markAsRead fallback to local state for $cleanId", it)
            }
            groupDao.markAllAsRead(cleanId, now, currentUserId)
            groupDao.resetUnreadCount(cleanId)
            runCatching {
                var notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(cleanId.hashCode())
            }
        }
    }



    suspend fun markMessageAsDelivered(groupId: String, messageId: String) {
        var cleanId = groupId.trim().lowercase()
        val cleanMessageId = messageId.trim().lowercase()
        if (cleanId.isBlank() || cleanMessageId.isBlank()) return
        withContext(Dispatchers.IO) {
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return@withContext
            var token = authService.getAccessToken()
            var now = java.time.Instant.now().toString()
            var remoteSynced = collectionMessageReceiptRemoteStore.markMessageDelivered(
                logTag = "GroupRepository",
                messageTable = "group_messages",
                scopeColumn = "group_id",
                scopeId = cleanId,
                messageId = cleanMessageId,
                currentUserId = currentUserId,
                token = token,
                confirmationStatuses = listOf("delivered", "read", "received")
            )
            groupDao.markMessageAsDelivered(cleanId, cleanMessageId, now, currentUserId)
            if (!remoteSynced) {
                android.util.Log.w("GroupRepository", "markMessageAsDelivered could not confirm remote transition for $cleanMessageId in $cleanId")
            }
        }
    }



    suspend fun markMessagesAsDelivered(groupId: String) {
        var cleanId = groupId.trim().lowercase()
        withContext(Dispatchers.IO) {
            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return@withContext
            var token = authService.getAccessToken()
            var now = java.time.Instant.now().toString()
            var remoteSynced = collectionMessageReceiptRemoteStore.markAllMessagesDelivered(
                logTag = "GroupRepository",
                scopeId = cleanId,
                token = token,
                rpcFunctionName = "secure_mark_group_messages_delivered",
                rpcScopeKey = "p_group_id"
            )
            if (remoteSynced) {
                groupDao.markAllAsDelivered(cleanId, now, currentUserId)
            }
        }
    }

    suspend fun sendGroupPollMessage(
        groupId: String,
        question: String,
        options: List<String>,
        allowsMultiple: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var cleanId = groupId.trim().lowercase()
        var userId = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: return@withContext Result.failure(Exception("No user"))

        val sanitizedQuestion = question.trim().take(300)
        val sanitizedOptions = options.map { it.trim().take(120) }.filter { it.isNotBlank() }.distinct()
        if (sanitizedQuestion.isBlank()) return@withContext Result.failure(Exception("La pregunta es obligatoria"))
        if (sanitizedOptions.size < 2) return@withContext Result.failure(Exception("La encuesta requiere al menos 2 opciones"))

        var msgId = UUID.randomUUID().toString()
        var now = java.time.Instant.now().toString()
        var optimistic = GroupMessage(
            id = msgId,
            group_id = cleanId,
            sender_id = userId,
            content = sanitizedQuestion,
            content_type = "poll",
            poll_id = null,
            created_at = now,
            status = "sending"
        )

        groupDao.insertMessages(
            listOf(
                GroupMessageEntity.fromDomain(
                    msg = optimistic,
                    isSynced = false,
                    pendingAction = "send",
                    retryCount = 0,
                    lastRetryAt = null
                )
            )
        )
        updateGroupMessageInFlow(cleanId, optimistic)
        groupDao.updateMetadata(cleanId, "📊 $sanitizedQuestion", now)

        var remote = createGroupPollMessageSecure(
            groupId = cleanId,
            messageId = msgId,
            question = sanitizedQuestion,
            options = sanitizedOptions,
            allowsMultiple = allowsMultiple
        )

        if (remote.isSuccess) {
            val inserted = remote.getOrThrow().copy(group_id = cleanId, status = "sent")
            groupDao.insertMessages(listOf(GroupMessageEntity.fromDomain(inserted, isSynced = true, pendingAction = null)))
            groupDao.updateMessageSyncStatus(msgId, status = "sent", isSynced = true, pendingAction = null)
            updateGroupMessageInFlow(cleanId, inserted)
            Result.success(Unit)
        } else {
            var error = remote.exceptionOrNull() ?: Exception("No se pudo crear la encuesta")
            groupDao.markRetryAttempt(msgId, lastRetryAt = java.time.Instant.now().toString(), status = "failed")
            groupDao.updateMessageSyncStatus(msgId, status = "failed", isSynced = false, pendingAction = "send")
            groupDao.getMessageById(msgId)?.toDomain()?.let { failed -> updateGroupMessageInFlow(failed.group_id, failed) }
            Result.failure(error)
        }
    }

    private suspend fun createGroupPollMessageSecure(
        groupId: String,
        messageId: String,
        question: String,
        options: List<String>,
        allowsMultiple: Boolean
    ): Result<GroupMessage> = collectionPollMessageRemoteStore.createPollMessage(
        scopeType = "group",
        scopeId = groupId,
        messageId = messageId,
        question = question,
        options = options,
        allowsMultiple = allowsMultiple,
        rpcFunctionName = "secure_create_group_poll_message",
        rpcScopeKey = "p_group_id",
        messageTable = "group_messages",
        messageScopeColumn = "group_id",
        includeStatusSentInFallbackPayload = true,
        parseMessage = ::parseRpcGroupMessage,
        buildFallbackMessage = { pollId, senderId, createdAt ->
            GroupMessage(
                id = messageId,
                group_id = groupId,
                sender_id = senderId,
                content = question,
                content_type = "poll",
                poll_id = pollId,
                created_at = createdAt,
                status = "sent"
            )
        }
    )

    private fun parseRpcGroupMessage(body: String): GroupMessage? {
        if (body.isBlank()) return null
        return try {
            json.decodeFromString<GroupMessage>(body)
        } catch (_: Exception) {
            runCatching { json.decodeFromString<List<GroupMessage>>(body).firstOrNull() }.getOrNull()
        }
    }
    
    suspend fun sendGroupMessage(
        groupId: String,
        content: String,
        replyToId: String? = null,
        contentType: String = "text",
        mediaUrl: String? = null,
        pollId: String? = null,
        isForwarded: Boolean = false,
        forwardedFromMessageId: String? = null,
        forwardedFromUserId: String? = null,
        messageId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            var userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("No user"))
            var cleanId = groupId.trim().lowercase()

            val guardDecision = operationGuard.acquire(
                action = GuardedAction.GROUP_MESSAGE_SEND,
                actorId = userId,
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
                context = ModerationContext.GROUP_MESSAGE
            )
            if (moderation.verdict == ModerationVerdict.BLOCK) {
                appMetrics.recordFailure(
                    metric = "group_message_send",
                    attributes = mapOf("reason" to "moderation_block")
                )
                return@withContext Result.failure(Exception("Tu mensaje incumple nuestras politicas"))
            }
            val finalContent = moderation.sanitizedText
            if (contentType == "text" && mediaUrl.isNullOrBlank() && finalContent.isBlank()) {
                return@withContext Result.failure(Exception("El mensaje no puede estar vacio"))
            }
            
            var msgId = messageId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                ?: java.util.UUID.randomUUID().toString()
            var now = java.time.Instant.now().toString()
            
            var msg = GroupMessage(
                id = msgId,
                group_id = cleanId,
                sender_id = userId,
                content = finalContent,
                content_type = contentType,
                media_url = mediaUrl,
                poll_id = pollId,
                created_at = now,
                status = "sending",
                reply_to_id = replyToId,
                is_forwarded = isForwarded,
                forwarded_from_message_id = forwardedFromMessageId,
                forwarded_from_user_id = forwardedFromUserId
            )
            
            // 1. Insert Optimistic (Instant UI update)
            groupDao.insertMessages(
                listOf(
                    conectate.com.data.local.entity.GroupMessageEntity.fromDomain(
                        msg = msg,
                        isSynced = false,
                        pendingAction = "send",
                        retryCount = 0,
                        lastRetryAt = null
                    )
            )
            )
            updateGroupMessageInFlow(cleanId, msg)
            val preview = buildConversationBackgroundPreview(contentType, finalContent)
                ?: buildCallEventPreview(contentType, finalContent)
                ?: if (contentType == "text") finalContent else "Multimedia"
            groupDao.updateMetadata(cleanId, preview, now)

            try {
                operationGuard.audit(
                    action = GuardedAction.GROUP_MESSAGE_SEND,
                    actorId = userId,
                    scopeId = cleanId,
                    outcome = GuardOutcome.QUEUED,
                    attributes = mapOf("message_id" to msgId)
                )
            } catch (_: Exception) {
                // Best effort audit only.
            }
            
            // 2. Send to Supabase and return real status to the UI.
            val msgJson = buildJsonObject {
                put("id", msgId)
                put("group_id", cleanId)
                put("sender_id", userId)
                put("content", finalContent)
                put("content_type", contentType)
                put("media_url", mediaUrl)
                put("reply_to_id", replyToId)
                put("poll_id", pollId)
                put("is_forwarded", isForwarded)
                put("forwarded_from_message_id", forwardedFromMessageId)
                put("forwarded_from_user_id", forwardedFromUserId)
            }

            val syncResult = if (authService.isLocalFallbackSession()) {
                val token = authService.getAccessTokenAwait()
                if (token.isNullOrBlank()) {
                    Result.failure(Exception("Sesion Ubuntu no disponible"))
                } else {
                    localFallbackApiClient.sendGroupMessage(
                        accessToken = token,
                        groupId = cleanId,
                        content = finalContent,
                        contentType = contentType,
                        mediaUrl = mediaUrl,
                        mediaSize = 0L,
                        replyToId = replyToId,
                        messageId = msgId
                    ).map { serverMessage ->
                        val synced = serverMessage.copy(group_id = cleanId, status = "sent")
                        groupDao.insertMessages(
                            listOf(
                                GroupMessageEntity.fromDomain(
                                    msg = synced,
                                    isSynced = true,
                                    pendingAction = null
                                )
                            )
                        )
                        groupDao.updateMetadata(
                            cleanId,
                            CollectionMessageSyncSupport.buildMetadataPreview(synced.content_type, synced.content),
                            synced.created_at
                        )
                        updateGroupMessageInFlow(cleanId, synced)
                        Unit
                    }
                }
            } else {
                insertGroupMessageWithRetry(msgId, msgJson)
            }
            if (syncResult.isSuccess) {
                android.util.Log.d("GroupRepository", "✅ [SERVER-ACK] Mensaje $msgId sincronizado")
                try {
                    operationGuard.audit(
                        action = GuardedAction.GROUP_MESSAGE_SEND,
                        actorId = userId,
                        scopeId = cleanId,
                        outcome = GuardOutcome.SUCCESS,
                        attributes = mapOf("message_id" to msgId)
                    )
                } catch (_: Exception) {
                    // Best effort audit only.
                }
                appMetrics.incrementCounter(
                    metric = "group_message_send_success",
                    attributes = mapOf("content_type" to contentType)
                )
                appMetrics.recordLatency(
                    metric = "group_message_send",
                    durationMs = System.currentTimeMillis() - startedAt,
                    attributes = mapOf("content_type" to contentType)
                )
                Result.success(Unit)
            } else {
                val syncError = syncResult.exceptionOrNull()
                android.util.Log.e("GroupRepository", "❌ [SERVER-FAIL] Falló subida de mensaje $msgId", syncError)
                try {
                    operationGuard.audit(
                        action = GuardedAction.GROUP_MESSAGE_SEND,
                        actorId = userId,
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
                    metric = "group_message_send",
                    throwable = syncError,
                    attributes = mapOf("content_type" to contentType)
                )
                Result.failure(syncError ?: Exception("No se pudo sincronizar mensaje de grupo"))
            }
        } catch (e: Exception) {
            android.util.Log.e("GroupRepository", "Error sending message", e)
            appMetrics.recordFailure("group_message_send", e)
            Result.failure(e)
        }
    }

    private suspend fun insertGroupMessageWithRetry(
        msgId: String,
        payload: JsonObject,
        maxAttempts: Int = 4
    ): Result<Unit> {
        return CollectionPendingMessageRetrySupport.retryPendingMessageSend(
            messageId = msgId,
            maxAttempts = maxAttempts,
            sendAction = { sendGroupMessageSecureOrFallback(msgId = msgId, payload = payload) },
            isDuplicateKeyError = CollectionRepositorySupport::isDuplicateKeyError,
            isRetryableNetworkError = { error -> CollectionRepositorySupport.isRetryableNetworkError(error, transientNetworkRetryCodes) },
            onConfirmedSuccess = {
                groupDao.updateMessageSyncStatus(msgId, status = "sent", isSynced = true, pendingAction = null)
                groupDao.getMessageById(msgId)?.toDomain()?.let { synced -> updateGroupMessageInFlow(synced.group_id, synced) }
            },
            onDuplicateTreatedAsSuccess = {
                groupDao.updateMessageSyncStatus(msgId, status = "sent", isSynced = true, pendingAction = null)
                groupDao.getMessageById(msgId)?.toDomain()?.let { synced -> updateGroupMessageInFlow(synced.group_id, synced) }
            },
            onTerminalFailure = {
                groupDao.markRetryAttempt(msgId, lastRetryAt = java.time.Instant.now().toString(), status = "failed")
                groupDao.updateMessageSyncStatus(msgId, status = "failed", isSynced = false, pendingAction = "send")
                groupDao.getMessageById(msgId)?.toDomain()?.let { failed -> updateGroupMessageInFlow(failed.group_id, failed) }
            },
            failureMessage = "No se pudo sincronizar mensaje de grupo"
        )
    }

    private suspend fun sendGroupMessageSecureOrFallback(
        msgId: String,
        payload: JsonObject
    ): Result<Unit> = collectionPendingMessageRemoteStore.sendSecureOrFallback(
        rpcFunctionName = "secure_send_group_message",
        scopePayloadKey = "group_id",
        scopeRpcKey = "p_group_id",
        tableName = "group_messages",
        messageId = msgId,
        payload = payload
    )

    private fun isMissingGroupRpcFunction(statusCode: Int, responseBody: String): Boolean {
        if (statusCode != 400 && statusCode != 404) return false
        val cleanBody = responseBody.lowercase()
        return cleanBody.contains("secure_send_group_message") &&
            (cleanBody.contains("could not find the function") || cleanBody.contains("undefined function"))
    }

    private suspend fun retryPendingGroupMessages(maxBatch: Int = 40) {
        var userId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return
        val pending = groupDao.getPendingOutgoingMessages(maxBatch)
        if (pending.isEmpty()) return

        pending.forEach { pendingEntity ->
            if (pendingEntity.sender_id.trim().lowercase() != userId) return@forEach

            var payload = buildJsonObject {
                put("id", pendingEntity.id)
                put("group_id", pendingEntity.group_id)
                put("sender_id", pendingEntity.sender_id)
                put("content", pendingEntity.content)
                put("content_type", pendingEntity.content_type)
                put("media_url", pendingEntity.media_url)
                put("reply_to_id", pendingEntity.reply_to_id)
                put("poll_id", pendingEntity.poll_id)
                put("is_forwarded", pendingEntity.is_forwarded)
                put("forwarded_from_message_id", pendingEntity.forwarded_from_message_id)
                put("forwarded_from_user_id", pendingEntity.forwarded_from_user_id)
            }

            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val token = authService.getAccessTokenAwait()
                if (!token.isNullOrBlank()) {
                    localFallbackApiClient.sendGroupMessage(
                        accessToken = token,
                        groupId = pendingEntity.group_id,
                        content = pendingEntity.content,
                        contentType = pendingEntity.content_type,
                        mediaUrl = pendingEntity.media_url,
                        mediaSize = 0L,
                        replyToId = pendingEntity.reply_to_id,
                        messageId = pendingEntity.id
                    ).onSuccess { serverMessage ->
                        groupDao.updateMessageSyncStatus(pendingEntity.id, status = "sent", isSynced = true, pendingAction = null)
                        updateGroupMessageInFlow(pendingEntity.group_id, serverMessage.copy(group_id = pendingEntity.group_id, status = "sent"))
                    }
                }
            } else {
                insertGroupMessageWithRetry(msgId = pendingEntity.id, payload = payload)
            }
        }
    }



    suspend fun editGroupMessage(messageId: String, newContent: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("No user"))
            
            supabaseConfig.client.postgrest["group_messages"].update({
                set("content", newContent)
                set("is_edited", true)
            }) {
                filter {
                    eq("id", messageId)
                    eq("sender_id", userId)
                }
            }
            
            // Optimistic update
            var existing = groupDao.getMessageById(messageId)
            if (existing != null) {
                var updated = existing.toDomain().copy(content = newContent, is_edited = true)
                groupDao.insertMessages(listOf(conectate.com.data.local.entity.GroupMessageEntity.fromDomain(updated)))
                updateGroupMessageInFlow(updated.group_id, updated)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    suspend fun deleteGroupMessage(messageId: String, forEveryone: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("No user"))

            var normalizedUserId = userId.trim().lowercase()
            val existingLocal = groupDao.getMessageById(messageId)?.toDomain()
            val optimisticMessage = existingLocal?.let { domain ->
                if (forEveryone) {
                    domain.copy(deleted_for_all = true)
                } else {
                    domain.copy(
                        deleted_by = (domain.deleted_by + normalizedUserId)
                            .map { it.trim().lowercase() }
                            .filter { it.isNotBlank() }
                            .distinct()
                    )
                }
            }

            if (optimisticMessage != null) {
                groupDao.insertMessages(listOf(GroupMessageEntity.fromDomain(optimisticMessage)))
                updateGroupMessageInFlow(optimisticMessage.group_id, optimisticMessage)
            }

            val remoteDeleteResult = CollectionDeleteRetrySupport.retryRemoteDelete(
                logTag = "GroupRepository",
                messageId = messageId,
                remoteDelete = {
                    if (forEveryone) {
                        supabaseConfig.client.postgrest["group_messages"].update({
                            set("deleted_for_all", true)
                        }) {
                            filter {
                                eq("id", messageId)
                                eq("sender_id", userId)
                            }
                        }
                    } else {
                        val remoteDeletedBy = optimisticMessage?.deleted_by
                            ?: existingLocal?.deleted_by
                                ?.map { it.trim().lowercase() }
                                ?.filter { it.isNotBlank() }
                                ?.distinct()
                            ?: emptyList()

                        if (remoteDeletedBy.isNotEmpty()) {
                            supabaseConfig.client.postgrest["group_messages"].update({
                                set("deleted_by", remoteDeletedBy)
                            }) {
                                filter { eq("id", messageId) }
                            }
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
                        "GroupRepository",
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



    suspend fun getGroupMessageReadReceipts(messageId: String): Result<List<MessageReadReceipt>> = withContext(Dispatchers.IO) {
        var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
        collectionMessageEngagementStore.getMessageReadReceipts(
            messageId = messageId,
            token = token,
            rpcFunctionName = "secure_get_group_message_receipts"
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

    data class GroupMessageReactionToggleResult(
        val messageId: String,
        val reactions: Map<String, List<String>>
    )



    suspend fun toggleGroupMessageReaction(messageId: String, emoji: String): Result<GroupMessageReactionToggleResult> = withContext(Dispatchers.IO) {
        try {
            var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            var userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("No user"))
            var local = groupDao.getMessageById(messageId)
                ?: return@withContext Result.failure(Exception("Mensaje no encontrado"))
            val reactionResult = collectionMessageEngagementStore.toggleReaction(
                messageId = messageId,
                emoji = emoji,
                currentReactions = local.toDomain().reactions,
                userId = userId,
                token = token,
                rpcFunctionName = "secure_toggle_group_message_reaction",
                tableName = "group_messages",
                missingRpcPredicate = ::isMissingGroupRpcFunction
            ).getOrElse { return@withContext Result.failure(it) }
            val updatedReactions = reactionResult.reactions

            var updated = local.toDomain().copy(reactions = updatedReactions)
            groupDao.insertMessages(listOf(GroupMessageEntity.fromDomain(updated, isSynced = local.is_synced, pendingAction = local.pending_action, retryCount = local.retry_count, lastRetryAt = local.last_retry_at)))
            updateGroupMessageInFlow(updated.group_id, updated)

            Result.success(GroupMessageReactionToggleResult(messageId = updated.id, reactions = updatedReactions))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun forwardGroupMessageToGroups(
        source: GroupMessage,
        targetGroupIds: List<String>
    ): Result<Int> = withContext(Dispatchers.IO) {
        CollectionMessageForwardingSupport.forwardMessage(
            sourceDeletedForAll = source.deleted_for_all,
            sourceScopeId = source.group_id,
            targetScopeIds = targetGroupIds,
            maxTargetsPerBatch = maxForwardTargetsPerBatch,
            emptySelectionMessage = "Selecciona al menos un grupo",
            batchLimitMessage = "Puedes reenviar a un máximo de $maxForwardTargetsPerBatch grupos por vez",
            totalFailureMessage = "No se pudo reenviar"
        ) { targetId ->
            sendGroupMessage(
                groupId = targetId,
                content = if (source.content.isNotBlank()) source.content else "Mensaje reenviado",
                contentType = source.content_type,
                mediaUrl = source.media_url,
                isForwarded = true,
                forwardedFromMessageId = source.id,
                forwardedFromUserId = source.sender_id
            )
        }
    }

    suspend fun syncGroupMessages(groupId: String) {
        syncGroupMessages(groupId, currentSessionUserId())
    }



    private suspend fun syncGroupMessages(groupId: String, sessionUserId: String?) {
        var cleanId = groupId.trim().lowercase()
        val normalizedSessionUserId = SessionWriteGuard.normalizeAccountId(sessionUserId) ?: return
        var sessionSnapshot = captureSessionSnapshot(normalizedSessionUserId)
        
        withContext(Dispatchers.IO) {
            if (!isSessionSnapshotValid(sessionSnapshot, "group-sync-messages-start:$cleanId")) return@withContext
            try {
                if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                    val token = authService.getAccessTokenAwait()
                    if (token.isNullOrBlank()) return@withContext
                    val payload = localFallbackApiClient.loadGroupRoom(token, cleanId).getOrElse { error ->
                        android.util.Log.e("GroupRepository", "Error syncing Ubuntu group messages for $groupId", error)
                        return@withContext
                    }
                    val localMessagesById = groupDao.getMessagesSync(cleanId)
                        .associateBy { it.id.trim().lowercase() }
                        .mapValues { (_, entity) -> entity.toDomain() }
                    val mergedResponse = payload.messages.mapNotNull { row ->
                        runCatching {
                            json.decodeFromJsonElement<GroupMessage>(row).copy(group_id = cleanId, status = "sent")
                        }.getOrNull()
                    }.map { incoming ->
                        mergeSyncedGroupMessageWithLocalState(
                            incoming = incoming,
                            local = localMessagesById[incoming.id.trim().lowercase()]
                        )
                    }
                    if (!isSessionSnapshotValid(sessionSnapshot, "group-sync-messages-after-ubuntu:$cleanId")) return@withContext
                    if (_activeGroupMessages.containsKey(cleanId)) {
                        val current = _activeGroupMessages[cleanId]?.value ?: emptyList()
                        _activeGroupMessages[cleanId]?.value = CollectionMessageSyncSupport.mergeActiveMessages(
                            current = current,
                            incoming = mergedResponse,
                            idSelector = { it.id },
                            createdAtSelector = { it.created_at }
                        )
                    }
                    if (mergedResponse.isNotEmpty()) {
                        groupDao.insertMessages(mergedResponse.map { GroupMessageEntity.fromDomain(it) })
                        mergedResponse.maxByOrNull { it.created_at }?.let { lastMsg ->
                            groupDao.updateMetadata(
                                cleanId,
                                CollectionMessageSyncSupport.buildMetadataPreview(lastMsg.content_type, lastMsg.content),
                                lastMsg.created_at
                            )
                        }
                    }
                    return@withContext
                }
                // 1. Fetch Remote Messages
                var response = supabaseConfig.client.postgrest["group_messages"].select {
                    filter { eq("group_id", cleanId) }
                    order("created_at", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                }.decodeList<GroupMessage>()
                
                if (response.isNotEmpty()) {
                    val receiptStates = fetchGroupReceiptStates(cleanId, normalizedSessionUserId)
                    val localMessagesById = groupDao.getMessagesSync(cleanId)
                        .associateBy { it.id.trim().lowercase() }
                        .mapValues { (_, entity) -> entity.toDomain() }
                    val hydratedResponse = hydrateGroupMessages(
                        response.map { it.copy(group_id = cleanId) }
                    ).map { message ->
                        var messageId = message.id.trim().lowercase()
                        applyGroupReceiptState(
                            message = message,
                            receipt = receiptStates[messageId],
                            currentUserId = normalizedSessionUserId
                        )
                    }
                    val mergedResponse = hydratedResponse.map { incoming ->
                        mergeSyncedGroupMessageWithLocalState(
                            incoming = incoming,
                            local = localMessagesById[incoming.id.trim().lowercase()]
                        )
                    }
                    if (!isSessionSnapshotValid(sessionSnapshot, "group-sync-messages-after-network:$cleanId")) return@withContext
                    if (_activeGroupMessages.containsKey(cleanId)) {
                        var current = _activeGroupMessages[cleanId]?.value ?: emptyList()
                        _activeGroupMessages[cleanId]?.value = CollectionMessageSyncSupport.mergeActiveMessages(
                            current = current,
                            incoming = mergedResponse,
                            idSelector = { it.id },
                            createdAtSelector = { it.created_at }
                        )
                    }
                    
                    if (!isSessionSnapshotValid(sessionSnapshot, "group-sync-messages-before-db:$cleanId")) return@withContext
                    groupDao.insertMessages(mergedResponse.map {
                        GroupMessageEntity.fromDomain(it) 
                    })

                    CollectionMessageSyncSupport.reconcileUnreadCount(
                        isConversationOpen = (cleanId == currentOpenedGroupId),
                        countUnread = { groupDao.countUnreadIncoming(cleanId, normalizedSessionUserId) },
                        resetUnread = { groupDao.resetUnreadCount(cleanId) },
                        incrementUnread = { unread -> groupDao.incrementUnreadCount(cleanId, unread) },
                        onUnreadReconciled = { unread ->
                            android.util.Log.d("GroupRepository", "🔔 [SYNC-UNREAD] Reconciled unread=$unread for group $cleanId")
                        }
                    )
                    
                    val lastMsg = mergedResponse.maxByOrNull { it.created_at }
                    if (lastMsg != null) {
                        if (!isSessionSnapshotValid(sessionSnapshot, "group-sync-messages-before-metadata:$cleanId")) return@withContext
                        groupDao.updateMetadata(
                            cleanId,
                            CollectionMessageSyncSupport.buildMetadataPreview(lastMsg.content_type, lastMsg.content),
                            lastMsg.created_at
                        )
                    }
                }
                Unit
            } catch (e: Exception) {
                android.util.Log.e("GroupRepository", "Error syncing group messages for $groupId", e)
            }
        }
    }


    suspend fun uploadGroupAudio(groupId: String, bytes: ByteArray): Result<String> =
        collectionMediaStore.uploadAudio(
            ownerId = groupId,
            bytes = bytes,
            primaryBucket = "group-audio",
            filePrefix = "group_audio",
            extension = "mp3",
            contentType = "audio/mpeg"
        )



    private fun groupFromFallbackInbox(item: LocalFallbackInboxItem, currentUserId: String): Group {
        val cleanId = item.id.trim().lowercase()
        val now = java.time.Instant.now().toString()
        val updatedAt = item.updatedAt.ifBlank { now }
        val preview = item.subtitle.takeIf { it.isNotBlank() && it != "Sin actividad reciente" }
        return Group(
            id = cleanId,
            name = item.title.ifBlank { "Grupo" },
            image = item.avatarUrl.ifBlank { null },
            description = item.subtitle.takeIf { it.isNotBlank() },
            created_at = updatedAt,
            creator_id = currentUserId,
            last_message = preview,
            last_message_at = updatedAt,
            is_member = true,
            is_deleted = false,
            only_admins_can_talk = item.onlyAdminsCanTalk
        )
    }

    private suspend fun syncGroupsFromLocalFallback(): Result<Unit> {
        return try {
            val userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return Result.failure(Exception("Not authenticated"))
            val token = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("Sesion Ubuntu no disponible"))
            val sessionSnapshot = captureSessionSnapshot(userId)
            val inbox = localFallbackApiClient.loadMessagesInbox(token, "groups").getOrThrow()
            val activeItems = inbox.items.filter { it.id.isNotBlank() }
            val activeIds = activeItems.map { it.id.trim().lowercase() }.distinct()
            val localUnreadCounts = groupDao.getUnreadCountRows(userId)
                .associate { row -> row.groupId.trim().lowercase() to row.unreadCount.coerceAtLeast(0) }

            if (!isSessionSnapshotValid(sessionSnapshot, "group-ubuntu-sync-before-upsert:$userId")) {
                return Result.success(Unit)
            }
            groupDao.upsertGroups(
                activeItems.map { item ->
                    val cleanId = item.id.trim().lowercase()
                    GroupEntity.fromDomain(
                        group = groupFromFallbackInbox(item, userId),
                        unreadCount = CollectionMessageSyncSupport.resolveDisplayedUnreadCount(
                            isConversationOpen = cleanId == currentOpenedGroupId,
                            exactUnread = localUnreadCounts[cleanId] ?: item.unreadCount
                        )
                    )
                }
            )
            groupDao.insertMembers(
                activeIds.map { groupId ->
                    GroupMemberEntity(groupId, userId, "member", java.time.Instant.now().toString())
                }
            )
            groupDao.markOthersAsNotMembers(activeIds)
            // CRITICAL FIX: Remove stale group_members rows so they don't appear via INNER JOIN
            if (activeIds.isEmpty()) {
                groupDao.deleteAllMembershipsForUser(userId)
            } else {
                groupDao.deleteUserMembershipsNotIn(userId, activeIds)
            }
            activeIds.forEach { cleanId -> syncGroupMessages(cleanId, userId) }
            android.util.Log.d("GroupRepository", "✅ [UBUNTU-SYNC] ${activeIds.size} grupos sincronizados desde Ubuntu")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("GroupRepository", "Error syncing Ubuntu groups", e)
            Result.failure(e)
        }
    }

    override suspend fun syncGroups(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (authService.isLocalFallbackModeEnabled()) {
                return@withContext syncGroupsFromLocalFallback()
            }
            var userId = (authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))).trim().lowercase()
            var sessionSnapshot = captureSessionSnapshot(userId)
            
            android.util.Log.d("GroupRepository", "🔄 [SYNC] Iniciando sincronización de grupos para $userId")
            
            // 1. Fetch memberships first
            var memberships = supabaseConfig.client.postgrest["group_members"].select {
                filter { eq("user_id", userId) }
            }.decodeList<GroupMember>()
            
            var ids = memberships.map { it.group_id.trim().lowercase() }
            
            if (ids.isEmpty()) {
                android.util.Log.d("GroupRepository", "⚠️ [SYNC] Usuario no tiene grupos. Limpiando local.")
                if (!isSessionSnapshotValid(sessionSnapshot, "group-sync-empty:$userId")) {
                    return@withContext Result.success(Unit)
                }
                groupDao.markOthersAsNotMembers(emptyList())
                return@withContext Result.success(Unit)
            }
            
            // 2. Fetch Groups details
             val groupsResponse = supabaseConfig.client.postgrest["groups"].select {
                filter {
                    isIn("id", ids)
                    eq("is_deleted", false)
                }
            }.decodeList<Group>()

            // 3. Batch Sync Room
            val activeIds = groupsResponse.map { it.id.trim().lowercase() }
            
            // Preserve unread counts
            val allLocalGroups = groupDao.getAllGroupsSync()
            val localUnreadCounts = groupDao.getUnreadCountRows(userId)
                .associate { row ->
                    row.groupId.trim().lowercase() to row.unreadCount.coerceAtLeast(0)
                }
            val localGroupsMap = allLocalGroups.associateBy { it.id.trim().lowercase() }

            if (!isSessionSnapshotValid(sessionSnapshot, "group-sync-before-upsert:$userId")) {
                return@withContext Result.success(Unit)
            }
            groupDao.upsertGroups(groupsResponse.map { 
                var cleanGroupId = it.id.trim().lowercase()
                var localGroup = localGroupsMap[cleanGroupId]
                val snapshot = CollectionSyncSupport.resolveLastMessageSnapshot(
                    serverMessage = it.last_message,
                    serverTime = it.last_message_at,
                    localMessage = localGroup?.last_message,
                    localTime = localGroup?.last_message_at
                )

                GroupEntity.fromDomain(
                    group = it.copy(
                        id = cleanGroupId,
                        is_member = true,
                        last_message = snapshot.lastMessage,
                        last_message_at = snapshot.lastMessageAt
                    ),
                    unreadCount = CollectionMessageSyncSupport.resolveDisplayedUnreadCount(
                        isConversationOpen = cleanGroupId == currentOpenedGroupId,
                        exactUnread = localUnreadCounts[cleanGroupId] ?: 0
                    )
                ) 
            })
            
            // 4. Update Memberships
            // 4. Update Memberships (Smart Update)
            val validMemberships = memberships.filter { activeIds.contains(it.group_id.trim().lowercase()) }
            
            // Upsert (Replace) current valid memberships
            if (!isSessionSnapshotValid(sessionSnapshot, "group-sync-before-members:$userId")) {
                return@withContext Result.success(Unit)
            }
            groupDao.insertMembers(validMemberships.map {
                GroupMemberEntity(it.group_id.trim().lowercase(), it.user_id.trim().lowercase(), it.role, it.joined_at)
            })

            // Remove memberships that are NO LONGER in the fetched list
            val validGroupIds = validMemberships.map { it.group_id.trim().lowercase() }
            if (validGroupIds.isEmpty()) {
                groupDao.deleteAllMembershipsForUser(userId)
            } else {
                groupDao.deleteUserMembershipsNotIn(userId, validGroupIds)
            }
            
            // 5. Cleanup
            if (!isSessionSnapshotValid(sessionSnapshot, "group-sync-before-cleanup:$userId")) {
                return@withContext Result.success(Unit)
            }
            groupDao.markOthersAsNotMembers(activeIds)
            
            // [SMART SYNC] Check for Ghost Messages after sync
            try {
                groupsResponse.forEach { group ->
                    var cleanId = group.id.trim().lowercase()
                    var localGroup = localGroupsMap[cleanId]
                    if (CollectionSyncSupport.hasServerNewerContent(group.last_message_at, localGroup?.last_message_at)) {
                        android.util.Log.i("GroupRepository", "👻 [GHOST-CHECK] Group $cleanId has newer content on server. Fetching...")
                        launch { syncGroupMessages(cleanId, userId) }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("GroupRepository", "Smart Sync Warning", e)
            }

            android.util.Log.d("GroupRepository", "✅ [SYNC] Sincronización completada. ${activeIds.size} grupos activos.")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("GroupRepository", "❌ [SYNC] Error crítico", e)
            Result.failure(e)
        }
    }

    suspend fun uploadGroupImage(groupId: String, bytes: ByteArray): Result<String> =
        collectionMediaStore.uploadImage(
            ownerId = groupId,
            bytes = bytes,
            bucketName = "groups",
            filePrefix = "group"
        )

    /**
     * Crea un nuevo grupo en Supabase e inserta al creador como admin.
     */


    suspend fun createGroup(name: String, description: String?, imageBytes: ByteArray?, members: List<String>): Result<String> = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("GroupRepository", "[GOD] Creando grupo: $name para integrantes: $members")
            var creatorId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            
            val groupId = UUID.randomUUID().toString().lowercase()
            android.util.Log.d("GroupRepository", "[GOD] Generando nuevo groupId: $groupId")
            
            var imageUrl: String? = null
            if (imageBytes != null) {
                var uploadResult = uploadGroupImage(groupId, imageBytes)
                if (uploadResult.isSuccess) {
                    imageUrl = uploadResult.getOrNull()
                }
            }

            // Usar JsonObject explícito para evitar fallos por campos nulos o inesperados
            var now = java.time.Instant.now().toString()
            val groupJson = buildJsonObject {
                put("id", groupId)
                put("name", name)
                put("description", description)
                put("image", imageUrl)
                put("created_at", now)
                put("creator_id", creatorId)
                put("is_deleted", false)
            }

            val allMembers = (members + creatorId)
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
            android.util.Log.d("GroupRepository", "[GOD] Insertando ${allMembers.size} miembros en Supabase...")
            var membershipPayloads = allMembers.map { uid ->
                buildJsonObject {
                    put("group_id", groupId)
                    put("user_id", uid)
                    put("role", if (uid == creatorId.trim().lowercase()) "owner" else "member")
                    put("joined_at", now)
                }
            }
            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val token = authService.getAccessTokenAwait()
                    ?: return@withContext Result.failure(Exception("Sesion Ubuntu no disponible"))
                localFallbackApiClient.createGroup(
                    accessToken = token,
                    id = groupId,
                    name = name,
                    description = description,
                    imageUrl = imageUrl,
                    memberIds = allMembers
                ).getOrThrow()
                val optimisticGroup = GroupEntity(
                    id = groupId,
                    name = name,
                    image = imageUrl,
                    description = description,
                    created_at = now,
                    creator_id = creatorId,
                    last_message = null,
                    last_message_at = null,
                    is_member = true,
                    is_deleted = false
                )
                groupDao.upsertGroup(optimisticGroup)
                groupDao.insertMembers(allMembers.map { uid ->
                    GroupMemberEntity(
                        group_id = groupId,
                        user_id = uid,
                        role = if (uid == creatorId.trim().lowercase()) "owner" else "member",
                        joined_at = now
                    )
                })
                return@withContext Result.success(groupId)
            }
            collectionCreationRemoteStore.createCollectionWithMemberships(
                collectionTable = "groups",
                collectionPayload = groupJson,
                membershipTable = "group_members",
                membershipPayloads = membershipPayloads
            ).getOrThrow()

            try {
                val optimisticGroup = GroupEntity(
                    id = groupId,
                    name = name,
                    image = imageUrl,
                    description = description,
                    created_at = now,
                    creator_id = creatorId,
                    last_message = null,
                    last_message_at = null,
                    is_member = true,
                    is_deleted = false
                )
                groupDao.upsertGroup(optimisticGroup)

                groupDao.insertMembers(allMembers.map { uid ->
                    conectate.com.data.local.entity.GroupMemberEntity(
                        group_id = groupId,
                        user_id = uid,
                        role = if (uid == creatorId.trim().lowercase()) "owner" else "member",
                        joined_at = now
                    )
                })
                android.util.Log.d("GroupRepository", "[GOD] Inserción local optimista exitosa para $groupId")
            } catch (e: Exception) {
                android.util.Log.e("GroupRepository", "Fallo inserción local optimista", e)
            }

            android.util.Log.d("GroupRepository", "[GOD] Creación completada. Realtime sincronizará automáticamente.")
            Result.success(groupId)
        } catch (e: Exception) {
            android.util.Log.e("GroupRepository", "[GOD] ERROR AL CREAR GRUPO", e)
            Result.failure(e)
        }
    }



    suspend fun getGroupMembers(groupId: String): Result<List<GroupMember>> = withContext(Dispatchers.IO) {
        try {
            val cleanGroupId = groupId.trim().lowercase()
            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val token = authService.getAccessTokenAwait()
                    ?: return@withContext Result.failure(Exception("Sesion Ubuntu no disponible"))
                val payload = localFallbackApiClient.loadGroupRoom(token, cleanGroupId).getOrThrow()
                val members = payload.participants
                    .mapNotNull { participant ->
                        val cleanUserId = participant.id.trim().lowercase()
                        if (cleanUserId.isBlank()) null else GroupMember(
                            group_id = cleanGroupId,
                            user_id = cleanUserId,
                            role = participant.role.ifBlank { "member" },
                            joined_at = participant.joinedAt.ifBlank { java.time.Instant.now().toString() },
                            userName = participant.name.ifBlank { null },
                            userImage = participant.avatarUrl.ifBlank { null }
                        )
                    }
                    .distinctBy { it.user_id }
                if (members.isNotEmpty()) {
                    groupDao.insertMembers(members.map {
                        GroupMemberEntity(
                            it.group_id.trim().lowercase(),
                            it.user_id.trim().lowercase(),
                            it.role,
                            it.joined_at
                        )
                    })
                    return@withContext Result.success(members)
                }
            }
            var response = supabaseConfig.client.postgrest["group_members"].select {
                filter { eq("group_id", groupId) }
            }.decodeList<GroupMember>()
            
            if (response.isEmpty()) return@withContext Result.success(emptyList())

            var profileMap = collectionMemberProfileStore.loadProfiles(response.map { it.user_id })
            val enrichedMembers = response.map { member ->
                var profile = profileMap[member.user_id.trim().lowercase()]
                member.copy(
                    userName = profile?.userName ?: member.userName ?: "Usuario",
                    userImage = profile?.userImage ?: member.userImage
                )
            }

            groupDao.insertMembers(enrichedMembers.map { 
                GroupMemberEntity(
                    it.group_id.trim().lowercase(),
                    it.user_id.trim().lowercase(),
                    it.role,
                    it.joined_at
                ) 
            })
            
            Result.success(enrichedMembers)
        } catch (e: Exception) {
            // Fallback to local
            var local = groupDao.getMembersFlow(groupId).first().map {
                GroupMember(it.group_id, it.user_id, it.role, it.joined_at)
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

    fun getMembersFlow(groupId: String): Flow<List<GroupMember>> {
        var cleanId = groupId.trim().lowercase()
        return groupDao.getMembersFlow(cleanId).map { list ->
            list.map { GroupMember(it.group_id, it.user_id, it.role, it.joined_at) }
        }
    }



    suspend fun leaveGroup(groupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
        if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
            val token = authService.getAccessTokenAwait()
                ?: return@withContext Result.failure(Exception("Sesion Ubuntu no disponible"))
            val cleanId = groupId.trim().lowercase()
            val cleanUserId = userId.trim().lowercase()
            localFallbackApiClient.leaveCollection(token, "groups", cleanId).getOrThrow()
            groupDao.removeMember(cleanId, cleanUserId)
            groupDao.updateMembership(cleanId, false)
            return@withContext Result.success(Unit)
        }
        CollectionMembershipMutationSupport.leaveCurrentUserCollection(
            scopeId = groupId,
            currentUserId = userId,
            applyLocalOptimistic = { cleanId, cleanUserId ->
                groupDao.removeMember(cleanId, cleanUserId)
                groupDao.updateMembership(cleanId, false)
            },
            remoteDelete = { cleanId, cleanUserId ->
                collectionAdminRemoteStore.deleteMember(
                    membershipTable = "group_members",
                    scopeColumn = "group_id",
                    scopeId = cleanId,
                    userId = cleanUserId
                )
            },
            applyLocalAfterRemote = { cleanId, cleanUserId ->
                groupDao.removeMember(cleanId, cleanUserId)
            }
        )
    }



    suspend fun addMember(groupId: String, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var me = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            var cleanId = groupId.trim().lowercase()
            var group = getGroupById(cleanId) ?: return@withContext Result.failure(Exception("Group not found"))
            var privileges = resolveGroupGovernancePrivileges(group, me)
            CollectionGovernanceSupport.requireCanManageMembers(
                privileges = privileges,
                errorMessage = "No tienes permisos para agregar miembros."
            )

            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val token = authService.getAccessTokenAwait()
                    ?: return@withContext Result.failure(Exception("Sesion Ubuntu no disponible"))
                val cleanUserId = userId.trim().lowercase()
                localFallbackApiClient.joinCollection(
                    accessToken = token,
                    collection = "groups",
                    roomId = cleanId,
                    userId = cleanUserId,
                    role = "member"
                ).getOrThrow()
                groupDao.insertMembers(
                    listOf(GroupMemberEntity(cleanId, cleanUserId, "member", java.time.Instant.now().toString()))
                )
                return@withContext Result.success(Unit)
            }

            collectionAdminRemoteStore.insertMember(
                membershipTable = "group_members",
                scopeColumn = "group_id",
                scopeId = cleanId,
                userId = userId,
                joinedAt = java.time.Instant.now().toString()
            ).getOrThrow()
            
            // Local update will be handled by realtime or refresh, but we can do it optimistically if needed
            // For now, let's rely on syncing
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    suspend fun removeMember(groupId: String, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var me = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            var cleanId = groupId.trim().lowercase()
            var targetUserId = userId.trim().lowercase()
            
            var group = getGroupById(cleanId) ?: return@withContext Result.failure(Exception("Group not found"))
            CollectionGovernanceSupport.requireTargetIsNotCreator(
                targetUserId = targetUserId,
                creatorId = group.creator_id,
                errorMessage = "No se puede eliminar al creador del grupo."
            )
            var privileges = resolveGroupGovernancePrivileges(group, me)
            CollectionGovernanceSupport.requireCanManageMembers(
                privileges = privileges,
                errorMessage = "No tienes permisos para eliminar miembros."
            )
            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val token = authService.getAccessTokenAwait() ?: return@withContext Result.failure(Exception("Sesion Ubuntu no disponible"))
                localFallbackApiClient.leaveCollection(token, "groups", cleanId, targetUserId).getOrThrow()
                groupDao.removeMember(cleanId, targetUserId)
                scope.launch { syncGroups() }
                return@withContext Result.success(Unit)
            }

            // Execute delete
            collectionAdminRemoteStore.deleteMember(
                membershipTable = "group_members",
                scopeColumn = "group_id",
                scopeId = cleanId,
                userId = targetUserId
            ).getOrThrow()
            
            // Remove locally
            groupDao.removeMember(cleanId, targetUserId)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    suspend fun deleteGroup(groupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            var cleanId = groupId.trim().lowercase()
            var group = getGroupById(cleanId)
            var privileges = group?.let { resolveGroupGovernancePrivileges(it, userId) }
            val isAuthorized = privileges?.canManageCollection == true

            if (authService.isLocalFallbackModeEnabled() && localFallbackApiClient.isConfigured) {
                val token = authService.getAccessTokenAwait()
                    ?: return@withContext Result.failure(Exception("Sesion Ubuntu no disponible"))
                if (isAuthorized) {
                    localFallbackApiClient.deleteCollection(token, "groups", cleanId).getOrThrow()
                } else {
                    localFallbackApiClient.leaveCollection(token, "groups", cleanId).getOrThrow()
                }
                groupDao.deleteGroup(cleanId)
                scope.launch { syncGroups() }
                return@withContext Result.success(Unit)
            }
            
            if (isAuthorized) {
                collectionAdminRemoteStore.softDeleteCollection(
                    collectionTable = "groups",
                    scopeId = cleanId
                ).getOrThrow()
            } else {
                // Si no soy admin, solo salgo del grupo localmente
                leaveGroup(cleanId)
            }
            
            // Borrado local en cualquier caso
            groupDao.deleteGroup(cleanId)
            
            // Sincronizar para asegurar consistencia
            scope.launch { syncGroups() }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun getMemberRole(groupId: String, userId: String): String? {
        if (authService.isLocalFallbackModeEnabled()) {
            return groupDao.getUserRoleSync(groupId.trim().lowercase(), userId.trim().lowercase())
        }
        return collectionAdminRemoteStore.getMemberRole(
            membershipTable = "group_members",
            scopeColumn = "group_id",
            scopeId = groupId,
            userId = userId
        )
    }

    suspend fun deleteGroupLocally(groupId: String) {
        var cleanId = groupId.trim().lowercase()
        groupDao.deleteGroup(cleanId)
    }



    suspend fun updateMemberRole(groupId: String, userId: String, newRole: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var me = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            var cleanId = groupId.trim().lowercase()
            var targetUserId = userId.trim().lowercase()
            
            var group = getGroupById(cleanId) ?: return@withContext Result.failure(Exception("Group not found"))
            CollectionGovernanceSupport.requireTargetIsNotCreator(
                targetUserId = targetUserId,
                creatorId = group.creator_id,
                errorMessage = "No se puede cambiar el rol del creador."
            )
            var privileges = resolveGroupGovernancePrivileges(group, me)
            CollectionGovernanceSupport.requireCanManageMembers(
                privileges = privileges,
                errorMessage = "No tienes permisos para gestionar roles."
            )

            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val token = authService.getAccessTokenAwait()
                    ?: return@withContext Result.failure(Exception("Sesion Ubuntu no disponible"))
                val cleanRole = newRole.trim().lowercase().takeIf { it == "admin" || it == "member" }
                    ?: return@withContext Result.failure(Exception("Rol inválido."))
                localFallbackApiClient.joinCollection(token, "groups", cleanId, targetUserId, cleanRole).getOrThrow()
                groupDao.insertMembers(
                    listOf(
                        GroupMemberEntity(
                            group_id = cleanId,
                            user_id = targetUserId,
                            role = cleanRole,
                            joined_at = java.time.Instant.now().toString()
                        )
                    )
                )
                scope.launch { syncGroups() }
                return@withContext Result.success(Unit)
            }

            collectionAdminRemoteStore.updateMemberRole(
                membershipTable = "group_members",
                scopeColumn = "group_id",
                scopeId = cleanId,
                userId = targetUserId,
                newRole = newRole
            ).getOrThrow()
            
            // Update local member cache
            getGroupMembers(cleanId)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateGroupInfo(
        groupId: String,
        name: String,
        description: String?,
        imageBytes: ByteArray?,
        onlyAdminsCanTalk: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var me = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            var cleanId = groupId.trim().lowercase()
            var group = getGroupById(cleanId) ?: return@withContext Result.failure(Exception("Group not found"))
            var privileges = resolveGroupGovernancePrivileges(group, me)
            CollectionGovernanceSupport.requireCanManageCollection(
                privileges = privileges,
                errorMessage = "No tienes permisos para editar el grupo."
            )
            var imageUrl: String? = null
            
            if (imageBytes != null) {
                var uploadResult = uploadGroupImage(cleanId, imageBytes)
                if (uploadResult.isSuccess) {
                    imageUrl = uploadResult.getOrNull()
                }
            }

            val updatePayload = buildJsonObject {
                put("name", name)
                put("description", description ?: "")
                put("only_admins_can_talk", onlyAdminsCanTalk)
                if (imageUrl != null) put("image", imageUrl)
            }
            collectionAdminRemoteStore.updateCollectionInfo(
                collectionTable = "groups",
                scopeId = cleanId,
                payload = updatePayload
            ).getOrThrow()
            
            syncGroups()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun resolveGroupGovernancePrivileges(
        group: Group,
        currentUserId: String
    ) = CollectionGovernanceSupport.resolvePrivileges(
        currentUserId = currentUserId,
        creatorId = group.creator_id,
        loadMemberRole = { normalizedUserId -> getMemberRole(group.id, normalizedUserId) },
        loadIsGlobalAdmin = { normalizedUserId ->
            userRepository.getUserById(normalizedUserId).getOrNull()?.is_admin == true
        }
    )

    suspend fun uploadGroupMedia(groupId: String, bytes: ByteArray, contentType: String): Result<String> =
        collectionMediaStore.uploadMessageMedia(
            ownerId = groupId,
            bytes = bytes,
            contentType = contentType,
            primaryBucket = "group-media",
            fallbackBucket = "groups",
            filePrefix = "media_group"
        )
}





