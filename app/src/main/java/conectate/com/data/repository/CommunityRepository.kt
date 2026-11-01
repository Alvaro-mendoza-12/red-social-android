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
import conectate.com.data.local.dao.CommunityDao
import conectate.com.data.local.entity.CommunityEntity
import conectate.com.data.local.entity.CommunityMemberEntity
import conectate.com.data.local.entity.CommunityMessageEntity
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.localfallback.LocalFallbackInboxItem
import conectate.com.data.model.Community
import conectate.com.data.model.CommunityChannel
import conectate.com.data.model.CommunityChannelCatalog
import conectate.com.data.model.CommunityEvent
import conectate.com.data.model.CommunityEventCatalog
import conectate.com.data.model.CommunityMember
import conectate.com.data.model.CommunityMemberProfile
import conectate.com.data.model.CommunityMemberProfileCatalog
import conectate.com.data.model.CommunityMessage
import conectate.com.data.model.CommunityRoleCatalog
import conectate.com.data.model.CommunityWithLastMessage
import conectate.com.data.model.defaultCommunityRoleCatalog
import conectate.com.data.model.MessagePollOptionState
import conectate.com.data.model.PollVoteUser
import conectate.com.data.model.MessageReadReceipt
import conectate.com.data.model.VoiceRoom
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.data.sync.PendingMessageSyncScheduler
import conectate.com.domain.sync.CommunitySyncDataSource
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
) : CommunitySyncDataSource {
    private val maxForwardTargetsPerBatch = 20
    private val deletedMessageStore by lazy { conectate.com.util.DeletedMessageStore(context) }

    suspend fun handleDeletedMessage(identity: conectate.com.util.DeletedMessageIdentity, createdAt: String? = null, channelId: String? = null) {
        if (identity.targetType != "community" || currentSessionUserId()?.trim()?.lowercase() != identity.accountId) return
        val session = captureSessionSnapshot(identity.accountId)
        if (!isSessionSnapshotValid(session, "deleted-message-start")) return
        conectate.com.util.MessageNotificationFactory.redactMessage(context, identity, createdAt, channelContextId = channelId)
        communityDao.redactDeletedMessage(identity.conversationId, identity.messageId)
        if (!isSessionSnapshotValid(session, "deleted-message-preview")) return
        communityDao.redactDeletedPreview(identity.conversationId, identity.messageId, createdAt)
        val local = communityDao.getMessageById(identity.messageId)?.toDomain()
        if (local != null && local.community_id == identity.conversationId && isSessionSnapshotValid(session, "deleted-message-flow")) {
            updateCommunityMessageInFlow(identity.conversationId, deletedMessageStore.redact(local, identity.accountId))
        }
    }

    private var scope = newSafeScope("CommunityRepository", Dispatchers.IO)
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
            authService = authService,
            logTag = "CommunityRepository",
            logPrefix = "[COMM]",
            readyLog = "🟢 [C-REALTIME] Sistema Iniciado",
            onAuthenticated = ::startRealtimeSubscriptions,
            onNotAuthenticated = ::stopRealtimeSubscriptions
        )
    }

    private val messageSendCoordinator = conectate.com.data.repository.message.MessageSendCoordinator()

    private var realtimeJob: Job? = null
    private var communityListenerJob: Job? = null
    private var ubuntuRealtimeJob: Job? = null
    private var subscribedCommunityIds: Set<String> = emptySet()
    private val activeCommunityMessageJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private fun startRealtimeSubscriptions(userId: String) {
        if (authService.isLocalFallbackModeEnabled()) {
            startUbuntuRealtimeSubscriptions(userId)
            return
        }
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
                if (!roomEventId.startsWith("room:community:")) return@collect
                val record = envelope.payload["message"]?.jsonObject ?: return@collect
                val message = runCatching { json.decodeFromJsonElement<CommunityMessage>(record) }
                    .getOrElse { error ->
                        android.util.Log.e("CommunityRepository", "Invalid Ubuntu community realtime payload", error)
                        return@collect
                    }
                val action = envelope.payload["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                if (action == "created" || action.isNullOrBlank()) {
                    handleIncomingMessage(message, cleanUserId)
                } else {
                    handleIncomingCommunityUpdate(message, cleanUserId)
                }
            }
        }
    }

    private fun stopRealtimeSubscriptions() {
        realtimeJob?.cancel()
        realtimeJob = null
        communityListenerJob?.cancel()
        communityListenerJob = null
        ubuntuRealtimeJob?.cancel()
        ubuntuRealtimeJob = null
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
                MessageNotificationFactory.cancelConversation(context, conectate.com.util.NotificationAccountTarget(userId = authService.getCurrentUserId()), "community", cleanId)
            } catch (e: Exception) {
                android.util.Log.e("CommunityRepository", "Error clearing notification", e)
            }
        }
    }

    fun getUserRoleFlow(communityId: String, userId: String): Flow<String?> {
        return communityDao.getUserRoleFlow(communityId.trim().lowercase(), userId.trim().lowercase())
    }

    suspend fun getCommunityRoleCatalog(
        communityId: String,
        currentRole: String?
    ): Result<CommunityRoleCatalog> = withContext(Dispatchers.IO) {
        if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
            val token = authService.getAccessTokenAwait()
                ?: return@withContext Result.failure(Exception("Sesión Ubuntu no disponible"))
            return@withContext localFallbackApiClient.loadCommunityRoleCatalog(
                accessToken = token,
                communityId = communityId.trim().lowercase()
            )
        }
        Result.success(defaultCommunityRoleCatalog(currentRole))
    }

    suspend fun getCommunityChannels(communityId: String): Result<CommunityChannelCatalog> =
        withContext(Dispatchers.IO) {
            val token = authService.getAccessTokenAwait()
                ?: return@withContext Result.failure(Exception("Sesión no disponible"))
            if (!authService.isLocalFallbackSession() || !localFallbackApiClient.isConfigured) {
                return@withContext Result.success(
                    CommunityChannelCatalog(
                        currentRole = getMemberRole(communityId, authService.getCurrentUserId().orEmpty()) ?: "member",
                        channels = listOf(
                            CommunityChannel(
                                id = "general",
                                communityId = communityId,
                                name = "General",
                                isDefault = true
                            )
                        )
                    )
                )
            }
            localFallbackApiClient.loadCommunityChannels(token, communityId.trim().lowercase())
        }

    suspend fun createCommunityChannel(
        communityId: String,
        name: String,
        description: String?,
        type: String
    ): Result<CommunityChannel> = withContext(Dispatchers.IO) {
        val token = authService.getAccessTokenAwait()
            ?: return@withContext Result.failure(Exception("Sesión no disponible"))
        localFallbackApiClient.createCommunityChannel(
            token,
            communityId.trim().lowercase(),
            name.trim(),
            description?.trim(),
            type.trim().lowercase()
        )
    }

    suspend fun updateCommunityChannel(
        communityId: String,
        channel: CommunityChannel,
        name: String,
        description: String?,
        type: String,
        position: Int
    ): Result<CommunityChannel> = withContext(Dispatchers.IO) {
        val token = authService.getAccessTokenAwait()
            ?: return@withContext Result.failure(Exception("Sesión no disponible"))
        localFallbackApiClient.updateCommunityChannel(
            token,
            communityId.trim().lowercase(),
            channel.id,
            name.trim(),
            description?.trim(),
            type.trim().lowercase(),
            position
        )
    }

    suspend fun pauseCommunityChannel(
        communityId: String,
        channelId: String,
        paused: Boolean,
        minutes: Int = 60,
        reason: String? = null
    ): Result<CommunityChannel> = withContext(Dispatchers.IO) {
        val token = authService.getAccessTokenAwait()
            ?: return@withContext Result.failure(Exception("Sesión no disponible"))
        localFallbackApiClient.pauseCommunityChannel(
            token,
            communityId.trim().lowercase(),
            channelId.trim().lowercase(),
            paused,
            minutes,
            reason?.trim()
        )
    }

    suspend fun deleteCommunityChannel(communityId: String, channelId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val token = authService.getAccessTokenAwait()
                ?: return@withContext Result.failure(Exception("Sesión no disponible"))
            localFallbackApiClient.deleteCommunityChannel(
                token,
                communityId.trim().lowercase(),
                channelId.trim().lowercase()
            )
        }

    suspend fun openCommunityVoiceChannel(
        communityId: String,
        channelId: String
    ): Result<VoiceRoom> = withContext(Dispatchers.IO) {
        val token = authService.getAccessTokenAwait()
            ?: return@withContext Result.failure(Exception("Sesión no disponible"))
        localFallbackApiClient.openCommunityVoiceChannel(
            accessToken = token,
            communityId = communityId.trim().lowercase(),
            channelId = channelId.trim().lowercase()
        )
    }

    suspend fun getCommunityMemberProfiles(
        communityId: String
    ): Result<CommunityMemberProfileCatalog> = withContext(Dispatchers.IO) {
        val userId = authService.getCurrentUserId().orEmpty()
        if (!authService.isLocalFallbackSession() || !localFallbackApiClient.isConfigured) {
            return@withContext Result.success(
                CommunityMemberProfileCatalog(
                    profile = CommunityMemberProfile(
                        communityId = communityId,
                        userId = userId
                    )
                )
            )
        }
        val token = authService.getAccessTokenAwait()
            ?: return@withContext Result.failure(Exception("Sesión no disponible"))
        localFallbackApiClient.loadCommunityMemberProfiles(
            token,
            communityId.trim().lowercase()
        )
    }

    suspend fun updateCommunityMemberProfile(
        communityId: String,
        alias: String?,
        bio: String?,
        distinctive: String?
    ): Result<CommunityMemberProfile> = withContext(Dispatchers.IO) {
        if (!authService.isLocalFallbackSession() || !localFallbackApiClient.isConfigured) {
            return@withContext Result.failure(
                Exception("El perfil por comunidad requiere conexión con el servidor Novvin.")
            )
        }
        val token = authService.getAccessTokenAwait()
            ?: return@withContext Result.failure(Exception("Sesión no disponible"))
        localFallbackApiClient.updateCommunityMemberProfile(
            token,
            communityId.trim().lowercase(),
            alias?.trim(),
            bio?.trim(),
            distinctive?.trim()
        )
    }

    suspend fun getCommunityEvents(communityId: String): Result<CommunityEventCatalog> =
        withContext(Dispatchers.IO) {
            if (!authService.isLocalFallbackSession() || !localFallbackApiClient.isConfigured) {
                return@withContext Result.success(CommunityEventCatalog())
            }
            val token = authService.getAccessTokenAwait()
                ?: return@withContext Result.failure(Exception("Sesión no disponible"))
            localFallbackApiClient.loadCommunityEvents(token, communityId.trim().lowercase())
        }

    suspend fun createCommunityEvent(
        communityId: String,
        title: String,
        description: String?,
        startsAt: String,
        endsAt: String?,
        location: String?
    ): Result<CommunityEvent> = withContext(Dispatchers.IO) {
        val token = authService.getAccessTokenAwait()
            ?: return@withContext Result.failure(Exception("Sesión no disponible"))
        localFallbackApiClient.createCommunityEvent(
            token,
            communityId.trim().lowercase(),
            title.trim(),
            description?.trim(),
            startsAt,
            endsAt,
            location?.trim()
        )
    }

    suspend fun cancelCommunityEvent(
        communityId: String,
        eventId: String
    ): Result<CommunityEvent> = withContext(Dispatchers.IO) {
        val token = authService.getAccessTokenAwait()
            ?: return@withContext Result.failure(Exception("Sesión no disponible"))
        localFallbackApiClient.cancelCommunityEvent(
            token,
            communityId.trim().lowercase(),
            eventId.trim().lowercase()
        )
    }

    suspend fun updateCommunityEventAttendance(
        communityId: String,
        eventId: String,
        attending: Boolean,
        reminderMinutes: Int?
    ): Result<CommunityEvent> = withContext(Dispatchers.IO) {
        val token = authService.getAccessTokenAwait()
            ?: return@withContext Result.failure(Exception("Sesión no disponible"))
        localFallbackApiClient.updateCommunityEventAttendance(
            token,
            communityId.trim().lowercase(),
            eventId.trim().lowercase(),
            if (attending) "going" else "not_going",
            reminderMinutes.takeIf { attending }
        )
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
        val msg = deletedMessageStore.redact(msg, sessionUserId)
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
                    val isNewMessage = existingMessage == null
                    if (!isNewMessage) {
                        android.util.Log.d("CommunityRepository", "♻️ [DUPLICATE] Fusionando datos más completos para ${msg.id}.")
                    }

                    android.util.Log.d("CommunityRepository", "📩 [MSG-VALID] Content='${msg.content.take(50)}...', Sender=${msg.sender_id}, Community=$cleanId")

                    // 4. Persistir Mensaje (Room)
                    var currentUserId = sessionSnapshot.accountId
                    
                    if (currentUserId != null && msg.sender_id.trim().lowercase() != currentUserId) {
                        scope.launch { markMessageAsDelivered(cleanId, msg.id) }
                    }

                    // Hydrate reply_to manually since Realtime payload allows it to be null/missing joins
                    val mergedMsg = mergeSyncedCommunityMessageWithLocalState(
                        incoming = msg.copy(community_id = cleanId),
                        local = existingMessage?.toDomain()
                    )
                    val normalizedMsg = if (mergedMsg.reply_to_id != null && mergedMsg.reply_to == null) {
                        val reply = communityDao.getMessageById(mergedMsg.reply_to_id)?.toDomain()
                        mergedMsg.copy(reply_to = reply)
                    } else mergedMsg

                    val hydratedMsg = deletedMessageStore.redact(hydrateCommunityMessage(normalizedMsg), currentUserId)

                    if (!isSessionSnapshotValid(sessionSnapshot, "community-insert-before-db:$cleanId")) return@withContext
                    communityDao.insertMessages(listOf(conectate.com.data.local.entity.CommunityMessageEntity.fromDomain(hydratedMsg)))
                    android.util.Log.d("CommunityRepository", "💾 [DB-SAVE] Mensaje ${msg.id} guardado en Room DB")
                    
                    // 5. Actualizar Flow Activo (Chat Screen)
                    if (!isSessionSnapshotValid(sessionSnapshot, "community-insert-before-flow:$cleanId")) return@withContext
                    updateCommunityMessageInFlow(cleanId, hydratedMsg)
                    android.util.Log.d("CommunityRepository", "🔄 [FLOW-UPDATE] Flow actualizado para $cleanId")
                    
                    if (hydratedMsg.deleted_for_all) {
                        conectate.com.util.DeletedMessageIdentity.create(currentUserId, "community", cleanId, hydratedMsg.id)?.let {
                            handleDeletedMessage(it, hydratedMsg.created_at, hydratedMsg.channel_id)
                        }
                        return@withContext
                    }

                    // 6. Actualizar Metadatos de la Comunidad (Lista de Chats) y Notificar
                    var cleanSenderId = msg.sender_id.trim().lowercase()
                    val isMyMessage = cleanSenderId == currentUserId
                    
                    if (!isMyMessage && isNewMessage) {
                        // CRITICAL FIX: Only increment unread & notify if this is a JOINED community
                        var isMember = if (currentUserId != null) communityDao.isUserMemberSync(cleanId, currentUserId) else false
                        
                        // Also check if this COMMUNITY is NOT currently open
                        val isCommunityCurrentlyOpen = (cleanId == currentOpenedCommunityId)
                        if (isMember && currentUserId != null && msg.id !in readJournal.seenIds(currentUserId, "community", cleanId) &&
                            UnreadPolicy.isEligibleStatusForReadTransition(msg.status) &&
                            !conectate.com.util.ConversationReadStore(context).isRead(currentUserId,"community",cleanId,msg.id,msg.created_at)) {
                            if (!isSessionSnapshotValid(sessionSnapshot, "community-insert-before-unread:$cleanId")) return@withContext
                            communityDao.incrementUnreadCount(cleanId)
                        }
                        
                        if (isMember && !isCommunityCurrentlyOpen) {
                            android.util.Log.d("CommunityRepository", "🔔 [UNREAD+1] Incrementando contador para $cleanId (comunidad cerrada y soy miembro)")
                            if (!isSessionSnapshotValid(sessionSnapshot, "community-insert-before-unread:$cleanId")) return@withContext
                            if (!isCallEventContentType(msg.content_type) && !isCollectionMembershipNotice(msg.content_type)) {
                                showNotification(msg, localCommunity.name)
                            }
                        } else {
                            android.util.Log.d("CommunityRepository", "👁️ [MSG-IGNORE-NOTIF] Member=$isMember, Open=$isCommunityCurrentlyOpen. Skipping notification.")
                            if (isMember && isCommunityCurrentlyOpen) {
                                if (!isCallEventContentType(msg.content_type) && !isCollectionMembershipNotice(msg.content_type)) {
                                    chatSoundPlayer.playReceive()
                                }
                            }
                        }
                    } else {
                        android.util.Log.d("CommunityRepository", "✍️ [MY-MSG] Mensaje enviado por mí. No incrementar unread.")
                    }
                    
                    // Actualizar last_message siempre (para todos los mensajes, incluso los míos)
                    if (!isSessionSnapshotValid(sessionSnapshot, "community-insert-before-metadata:$cleanId")) return@withContext
                    deletedMessageStore.recordSummary(currentUserId, "community", cleanId, hydratedMsg.id, hydratedMsg.created_at)
                    communityDao.updateMetadata(cleanId, 
                        buildConversationBackgroundPreview(msg.content_type, msg.content)
                            ?: buildCallEventPreview(msg.content_type, msg.content)
                            ?: CollectionMessageSyncSupport.buildMetadataPreview(msg.content_type, msg.content),
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
            if (msg.deleted_for_all) conectate.com.util.DeletedMessageIdentity.create(sessionSnapshot.accountId, "community", msg.community_id, msg.id)?.let {
                handleDeletedMessage(it, msg.created_at, msg.channel_id)
            }
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
                    
                    if (!isSessionSnapshotValid(sessionSnapshot, "community-update-before-db:${msg.id}")) return@withContext
                    communityDao.insertMessages(listOf(conectate.com.data.local.entity.CommunityMessageEntity.fromDomain(finalMsg)))
                    if (!isSessionSnapshotValid(sessionSnapshot, "community-update-before-flow:${msg.id}")) return@withContext
                    updateCommunityMessageInFlow(finalMsg.community_id, finalMsg)
                    if (finalMsg.deleted_for_all) conectate.com.util.DeletedMessageIdentity.create(sessionSnapshot.accountId, "community", finalMsg.community_id, finalMsg.id)?.let {
                        handleDeletedMessage(it, finalMsg.created_at, finalMsg.channel_id)
                    }
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


    private suspend fun showNotification(msg: CommunityMessage, communityName: String) {
        if (isCollectionMembershipNotice(msg.content_type)) return
        val receivingAccount = conectate.com.util.NotificationAccountTarget(userId = authService.getCurrentUserId(), email = authService.getCurrentUserEmail())
        val photos = withContext(Dispatchers.IO) {
            if (!receivingAccount.matches(authService.getCurrentUserId(), authService.getCurrentUserEmail())) return@withContext null
            kotlinx.coroutines.coroutineScope {
                val person = async { conectate.com.core.media.NotificationAvatarLoader.senderPhoto(context, userRepository, msg.sender_id) }
                val room = async {
                    val image = conectate.com.core.media.NotificationAvatarLoader.optionalValue {
                        communityDao.getCommunityById(msg.community_id.trim().lowercase())?.image
                    }
                    conectate.com.core.media.NotificationAvatarLoader.load(context, image)
                }
                person.await() to room.await()
            }
        }
        withContext(Dispatchers.Main) {
            if (!receivingAccount.matches(authService.getCurrentUserId(), authService.getCurrentUserEmail())) return@withContext
            try {
                var cleanId = msg.community_id.trim().lowercase()
                if (cleanId == currentOpenedCommunityId) return@withContext
                val isMention = msg.mentioned_user_ids.any { it.equals(receivingAccount.userId, true) }
                if (!isMention && ConversationNotificationPrefs(context).isMuted("community", cleanId, authService.getCurrentUserId())) {
                    android.util.Log.d("CommunityRepository", "🔕 [NOTIFICATION] Community $cleanId muted locally. Skipping notification.")
                    return@withContext
                }

                var notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                var channelId = "community_messages_channel_v2"
                val previewText = buildConversationBackgroundPreview(msg.content_type, msg.content)
                    ?: buildCallEventPreview(msg.content_type, msg.content)
                    ?: if (msg.content_type == "text") msg.content else "📎 Multimedia"
                MessageNotificationFactory.post(
                    context = context,
                    payload = MessageNotificationPayload(
                        channelId = channelId,
                        channelName = "Mensajes de Comunidades",
                        channelDescription = "Notificaciones de mensajes en comunidades",
                        conversationId = cleanId,
                        targetType = "community",
                        conversationTitle = communityName,
                        senderName = msg.sender_name ?: "Alguien",
                        messageBody = conectate.com.util.mentionNotificationPreview(previewText,
                            msg.mentioned_user_ids.any { it.equals(receivingAccount.userId, true) }),
                        notificationId = cleanId.hashCode(),
                        senderId = msg.sender_id,
                        messageId = msg.id,
                        largeIcon = photos?.first,
                        conversationIcon = photos?.second,
                        isGroupConversation = true,
                        notificationAccountTarget = receivingAccount,
                        channelContextId = msg.channel_id
                    )
                )

                android.util.Log.d("CommunityRepository", "🔔 [NOTIFICATION] Sent notification for community $communityName, messageId=${msg.id}")
            } catch (e: Exception) {
                android.util.Log.e("CommunityRepository", "❌ [NOTIFICATION] Failed to show notification", e)
            }
        }
    }

    // --- FLOWS ---



    private suspend fun cachedHistoryAccess(roomId: String, account: String): conectate.com.data.localfallback.CollectionHistoryAccess {
        val room = communityDao.getCommunityById(roomId)
        return conectate.com.data.localfallback.resolveCachedCollectionHistory(
            cached = collectionHistoryStore.load(account, "community", roomId),
            joinedAt = communityDao.getMemberJoinedAt(roomId, account),
            isCreator = room?.creator_id?.equals(account, ignoreCase = true) == true && communityDao.getUserRoleSync(roomId, account) != null,
            isMember = communityDao.getUserRoleSync(roomId, account) != null
        )
    }

    suspend fun getCachedMessages(communityId: String): List<CommunityMessage> = withContext(Dispatchers.IO) {
        var cleanId = communityId.trim().lowercase()
        if (cleanId.isBlank()) return@withContext emptyList()
        val account = currentSessionUserId() ?: return@withContext emptyList()
        val access = if (authService.isLocalFallbackSession()) cachedHistoryAccess(cleanId, account) else null
        val localMessages = communityDao.getMessagesSync(cleanId).filter {
            access == null || access.allows(it.created_at) || (!it.is_synced && it.pending_action == "send")
        }.map { it.toDomain() }
        unifyCommunityMessages(localMessages, account)
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
                        val activeUserId = currentSessionUserId() ?: return@collect
                        val access = if (authService.isLocalFallbackSession()) cachedHistoryAccess(cleanId, activeUserId) else null
                        val pendingIds = entities.filter { !it.is_synced && it.pending_action == "send" }.map { it.id }.toSet()
                        val localMessages = entities.filter { access == null || access.allows(it.created_at) || it.id in pendingIds }.map { it.toDomain() }
                        state.update { current ->
                            unifyCommunityMessages(localMessages + current.filter { access == null || access.allows(it.created_at) || it.id in pendingIds }, activeUserId)
                        }
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

    private fun acknowledgeCommunityMessage(
        message: CommunityMessage,
        communityId: String = message.community_id
    ): CommunityMessage {
        return message.copy(
            community_id = communityId.trim().lowercase(),
            status = MessageStatusNormalizer.acknowledgedAfterSend(
                status = message.status,
                deliveredAt = message.delivered_at,
                readAt = message.read_at
            )
        )
    }

    private suspend fun markCommunityMessageAcknowledged(messageId: String) {
        val existing = communityDao.getMessageById(messageId) ?: return
        val acknowledged = acknowledgeCommunityMessage(existing.toDomain())
        communityDao.insertMessages(
            listOf(
                CommunityMessageEntity.fromDomain(
                    msg = acknowledged,
                    isSynced = true,
                    pendingAction = null,
                    retryCount = existing.retry_count,
                    lastRetryAt = existing.last_retry_at
                )
            )
        )
        updateCommunityMessageInFlow(acknowledged.community_id, acknowledged)
    }

    private fun mergeSyncedCommunityMessageWithLocalState(
        incoming: CommunityMessage,
        local: CommunityMessage?
    ): CommunityMessage {
        val wasRead = conectate.com.util.ConversationReadStore(context).isRead(currentSessionUserId(),"community",incoming.community_id,incoming.id,incoming.created_at)
        local ?: return deletedMessageStore.redact(if (wasRead) incoming.copy(status="read") else incoming, currentSessionUserId())
        var mergedReceipt = mergeCommunityReceiptState(
            currentStatus = if (wasRead) "read" else local.status,
            currentDeliveredAt = local.delivered_at,
            currentReadAt = local.read_at,
            incomingStatus = incoming.status,
            incomingDeliveredAt = incoming.delivered_at,
            incomingReadAt = incoming.read_at
        )
        return deletedMessageStore.redact(incoming.copy(
            community_id = incoming.community_id.ifBlank { local.community_id },
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

    private fun unifyCommunityMessages(messages: List<CommunityMessage>, currentUserId: String?): List<CommunityMessage> {
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
                    mergeSyncedCommunityMessageWithLocalState(incoming, local)
                }
            }

        // [FIX] Hydrate reply_to relations
        val msgMap = mergedById.associateBy { it.id }
        val hydrated = mergedById.map { msg ->
            if (msg.reply_to_id != null && msg.reply_to == null) {
                msg.copy(reply_to = msgMap[msg.reply_to_id])
            } else msg
        }

        return hydrated.map { deletedMessageStore.redact(it, currentUserId) }.sortedBy { it.created_at }
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
                        .combine(deletedMessageStore.changes) { entities, _ -> entities }
                        .map { entities ->
                            entities
                                .filter { !it.is_deleted }
                                .map { entity ->
                                    CommunityWithLastMessage(
                                        community = entity.toDomain().copy(last_message = deletedMessageStore.redactSummary(activeUserId, "community", entity.id, entity.last_message, entity.last_message_at)),
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
            .flatMapLatest {
                communityDao.getAllCommunitiesFlow()
                    .combine(deletedMessageStore.changes) { entities, _ -> entities }
                    .map { entities ->
                        entities
                            .filter { entity ->
                                !entity.is_deleted
                            }
                            .map { entity ->
                                CommunityWithLastMessage(
                                    community = entity.toDomain().copy(last_message = deletedMessageStore.redactSummary(currentSessionUserId(), "community", entity.id, entity.last_message, entity.last_message_at)),
                                    unreadCount = entity.unread_count
                                )
                            }
                            .sortedByDescending { it.community.last_message_at }
                    }
            }
            .flowOn(Dispatchers.IO)
    }

    // --- SYNC ---



    private fun communityFromFallbackInbox(item: LocalFallbackInboxItem, currentUserId: String): Community {
        val cleanId = item.id.trim().lowercase()
        val now = java.time.Instant.now().toString()
        val updatedAt = item.updatedAt.ifBlank { now }
        deletedMessageStore.recordSummary(currentUserId, "community", cleanId, item.lastMessageId, updatedAt)
        val preview = deletedMessageStore.redactSummary(currentUserId, "community", cleanId,
            item.subtitle.takeIf { it.isNotBlank() && it != "Sin actividad reciente" }, updatedAt)
        val creatorId = item.creatorId?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: currentUserId
        val isMember = item.isMember
        return Community(
            id = cleanId,
            name = item.title.ifBlank { "Comunidad" },
            image = item.avatarUrl.ifBlank { null },
            description = item.description.takeIf { it.isNotBlank() },
            category = item.category.trim().takeIf { it.isNotBlank() },
            is_public = item.isPublic,
            created_at = updatedAt,
            creator_id = creatorId,
            last_message = preview,
            last_message_at = updatedAt,
            is_member = isMember,
            is_deleted = false,
            only_admins_can_talk = item.onlyAdminsCanTalk,
            daily_message_limit = item.dailyMessageLimit.coerceIn(0, 1000),
            weekly_message_limit = item.weeklyMessageLimit.coerceIn(0, 10000),
            current_user_role = item.currentUserRole
        )
    }

    private fun communityFromFallbackRoom(
        room: JsonObject?,
        currentUserId: String,
        fallback: Community,
    ): Community {
        val source = room ?: return fallback
        fun text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            source[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        }
        val creatorId = text("creator_id", "owner_id", "user_id")?.lowercase() ?: fallback.creator_id
        return fallback.copy(
            name = text("name", "title") ?: fallback.name,
            image = text("image", "image_url", "avatar_url") ?: fallback.image,
            description = text("description", "about") ?: fallback.description,
            category = text("category") ?: fallback.category,
            is_public = source["is_public"]?.jsonPrimitive?.booleanOrNull ?: fallback.is_public,
            creator_id = creatorId,
            created_at = text("created_at") ?: fallback.created_at,
            is_member = fallback.is_member,
        )
    }

    private suspend fun syncCommunitiesFromLocalFallback(): Result<Unit> {
        return try {
            val userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return Result.failure(Exception("Not authenticated"))
            val token = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("Sesion Ubuntu no disponible"))
            val sessionSnapshot = captureSessionSnapshot(userId)
            scope.launch { readSynchronizer.flush(userId, "community", token) }
            val inbox = localFallbackApiClient.loadMessagesInbox(token, "communities").getOrThrow()
            val activeItems = inbox.items.filter { it.id.isNotBlank() }
            activeItems.forEach { item ->
                item.historyAccess?.let { collectionHistoryStore.save(userId, "community", item.id.trim().lowercase(), it) }
            }
            val activeIds = activeItems.map { it.id.trim().lowercase() }.distinct()
            val joinedIds = activeItems
                .filter { it.isMember }
                .map { it.id.trim().lowercase() }
                .distinct()
            val localUnreadCounts = communityDao.getUnreadCountRows(userId)
                .associate { row -> row.communityId.trim().lowercase() to row.unreadCount.coerceAtLeast(0) }

            if (!isSessionSnapshotValid(sessionSnapshot, "community-ubuntu-sync-before-upsert:$userId")) {
                return Result.success(Unit)
            }
            val communities = activeItems.map { item ->
                    val cleanId = item.id.trim().lowercase()
                    val inboxCommunity = communityFromFallbackInbox(item, userId)
                    // Older deployed inbox payloads may omit category. The room endpoint
                    // already contains the canonical value, so enrich only missing rows.
                    val completeCommunity = if (inboxCommunity.category.isNullOrBlank()) {
                        localFallbackApiClient.loadCommunityRoom(token, cleanId)
                            .getOrNull()
                            ?.let { room -> communityFromFallbackRoom(room.chat, userId, inboxCommunity) }
                            ?: inboxCommunity
                    } else {
                        inboxCommunity
                    }
                    CommunityEntity.fromDomain(
                        community = completeCommunity,
                        unreadCount = CollectionMessageSyncSupport.resolveDisplayedUnreadCount(
                            isConversationOpen = cleanId == currentOpenedCommunityId,
                            exactUnread = readJournal.resolveUnread(userId, "community", cleanId, item.unreadCount, item.unreadMessageIds)
                        )
                    )
                }
            communityDao.insertCommunities(communities)
            conectate.com.core.media.ProfileIdentityImageStore.warm(
                context, activeItems.map { it.avatarUrl }, userId,
                conectate.com.core.media.ProfileIdentityImageStore.generation(userId)
            )
            communityDao.insertMembers(activeItems.filter { it.isMember || it.historyAccess?.canReadHistory == true }.map { item ->
                CommunityMemberEntity(item.id.trim().lowercase(), userId,
                    if (item.isMember) item.currentUserRole ?: "member" else "former_member",
                    item.historyAccess?.joinedAt ?: java.time.Instant.now().toString())
            })
            communityDao.markOthersAsNotJoined(joinedIds)
            activeItems.filter { it.isMember || it.historyAccess?.canReadHistory == true }.forEach { syncCommunityMessages(it.id.trim().lowercase(), userId) }
            android.util.Log.d("CommunityRepository", "✅ [UBUNTU-SYNC] ${activeIds.size} comunidades sincronizadas desde Ubuntu")
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("CommunityRepository", "Error syncing Ubuntu communities", e)
            Result.failure(e)
        }
    }

    override suspend fun syncCommunities(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            retryPendingCommunityMessages()
            if (authService.isLocalFallbackModeEnabled()) {
                return@withContext syncCommunitiesFromLocalFallback()
            }
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

    suspend fun createCommunityInvite(communityId: String): Result<String> = withContext(Dispatchers.IO) {
        val token = authService.getAccessTokenAwait()
            ?: return@withContext Result.failure(Exception("Inicia sesión para compartir la comunidad."))
        localFallbackApiClient.createCollectionInvite(token, "communities", communityId.trim().lowercase())
    }

    suspend fun createCommunity(
        name: String, description: String?, imageBytes: ByteArray?, category: String?, isPublic: Boolean,
        expectedAccountId: String? = null,
    ): Result<String> = conectate.com.data.repository.collection.withCollectionCreationAccount(
        lease = authService.captureAccountLease(), expectedAccountId = expectedAccountId,
        tokenForAccount = authService::getAccessTokenForAccountAwait,
    ) { creatorId, token, requireCurrent ->
        try {
            val primary = authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured
            val prepare: suspend () -> conectate.com.data.repository.collection.CollectionCreationRequest = {
                conectate.com.data.repository.collection.requireCollectionCreationDetails(name.trim(), description, category)
                val id = UUID.randomUUID().toString()
                val image = imageBytes?.let {
                    collectionMediaStore.uploadImage(id, it, "communities", "community", creatorId).getOrThrow()
                }
                requireCurrent()
                conectate.com.data.repository.collection.CollectionCreationRequest(
                    id = id, accountId = creatorId, kind = "community", name = name.trim(), description = description,
                    imageUrl = image, category = category, isPublic = isPublic, createdAt = java.time.Instant.now().toString(),
                    memberIds = listOf(creatorId.trim().lowercase()),
                )
            }
            val sendAndSave: suspend (conectate.com.data.repository.collection.CollectionCreationRequest) -> Unit = { request ->
                requireCurrent()
                var receipt: conectate.com.data.localfallback.LocalFallbackCreateCollectionResponse? = null
                if (primary) {
                    receipt = localFallbackApiClient.createCollectionOnce(token, request).getOrThrow()
                } else {
                    collectionCreationRemoteStore.createCollectionWithMemberships(
                        collectionTable = "communities",
                        collectionPayload = buildJsonObject {
                            put("id", request.id); put("name", request.name); put("description", request.description)
                            put("image", request.imageUrl); put("category", request.category); put("is_public", request.isPublic)
                            put("created_at", request.createdAt); put("creator_id", creatorId)
                            put("is_deleted", false); put("only_admins_can_talk", true)
                        }, membershipTable = "community_members",
                        membershipPayloads = listOf(buildJsonObject {
                            put("community_id", request.id); put("user_id", creatorId.trim().lowercase())
                            put("role", "owner"); put("joined_at", request.createdAt)
                        }), requireCurrent = requireCurrent,
                    ).getOrThrow()
                }
                requireCurrent()
                if (receipt != null) {
                    val confirmed = conectate.com.data.repository.collection.collectionCreationCommunityCache(receipt, creatorId)
                    communityDao.saveCreatedCommunity(confirmed.first, confirmed.second, requireCurrent)
                } else {
                    communityDao.saveCreatedCommunity(
                        CommunityEntity.fromDomain(Community(id = request.id, name = request.name, description = request.description,
                            image = request.imageUrl, category = request.category, is_public = request.isPublic,
                            created_at = request.createdAt, only_admins_can_talk = true, creator_id = creatorId, is_member = true)),
                        listOf(CommunityMemberEntity(community_id = request.id, user_id = creatorId.trim().lowercase(),
                            role = "owner", joined_at = request.createdAt)), requireCurrent,
                    )
                }
            }
            val id = if (primary) creationJournal.execute(creatorId, "community", requireCurrent, prepare, sendAndSave)
                else prepare().let { sendAndSave(it); it.id }
            Result.success(id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }

    private suspend fun getMemberRole(communityId: String, userId: String): String? {
        if (authService.isLocalFallbackModeEnabled()) {
            return communityDao.getUserRoleSync(communityId.trim().lowercase(), userId.trim().lowercase())
        }
        return collectionAdminRemoteStore.getMemberRole(
            membershipTable = "community_members",
            scopeColumn = "community_id",
            scopeId = communityId,
            userId = userId
        )
    }

    suspend fun deleteCommunityLocally(communityId: String) {
        val cleanId = communityId.trim().lowercase()
        if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
            val token = authService.getAccessTokenAwait() ?: throw IllegalStateException("No se pudo borrar el chat. Inténtalo de nuevo.")
            localFallbackApiClient.hideCollectionHistory(token, "communities", cleanId).getOrThrow()
        }
        val ownAccount = authService.getCurrentUserId()?.trim()?.lowercase() ?: return
        communityDao.removeMember(cleanId, ownAccount)
    }



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
            val session = captureSessionSnapshot(currentUserId)
            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val incomingIds = communityDao.getMessagesSync(cleanId).filter {
                    it.id in cleanMessageIds && it.sender_id.trim().lowercase() != currentUserId &&
                        conectate.com.core.messaging.UnreadPolicy.isEligibleStatusForReadTransition(it.status)
                }.map { it.id }
                if (!isSessionSnapshotValid(session, "community-visible-local")) return@withContext
                val newlySeen = readJournal.record(currentUserId, "community", cleanId, incomingIds)
                communityDao.markMessagesAsReadByIds(cleanId, now, currentUserId, incomingIds)
                communityDao.subtractReadMessages(cleanId, newlySeen.size)
                _activeCommunityMessages[cleanId]?.update { messages -> messages.map {
                    if (it.id in incomingIds) it.copy(status = "read", read_at = now) else it
                } }
                if (!token.isNullOrBlank()) readSynchronizer.flush(currentUserId, "community", token)
                return@withContext
            }
            var remotelyReadIds = if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                cleanMessageIds.toSet()
            } else runCatching {
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
            if (!isSessionSnapshotValid(session, "community-visible-persist")) return@withContext
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



    suspend fun markAsRead(
        communityId: String,
        requireRemoteConfirmation: Boolean = false,
        expectedAccountId: String? = null
    ) {
        val cleanId = communityId.trim().lowercase()
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
                isSessionCurrent = { isSessionSnapshotValid(session, "community-notification-read") },
                synchronize = {
                    if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured && !token.isNullOrBlank()) {
                        localFallbackApiClient.markCollectionAsRead(token, "communities", cleanId)
                            .onFailure { android.util.Log.w("CommunityRepository", "Ubuntu mark community read failed for $cleanId", it) }.isSuccess
                    } else runCatching {
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
                    }.getOrDefault(false)
                },
                applyLocal = {
                    communityDao.markAllAsRead(cleanId, now, currentUserId)
                    if (!isSessionSnapshotValid(session, "community-read-before-unread"))
                        throw CancellationException("Read receipt session changed")
                    communityDao.resetUnreadCount(cleanId)
                    if (!requireRemoteConfirmation && isSessionSnapshotValid(session, "community-read-before-cancel")) {
                        runCatching {
                            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            MessageNotificationFactory.cancelConversation(context, conectate.com.util.NotificationAccountTarget(userId = authService.getCurrentUserId()), "community", cleanId)
                        }
                    }
                }
            )
        }
    }



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


    suspend fun searchCommunities(query: String): Result<List<Community>> = withContext(Dispatchers.IO) {
        try {
            if (authService.isLocalFallbackModeEnabled() && localFallbackApiClient.isConfigured) {
                val userId = authService.getCurrentUserId()?.trim()?.lowercase()
                    ?: return@withContext Result.failure(Exception("Not authenticated"))
                val token = authService.getAccessTokenAwait()
                    ?: return@withContext Result.failure(Exception("Sesion Ubuntu no disponible"))
                val cleanQuery = query.trim()
                return@withContext localFallbackApiClient.exploreCommunities(token, cleanQuery)
            }
            var response = supabaseConfig.client.postgrest["communities"].select {
                filter { 
                    if (query.isNotBlank()) {
                        or {
                            ilike("name", "%$query%")
                            ilike("description", "%$query%")
                            ilike("category", "%$query%")
                        }
                    }
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
        onlyAdminsCanTalk: Boolean = false,
        weeklyMessageLimit: Int = 0
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var me = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            var cleanId = communityId.trim().lowercase()
            var trimmedName = name.trim()
            if (trimmedName.isBlank()) {
                return@withContext Result.failure(Exception("El nombre de la comunidad no puede estar vacío."))
            }
            var normalizedDescription = description?.trim().orEmpty()
            var community = getCommunityById(cleanId) ?: return@withContext Result.failure(Exception("Community not found"))
            var privileges = resolveCommunityGovernancePrivileges(community, me)
            CollectionGovernanceSupport.requireCanManageCollection(
                privileges = privileges,
                errorMessage = "No tienes permisos para editar la comunidad."
            )
            var imageUrl: String? = null
            
            if (imageBytes != null) {
                imageUrl = uploadCommunityImage(cleanId, imageBytes).getOrThrow()
            }

            val updatePayload = buildJsonObject {
                put("name", trimmedName)
                put("description", normalizedDescription)
                put("is_public", isPublic)
                put("only_admins_can_talk", onlyAdminsCanTalk)
                put("daily_message_limit", 0)
                put("weekly_message_limit", weeklyMessageLimit.coerceIn(0, 10000))
                if (imageUrl != null) put("image", imageUrl)
            }
            val primaryResult = if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val token = authService.getAccessTokenAwait() ?: return@withContext Result.failure(Exception("No token"))
                localFallbackApiClient.updateCommunityInfo(
                    token,
                    cleanId,
                    trimmedName,
                    normalizedDescription,
                    isPublic,
                    onlyAdminsCanTalk,
                    weeklyMessageLimit,
                    imageUrl
                )
            } else {
                collectionAdminRemoteStore.updateCollectionInfo(
                    collectionTable = "communities",
                    scopeId = cleanId,
                    payload = updatePayload
                )
            }

            val finalResult = if (primaryResult.isSuccess) {
                primaryResult
            } else if (localFallbackApiClient.isConfigured) {
                val token = authService.getAccessTokenAwait()
                    ?: return@withContext Result.failure(primaryResult.exceptionOrNull() ?: Exception("No token"))
                localFallbackApiClient.updateCommunityInfo(
                    token,
                    cleanId,
                    trimmedName,
                    normalizedDescription,
                    isPublic,
                    onlyAdminsCanTalk,
                    weeklyMessageLimit,
                    imageUrl
                )
            } else {
                primaryResult
            }

            finalResult.getOrThrow()

            val cached = communityDao.getCommunityById(cleanId)
            val updatedCommunity = community.copy(
                name = trimmedName,
                description = normalizedDescription.ifBlank { null },
                image = imageUrl ?: community.image,
                is_public = isPublic,
                only_admins_can_talk = onlyAdminsCanTalk,
                daily_message_limit = 0,
                weekly_message_limit = weeklyMessageLimit.coerceIn(0, 10000)
            )
            communityDao.insertCommunities(
                listOf(cached?.copy(
                    name = updatedCommunity.name,
                    description = updatedCommunity.description,
                    image = updatedCommunity.image,
                    is_public = updatedCommunity.is_public,
                    only_admins_can_talk = updatedCommunity.only_admins_can_talk,
                    daily_message_limit = updatedCommunity.daily_message_limit,
                    weekly_message_limit = updatedCommunity.weekly_message_limit
                ) ?: CommunityEntity.fromDomain(updatedCommunity))
            )
            scope.launch { syncCommunities() }
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



    suspend fun leaveCommunity(communityId: String): Result<Unit> = withContext(Dispatchers.IO) {
        var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
        if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
            val token = authService.getAccessTokenAwait()
                ?: return@withContext Result.failure(Exception("Sesion Ubuntu no disponible"))
            val cleanId = communityId.trim().lowercase()
            val cleanUserId = userId.trim().lowercase()
            val exit = localFallbackApiClient.leaveCollectionWithHistory(token, "communities", cleanId).getOrThrow()
            val history = exit.historyAccess ?: localFallbackApiClient.loadCommunityRoom(token, cleanId).getOrNull()?.historyAccess
            history?.let { collectionHistoryStore.save(cleanUserId, "community", cleanId, it) }
            communityDao.insertMembers(listOf(CommunityMemberEntity(cleanId, cleanUserId, "former_member", history?.joinedAt ?: java.time.Instant.now().toString())))
            communityDao.updateMembership(cleanId, false)
            return@withContext Result.success(Unit)
        }
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
            contentType = "audio/mp4"
        )



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

            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val token = authService.getAccessTokenAwait() ?: return@withContext Result.failure(Exception("Sesion Ubuntu no disponible"))
                localFallbackApiClient.leaveCollection(token, "communities", cleanId, targetUserId).getOrThrow()
                communityDao.removeMember(cleanId, targetUserId)
                scope.launch { syncCommunities() }
                return@withContext Result.success(Unit)
            }

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

            if (!privileges.isCreator) return@withContext Result.failure(Exception("Solo quien creó la comunidad puede gestionar administradores."))
            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val token = authService.getAccessTokenAwait()
                    ?: return@withContext Result.failure(Exception("Sesion Ubuntu no disponible"))
                localFallbackApiClient.joinCollection(token, "communities", cleanId, targetUserId, newRole).getOrThrow()
                val originalJoinedAt = communityDao.getMemberJoinedAt(cleanId, targetUserId)
                if (originalJoinedAt != null) communityDao.insertMembers(listOf(
                    CommunityMemberEntity(
                        community_id = cleanId,
                        user_id = targetUserId,
                        role = newRole.trim().lowercase(),
                        joined_at = originalJoinedAt
                    )
                ))
                scope.launch { syncCommunities() }
                return@withContext Result.success(Unit)
            }

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



    suspend fun joinCommunity(communityId: String): Result<Unit> = withContext(Dispatchers.IO) {
        var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
        var joinedAt = java.time.Instant.now().toString()
        if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
            val token = authService.getAccessTokenAwait()
                ?: return@withContext Result.failure(Exception("Sesion Ubuntu no disponible"))
            val cleanId = communityId.trim().lowercase()
            localFallbackApiClient.joinCollection(token, "communities", cleanId).getOrThrow()
            val cleanUserId = userId.trim().lowercase()
            communityDao.insertMembers(listOf(CommunityMemberEntity(cleanId, cleanUserId, "member", joinedAt)))
            communityDao.updateMembership(cleanId, true)
            syncCommunities()
            return@withContext Result.success(Unit)
        }
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



    suspend fun deleteCommunity(communityId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("Not authenticated"))
            var cleanId = communityId.trim().lowercase()
            var community = getCommunityById(cleanId)
            var privileges = community?.let { resolveCommunityGovernancePrivileges(it, userId) }

            if (authService.isLocalFallbackModeEnabled() && localFallbackApiClient.isConfigured) {
                val token = authService.getAccessTokenAwait()
                    ?: return@withContext Result.failure(Exception("Sesion Ubuntu no disponible"))
                if (privileges?.canManageCollection == true) {
                    localFallbackApiClient.deleteCollection(token, "communities", cleanId).getOrThrow()
                } else {
                    localFallbackApiClient.leaveCollection(token, "communities", cleanId).getOrThrow()
                }
                communityDao.deleteCommunity(cleanId)
                scope.launch { syncCommunities() }
                return@withContext Result.success(Unit)
            }

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
        replyTo: conectate.com.data.model.Message? = null,
        pollId: String? = null,
        isForwarded: Boolean = false,
        forwardedFromMessageId: String? = null,
        forwardedFromUserId: String? = null,
        messageId: String? = null,
        channelId: String? = null,
        requireServerAcknowledgement: Boolean = false,
        onQueued: suspend () -> Unit = {}
    ): Result<Unit> = sendCommunityMessage(
        accountLease = authService.captureAccountLease(),
        communityId = communityId,
        content = content,
        contentType = contentType,
        mediaUrl = mediaUrl,
        replyToId = replyToId,
        replyTo = replyTo,
        pollId = pollId,
        isForwarded = isForwarded,
        forwardedFromMessageId = forwardedFromMessageId,
        forwardedFromUserId = forwardedFromUserId,
        messageId = messageId,
        channelId = channelId,
        requireServerAcknowledgement = requireServerAcknowledgement,
        onQueued = onQueued,
    )

    internal suspend fun sendCommunityMessage(
        accountLease: conectate.com.data.supabase.AuthSessionLease,
        communityId: String,
        content: String,
        contentType: String = "text",
        mediaUrl: String? = null,
        replyToId: String? = null,
        replyTo: conectate.com.data.model.Message? = null,
        pollId: String? = null,
        isForwarded: Boolean = false,
        forwardedFromMessageId: String? = null,
        forwardedFromUserId: String? = null,
        messageId: String? = null,
        channelId: String? = null,
        requireServerAcknowledgement: Boolean = false,
        onQueued: suspend () -> Unit = {}
    ): Result<Unit> {
        val id = messageId?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
        return conectate.com.data.repository.message.executeOwnedMessageSend(accountLease, sessionWriteGuard, onQueued) { session, queued ->
            messageSendCoordinator.withMessage(id) {
                sessionWriteGuard.requireValid(session)
                val existing = communityDao.getMessageById(id)
                sessionWriteGuard.requireValid(session)
                if (existing != null) {
                    if (existing.sender_id.trim().lowercase() != session.accountId || existing.community_id.trim().lowercase() != communityId.trim().lowercase()) {
                        return@withMessage Result.failure(IllegalArgumentException("El mensaje pertenece a otra conversación."))
                    }
                    if (existing.deleted_for_all) return@withMessage Result.failure(IllegalStateException("No se puede reenviar un mensaje eliminado."))
                    if (existing.is_synced && conectate.com.core.messaging.messageSendConfirmation(
                            synced = true, status = existing.status, pendingAction = existing.pending_action).isSuccess) {
                        return@withMessage Result.success(Unit)
                    }
                }
                if (!isSessionSnapshotValid(session, "collection-foreground-send")) {
                    Result.failure(IllegalStateException("La cuenta cambió durante el envío."))
                } else sendCommunityMessageInternal(
                    session = session,
                    communityId = communityId,
                    content = content,
                    contentType = contentType,
                    mediaUrl = mediaUrl,
                    replyToId = replyToId,
                    replyTo = replyTo,
                    pollId = pollId,
                    isForwarded = isForwarded,
                    forwardedFromMessageId = forwardedFromMessageId,
                    forwardedFromUserId = forwardedFromUserId,
                    messageId = id,
                    channelId = channelId,
                    onQueued = queued
                ).let { result ->
                    if (result.isFailure || !requireServerAcknowledgement) result else {
                        val persisted = communityDao.getMessageById(id)
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
    }

    private suspend fun sendCommunityMessageInternal(
        session: SessionWriteGuard.Snapshot,
        communityId: String,
        content: String,
        contentType: String = "text",
        mediaUrl: String? = null,
        replyToId: String? = null,
        replyTo: conectate.com.data.model.Message? = null,
        pollId: String? = null,
        isForwarded: Boolean = false,
        forwardedFromMessageId: String? = null,
        forwardedFromUserId: String? = null,
        messageId: String? = null,
        channelId: String? = null,
        onQueued: suspend () -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            sessionWriteGuard.requireValid(session)
            val senderId = checkNotNull(session.accountId)
            var cleanId = communityId.trim().lowercase()
            var community = getCommunityById(cleanId)
                ?: return@withContext Result.failure(Exception("La comunidad ya no está disponible."))
            val role = communityDao.getUserRoleSync(cleanId, senderId)?.trim()?.lowercase()
            val isCreator = community.creator_id.trim().lowercase() == senderId && role != null
            var isMember = role != null
            val isAdmin = isCreator || role == "admin" || role == "owner" || role == "creator"
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
            if (community.only_admins_can_talk && !isAdmin) {
                return@withContext Result.failure(Exception("Solo los administradores pueden escribir en esta comunidad."))
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
            val finalContent = if (contentType == "contact") {
                // No redactar teléfonos/correos dentro del JSON del contacto:
                // la privacidad de la comunidad ya se controla por membresía.
                sanitizedContent
            } else {
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
                moderation.sanitizedText
            }
            if (contentType == "text" && mediaUrl.isNullOrBlank() && finalContent.isBlank()) {
                return@withContext Result.failure(Exception("El mensaje no puede estar vacio"))
            }
            
            val msgId = messageId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().lowercase()
            var now = java.time.Instant.now().toString()

            var msg = CommunityMessage(
                id = msgId,
                community_id = cleanId,
                channel_id = channelId?.trim()?.lowercase()?.ifBlank { null },
                sender_id = senderId,
                content = finalContent,
                content_type = contentType,
                media_url = mediaUrl,
                poll_id = pollId,
                created_at = now,
                reply_to_id = replyToId,
                reply_to = replyTo?.let {
                    CommunityMessage(
                        id = it.id,
                        community_id = cleanId,
                        channel_id = channelId?.trim()?.lowercase()?.ifBlank { null },
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

            // Optimistic local insert
            sessionWriteGuard.requireValid(session)
            communityDao.insertMessagesOwned(
                listOf(
                    CommunityMessageEntity.fromDomain(
                        msg = msg,
                        isSynced = false,
                        pendingAction = "send",
                        retryCount = 0,
                        lastRetryAt = null
                    )
                )
            ) { sessionWriteGuard.requireValid(session) }
            PendingMessageSyncScheduler.enqueue(context, "community_message_pending")
            sessionWriteGuard.requireValid(session)
            updateCommunityMessageInFlow(cleanId, msg)
            onQueued()
            if (!isSessionSnapshotValid(session, "community-foreground-resume")) return@withContext Result.failure(IllegalStateException("La cuenta cambió durante el envío."))
            val preview = buildConversationBackgroundPreview(contentType, finalContent)
                ?: buildCallEventPreview(contentType, finalContent)
                ?: CollectionMessageSyncSupport.buildMetadataPreview(contentType, finalContent)
            communityDao.updateMetadataOwned(cleanId, preview, now) { sessionWriteGuard.requireValid(session) }

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

            var outgoingMediaUrl = mediaUrl
            PendingOutgoingMediaStore.resolve(context, mediaUrl)?.let { localMediaUri ->
                val uploadResult = collectionMediaStore.uploadMessageMediaFromUri(
                    context = context,
                    ownerId = cleanId,
                    sourceUri = localMediaUri,
                    preferredMediaType = contentType,
                    primaryBucket = "community-media",
                    fallbackBucket = "communities",
                    filePrefix = "media_comm",
                    accountId = senderId,
                    messageId = msgId
                )
                outgoingMediaUrl = uploadResult.getOrElse { error ->
                    val retryable = CollectionRepositorySupport.isRetryableNetworkError(
                        error as? Exception ?: Exception(error),
                        transientNetworkRetryCodes
                    )
                    markPendingCommunityMessageFailed(msgId, session)
                    if (retryable) return@withContext Result.success(Unit)
                    communityDao.updateMessageSyncStatusOwned(msgId, isSynced = false, pendingAction = "send") { sessionWriteGuard.requireValid(session) }
                    return@withContext Result.failure(error)
                }
                if (!isSessionSnapshotValid(session, "community-foreground-resume")) return@withContext Result.failure(IllegalStateException("La cuenta cambió durante el envío."))
                msg = msg.copy(media_url = outgoingMediaUrl)
                communityDao.insertMessagesOwned(
                    listOf(CommunityMessageEntity.fromDomain(msg, isSynced = false, pendingAction = "send"))
                ) { sessionWriteGuard.requireValid(session) }
                sessionWriteGuard.requireValid(session)
                updateCommunityMessageInFlow(cleanId, msg)
                PendingOutgoingMediaStore.delete(context, localMediaUri)
            }

            val msgJson = buildJsonObject {
                put("id", msgId)
                put("community_id", cleanId)
                put("channel_id", msg.channel_id)
                put("sender_id", senderId)
                put("content", finalContent)
                put("content_type", contentType)
                put("media_url", outgoingMediaUrl)
                put("reply_to_id", replyToId)
                put("poll_id", pollId)
                put("created_at", now)
                put("is_forwarded", isForwarded)
                put("forwarded_from_message_id", forwardedFromMessageId)
                put("forwarded_from_user_id", forwardedFromUserId)
            }
            var result = if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val token = authService.getAccessTokenForAccountAwait(senderId)
                if (!isSessionSnapshotValid(session, "community-foreground-resume")) return@withContext Result.failure(IllegalStateException("La cuenta cambió durante el envío."))
                if (token.isNullOrBlank()) {
                    Result.failure(Exception("Sesion Ubuntu no disponible"))
                } else {
                    localFallbackApiClient.sendCommunityMessage(
                        accessToken = token,
                        communityId = cleanId,
                        content = finalContent,
                        contentType = contentType,
                        mediaUrl = outgoingMediaUrl,
                        mediaSize = 0L,
                        replyToId = replyToId,
                        channelId = msg.channel_id,
                        messageId = msgId,
                        isForwarded = isForwarded,
                        forwardedFromMessageId = forwardedFromMessageId,
                        forwardedFromUserId = forwardedFromUserId
                    ).map { serverMessage ->
                        if (!isSessionSnapshotValid(session, "community-foreground-resume")) return@withContext Result.failure(IllegalStateException("La cuenta cambió durante el envío."))
                        val synced = mergeSyncedCommunityMessageWithLocalState(
                            incoming = acknowledgeCommunityMessage(serverMessage, cleanId).copy(
                                channel_id = serverMessage.channel_id ?: msg.channel_id,
                                reply_to_id = serverMessage.reply_to_id ?: msg.reply_to_id,
                                reply_to = serverMessage.reply_to ?: msg.reply_to
                            ),
                            local = msg
                        )
                        communityDao.insertMessagesOwned(
                            listOf(
                                CommunityMessageEntity.fromDomain(
                                    msg = synced,
                                    isSynced = true,
                                    pendingAction = null
                                )
                            )
                        ) { sessionWriteGuard.requireValid(session) }
                        communityDao.updateMetadataOwned(
                            cleanId,
                            CollectionMessageSyncSupport.buildMetadataPreview(synced.content_type, synced.content),
                            synced.created_at
                        ) { sessionWriteGuard.requireValid(session) }
                        sessionWriteGuard.requireValid(session)
                        updateCommunityMessageInFlow(cleanId, synced)
                        Unit
                    }
                }
            } else {
                insertCommunityMessageWithRetry(msgId, msgJson, isCurrentSession = { isSessionSnapshotValid(session, "community-foreground-retry") })
            }
            sessionWriteGuard.requireValid(session)
            if (result.isFailure) {
                val syncError = result.exceptionOrNull()
                val shouldRemainQueued = syncError?.message?.contains("Sesion Ubuntu no disponible", ignoreCase = true) == true ||
                    (syncError != null && CollectionRepositorySupport.isRetryableNetworkError(
                        syncError as? Exception ?: Exception(syncError),
                        transientNetworkRetryCodes
                    ))
                if (shouldRemainQueued) {
                    communityDao.updateMessageSyncStatusOwned(msgId, isSynced = false, pendingAction = "send") { sessionWriteGuard.requireValid(session) }
                    communityDao.getMessageById(msgId)?.toDomain()?.let { queued -> updateCommunityMessageInFlow(cleanId, queued) }
                    runCatching {
                        operationGuard.audit(
                            action = GuardedAction.COMMUNITY_MESSAGE_SEND,
                            actorId = senderId,
                            scopeId = cleanId,
                            outcome = GuardOutcome.QUEUED,
                            attributes = mapOf("message_id" to msgId, "reason" to "network_unavailable")
                        )
                    }
                    return@withContext Result.success(Unit)
                }
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
                markPendingCommunityMessageFailed(msgId, session)
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
        } catch (e: CancellationException) {
            throw e
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
        maxAttempts: Int = 4,
        isCurrentSession: () -> Boolean = { true }
    ): Result<Unit> {
        return CollectionPendingMessageRetrySupport.retryPendingMessageSend(
            messageId = messageId,
            maxAttempts = maxAttempts,
            sendAction = {
                if (!isCurrentSession()) Result.failure(IllegalStateException("La cuenta cambió durante el envío"))
                else sendCommunityMessageSecureOrFallback(messageId = messageId, payload = payload)
            },
            isDuplicateKeyError = CollectionRepositorySupport::isDuplicateKeyError,
            isRetryableNetworkError = { error -> CollectionRepositorySupport.isRetryableNetworkError(error, transientNetworkRetryCodes) },
            onConfirmedSuccess = { if (isCurrentSession()) markCommunityMessageAcknowledged(messageId) },
            onDuplicateTreatedAsSuccess = { if (isCurrentSession()) markCommunityMessageAcknowledged(messageId) },
            onTerminalFailure = {
                if (isCurrentSession()) {
                    communityDao.markRetryAttempt(messageId, java.time.Instant.now().toString())
                    communityDao.updateMessageSyncStatus(messageId, isSynced = false, pendingAction = "send")
                    communityDao.getMessageById(messageId)?.let { row ->
                        val failed = row.copy(status = "failed")
                        communityDao.insertMessages(listOf(failed))
                        updateCommunityMessageInFlow(failed.community_id, failed.toDomain())
                    }
                }
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

    suspend fun retryMessage(messageId: String, communityId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val actor = authService.getCurrentUserId()?.trim()?.lowercase() ?: error("Inicia sesión de nuevo.")
            val session = captureSessionSnapshot(actor)
            val pending = communityDao.getMessageById(messageId) ?: error("El mensaje ya no está disponible.")
            check(pending.sender_id.equals(actor, true) && pending.community_id.equals(communityId, true) && !pending.deleted_for_all) {
                "No puedes reenviar este mensaje."
            }
            if (pending.is_synced) return@runCatching
            check(isSessionSnapshotValid(session, "community-manual-retry")) { "La cuenta cambió." }
            retryPendingCommunityMessages(targetMessageId = messageId)
            check(isSessionSnapshotValid(session, "community-manual-retry-complete")) { "La cuenta cambió." }
            val latest = communityDao.getMessageById(messageId) ?: error("El mensaje ya no está disponible.")
            check(latest.status != "failed") { "No se pudo reenviar. Revisa tu conexión e inténtalo de nuevo." }
        }.onFailure { if (it is CancellationException) throw it }
    }


    private suspend fun markPendingCommunityMessageFailed(messageId: String, session: SessionWriteGuard.Snapshot) {
        if (!isSessionSnapshotValid(session, "community-pending-failure")) return
        val current = communityDao.getMessageById(messageId) ?: return
        if (current.is_synced || current.deleted_for_all || current.pending_action != "send") return
        val failed = current.copy(status = "failed", retry_count = current.retry_count + 1, last_retry_at = java.time.Instant.now().toString())
        communityDao.insertMessagesOwned(listOf(failed)) { sessionWriteGuard.requireValid(session) }
        sessionWriteGuard.requireValid(session)
        updateCommunityMessageInFlow(failed.community_id, failed.toDomain())
    }
    private suspend fun retryPendingCommunityMessages(maxBatch: Int = 40, targetMessageId: String? = null) {
        var userId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return
        val session = captureSessionSnapshot(userId)
        val pending = if (targetMessageId == null) communityDao.getPendingOutgoingMessages(userId, maxBatch)
            else listOfNotNull(communityDao.getMessageById(targetMessageId)).filter { !it.is_synced && it.pending_action == "send" }
        if (!isSessionSnapshotValid(session, "community-pending-loaded")) return
        if (pending.isEmpty()) return

        pending.forEach { originalEntity ->
            if (!isSessionSnapshotValid(session, "community-pending-start")) return
            if (originalEntity.sender_id.trim().lowercase() != userId) return@forEach
            messageSendCoordinator.withMessage(originalEntity.id) pendingMessage@ {
                val fresh = communityDao.getMessageById(originalEntity.id) ?: return@pendingMessage
                if (fresh.is_synced || fresh.pending_action != "send" || fresh.deleted_for_all || !fresh.sender_id.trim().equals(userId, true)) return@pendingMessage
                if (!isSessionSnapshotValid(session, "community-pending-locked")) return@pendingMessage
                var pendingEntity = fresh.copy(status = "sending")
                communityDao.insertMessages(listOf(pendingEntity))
                updateCommunityMessageInFlow(pendingEntity.community_id, pendingEntity.toDomain())
                PendingOutgoingMediaStore.resolve(context, pendingEntity.media_url)?.let { localMediaUri ->
                    val remoteUrl = collectionMediaStore.uploadMessageMediaFromUri(
                        context = context,
                        ownerId = pendingEntity.community_id,
                        sourceUri = localMediaUri,
                        preferredMediaType = pendingEntity.content_type,
                        primaryBucket = "community-media",
                        fallbackBucket = "communities",
                        filePrefix = "media_comm",
                        accountId = userId,
                        messageId = pendingEntity.id
                    ).getOrElse { error ->
                        if (error is CancellationException) throw error
                        markPendingCommunityMessageFailed(pendingEntity.id, session)
                        android.util.Log.w("CommunityRepository", "Pending community media still unavailable: ${pendingEntity.id}", error)
                        return@pendingMessage
                    }
                    if (!isSessionSnapshotValid(session, "community-pending-media-uploaded")) return@pendingMessage
                    pendingEntity = pendingEntity.copy(media_url = remoteUrl)
                    communityDao.insertMessages(listOf(pendingEntity))
                    if (!isSessionSnapshotValid(session, "community-pending-media-persisted")) return@pendingMessage
                    updateCommunityMessageInFlow(pendingEntity.community_id, pendingEntity.toDomain())
                    PendingOutgoingMediaStore.delete(context, localMediaUri)
                }

                val payload = buildJsonObject {
                    put("id", pendingEntity.id)
                    put("community_id", pendingEntity.community_id)
                    put("channel_id", pendingEntity.channel_id)
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
                if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                    val token = authService.getAccessTokenAwait()
                    if (!token.isNullOrBlank()) {
                        if (!isSessionSnapshotValid(session, "community-pending-token")) return@pendingMessage
                        localFallbackApiClient.sendCommunityMessage(
                            accessToken = token,
                            communityId = pendingEntity.community_id,
                            content = pendingEntity.content,
                            contentType = pendingEntity.content_type,
                            mediaUrl = pendingEntity.media_url,
                            mediaSize = 0L,
                            replyToId = pendingEntity.reply_to_id,
                            channelId = pendingEntity.channel_id,
                            messageId = pendingEntity.id,
                            isForwarded = pendingEntity.is_forwarded,
                            forwardedFromMessageId = pendingEntity.forwarded_from_message_id,
                            forwardedFromUserId = pendingEntity.forwarded_from_user_id
                        ).onSuccess { serverMessage ->
                            if (!isSessionSnapshotValid(session, "community-pending-acknowledge")) return@onSuccess
                            val synced = mergeSyncedCommunityMessageWithLocalState(
                                incoming = acknowledgeCommunityMessage(serverMessage, pendingEntity.community_id),
                                local = pendingEntity.toDomain()
                            )
                            communityDao.insertMessages(
                                listOf(
                                    CommunityMessageEntity.fromDomain(
                                        msg = synced,
                                        isSynced = true,
                                        pendingAction = null,
                                        retryCount = pendingEntity.retry_count,
                                        lastRetryAt = pendingEntity.last_retry_at
                                    )
                                )
                            )
                            if (!isSessionSnapshotValid(session, "community-pending-acknowledged")) return@onSuccess
                            updateCommunityMessageInFlow(pendingEntity.community_id, synced)
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            markPendingCommunityMessageFailed(pendingEntity.id, session)
                        }
                    } else {
                        markPendingCommunityMessageFailed(pendingEntity.id, session)
                    }
                } else {
                    insertCommunityMessageWithRetry(
                        pendingEntity.id, payload,
                        isCurrentSession = { isSessionSnapshotValid(session, "community-pending-retry") }
                    )
                }
            }
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

    suspend fun sendCommunityMediaMessage(
        communityId: String,
        sourceUri: android.net.Uri,
        contentType: String,
        content: String,
        channelId: String? = null,
        replyToId: String? = null,
        replyTo: conectate.com.data.model.Message? = null,
        onQueued: suspend () -> Unit = {}
    ): Result<Unit> = sendCommunityMediaMessage(
        accountLease = authService.captureAccountLease(),
        communityId = communityId,
        sourceUri = sourceUri,
        contentType = contentType,
        content = content,
        channelId = channelId,
        replyToId = replyToId,
        replyTo = replyTo,
        onQueued = onQueued,
    )

    internal suspend fun sendCommunityMediaMessage(
        accountLease: conectate.com.data.supabase.AuthSessionLease,
        communityId: String,
        sourceUri: android.net.Uri,
        contentType: String,
        content: String,
        channelId: String? = null,
        replyToId: String? = null,
        replyTo: conectate.com.data.model.Message? = null,
        stableMessageId: String? = null,
        onQueued: suspend () -> Unit = {}
    ): Result<Unit> {
        val accountId = accountLease.accountId ?: return Result.failure(Exception("Inicia sesión para enviar mensajes."))
        val session = sessionWriteGuard.capture(accountLease, kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
        sessionWriteGuard.requireValid(session)
        val messageId = stableMessageId?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
        val existing = if (stableMessageId != null) communityDao.getMessageById(messageId) else null
        sessionWriteGuard.requireValid(session)
        if (existing != null && (existing.is_synced || existing.deleted_for_all ||
                existing.sender_id.trim().lowercase() != session.accountId || existing.community_id.trim().lowercase() != communityId.trim().lowercase() ||
                existing.media_url?.let { it.startsWith("https://", true) || it.startsWith("http://", true) } == true)) {
            return sendCommunityMessage(accountLease = accountLease, communityId = communityId, content = content,
                contentType = contentType, mediaUrl = existing.media_url, messageId = messageId,
                replyToId = replyToId, replyTo = replyTo, channelId = channelId, onQueued = onQueued)
        }
        val pending = PendingOutgoingMediaStore.persist(
            context = context,
            accountId = accountId,
            namespace = "community",
            messageId = messageId,
            sourceUri = sourceUri,
            preferredMediaType = contentType,
            requireCurrent = { sessionWriteGuard.requireValid(session) },
        ).getOrElse { return Result.failure(it) }
        if (!isSessionSnapshotValid(session, "community-media-persisted")) {
            return Result.failure(IllegalStateException("La cuenta cambió durante el envío."))
        }
        return sendCommunityMessage(
            accountLease = accountLease,
            communityId = communityId,
            content = content,
            contentType = contentType,
            mediaUrl = pending.uri.toString(),
            messageId = messageId,
            channelId = channelId,
            replyToId = replyToId,
            replyTo = replyTo,
            onQueued = onQueued
        )
    }



    suspend fun editCommunityMessage(messageId: String, newContent: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("Not authenticated"))
            val normalizedContent = newContent.trim()
            if (normalizedContent.isBlank()) return@withContext Result.failure(Exception("El mensaje no puede estar vacío"))
            val existing = communityDao.getMessageById(messageId)?.toDomain()

            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val token = authService.getAccessTokenAwait()
                    ?: return@withContext Result.failure(Exception("No token"))
                val communityId = existing?.community_id?.trim()?.lowercase()
                    ?: return@withContext Result.failure(Exception("Mensaje no encontrado"))
                localFallbackApiClient.editCollectionMessage(
                    accessToken = token,
                    collection = "communities",
                    roomId = communityId,
                    messageId = messageId,
                    content = normalizedContent
                ).getOrElse { return@withContext Result.failure(it) }

                val updated = existing.copy(content = normalizedContent, is_edited = true)
                communityDao.insertMessages(listOf(CommunityMessageEntity.fromDomain(updated)))
                updateCommunityMessageInFlow(communityId, updated)
                return@withContext Result.success(Unit)
            }

            var updateJson = buildJsonObject {
                put("content", normalizedContent)
                put("is_edited", true)
            }
            supabaseConfig.client.postgrest["community_messages"].update(updateJson) {
                filter {
                    eq("id", messageId)
                    eq("sender_id", userId)
                }
            }
            existing?.copy(content = normalizedContent, is_edited = true)?.let { updated ->
                communityDao.insertMessages(listOf(CommunityMessageEntity.fromDomain(updated)))
                updateCommunityMessageInFlow(updated.community_id, updated)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    suspend fun deleteCommunityMessage(messageId: String, forEveryone: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
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
                isCurrentSession = { isSessionSnapshotValid(session, "community-delete") },
                load = { communityDao.getMessageById(messageId)?.toDomain() },
                markDeleted = { message ->
                    check(!forEveryone || message.sender_id.trim().lowercase() == normalizedUserId) {
                        "Solo puedes eliminar para todos tus propios mensajes"
                    }
                    if (forEveryone) message.copy(deleted_for_all = true)
                    else message.copy(deleted_by = (message.deleted_by + normalizedUserId)
                        .map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct())
                },
                save = { message ->
                    if (!isSessionSnapshotValid(session, "community-delete-save")) throw CancellationException("La cuenta cambió.")
                    communityDao.insertMessages(listOf(CommunityMessageEntity.fromDomain(message)))
                    if (!isSessionSnapshotValid(session, "community-delete-publish")) throw CancellationException("La cuenta cambió.")
                    updateCommunityMessageInFlow(message.community_id, message)
                },
                onOptimistic = { message ->
                    conectate.com.util.DeletedMessageIdentity.create(normalizedUserId, "community", message.community_id, message.id)?.let {
                        MessageNotificationFactory.redactMessage(context, it, message.created_at, forEveryone = false)
                    }
                },
                remoteDelete = { message ->
                    if (useLocalApi) {
                        localFallbackApiClient.deleteCollectionMessage(
                            accessToken = requireNotNull(token), collection = "communities", roomId = message.community_id,
                            messageId = messageId, forEveryone = forEveryone,
                        )
                    } else {
                        CollectionDeleteRetrySupport.retryRemoteDelete(
                            logTag = "CommunityRepository", messageId = messageId,
                            remoteDelete = {
                                val payload = buildJsonObject {
                                    if (forEveryone) put("deleted_for_all", true)
                                    else put("deleted_by", kotlinx.serialization.json.JsonArray(message.deleted_by.map(::JsonPrimitive)))
                                }
                                supabaseConfig.client.postgrest["community_messages"].update(payload) {
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

    suspend fun getCommunityMessageReadReceipts(messageId: String): Result<List<MessageReadReceipt>> = withContext(Dispatchers.IO) {
        var token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
        collectionMessageEngagementStore.getMessageReadReceipts(
            messageId = messageId,
            token = token,
            rpcFunctionName = "secure_get_community_message_receipts"
        )
    }

    fun observePollUpdates(): kotlinx.coroutines.flow.Flow<String> =
        conectate.com.data.repository.collection.collectionPollUpdates(ubuntuRealtimeClient, authService, "community")

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



    suspend fun getCommunityMembers(communityId: String): Result<List<CommunityMember>> = withContext(Dispatchers.IO) {
        val cleanCommunityId = communityId.trim().lowercase()
        val cachedMembers = runCatching { getCachedCommunityMembers(cleanCommunityId) }
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
                val payload = localFallbackApiClient.loadCommunityRoom(token, cleanCommunityId).getOrThrow()
                val members = payload.participants
                    .mapNotNull { participant ->
                        val cleanUserId = participant.id.trim().lowercase()
                        if (cleanUserId.isBlank()) null else CommunityMember(
                            community_id = cleanCommunityId,
                            user_id = cleanUserId,
                            role = participant.role.ifBlank { "member" },
                            joined_at = participant.joinedAt.ifBlank { java.time.Instant.now().toString() },
                            userName = participant.name.ifBlank { null },
                            userImage = participant.avatarUrl.ifBlank { null }
                        )
                    }
                    .distinctBy { it.user_id }
                if (members.isNotEmpty()) {
                    communityDao.insertMembers(members.map {
                        CommunityMemberEntity(
                            it.community_id.trim().lowercase(),
                            it.user_id.trim().lowercase(),
                            it.role,
                            it.joined_at
                        )
                    })
                    return@withContext Result.success(members)
                }
                // Keep the last usable snapshot if the room temporarily returns
                // no participants while the server is recovering.
                if (cachedMembers.isNotEmpty()) return@withContext Result.success(cachedMembers)
            }
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
            val local = cachedMembers.ifEmpty {
                runCatching { getCachedCommunityMembers(cleanCommunityId) }.getOrDefault(emptyList())
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
    internal suspend fun getCachedCommunityMembers(communityId: String): List<CommunityMember> {
        val basicMembers = communityDao.getMembersFlow(communityId).first().map {
            CommunityMember(it.community_id, it.user_id, it.role, it.joined_at)
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
            if (authService.isLocalFallbackSession() && localFallbackApiClient.isConfigured) {
                val token = authService.getAccessTokenAwait() ?: return
                val seenIds = readJournal.seenIds(currentUserId, "community", cleanId)
                val payload = localFallbackApiClient.loadCommunityRoom(token, cleanId).getOrElse { error ->
                    android.util.Log.e("CommunityRepository", "Error syncing Ubuntu community messages for $communityId", error)
                    return
                }
                val historyAccess = payload.historyAccess
                val pendingHistoryIds = communityDao.getMessagesSync(cleanId).filter { !it.is_synced && it.pending_action == "send" }.map { it.id }.toSet()
                if (historyAccess != null) {
                    if (!isSessionSnapshotValid(sessionSnapshot, "community-history-access:$cleanId")) return
                    collectionHistoryStore.save(currentUserId, "community", cleanId, historyAccess)
                        if (!historyAccess.isMember) {
                            if (historyAccess.canReadHistory) communityDao.insertMembers(listOf(CommunityMemberEntity(cleanId, currentUserId, "former_member", historyAccess.joinedAt ?: java.time.Instant.now().toString())))
                            else communityDao.removeMember(cleanId, currentUserId)
                        }
                    // Access is filtered on reads; metadata synchronization is not a delete command.
                }
                val localMessagesById = communityDao.getMessagesSync(cleanId)
                    .associateBy { it.id.trim().lowercase() }
                    .mapValues { (_, entity) -> entity.toDomain() }
                val mergedResponse = payload.messages.mapNotNull { row ->
                    runCatching {
                        acknowledgeCommunityMessage(
                            json.decodeFromJsonElement<CommunityMessage>(row),
                            cleanId
                        )
                    }.getOrNull()
                }.map { incoming ->
                    mergeSyncedCommunityMessageWithLocalState(
                        incoming = incoming,
                        local = localMessagesById[incoming.id.trim().lowercase()]
                    )
                }.map { message ->
                        if (message.id in seenIds && !message.deleted_for_all &&
                            conectate.com.core.messaging.UnreadPolicy.isEligibleStatusForReadTransition(message.status))
                            message.copy(status = "read") else message
                    }
                if (!isSessionSnapshotValid(sessionSnapshot, "community-sync-messages-after-ubuntu:$cleanId")) return
                if (_activeCommunityMessages.containsKey(cleanId)) {
                    val current = _activeCommunityMessages[cleanId]?.value ?: emptyList()
                    _activeCommunityMessages[cleanId]?.value = CollectionMessageSyncSupport.mergeActiveMessages(
                        current = current.filter { historyAccess == null || historyAccess.allows(it.created_at) || it.id in pendingHistoryIds },
                        incoming = mergedResponse,
                        idSelector = { it.id },
                        createdAtSelector = { it.created_at }
                    )
                }
                if (mergedResponse.isNotEmpty()) {
                    communityDao.insertMessages(mergedResponse.map { CommunityMessageEntity.fromDomain(it) })
                    chatImages?.get()?.prefetchRecentCommunityMedia(mergedResponse)
                    mergedResponse.maxByOrNull { it.created_at }?.let { lastMsg ->
                        deletedMessageStore.recordSummary(currentSessionUserId(), "community", cleanId, lastMsg.id, lastMsg.created_at)
                        communityDao.updateMetadata(
                            cleanId,
                            if (lastMsg.deleted_for_all) "Mensaje eliminado" else CollectionMessageSyncSupport.buildMetadataPreview(lastMsg.content_type, lastMsg.content),
                            lastMsg.created_at
                        )
                    }
                }
                return
            }
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
                        deletedMessageStore.recordSummary(currentSessionUserId(), "community", cleanId, lastMsg.id, lastMsg.created_at)
                    if (!isSessionSnapshotValid(sessionSnapshot, "community-sync-messages-before-metadata:$cleanId")) return
                    communityDao.updateMetadata(
                        cleanId,
                        if (lastMsg.deleted_for_all) "Mensaje eliminado" else CollectionMessageSyncSupport.buildMetadataPreview(lastMsg.content_type, lastMsg.content),
                        lastMsg.created_at
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CommunityRepository", "Error syncing messages", e)
        }
    }

}
