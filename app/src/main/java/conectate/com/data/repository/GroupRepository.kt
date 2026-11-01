package conectate.com.data.repository
import conectate.com.core.messaging.UnreadPolicy

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
import conectate.com.data.repository.collection.deleteCollectionMessage
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
import conectate.com.data.localfallback.UbuntuRealtimeClient
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
import conectate.com.data.sync.PendingMessageSyncScheduler
import conectate.com.domain.sync.GroupSyncDataSource
import conectate.com.util.ChatSoundPlayer
import conectate.com.util.buildCallEventPreview
import conectate.com.util.buildConversationBackgroundPreview
import conectate.com.util.ConversationNotificationPrefs
import conectate.com.util.isCallEventContentType
import conectate.com.util.isCollectionMembershipNotice
import conectate.com.util.MessageNotificationFactory
import conectate.com.util.MessageNotificationPayload
import conectate.com.util.PendingOutgoingMediaStore
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
    private val ubuntuRealtimeClient: UbuntuRealtimeClient,
    private val storageService: conectate.com.data.supabase.StorageService,
    private val moderationService: ContentModerationService,
    private val appMetrics: AppMetrics,
    private val operationGuard: OperationGuard,
    private val chatSoundPlayer: ChatSoundPlayer,
    @param:ApplicationContext private val context: Context,
    private val readJournal: conectate.com.data.repository.message.VisibleReadJournal = conectate.com.data.repository.message.VisibleReadJournal(context),
    private val readSynchronizer: conectate.com.data.repository.message.VisibleReadSynchronizer = conectate.com.data.repository.message.VisibleReadSynchronizer(readJournal, localFallbackApiClient, authService),
    private val chatImages: dagger.Lazy<MessageRepository>? = null,
) : GroupSyncDataSource {
    private val maxForwardTargetsPerBatch = 20
    private val deletedMessageStore by lazy { conectate.com.util.DeletedMessageStore(context) }

    suspend fun handleDeletedMessage(identity: conectate.com.util.DeletedMessageIdentity, createdAt: String? = null, channelId: String? = null) {
        if (identity.targetType != "group" || currentSessionUserId()?.trim()?.lowercase() != identity.accountId) return
        val session = captureSessionSnapshot(identity.accountId)
        if (!isSessionSnapshotValid(session, "deleted-message-start")) return
        conectate.com.util.MessageNotificationFactory.redactMessage(context, identity, createdAt, channelContextId = channelId)
        groupDao.redactDeletedMessage(identity.conversationId, identity.messageId)
        if (!isSessionSnapshotValid(session, "deleted-message-preview")) return
        groupDao.redactDeletedPreview(identity.conversationId, identity.messageId, createdAt)
        val local = groupDao.getMessageById(identity.messageId)?.toDomain()
        if (local != null && local.group_id == identity.conversationId && isSessionSnapshotValid(session, "deleted-message-flow")) {
            updateGroupMessageInFlow(identity.conversationId, deletedMessageStore.redact(local, identity.accountId))
        }
    }

    private var scope = newSafeScope("GroupRepository", Dispatchers.IO)
    private var json = Json { ignoreUnknownKeys = true }
    private val transientNetworkRetryCodes = listOf("timeout", "timed out", "connection", "network", "502", "503", "504")
    private val collectionAdminRemoteStore = CollectionAdminRemoteStore(supabaseConfig)
    private val collectionCreationRemoteStore = CollectionCreationRemoteStore(supabaseConfig)
    private val creationJournal = conectate.com.data.repository.collection.CollectionCreationJournal(context)
    private val collectionMediaStore = CollectionMediaStore(storageService)
    private val collectionMessageEngagementStore = CollectionMessageEngagementStore(supabaseConfig, json)
    private val collectionHistoryStore by lazy {
        conectate.com.data.localfallback.CollectionHistoryAccessStore(context)
    }
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

    private val messageSendCoordinator = conectate.com.data.repository.message.MessageSendCoordinator()

    private var realtimeJob: Job? = null
    private var groupListenerJob: Job? = null
    private var ubuntuRealtimeJob: Job? = null
    private var subscribedGroupIds: Set<String> = emptySet()
    private val activeGroupMessageJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private fun startRealtimeSubscriptions(userId: String) {
        if (authService.isLocalFallbackModeEnabled()) {
            startUbuntuRealtimeSubscriptions(userId)
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

    private fun startUbuntuRealtimeSubscriptions(userId: String) {
        val cleanUserId = userId.trim().lowercase()
        if (cleanUserId.isBlank()) return
        ubuntuRealtimeJob?.cancel()
        ubuntuRealtimeJob = scope.launch {
            val token = authService.getAccessTokenAwait()?.trim().orEmpty()
            if (token.isBlank()) return@launch
            ubuntuRealtimeClient.updateAccessToken(token)
            ubuntuRealtimeClient.replaceUserSubscription(cleanUserId)
            ubuntuRealtimeClient.events.collect { envelope ->
                if (envelope.channel != "user:$cleanUserId" || envelope.event != "message") return@collect
                val roomEventId = envelope.payload["chatId"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (!roomEventId.startsWith("room:group:")) return@collect
                val record = envelope.payload["message"]?.jsonObject ?: return@collect
                val message = runCatching { json.decodeFromJsonElement<GroupMessage>(record) }
                    .getOrElse { error ->
                        android.util.Log.e("GroupRepository", "Invalid Ubuntu group realtime payload", error)
                        return@collect
                    }
                val action = envelope.payload["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                if (action == "created" || action.isNullOrBlank()) {
                    handleIncomingMessage(message, cleanUserId)
                } else {
                    handleIncomingGroupUpdate(message, cleanUserId)
                }
            }
        }
    }

    private fun stopRealtimeSubscriptions() {
        realtimeJob?.cancel()
        realtimeJob = null
        groupListenerJob?.cancel()
        groupListenerJob = null
        ubuntuRealtimeJob?.cancel()
        ubuntuRealtimeJob = null
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
                MessageNotificationFactory.cancelConversation(context, conectate.com.util.NotificationAccountTarget(userId = authService.getCurrentUserId()), "group", cleanId)
            } catch (e: Exception) {
                android.util.Log.e("GroupRepository", "Error clearing notification", e)
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
                                val delayMs = minOf(1000L * (1 shl minOf(retryCount, 3)), 8000L)
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
        val msg = deletedMessageStore.redact(msg, sessionUserId)
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
                    val isNewMessage = existingMessage == null
                    if (!isNewMessage) {
                        android.util.Log.d("GroupRepository", "♻️ [DUPLICATE] Fusionando datos más completos para ${msg.id}.")
                    }

                    android.util.Log.d("GroupRepository", "📩 [MSG-VALID] Content='${msg.content.take(50)}...', Sender=${msg.sender_id}, Group=$cleanId")

                    // 4. Persistir Mensaje (Room)
                    var currentUserId = sessionSnapshot.accountId
                    
                    if (currentUserId != null && msg.sender_id.trim().lowercase() != currentUserId) {
                        scope.launch { markMessageAsDelivered(cleanId, msg.id) }
                    }

                    // Hydrate reply_to manually since Realtime payload allows it to be null/missing joins
                    val mergedMsg = mergeSyncedGroupMessageWithLocalState(
                        incoming = msg.copy(group_id = cleanId),
                        local = existingMessage?.toDomain()
                    )
                    val normalizedMsg = if (mergedMsg.reply_to_id != null && mergedMsg.reply_to == null) {
                        val reply = groupDao.getMessageById(mergedMsg.reply_to_id)?.toDomain()
                        mergedMsg.copy(reply_to = reply)
                    } else mergedMsg

                    val hydratedMsg = deletedMessageStore.redact(hydrateGroupMessage(normalizedMsg), currentUserId)

                    if (!isSessionSnapshotValid(sessionSnapshot, "group-insert-before-db:$cleanId")) return@withContext
                    groupDao.insertMessages(listOf(conectate.com.data.local.entity.GroupMessageEntity.fromDomain(hydratedMsg)))
                    android.util.Log.d("GroupRepository", "💾 [DB-SAVE] Mensaje ${msg.id} guardado en Room DB")
                    
                    // 5. Actualizar Flow Activo (Chat Screen)
                    if (!isSessionSnapshotValid(sessionSnapshot, "group-insert-before-flow:$cleanId")) return@withContext
                    updateGroupMessageInFlow(cleanId, hydratedMsg)
                    android.util.Log.d("GroupRepository", "🔄 [FLOW-UPDATE] Flow actualizado para $cleanId")
                    
                    if (hydratedMsg.deleted_for_all) {
                        conectate.com.util.DeletedMessageIdentity.create(currentUserId, "group", cleanId, hydratedMsg.id)?.let {
                            handleDeletedMessage(it, hydratedMsg.created_at)
                        }
                        return@withContext
                    }

                    // 6. Actualizar Metadatos del Grupo (Lista de Chats) y Notificar
                    var cleanSenderId = msg.sender_id.trim().lowercase()
                    val isMyMessage = cleanSenderId == currentUserId
                    
                    if (!isMyMessage && isNewMessage) {
                        // Count until the viewport confirms reading, even while the room is open.
                        val isGroupCurrentlyOpen = (cleanId == currentOpenedGroupId)
                        if (currentUserId != null && msg.id !in readJournal.seenIds(currentUserId, "group", cleanId) &&
                            UnreadPolicy.isEligibleStatusForReadTransition(msg.status) &&
                            !conectate.com.util.ConversationReadStore(context).isRead(currentUserId,"group",cleanId,msg.id,msg.created_at)) {
                            if (!isSessionSnapshotValid(sessionSnapshot, "group-insert-before-unread:$cleanId")) return@withContext
                            groupDao.incrementUnreadCount(cleanId)
                        }
                        
                        if (!isGroupCurrentlyOpen) {
                            android.util.Log.d("GroupRepository", "🔔 [UNREAD+1] Incrementando contador para $cleanId (grupo cerrado)")
                            if (!isSessionSnapshotValid(sessionSnapshot, "group-insert-before-unread:$cleanId")) return@withContext
                            if (!isCallEventContentType(msg.content_type) && !isCollectionMembershipNotice(msg.content_type)) {
                                showNotification(msg, localGroup.name)
                            }
                        } else {
                            if (!isCallEventContentType(msg.content_type) && !isCollectionMembershipNotice(msg.content_type)) {
                                chatSoundPlayer.playReceive()
                            }
                        }
                    } else {
                        android.util.Log.d("GroupRepository", "✍️ [MY-MSG] Mensaje enviado por mí. No incrementar unread.")
                    }
                    
                    // Actualizar last_message siempre (para todos los mensajes, incluso los míos)
                    if (!isSessionSnapshotValid(sessionSnapshot, "group-insert-before-metadata:$cleanId")) return@withContext
                    deletedMessageStore.recordSummary(currentUserId, "group", cleanId, hydratedMsg.id, hydratedMsg.created_at)
                    groupDao.updateMetadata(cleanId, 
                        buildConversationBackgroundPreview(msg.content_type, msg.content)
                            ?: buildCallEventPreview(msg.content_type, msg.content)
                            ?: CollectionMessageSyncSupport.buildMetadataPreview(msg.content_type, msg.content),
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
            if (msg.deleted_for_all) conectate.com.util.DeletedMessageIdentity.create(sessionSnapshot.accountId, "group", msg.group_id, msg.id)?.let {
                handleDeletedMessage(it, msg.created_at)
            }
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
                    val finalMsg = deletedMessageStore.redact(currentDomain.copy(
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
                    ), sessionSnapshot.accountId)
                    
                    if (!isSessionSnapshotValid(sessionSnapshot, "group-update-before-db:${msg.id}")) return@withContext
                    groupDao.insertMessages(listOf(conectate.com.data.local.entity.GroupMessageEntity.fromDomain(finalMsg)))
                    if (!isSessionSnapshotValid(sessionSnapshot, "group-update-before-flow:${msg.id}")) return@withContext
                    updateGroupMessageInFlow(finalMsg.group_id, finalMsg)
                    if (finalMsg.deleted_for_all) conectate.com.util.DeletedMessageIdentity.create(sessionSnapshot.accountId, "group", finalMsg.group_id, finalMsg.id)?.let {
                        handleDeletedMessage(it, finalMsg.created_at)
                    }
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
        if (isCollectionMembershipNotice(msg.content_type)) return
        val receivingAccount = conectate.com.util.NotificationAccountTarget(userId = authService.getCurrentUserId(), email = authService.getCurrentUserEmail())
        val photos = withContext(Dispatchers.IO) {
            if (!receivingAccount.matches(authService.getCurrentUserId(), authService.getCurrentUserEmail())) return@withContext null
            kotlinx.coroutines.coroutineScope {
                val person = async { conectate.com.core.media.NotificationAvatarLoader.senderPhoto(context, userRepository, msg.sender_id) }
                val room = async {
                    val image = conectate.com.core.media.NotificationAvatarLoader.optionalValue {
                        groupDao.getGroupById(msg.group_id.trim().lowercase())?.image
                    }
                    conectate.com.core.media.NotificationAvatarLoader.load(context, image)
                }
                person.await() to room.await()
            }
        }
        withContext(Dispatchers.Main) {
            if (!receivingAccount.matches(authService.getCurrentUserId(), authService.getCurrentUserEmail())) return@withContext
            try {
                var cleanId = msg.group_id.trim().lowercase()
                if (cleanId == currentOpenedGroupId) return@withContext
                val isMention = msg.mentioned_user_ids.any { it.equals(receivingAccount.userId, true) }
                if (!isMention && ConversationNotificationPrefs(context).isMuted("group", cleanId, authService.getCurrentUserId())) {
                    android.util.Log.d("GroupRepository", "🔕 [NOTIFICATION] Group $cleanId muted locally. Skipping notification.")
                    return@withContext
                }

                var notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                var channelId = "group_messages_channel_v2"
                val previewText = buildConversationBackgroundPreview(msg.content_type, msg.content)
                    ?: buildCallEventPreview(msg.content_type, msg.content)
                    ?: if (msg.content_type == "text") msg.content else "📎 Multimedia"
                MessageNotificationFactory.post(
                    context = context,
                    payload = MessageNotificationPayload(
                        channelId = channelId,
                        channelName = "Mensajes de Grupos",
                        channelDescription = "Notificaciones de mensajes en grupos",
                        conversationId = cleanId,
                        targetType = "group",
                        conversationTitle = groupName,
                        senderName = msg.sender_name ?: "Alguien",
                        messageBody = conectate.com.util.mentionNotificationPreview(previewText,
                            msg.mentioned_user_ids.any { it.equals(receivingAccount.userId, true) }),
                        notificationId = cleanId.hashCode(),
                        senderId = msg.sender_id,
                        messageId = msg.id,
                        largeIcon = photos?.first,
                        conversationIcon = photos?.second,
                        isGroupConversation = true,
                        notificationAccountTarget = receivingAccount
                    )
                )

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
                        .combine(deletedMessageStore.changes) { entities, _ -> entities }
                        .map { entities ->
                            entities
                                .filter { !it.is_deleted }
                                .map { entity ->
                                    GroupWithLastMessage(
                                        group = entity.toDomain().copy(is_member = groupDao.getUserRoleSync(entity.id, activeUserId) != null, last_message = deletedMessageStore.redactSummary(activeUserId, "group", entity.id, entity.last_message, entity.last_message_at)),
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
    


    private suspend fun cachedHistoryAccess(roomId: String, account: String): conectate.com.data.localfallback.CollectionHistoryAccess {
        val room = groupDao.getGroupById(roomId)
        return conectate.com.data.localfallback.resolveCachedCollectionHistory(
            cached = collectionHistoryStore.load(account, "group", roomId),
            joinedAt = groupDao.getMemberJoinedAt(roomId, account),
            isCreator = room?.creator_id?.equals(account, ignoreCase = true) == true && groupDao.getUserRoleSync(roomId, account) != null,
            isMember = groupDao.getUserRoleSync(roomId, account) != null
        )
    }

    suspend fun getCachedMessages(groupId: String): List<GroupMessage> = withContext(Dispatchers.IO) {
        var cleanId = groupId.trim().lowercase()
        if (cleanId.isBlank()) return@withContext emptyList()
        val account = currentSessionUserId() ?: return@withContext emptyList()
        val access = if (authService.isLocalFallbackSession()) cachedHistoryAccess(cleanId, account) else null
        val localMessages = groupDao.getMessagesSync(cleanId).filter {
            access == null || access.allows(it.created_at) || (!it.is_synced && it.pending_action == "send")
        }.map { it.toDomain() }
        unifyGroupMessages(localMessages, account)
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
                        val activeUserId = currentSessionUserId() ?: return@collect
                        val access = if (authService.isLocalFallbackSession()) cachedHistoryAccess(cleanId, activeUserId) else null
                        val pendingIds = entities.filter { !it.is_synced && it.pending_action == "send" }.map { it.id }.toSet()
                        val localMessages = entities.filter { access == null || access.allows(it.created_at) || it.id in pendingIds }.map { it.toDomain() }
                        state.update { current ->
                            unifyGroupMessages(localMessages + current.filter { access == null || access.allows(it.created_at) || it.id in pendingIds }, activeUserId)
                        }
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

    private fun acknowledgeGroupMessage(
        message: GroupMessage,
        groupId: String = message.group_id
    ): GroupMessage {
        return message.copy(
            group_id = groupId.trim().lowercase(),
            status = MessageStatusNormalizer.acknowledgedAfterSend(
                status = message.status,
                deliveredAt = message.delivered_at,
                readAt = message.read_at
            )
        )
    }

    private suspend fun markGroupMessageAcknowledged(messageId: String) {
        val existing = groupDao.getMessageById(messageId) ?: return
        val acknowledged = acknowledgeGroupMessage(existing.toDomain())
        groupDao.insertMessages(
            listOf(
                GroupMessageEntity.fromDomain(
                    msg = acknowledged,
                    isSynced = true,
                    pendingAction = null,
                    retryCount = existing.retry_count,
                    lastRetryAt = existing.last_retry_at
                )
            )
        )
        updateGroupMessageInFlow(acknowledged.group_id, acknowledged)
    }

    private fun mergeSyncedGroupMessageWithLocalState(
        incoming: GroupMessage,
        local: GroupMessage?
    ): GroupMessage {
        val wasRead = conectate.com.util.ConversationReadStore(context).isRead(currentSessionUserId(),"group",incoming.group_id,incoming.id,incoming.created_at)
        local ?: return deletedMessageStore.redact(if (wasRead) incoming.copy(status="read") else incoming, currentSessionUserId())
        var mergedReceipt = mergeGroupReceiptState(
            currentStatus = if (wasRead) "read" else local.status,
            currentDeliveredAt = local.delivered_at,
            currentReadAt = local.read_at,
            incomingStatus = incoming.status,
            incomingDeliveredAt = incoming.delivered_at,
            incomingReadAt = incoming.read_at
        )
        return deletedMessageStore.redact(incoming.copy(
            group_id = incoming.group_id.ifBlank { local.group_id },
            sender_id = incoming.sender_id.ifBlank { local.sender_id },
            content = incoming.content.ifBlank { local.content },
            media_url = incoming.media_url ?: local.media_url,
            poll_id = incoming.poll_id ?: local.poll_id,
            created_at = incoming.created_at.ifBlank { local.created_at },
            sender_name = sanitizeMemberSenderName(incoming.sender_name)
                ?: sanitizeMemberSenderName(local.sender_name)
                ?: incoming.sender_name,
            sender_image = sanitizeMemberSenderImage(incoming.sender_image)
                ?: sanitizeMemberSenderImage(local.sender_image)
                ?: incoming.sender_image,
            reply_to_id = incoming.reply_to_id ?: local.reply_to_id,
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
        ), currentSessionUserId())
    }

    private fun unifyGroupMessages(messages: List<GroupMessage>, currentUserId: String?): List<GroupMessage> {
        if (messages.isEmpty()) return emptyList()
        val filtered = messages.filter { msg ->
            if (currentUserId != null) {
                !msg.deleted_by.contains(currentUserId.trim().lowercase())
            } else true
        }
        
        val mergedById = filtered
            .groupBy { it.id.trim().lowercase() }
            .values
            .map { variations ->
                variations.drop(1).fold(variations.first()) { local, incoming ->
                    mergeSyncedGroupMessageWithLocalState(incoming, local)
                }
            }

        // [FIX] Hydrate reply_to relations
        val msgMap = mergedById.associateBy { it.id }
        val hydrated = mergedById.map { msg ->
            if (msg.reply_to_id != null && msg.reply_to == null) {
                msg.copy(reply_to = msgMap[msg.reply_to_id])
            } else msg
        }

        return hydrated.map { deletedMessageStore.redact(it, currentUserId) }.sortedBy { it.created_at } // Sort by created_at explicitly as backup
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
            val session = captureSessionSnapshot(currentUserId)
            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val incomingIds = groupDao.getMessagesSync(cleanId).filter {
                    it.id in cleanMessageIds && it.sender_id.trim().lowercase() != currentUserId &&
                        conectate.com.core.messaging.UnreadPolicy.isEligibleStatusForReadTransition(it.status)
                }.map { it.id }
                if (!isSessionSnapshotValid(session, "group-visible-local")) return@withContext
                val newlySeen = readJournal.record(currentUserId, "group", cleanId, incomingIds)
                groupDao.markMessagesAsReadByIds(cleanId, now, currentUserId, incomingIds)
                groupDao.subtractReadMessages(cleanId, newlySeen.size)
                _activeGroupMessages[cleanId]?.update { messages -> messages.map {
                    if (it.id in incomingIds) it.copy(status = "read", read_at = now) else it
                } }
                if (!token.isNullOrBlank()) readSynchronizer.flush(currentUserId, "group", token)
                return@withContext
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
            if (!isSessionSnapshotValid(session, "group-visible-persist")) return@withContext
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



    suspend fun markAsRead(
        groupId: String,
        requireRemoteConfirmation: Boolean = false,
        expectedAccountId: String? = null
    ) {
        val cleanId = groupId.trim().lowercase()
        withContext(Dispatchers.IO) {
            val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: run {
                    if (requireRemoteConfirmation) throw CancellationException("No active read receipt session")
                    return@withContext
                }
            val session = captureSessionSnapshot(expectedAccountId ?: currentUserId)
            val token = authService.getAccessToken()
            val now = java.time.Instant.now().toString()
            conectate.com.data.repository.collection.applyCollectionReadReceipt(
                requireRemoteConfirmation = requireRemoteConfirmation,
                isSessionCurrent = { isSessionSnapshotValid(session, "group-notification-read") },
                synchronize = {
                    if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured && !token.isNullOrBlank()) {
                        localFallbackApiClient.markCollectionAsRead(token, "groups", cleanId)
                            .onFailure { android.util.Log.w("GroupRepository", "Ubuntu mark group read failed for $cleanId", it) }.isSuccess
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
                    }.getOrDefault(false)
                },
                applyLocal = {
                    groupDao.markAllAsRead(cleanId, now, currentUserId)
                    if (!isSessionSnapshotValid(session, "group-read-before-unread"))
                        throw CancellationException("Read receipt session changed")
                    groupDao.resetUnreadCount(cleanId)
                    if (!requireRemoteConfirmation && isSessionSnapshotValid(session, "group-read-before-cancel")) {
                        runCatching {
                            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            MessageNotificationFactory.cancelConversation(context, conectate.com.util.NotificationAccountTarget(userId = authService.getCurrentUserId()), "group", cleanId)
                        }
                    }
                }
            )
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
            val inserted = acknowledgeGroupMessage(remote.getOrThrow(), cleanId)
            groupDao.insertMessages(listOf(GroupMessageEntity.fromDomain(inserted, isSynced = true, pendingAction = null)))
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
        replyTo: conectate.com.data.model.Message? = null,
        contentType: String = "text",
        mediaUrl: String? = null,
        pollId: String? = null,
        isForwarded: Boolean = false,
        forwardedFromMessageId: String? = null,
        forwardedFromUserId: String? = null,
        messageId: String? = null,
        requireServerAcknowledgement: Boolean = false,
        onQueued: suspend () -> Unit = {}
    ): Result<Unit> {
        val id = messageId?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
        val account = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: return Result.failure(Exception("Inicia sesión de nuevo."))
        val session = captureSessionSnapshot(account)
        return messageSendCoordinator.withMessage(id) {
            if (!isSessionSnapshotValid(session, "collection-foreground-send")) {
                Result.failure(IllegalStateException("La cuenta cambió durante el envío."))
            } else sendGroupMessageInternal(
                groupId = groupId,
                content = content,
                replyToId = replyToId,
                replyTo = replyTo,
                contentType = contentType,
                mediaUrl = mediaUrl,
                pollId = pollId,
                isForwarded = isForwarded,
                forwardedFromMessageId = forwardedFromMessageId,
                forwardedFromUserId = forwardedFromUserId,
                messageId = id,
                onQueued = onQueued
            ).let { result ->
                if (result.isFailure || !requireServerAcknowledgement) result else {
                    val persisted = groupDao.getMessageById(id)
                    if (!isSessionSnapshotValid(session, "collection-share-confirmation")) {
                        Result.failure(IllegalStateException("La cuenta cambió durante el envío."))
                    } else conectate.com.core.messaging.messageSendConfirmation(
                        synced = persisted?.is_synced == true,
                        status = persisted?.status.orEmpty(),
                        pendingAction = persisted?.pending_action,
                        deletedForAll = persisted?.deleted_for_all == true,
                    )
                }
            }
        }
    }

    private suspend fun sendGroupMessageInternal(
        groupId: String,
        content: String,
        replyToId: String? = null,
        replyTo: conectate.com.data.model.Message? = null,
        contentType: String = "text",
        mediaUrl: String? = null,
        pollId: String? = null,
        isForwarded: Boolean = false,
        forwardedFromMessageId: String? = null,
        forwardedFromUserId: String? = null,
        messageId: String? = null,
        onQueued: suspend () -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            var userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("No user"))
            val session = captureSessionSnapshot(userId)
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
            val finalContent = if (contentType == "contact") {
                sanitizedContent
            } else {
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
                moderation.sanitizedText
            }
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
                reply_to = replyTo?.let {
                    GroupMessage(
                        id = it.id,
                        group_id = cleanId,
                        sender_id = it.sender_id,
                        content = it.content,
                        content_type = it.content_type,
                        media_url = it.media_url,
                        poll_id = it.poll_id,
                        created_at = it.created_at.orEmpty(),
                        status = it.status,
                        sender_name = it.sender_name,
                        reply_to_id = it.reply_to_id
                    )
                },
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
            PendingMessageSyncScheduler.enqueue(context, "group_message_pending")
            updateGroupMessageInFlow(cleanId, msg)
            onQueued()
            if (!isSessionSnapshotValid(session, "group-foreground-resume")) return@withContext Result.failure(IllegalStateException("La cuenta cambió durante el envío."))
            val preview = buildConversationBackgroundPreview(contentType, finalContent)
                ?: buildCallEventPreview(contentType, finalContent)
                ?: CollectionMessageSyncSupport.buildMetadataPreview(contentType, finalContent)
            groupDao.updateMetadataIfNewer(cleanId, preview, now)

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

            var outgoingMediaUrl = mediaUrl
            PendingOutgoingMediaStore.resolve(context, mediaUrl)?.let { localMediaUri ->
                val uploadResult = collectionMediaStore.uploadMessageMediaFromUri(
                    context = context,
                    ownerId = cleanId,
                    sourceUri = localMediaUri,
                    preferredMediaType = contentType,
                    primaryBucket = "group-media",
                    fallbackBucket = "groups",
                    filePrefix = "media_group",
                    accountId = userId,
                    messageId = msgId
                )
                outgoingMediaUrl = uploadResult.getOrElse { error ->
                    val retryable = CollectionRepositorySupport.isRetryableNetworkError(
                        error as? Exception ?: Exception(error),
                        transientNetworkRetryCodes
                    )
                    markPendingGroupMessageFailed(msgId, session)
                    if (retryable) return@withContext Result.success(Unit)
                    groupDao.updateMessageSyncStatus(msgId, status = "failed", isSynced = false, pendingAction = "send")
                    return@withContext Result.failure(error)
                }
                if (!isSessionSnapshotValid(session, "group-foreground-resume")) return@withContext Result.failure(IllegalStateException("La cuenta cambió durante el envío."))
                msg = msg.copy(media_url = outgoingMediaUrl)
                groupDao.insertMessages(
                    listOf(GroupMessageEntity.fromDomain(msg, isSynced = false, pendingAction = "send"))
                )
                updateGroupMessageInFlow(cleanId, msg)
                PendingOutgoingMediaStore.delete(context, localMediaUri)
            }

            // 2. Send to Supabase and return real status to the UI.
            val msgJson = buildJsonObject {
                put("id", msgId)
                put("group_id", cleanId)
                put("sender_id", userId)
                put("content", finalContent)
                put("content_type", contentType)
                put("media_url", outgoingMediaUrl)
                put("reply_to_id", replyToId)
                put("poll_id", pollId)
                put("is_forwarded", isForwarded)
                put("forwarded_from_message_id", forwardedFromMessageId)
                put("forwarded_from_user_id", forwardedFromUserId)
            }

            val syncResult = if (authService.isLocalFallbackSession()) {
                val token = authService.getAccessTokenAwait()
                if (!isSessionSnapshotValid(session, "group-foreground-resume")) return@withContext Result.failure(IllegalStateException("La cuenta cambió durante el envío."))
                if (token.isNullOrBlank()) {
                    Result.failure(Exception("Sesion Ubuntu no disponible"))
                } else {
                    localFallbackApiClient.sendGroupMessage(
                        accessToken = token,
                        groupId = cleanId,
                        content = finalContent,
                        contentType = contentType,
                        mediaUrl = outgoingMediaUrl,
                        mediaSize = 0L,
                        replyToId = replyToId,
                        messageId = msgId,
                        isForwarded = isForwarded,
                        forwardedFromMessageId = forwardedFromMessageId,
                        forwardedFromUserId = forwardedFromUserId
                    ).map { serverMessage ->
                        if (!isSessionSnapshotValid(session, "group-foreground-resume")) return@withContext Result.failure(IllegalStateException("La cuenta cambió durante el envío."))
                        val synced = mergeSyncedGroupMessageWithLocalState(
                            incoming = acknowledgeGroupMessage(serverMessage, cleanId).copy(
                                reply_to_id = serverMessage.reply_to_id ?: msg.reply_to_id,
                                reply_to = serverMessage.reply_to ?: msg.reply_to
                            ),
                            local = msg
                        )
                        groupDao.insertMessages(
                            listOf(
                                GroupMessageEntity.fromDomain(
                                    msg = synced,
                                    isSynced = true,
                                    pendingAction = null
                                )
                            )
                        )
                        groupDao.updateMetadataIfNewer(
                            cleanId,
                            CollectionMessageSyncSupport.buildMetadataPreview(synced.content_type, synced.content),
                            synced.created_at
                        )
                        updateGroupMessageInFlow(cleanId, synced)
                        Unit
                    }
                }
            } else {
                insertGroupMessageWithRetry(msgId, msgJson, isCurrentSession = { isSessionSnapshotValid(session, "group-foreground-retry") })
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
                val shouldRemainQueued = syncError?.message?.contains("Sesion Ubuntu no disponible", ignoreCase = true) == true ||
                    (syncError != null && CollectionRepositorySupport.isRetryableNetworkError(
                        syncError as? Exception ?: Exception(syncError),
                        transientNetworkRetryCodes
                    ))
                if (shouldRemainQueued) {
                    groupDao.updateMessageSyncStatus(msgId, status = "sending", isSynced = false, pendingAction = "send")
                    groupDao.getMessageById(msgId)?.toDomain()?.let { queued -> updateGroupMessageInFlow(cleanId, queued) }
                    runCatching {
                        operationGuard.audit(
                            action = GuardedAction.GROUP_MESSAGE_SEND,
                            actorId = userId,
                            scopeId = cleanId,
                            outcome = GuardOutcome.QUEUED,
                            attributes = mapOf("message_id" to msgId, "reason" to "network_unavailable")
                        )
                    }
                    return@withContext Result.success(Unit)
                }
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
                markPendingGroupMessageFailed(msgId, session)
                Result.failure(syncError ?: Exception("No se pudo sincronizar mensaje de grupo"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("GroupRepository", "Error sending message", e)
            appMetrics.recordFailure("group_message_send", e)
            Result.failure(e)
        }
    }

    private suspend fun insertGroupMessageWithRetry(
        msgId: String,
        payload: JsonObject,
        maxAttempts: Int = 4,
        isCurrentSession: () -> Boolean = { true }
    ): Result<Unit> {
        return CollectionPendingMessageRetrySupport.retryPendingMessageSend(
            messageId = msgId,
            maxAttempts = maxAttempts,
            sendAction = {
                if (!isCurrentSession()) Result.failure(IllegalStateException("La cuenta cambió durante el envío"))
                else sendGroupMessageSecureOrFallback(msgId = msgId, payload = payload)
            },
            isDuplicateKeyError = CollectionRepositorySupport::isDuplicateKeyError,
            isRetryableNetworkError = { error -> CollectionRepositorySupport.isRetryableNetworkError(error, transientNetworkRetryCodes) },
            onConfirmedSuccess = { if (isCurrentSession()) markGroupMessageAcknowledged(msgId) },
            onDuplicateTreatedAsSuccess = { if (isCurrentSession()) markGroupMessageAcknowledged(msgId) },
            onTerminalFailure = {
                if (isCurrentSession()) {
                    groupDao.markRetryAttempt(msgId, lastRetryAt = java.time.Instant.now().toString(), status = "failed")
                    groupDao.updateMessageSyncStatus(msgId, status = "failed", isSynced = false, pendingAction = "send")
                    groupDao.getMessageById(msgId)?.toDomain()?.let { failed -> updateGroupMessageInFlow(failed.group_id, failed) }
                }
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

    suspend fun retryMessage(messageId: String, groupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val actor = authService.getCurrentUserId()?.trim()?.lowercase() ?: error("Inicia sesión de nuevo.")
            val session = captureSessionSnapshot(actor)
            val pending = groupDao.getMessageById(messageId) ?: error("El mensaje ya no está disponible.")
            check(pending.sender_id.equals(actor, true) && pending.group_id.equals(groupId, true) && !pending.deleted_for_all) {
                "No puedes reenviar este mensaje."
            }
            if (pending.is_synced) return@runCatching
            check(isSessionSnapshotValid(session, "group-manual-retry")) { "La cuenta cambió." }
            retryPendingGroupMessages(targetMessageId = messageId)
            check(isSessionSnapshotValid(session, "group-manual-retry-complete")) { "La cuenta cambió." }
            val latest = groupDao.getMessageById(messageId) ?: error("El mensaje ya no está disponible.")
            check(latest.status != "failed") { "No se pudo reenviar. Revisa tu conexión e inténtalo de nuevo." }
        }.onFailure { if (it is CancellationException) throw it }
    }


    private suspend fun markPendingGroupMessageFailed(messageId: String, session: SessionWriteGuard.Snapshot) {
        if (!isSessionSnapshotValid(session, "group-pending-failure")) return
        val current = groupDao.getMessageById(messageId) ?: return
        if (current.is_synced || current.deleted_for_all || current.pending_action != "send") return
        val failed = current.copy(status = "failed", retry_count = current.retry_count + 1, last_retry_at = java.time.Instant.now().toString())
        groupDao.insertMessages(listOf(failed))
        updateGroupMessageInFlow(failed.group_id, failed.toDomain())
    }
    private suspend fun retryPendingGroupMessages(maxBatch: Int = 40, targetMessageId: String? = null) {
        var userId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return
        val session = captureSessionSnapshot(userId)
        val pending = if (targetMessageId == null) groupDao.getPendingOutgoingMessages(userId, maxBatch)
            else listOfNotNull(groupDao.getMessageById(targetMessageId)).filter { !it.is_synced && it.pending_action == "send" }
        if (!isSessionSnapshotValid(session, "group-pending-loaded")) return
        if (pending.isEmpty()) return

        pending.forEach { originalEntity ->
            if (!isSessionSnapshotValid(session, "group-pending-start")) return
            if (originalEntity.sender_id.trim().lowercase() != userId) return@forEach
            messageSendCoordinator.withMessage(originalEntity.id) pendingMessage@ {
                val fresh = groupDao.getMessageById(originalEntity.id) ?: return@pendingMessage
                if (fresh.is_synced || fresh.pending_action != "send" || fresh.deleted_for_all || !fresh.sender_id.trim().equals(userId, true)) return@pendingMessage
                if (!isSessionSnapshotValid(session, "group-pending-locked")) return@pendingMessage
                var pendingEntity = fresh.copy(status = "sending")
                groupDao.insertMessages(listOf(pendingEntity))
                updateGroupMessageInFlow(pendingEntity.group_id, pendingEntity.toDomain())
                PendingOutgoingMediaStore.resolve(context, pendingEntity.media_url)?.let { localMediaUri ->
                    val remoteUrl = collectionMediaStore.uploadMessageMediaFromUri(
                        context = context,
                        ownerId = pendingEntity.group_id,
                        sourceUri = localMediaUri,
                        preferredMediaType = pendingEntity.content_type,
                        primaryBucket = "group-media",
                        fallbackBucket = "groups",
                        filePrefix = "media_group",
                        accountId = userId,
                        messageId = pendingEntity.id
                    ).getOrElse { error ->
                        if (error is CancellationException) throw error
                        markPendingGroupMessageFailed(pendingEntity.id, session)
                        android.util.Log.w("GroupRepository", "Pending group media still unavailable: ${pendingEntity.id}", error)
                        return@pendingMessage
                    }
                    if (!isSessionSnapshotValid(session, "group-pending-media-uploaded")) return@pendingMessage
                    pendingEntity = pendingEntity.copy(media_url = remoteUrl)
                    groupDao.insertMessages(listOf(pendingEntity))
                    if (!isSessionSnapshotValid(session, "group-pending-media-persisted")) return@pendingMessage
                    updateGroupMessageInFlow(pendingEntity.group_id, pendingEntity.toDomain())
                    PendingOutgoingMediaStore.delete(context, localMediaUri)
                }

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
                        if (!isSessionSnapshotValid(session, "group-pending-token")) return@pendingMessage
                        localFallbackApiClient.sendGroupMessage(
                            accessToken = token,
                            groupId = pendingEntity.group_id,
                            content = pendingEntity.content,
                            contentType = pendingEntity.content_type,
                            mediaUrl = pendingEntity.media_url,
                            mediaSize = 0L,
                            replyToId = pendingEntity.reply_to_id,
                            messageId = pendingEntity.id,
                            isForwarded = pendingEntity.is_forwarded,
                            forwardedFromMessageId = pendingEntity.forwarded_from_message_id,
                            forwardedFromUserId = pendingEntity.forwarded_from_user_id
                        ).onSuccess { serverMessage ->
                            if (!isSessionSnapshotValid(session, "group-pending-acknowledge")) return@onSuccess
                            val synced = mergeSyncedGroupMessageWithLocalState(
                                incoming = acknowledgeGroupMessage(serverMessage, pendingEntity.group_id),
                                local = pendingEntity.toDomain()
                            )
                            groupDao.insertMessages(
                                listOf(
                                    GroupMessageEntity.fromDomain(
                                        msg = synced,
                                        isSynced = true,
                                        pendingAction = null,
                                        retryCount = pendingEntity.retry_count,
                                        lastRetryAt = pendingEntity.last_retry_at
                                    )
                                )
                            )
                            if (!isSessionSnapshotValid(session, "group-pending-acknowledged")) return@onSuccess
                            updateGroupMessageInFlow(pendingEntity.group_id, synced)
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            markPendingGroupMessageFailed(pendingEntity.id, session)
                        }
                    } else {
                        markPendingGroupMessageFailed(pendingEntity.id, session)
                    }
                } else {
                    insertGroupMessageWithRetry(
                        msgId = pendingEntity.id,
                        payload = payload,
                        isCurrentSession = { isSessionSnapshotValid(session, "group-pending-retry") }
                    )
                }
            }
        }
    }



    suspend fun editGroupMessage(messageId: String, newContent: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("No user"))
            val normalizedContent = newContent.trim()
            if (normalizedContent.isBlank()) return@withContext Result.failure(Exception("El mensaje no puede estar vacío"))
            val existing = groupDao.getMessageById(messageId)?.toDomain()

            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val token = authService.getAccessTokenAwait()
                    ?: return@withContext Result.failure(Exception("No token"))
                val groupId = existing?.group_id?.trim()?.lowercase()
                    ?: return@withContext Result.failure(Exception("Mensaje no encontrado"))
                localFallbackApiClient.editCollectionMessage(
                    accessToken = token,
                    collection = "groups",
                    roomId = groupId,
                    messageId = messageId,
                    content = normalizedContent
                ).getOrElse { return@withContext Result.failure(it) }

                val updated = existing.copy(content = normalizedContent, is_edited = true)
                groupDao.insertMessages(listOf(GroupMessageEntity.fromDomain(updated)))
                updateGroupMessageInFlow(groupId, updated)
                return@withContext Result.success(Unit)
            }

            supabaseConfig.client.postgrest["group_messages"].update({
                set("content", normalizedContent)
                set("is_edited", true)
            }) {
                filter {
                    eq("id", messageId)
                    eq("sender_id", userId)
                }
            }
            
            // Optimistic update
            var existingEntity = groupDao.getMessageById(messageId)
            if (existingEntity != null) {
                var updated = existingEntity.toDomain().copy(content = normalizedContent, is_edited = true)
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
            val userId = authService.getCurrentUserId()
                ?: return@withContext Result.failure(Exception("Inicia sesión de nuevo."))
            val normalizedUserId = userId.trim().lowercase()
            val session = captureSessionSnapshot(normalizedUserId)
            val useLocalApi = authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured
            // Resolve credentials before hiding the message; an expired session must leave it visible.
            val token = if (useLocalApi) authService.getAccessTokenAwait()
                ?: return@withContext Result.failure(Exception("No se pudo renovar la sesión.")) else null
            deleteCollectionMessage(
                isCurrentSession = { isSessionSnapshotValid(session, "group-delete") },
                load = { groupDao.getMessageById(messageId)?.toDomain() },
                markDeleted = { message ->
                    check(!forEveryone || message.sender_id.trim().lowercase() == normalizedUserId) {
                        "Solo puedes eliminar para todos tus propios mensajes"
                    }
                    if (forEveryone) message.copy(deleted_for_all = true)
                    else message.copy(deleted_by = (message.deleted_by + normalizedUserId)
                        .map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct())
                },
                save = { message ->
                    if (!isSessionSnapshotValid(session, "group-delete-save")) throw CancellationException("La cuenta cambió.")
                    groupDao.insertMessages(listOf(GroupMessageEntity.fromDomain(message)))
                    if (!isSessionSnapshotValid(session, "group-delete-publish")) throw CancellationException("La cuenta cambió.")
                    updateGroupMessageInFlow(message.group_id, message)
                },
                onOptimistic = { message ->
                    conectate.com.util.DeletedMessageIdentity.create(normalizedUserId, "group", message.group_id, message.id)?.let {
                        MessageNotificationFactory.redactMessage(context, it, message.created_at, forEveryone = false)
                    }
                },
                remoteDelete = { message ->
                    if (useLocalApi) {
                        localFallbackApiClient.deleteCollectionMessage(
                            accessToken = requireNotNull(token), collection = "groups", roomId = message.group_id,
                            messageId = messageId, forEveryone = forEveryone,
                        )
                    } else {
                        CollectionDeleteRetrySupport.retryRemoteDelete(
                            logTag = "GroupRepository", messageId = messageId,
                            remoteDelete = {
                                val payload = buildJsonObject {
                                    if (forEveryone) put("deleted_for_all", true)
                                    else put("deleted_by", kotlinx.serialization.json.JsonArray(message.deleted_by.map(::JsonPrimitive)))
                                }
                                supabaseConfig.client.postgrest["group_messages"].update(payload) {
                                    filter {
                                        eq("id", messageId)
                                        if (forEveryone) eq("sender_id", normalizedUserId)
                                    }
                                }
                            },
                            isRetryableNetworkError = { error ->
                                CollectionRepositorySupport.isRetryableNetworkError(error, transientNetworkRetryCodes)
                            },
                        )
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
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

    fun observePollUpdates(): kotlinx.coroutines.flow.Flow<String> =
        conectate.com.data.repository.collection.collectionPollUpdates(ubuntuRealtimeClient, authService, "group")

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
                    val seenIds = readJournal.seenIds(normalizedSessionUserId, "group", cleanId)
                    val payload = localFallbackApiClient.loadGroupRoom(token, cleanId).getOrElse { error ->
                        android.util.Log.e("GroupRepository", "Error syncing Ubuntu group messages for $groupId", error)
                        return@withContext
                    }
                    val historyAccess = payload.historyAccess
                    val pendingHistoryIds = groupDao.getMessagesSync(cleanId).filter { !it.is_synced && it.pending_action == "send" }.map { it.id }.toSet()
                    if (historyAccess != null) {
                        if (!isSessionSnapshotValid(sessionSnapshot, "group-history-access:$cleanId")) return@withContext
                        collectionHistoryStore.save(normalizedSessionUserId, "group", cleanId, historyAccess)
                        if (!historyAccess.isMember) {
                            if (historyAccess.canReadHistory) groupDao.insertMembers(listOf(GroupMemberEntity(cleanId, normalizedSessionUserId, "former_member", historyAccess.joinedAt ?: java.time.Instant.now().toString())))
                            else groupDao.removeMember(cleanId, normalizedSessionUserId)
                        }
                        // The policy filters reads. A sync snapshot must not destroy cached history;
                        // a corrected membership epoch can make it accessible again.
                    }
                    val localMessagesById = groupDao.getMessagesSync(cleanId)
                        .associateBy { it.id.trim().lowercase() }
                        .mapValues { (_, entity) -> entity.toDomain() }
                    val mergedResponse = payload.messages.mapNotNull { row ->
                        runCatching {
                            acknowledgeGroupMessage(
                                json.decodeFromJsonElement<GroupMessage>(row),
                                cleanId
                            )
                        }.getOrNull()
                    }.map { incoming ->
                        mergeSyncedGroupMessageWithLocalState(
                            incoming = incoming,
                            local = localMessagesById[incoming.id.trim().lowercase()]
                        )
                    }.map { message ->
                        if (message.id in seenIds && !message.deleted_for_all &&
                            conectate.com.core.messaging.UnreadPolicy.isEligibleStatusForReadTransition(message.status))
                            message.copy(status = "read") else message
                    }
                    if (!isSessionSnapshotValid(sessionSnapshot, "group-sync-messages-after-ubuntu:$cleanId")) return@withContext
                    if (_activeGroupMessages.containsKey(cleanId)) {
                        val current = _activeGroupMessages[cleanId]?.value ?: emptyList()
                        _activeGroupMessages[cleanId]?.value = CollectionMessageSyncSupport.mergeActiveMessages(
                            current = current.filter { historyAccess == null || historyAccess.allows(it.created_at) || it.id in pendingHistoryIds },
                            incoming = mergedResponse,
                            idSelector = { it.id },
                            createdAtSelector = { it.created_at }
                        )
                    }
                    if (mergedResponse.isNotEmpty()) {
                        groupDao.insertMessages(mergedResponse.map { GroupMessageEntity.fromDomain(it) })
                        chatImages?.get()?.prefetchRecentGroupMedia(mergedResponse)
                        mergedResponse.maxByOrNull { it.created_at }?.let { lastMsg ->
                        deletedMessageStore.recordSummary(currentSessionUserId(), "group", cleanId, lastMsg.id, lastMsg.created_at)
                            groupDao.updateMetadata(
                                cleanId,
                                if (lastMsg.deleted_for_all) "Mensaje eliminado" else CollectionMessageSyncSupport.buildMetadataPreview(lastMsg.content_type, lastMsg.content),
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
                        deletedMessageStore.recordSummary(currentSessionUserId(), "group", cleanId, lastMsg.id, lastMsg.created_at)
                        if (!isSessionSnapshotValid(sessionSnapshot, "group-sync-messages-before-metadata:$cleanId")) return@withContext
                        groupDao.updateMetadata(
                            cleanId,
                            if (lastMsg.deleted_for_all) "Mensaje eliminado" else CollectionMessageSyncSupport.buildMetadataPreview(lastMsg.content_type, lastMsg.content),
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
            extension = "m4a",
            contentType = "audio/mp4"
        )



    private fun groupFromFallbackInbox(item: LocalFallbackInboxItem, currentUserId: String): Group {
        val cleanId = item.id.trim().lowercase()
        val now = java.time.Instant.now().toString()
        val updatedAt = item.updatedAt.ifBlank { now }
        deletedMessageStore.recordSummary(currentUserId, "group", cleanId, item.lastMessageId, updatedAt)
        val preview = deletedMessageStore.redactSummary(currentUserId, "group", cleanId,
            item.subtitle.takeIf { it.isNotBlank() && it != "Sin actividad reciente" }, updatedAt)
        val creatorId = item.creatorId?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: currentUserId
        return Group(
            id = cleanId,
            name = item.title.ifBlank { "Grupo" },
            image = item.avatarUrl.ifBlank { null },
            description = item.description.takeIf { it.isNotBlank() },
            created_at = updatedAt,
            creator_id = creatorId,
            last_message = preview,
            last_message_at = updatedAt,
            is_member = item.isMember,
            is_deleted = false,
            only_admins_can_talk = item.onlyAdminsCanTalk,
            rest_until = item.restUntil
        )
    }

    private suspend fun syncGroupsFromLocalFallback(): Result<Unit> {
        return try {
            val userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return Result.failure(Exception("Not authenticated"))
            val token = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("Sesion Ubuntu no disponible"))
            val sessionSnapshot = captureSessionSnapshot(userId)
            scope.launch { readSynchronizer.flush(userId, "group", token) }
            val inbox = localFallbackApiClient.loadMessagesInbox(token, "groups").getOrThrow()
            val activeItems = inbox.items.filter { it.id.isNotBlank() }
            activeItems.forEach { item ->
                item.historyAccess?.let { collectionHistoryStore.save(userId, "group", item.id.trim().lowercase(), it) }
            }
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
                            exactUnread = readJournal.resolveUnread(userId, "group", cleanId, item.unreadCount, item.unreadMessageIds)
                        )
                    )
                }
            )
            conectate.com.core.media.ProfileIdentityImageStore.warm(
                context, activeItems.map { it.avatarUrl }, userId,
                conectate.com.core.media.ProfileIdentityImageStore.generation(userId)
            )
            groupDao.insertMembers(
                activeIds.map { groupId ->
                    val item = activeItems.first { it.id.trim().lowercase() == groupId }
                    GroupMemberEntity(groupId, userId, if (item.isMember) "member" else "former_member", item.historyAccess?.joinedAt ?: java.time.Instant.now().toString())
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
            retryPendingGroupMessages()
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


    suspend fun createGroup(
        name: String, description: String?, imageBytes: ByteArray?, members: List<String>, expectedAccountId: String? = null,
    ): Result<String> = conectate.com.data.repository.collection.withCollectionCreationAccount(
        lease = authService.captureAccountLease(), expectedAccountId = expectedAccountId,
        tokenForAccount = authService::getAccessTokenForAccountAwait,
    ) { creatorId, token, requireCurrent ->
        try {
            val primary = authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured
            val prepare: suspend () -> conectate.com.data.repository.collection.CollectionCreationRequest = {
                conectate.com.data.repository.collection.requireCollectionCreationDetails(name.trim(), description)
                val id = UUID.randomUUID().toString()
                val image = imageBytes?.let {
                    collectionMediaStore.uploadImage(id, it, "groups", "group", creatorId).getOrThrow()
                }
                requireCurrent()
                conectate.com.data.repository.collection.CollectionCreationRequest(
                    id = id, accountId = creatorId, kind = "group", name = name.trim(), description = description,
                    imageUrl = image, memberIds = (members + creatorId).map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct(),
                    createdAt = java.time.Instant.now().toString(),
                )
            }
            val sendAndSave: suspend (conectate.com.data.repository.collection.CollectionCreationRequest) -> Unit = { request ->
                requireCurrent()
                var receipt: conectate.com.data.localfallback.LocalFallbackCreateCollectionResponse? = null
                if (primary) {
                    receipt = localFallbackApiClient.createCollectionOnce(token, request).getOrThrow()
                } else {
                    collectionCreationRemoteStore.createCollectionWithMemberships(
                        collectionTable = "groups",
                        collectionPayload = buildJsonObject {
                            put("id", request.id); put("name", request.name); put("description", request.description)
                            put("image", request.imageUrl); put("created_at", request.createdAt)
                            put("creator_id", creatorId); put("is_deleted", false)
                        },
                        membershipTable = "group_members",
                        membershipPayloads = request.memberIds.map { uid -> buildJsonObject {
                            put("group_id", request.id); put("user_id", uid)
                            put("role", if (uid.equals(creatorId, true)) "owner" else "member"); put("joined_at", request.createdAt)
                        } }, requireCurrent = requireCurrent,
                    ).getOrThrow()
                }
                requireCurrent()
                if (receipt != null) {
                    val confirmed = conectate.com.data.repository.collection.collectionCreationGroupCache(receipt, creatorId)
                    groupDao.saveCreatedGroup(confirmed.first, confirmed.second, requireCurrent)
                } else {
                    groupDao.saveCreatedGroup(
                        GroupEntity(id = request.id, name = request.name, image = request.imageUrl, description = request.description,
                            created_at = request.createdAt, creator_id = creatorId, last_message = null, last_message_at = null,
                            is_member = true, is_deleted = false),
                        request.memberIds.map { uid -> GroupMemberEntity(group_id = request.id, user_id = uid,
                            role = if (uid.equals(creatorId, true)) "owner" else "member", joined_at = request.createdAt) }, requireCurrent,
                    )
                }
            }
            val id = if (primary) creationJournal.execute(creatorId, "group", requireCurrent, prepare, sendAndSave)
                else prepare().let { sendAndSave(it); it.id }
            Result.success(id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun getGroupMembers(groupId: String): Result<List<GroupMember>> = withContext(Dispatchers.IO) {
        val cleanGroupId = groupId.trim().lowercase()
        val cachedMembers = runCatching { getCachedGroupMembers(cleanGroupId) }
            .getOrDefault(emptyList())
        try {
            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val token = authService.getAccessTokenAwait()?.trim().orEmpty()
                if (token.isBlank()) {
                    return@withContext if (cachedMembers.isNotEmpty()) {
                        Result.success(cachedMembers)
                    } else {
                        Result.failure(Exception("Sesion Ubuntu no disponible"))
                    }
                }
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
                // A valid but temporarily incomplete room response must not erase
                // the last usable member list from the device.
                if (cachedMembers.isNotEmpty()) return@withContext Result.success(cachedMembers)
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
            val local = cachedMembers.ifEmpty {
                runCatching { getCachedGroupMembers(cleanGroupId) }.getOrDefault(emptyList())
            }
            if (local.isNotEmpty()) Result.success(local)
            else Result.failure(e)
        }
    }

    /**
     * Reads the last membership snapshot without touching the network.
     * Room stores membership separately from profile data, so enrich it with
     * the account-scoped UserRepository cache for offline chat headers/lists.
     */
    internal suspend fun getCachedGroupMembers(groupId: String): List<GroupMember> {
        val basicMembers = groupDao.getMembersFlow(groupId).first().map {
            GroupMember(it.group_id, it.user_id, it.role, it.joined_at)
        }
        if (basicMembers.isEmpty()) return emptyList()

        val cachedProfiles = basicMembers
            .map { it.user_id.trim().lowercase() }
            .distinct()
            .associateWith { userRepository.getCachedUserById(it) }

        return basicMembers.map { member ->
            val profile = cachedProfiles[member.user_id.trim().lowercase()]
            member.copy(
                userName = member.userName?.takeIf { it.isNotBlank() } ?: profile?.displayName,
                userImage = member.userImage?.takeIf { it.isNotBlank() } ?: profile?.avatar_url
            )
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
            val exit = localFallbackApiClient.leaveCollectionWithHistory(token, "groups", cleanId).getOrThrow()
            val history = exit.historyAccess ?: localFallbackApiClient.loadGroupRoom(token, cleanId).getOrNull()?.historyAccess
            history?.let { collectionHistoryStore.save(cleanUserId, "group", cleanId, it) }
            groupDao.insertMembers(listOf(GroupMemberEntity(cleanId, cleanUserId, "former_member", history?.joinedAt ?: java.time.Instant.now().toString())))
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
                val joinedAt = groupDao.getMemberJoinedAt(cleanId, cleanUserId) ?: java.time.Instant.now().toString()
                groupDao.insertMembers(
                    listOf(GroupMemberEntity(cleanId, cleanUserId, "member", joinedAt))
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

    suspend fun createGroupInvite(groupId: String): Result<String> = withContext(Dispatchers.IO) {
        val token = authService.getAccessTokenAwait()
            ?: return@withContext Result.failure(Exception("Sesión Ubuntu no disponible"))
        localFallbackApiClient.createGroupInvite(token, groupId.trim().lowercase())
    }

    suspend fun joinGroupWithInvite(groupId: String, inviteToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = authService.getAccessTokenAwait()
            ?: return@withContext Result.failure(Exception("Sesión Ubuntu no disponible"))
        val cleanId = groupId.trim().lowercase()
        localFallbackApiClient.joinGroupWithInvite(token, cleanId, inviteToken).onSuccess {
            syncGroups()
            syncGroupMessages(cleanId)
        }
    }

    suspend fun setGroupRestUntil(groupId: String, restUntil: String?): Result<Unit> = withContext(Dispatchers.IO) {
        val token = authService.getAccessTokenAwait()
            ?: return@withContext Result.failure(Exception("Sesión Ubuntu no disponible"))
        val cleanId = groupId.trim().lowercase()
        localFallbackApiClient.setGroupRestUntil(token, cleanId, restUntil).onSuccess {
            val notificationPrefs = ConversationNotificationPrefs(context)
            val untilMillis = runCatching { java.time.Instant.parse(restUntil).toEpochMilli() }.getOrNull()
            if (untilMillis != null) {
                notificationPrefs.muteConversationUntil("group", cleanId, untilMillis, authService.getCurrentUserId())
            } else {
                notificationPrefs.unmuteConversation("group", cleanId, authService.getCurrentUserId())
            }
            groupDao.getGroupById(cleanId)?.let { cached ->
                groupDao.upsertGroup(cached.copy(rest_until = restUntil))
            }
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
        val cleanId = groupId.trim().lowercase()
        if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
            val token = authService.getAccessTokenAwait() ?: throw IllegalStateException("No se pudo borrar el chat. Inténtalo de nuevo.")
            localFallbackApiClient.hideCollectionHistory(token, "groups", cleanId).getOrThrow()
        }
        val ownAccount = authService.getCurrentUserId()?.trim()?.lowercase() ?: return
        groupDao.removeMember(cleanId, ownAccount)
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
                val originalJoinedAt = groupDao.getMemberJoinedAt(cleanId, targetUserId)
                if (originalJoinedAt != null) groupDao.insertMembers(
                    listOf(
                        GroupMemberEntity(
                            group_id = cleanId,
                            user_id = targetUserId,
                            role = cleanRole,
                            joined_at = originalJoinedAt
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
            var trimmedName = name.trim()
            if (trimmedName.isBlank()) {
                return@withContext Result.failure(Exception("El nombre del grupo no puede estar vacío."))
            }
            var normalizedDescription = description?.trim().orEmpty()
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
                put("name", trimmedName)
                put("description", normalizedDescription)
                put("only_admins_can_talk", onlyAdminsCanTalk)
                if (imageUrl != null) put("image", imageUrl)
            }
            val primaryResult = collectionAdminRemoteStore.updateCollectionInfo(
                collectionTable = "groups",
                scopeId = cleanId,
                payload = updatePayload
            )

            val finalResult = if (primaryResult.isSuccess) {
                primaryResult
            } else if (localFallbackApiClient.isConfigured) {
                val token = authService.getAccessTokenAwait()
                    ?: return@withContext Result.failure(primaryResult.exceptionOrNull() ?: Exception("No token"))
                localFallbackApiClient.updateGroupInfo(
                    accessToken = token,
                    groupId = cleanId,
                    name = trimmedName,
                    description = normalizedDescription,
                    onlyAdminsCanTalk = onlyAdminsCanTalk,
                    imageUrl = imageUrl
                )
            } else {
                primaryResult
            }

            finalResult.getOrThrow()
            
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

    suspend fun sendGroupMediaMessage(
        groupId: String,
        sourceUri: android.net.Uri,
        contentType: String,
        content: String,
        replyToId: String? = null,
        replyTo: conectate.com.data.model.Message? = null,
        onQueued: suspend () -> Unit = {}
    ): Result<Unit> {
        val accountId = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: return Result.failure(Exception("No user"))
        val session = captureSessionSnapshot(accountId)
        val messageId = UUID.randomUUID().toString()
        val pending = PendingOutgoingMediaStore.persist(
            context = context,
            accountId = accountId,
            namespace = "group",
            messageId = messageId,
            sourceUri = sourceUri,
            preferredMediaType = contentType
        ).getOrElse { return Result.failure(it) }
        if (!isSessionSnapshotValid(session, "group-media-persisted")) {
            return Result.failure(IllegalStateException("La cuenta cambió durante el envío."))
        }
        return sendGroupMessage(
            groupId = groupId,
            content = content,
            contentType = contentType,
            mediaUrl = pending.uri.toString(),
            messageId = messageId,
            replyToId = replyToId,
            replyTo = replyTo,
            onQueued = onQueued
        )
    }
}




