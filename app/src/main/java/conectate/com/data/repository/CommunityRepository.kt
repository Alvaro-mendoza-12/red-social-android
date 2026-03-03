package conectate.com.data.repository

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.app.NotificationManager
import android.app.NotificationChannel
import conectate.com.BuildConfig
import conectate.com.core.moderation.ContentModerationService
import conectate.com.core.moderation.ModerationContext
import conectate.com.core.moderation.ModerationVerdict
import conectate.com.core.observability.AppMetrics
import conectate.com.core.security.GuardedAction
import conectate.com.core.security.GuardOutcome
import conectate.com.core.security.InputSecurityPolicy
import conectate.com.core.security.OperationGuard
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
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.gotrue.auth
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
import android.widget.Toast
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
    @ApplicationContext private val context: Context
) : CommunitySyncDataSource {
    private val maxForwardTargetsPerBatch = 20
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }
    private val transientNetworkRetryCodes = listOf("timeout", "timed out", "connection", "network", "502", "503", "504")

    init {
        // [FIX] SINGLE POINT OF TRUTH for session/realtime initialization
        scope.launch {
            // Immediate check for existing session
            val currentSession = supabaseConfig.client.auth.currentSessionOrNull()
            if (currentSession != null) {
                val userId = currentSession.user?.id
                android.util.Log.d("CommunityRepository", "[COMM] Initial Session found ($userId). Starting listeners...")
                startRealtimeSubscriptions(userId ?: "")
                
                // Show Proof of Life Log (Debug only - No Toast)
                android.util.Log.d("CommunityRepository", "🟢 [C-REALTIME] Sistema Iniciado")
            }

            // Listen for future changes
            supabaseConfig.client.auth.sessionStatus.collect { status ->
                android.util.Log.d("CommunityRepository", "[COMM] sessionStatus update: ${status::class.simpleName}")
                if (status is io.github.jan.supabase.gotrue.SessionStatus.Authenticated) {
                    val userId = status.session.user?.id
                    android.util.Log.d("CommunityRepository", "[COMM] User authenticated ($userId). Starting listeners...")
                    startRealtimeSubscriptions(userId ?: "")
                } else if (status is io.github.jan.supabase.gotrue.SessionStatus.NotAuthenticated) {
                    android.util.Log.d("CommunityRepository", "[COMM] User not authenticated. Stopping listeners...")
                    stopRealtimeSubscriptions()
                }
            }
        }
    }

    private var realtimeJob: Job? = null
    private var communityListenerJob: Job? = null
    private var subscribedCommunityIds: Set<String> = emptySet()

    private fun startRealtimeSubscriptions(userId: String) {
        if (userId.isBlank()) return
        
        realtimeJob?.cancel()
        realtimeJob = scope.launch {
            // [CRITICAL] Force a sync first
            syncCommunities()
            retryPendingCommunityMessages()
            
            // Recalculate joined community IDs to filter Realtime
            val myCommunities = communityDao.getAllCommunitiesSync()
                .filter { it.is_member && !it.is_deleted }
                .map { it.id.trim().lowercase() }
                .toSet()
            subscribedCommunityIds = myCommunities

            launch {
                while (isActive) {
                    delay(45_000)
                    retryPendingCommunityMessages()
                }
            }
            
            android.util.Log.d("CommunityRepository", "🚀 [REALTIME] Starting subscriptions for ${myCommunities.size} communities")
            subscribeToCommunityChanges(userId, myCommunities.toList())
        }
    }

    private fun stopRealtimeSubscriptions() {
        realtimeJob?.cancel()
        realtimeJob = null
        communityListenerJob?.cancel()
        communityListenerJob = null
        android.util.Log.d("CommunityRepository", "[COMM] Realtime subscriptions stopped")
    }

    // --- STATE ---
    private val _activeCommunityMessages = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<List<CommunityMessage>>>()
    private var currentOpenedCommunityId: String? = null

    fun setActiveCommunity(communityId: String?) {
        currentOpenedCommunityId = communityId?.trim()?.lowercase()
        if (communityId != null) {
            val cleanId = communityId.trim().lowercase()
            // Clear system notifications for this community
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(cleanId.hashCode())
            } catch (e: Exception) {
                android.util.Log.e("CommunityRepository", "Error clearing notification", e)
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
                            val client = supabaseConfig.client
                            val cleanUserId = userId.trim().lowercase()
                            val channelName = "communities-messages-$cleanUserId"
                            val channel = client.channel(channelName)
                            
                            // [REALTIME FIX] Remove 'in.' filter as it might be unsupported and causing delivery failures.
                            // RLS already restricts messages to those the user should see.
                            val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                                table = "community_messages"
                            }

                            coroutineScope {
                                val msgScope = this
                                changes.onEach { change: PostgresAction ->
                                    android.util.Log.d("CommunityRepository", "📡 [REALTIME-MSG] Change received: $change")
                                    when (change) {
                                        is PostgresAction.Insert -> {
                                            val msg = json.decodeFromJsonElement<CommunityMessage>(change.record)
                                            handleIncomingMessage(msg)
                                        }
                                        is PostgresAction.Update -> {
                                            val msg = json.decodeFromJsonElement<CommunityMessage>(change.record)
                                            handleIncomingCommunityUpdate(msg)
                                        }
                                        else -> {}
                                    }
                                }.launchIn(this)
                                launch {
                                    channel.subscribe()
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
                        val client = supabaseConfig.client
                        val cleanUserId = userId.trim().lowercase()
                        val channelName = "communities-members-$cleanUserId"
                        val channel = client.channel(channelName)
                        
                        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
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
                                            val member = json.decodeFromJsonElement<CommunityMember>(change.record)
                                            communityDao.insertMembers(listOf(CommunityMemberEntity(member.community_id.trim().lowercase(), member.user_id.trim().lowercase(), member.role, member.joined_at)))
                                        } catch (e: Exception) {
                                            android.util.Log.e("CommunityRepository", "Error decoding member insert", e)
                                        }
                                    }
                                    is PostgresAction.Update -> {
                                        try {
                                            val member = json.decodeFromJsonElement<CommunityMember>(change.record)
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
                                channel.subscribe()
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
                        val client = supabaseConfig.client
                        val cleanUserId = userId.trim().lowercase()
                        val channelName = "communities-discovery-$cleanUserId"
                        val channel = client.channel(channelName)
                        
                        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                            table = "communities"
                        }
                        
                        coroutineScope {
                            val discoveryScope = this
                            changes.onEach { change: PostgresAction ->
                                android.util.Log.d("CommunityRepository", "🌍 [REALTIME-DISCOVERY] Community table change: $change")
                                when (change) {
                                    is PostgresAction.Insert -> {
                                        try {
                                            val community = json.decodeFromJsonElement<conectate.com.data.model.Community>(change.record)
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
                                            val community = json.decodeFromJsonElement<conectate.com.data.model.Community>(change.record)
                                            // Update local cache to reflect changes (name, image, etc.)
                                            val local = communityDao.getCommunityById(community.id)
                                            android.util.Log.i("CommunityRepository", "📝 [META-UPDATE] Community ${community.name} updated. OnlyAdmins=${community.only_admins_can_talk}")
                                            if (local != null || community.is_public) {
                                                communityDao.insertCommunities(listOf(conectate.com.data.local.entity.CommunityEntity.fromDomain(community, local?.unread_count ?: 0)))
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("CommunityRepository", "Error decoding community update. Check REPLICA IDENTITY FULL.", e)
                                        }
                                    }
                                    else -> {}
                                }
                            }.launchIn(this)
                            launch {
                                channel.subscribe()
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
    
    suspend fun handleIncomingMessage(msg: CommunityMessage) {
        withContext(Dispatchers.IO) {
            val cleanId = msg.community_id.trim().lowercase()
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
                    val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
                    
                    if (currentUserId != null && msg.sender_id.trim().lowercase() != currentUserId) {
                        scope.launch { markMessageAsDelivered(cleanId, msg.id) }
                    }

                    // Hydrate reply_to manually since Realtime payload allows it to be null/missing joins
                    val normalizedMsg = if (msg.reply_to_id != null && msg.reply_to == null) {
                        val reply = communityDao.getMessageById(msg.reply_to_id)?.toDomain()
                        msg.copy(reply_to = reply)
                    } else msg.copy(community_id = cleanId) // Ensure clean ID

                    // Hydrate sender name if missing
                    val hydratedMsg = if (normalizedMsg.sender_name.isNullOrBlank()) {
                        try {
                            val senderProfile = userRepository.getUserById(normalizedMsg.sender_id).getOrNull()
                            val displayName = senderProfile?.displayName?.ifBlank { senderProfile.username } 
                                ?: senderProfile?.username ?: "Usuario"
                            normalizedMsg.copy(sender_name = displayName)
                        } catch (e: Exception) {
                            normalizedMsg
                        }
                    } else normalizedMsg

                    communityDao.insertMessages(listOf(conectate.com.data.local.entity.CommunityMessageEntity.fromDomain(hydratedMsg)))
                    android.util.Log.d("CommunityRepository", "💾 [DB-SAVE] Mensaje ${msg.id} guardado en Room DB")
                    
                    // 5. Actualizar Flow Activo (Chat Screen)
                    updateCommunityMessageInFlow(cleanId, hydratedMsg)
                    android.util.Log.d("CommunityRepository", "🔄 [FLOW-UPDATE] Flow actualizado para $cleanId")
                    
                    // 6. Actualizar Metadatos de la Comunidad (Lista de Chats) y Notificar
                    val cleanSenderId = msg.sender_id.trim().lowercase()
                    val isMyMessage = cleanSenderId == currentUserId
                    
                    if (!isMyMessage) {
                        // CRITICAL FIX: Only increment unread & notify if this is a JOINED community
                        val isMember = if (currentUserId != null) communityDao.isUserMemberSync(cleanId, currentUserId) else false
                        
                        // Also check if this COMMUNITY is NOT currently open
                        val isCommunityCurrentlyOpen = (cleanId == currentOpenedCommunityId)
                        
                        if (isMember && !isCommunityCurrentlyOpen) {
                            android.util.Log.d("CommunityRepository", "🔔 [UNREAD+1] Incrementando contador para $cleanId (comunidad cerrada y soy miembro)")
                            communityDao.incrementUnreadCount(cleanId)
                            showNotification(msg, localCommunity.name)
                        } else {
                            android.util.Log.d("CommunityRepository", "👁️ [MSG-IGNORE-NOTIF] Member=$isMember, Open=$isCommunityCurrentlyOpen. Skipping notification.")
                        }
                    } else {
                        android.util.Log.d("CommunityRepository", "✍️ [MY-MSG] Mensaje enviado por mí. No incrementar unread.")
                    }
                    
                    // Actualizar last_message siempre (para todos los mensajes, incluso los míos)
                    communityDao.updateMetadata(cleanId, 
                        when(msg.content_type) {
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

    private suspend fun handleIncomingCommunityUpdate(msg: CommunityMessage) {
        withContext(Dispatchers.IO) {
            try {
                val existing = communityDao.getMessageById(msg.id)
                if (existing != null) {
                    val currentDomain = existing.toDomain()
                    val mergedReceipt = mergeCommunityReceiptState(
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
                    
                    communityDao.insertMessages(listOf(conectate.com.data.local.entity.CommunityMessageEntity.fromDomain(finalMsg)))
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
        val current = normalizeCommunityReceiptStatus(currentStatus)
        val incoming = normalizeCommunityReceiptStatus(incomingStatus)
        var mergedReadAt = incomingReadAt ?: currentReadAt
        var mergedDeliveredAt = incomingDeliveredAt ?: currentDeliveredAt

        val candidate = when {
            !mergedReadAt.isNullOrBlank() || incoming == "read" -> "read"
            !mergedDeliveredAt.isNullOrBlank() || incoming == "delivered" -> "delivered"
            else -> incoming
        }
        val finalStatus = if (communityReceiptRank(candidate) >= communityReceiptRank(current)) candidate else current
        if (finalStatus == "read" && mergedDeliveredAt.isNullOrBlank()) {
            mergedDeliveredAt = mergedReadAt
        }
        return CommunityReceiptMergeResult(finalStatus, mergedDeliveredAt, mergedReadAt)
    }

    // --- NOTIFICATIONS (SYSTEM) ---
    
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    private suspend fun showNotification(msg: CommunityMessage, communityName: String) {
        withContext(Dispatchers.Main) {
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                
                // CRITICAL: Use unique channel ID for communities (different from 1-to-1 and groups)
                val channelId = "community_messages_channel_v2"
                
                val channel = android.app.NotificationChannel(
                    channelId,
                    "Mensajes de Comunidades",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notificaciones de mensajes en comunidades"
                    enableVibration(true)
                    enableLights(true)
                    lightColor = android.graphics.Color.BLUE
                    setShowBadge(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                notificationManager.createNotificationChannel(channel)
                android.util.Log.d("CommunityRepository", "🔔 [NOTIFICATION] Channel created/verified: $channelId")

                val intent = android.content.Intent(context, conectate.com.MainActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("chat_id", msg.community_id)
                    putExtra("community_id", msg.community_id)
                    putExtra("is_community", true)
                    putExtra("target_type", "community")
                    putExtra("type", "new_message")
                }
                val pendingIntent = android.app.PendingIntent.getActivity(
                    context, 
                    msg.community_id.hashCode(), 
                    intent, 
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )

                val contentText = "${msg.sender_name ?: "Alguien"}: ${if(msg.content_type == "text") msg.content else "📎 Multimedia"}"

                val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(conectate.com.R.drawable.ic_notification)
                    .setContentTitle(communityName)
                    .setContentText(contentText)
                    .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(contentText))
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX) // MAXIMUM priority
                    .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setGroup("COMMUNITY_MESSAGES") // Group all community messages together
                    .setGroupSummary(false) // Individual notifications, not summary
                    .setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION))
                    .setVibrate(longArrayOf(0, 250, 250, 250))
                    .setDefaults(android.app.Notification.DEFAULT_ALL)
                    .build()

                notificationManager.notify(msg.community_id.hashCode(), notification)
                android.util.Log.d("CommunityRepository", "🔔 [NOTIFICATION] Sent notification for community $communityName, messageId=${msg.id}")
            } catch (e: Exception) {
                android.util.Log.e("CommunityRepository", "❌ [NOTIFICATION] Failed to show notification", e)
            }
        }
    }

    // --- FLOWS ---

    fun getCommunityMessagesFlow(communityId: String): Flow<List<CommunityMessage>> {
        val cleanId = communityId.trim().lowercase()
        val currentUserId = authService.getCurrentUserId()
        
        return _activeCommunityMessages.getOrPut(cleanId) {
            val state = MutableStateFlow<List<CommunityMessage>>(emptyList())
            
            // 1. Room Collection
            scope.launch {
                communityDao.getMessagesFlow(cleanId)
                    .distinctUntilChanged()
                    .collect { entities ->
                        val localMessages = entities.map { it.toDomain() }
                        state.update { current -> unifyCommunityMessages(localMessages + current, currentUserId) }
                    }
            }
            // 2. Initial Sync
            scope.launch { syncCommunityMessages(cleanId) }
            
            state
        }
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
        val cleanId = communityId.trim().lowercase()
        val flow = _activeCommunityMessages[cleanId] ?: return
        val currentUserId = authService.getCurrentUserId()
        
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

    fun getCommunitiesFlow(): Flow<List<CommunityWithLastMessage>> {
        return communityDao.getAllCommunitiesFlow()
            .distinctUntilChanged()
            .map { entities ->
                entities.filter { !it.is_deleted }.map { entity ->
                    CommunityWithLastMessage(
                        community = entity.toDomain(),
                        unreadCount = entity.unread_count
                    )
                }.sortedByDescending { it.community.last_message_at }
            }
            .flowOn(Dispatchers.IO)
    }

    // --- SYNC ---

    override suspend fun syncCommunities(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = (authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))).trim().lowercase()
            
            android.util.Log.d("CommunityRepository", "🔄 [SYNC] Sync Communities for $userId")

            // 1. Fetch memberships
            val memberships = supabaseConfig.client.postgrest["community_members"].select {
                filter { eq("user_id", userId) }
            }.decodeList<CommunityMember>()
            val joinedIds = memberships.map { it.community_id.trim().lowercase() }

            // 2. Fetch Communities (Public + Joined)
            val communities = supabaseConfig.client.postgrest["communities"].select {
                filter {
                    or {
                        eq("is_public", true)
                        isIn("id", joinedIds)
                    }
                    eq("is_deleted", false)
                }
            }.decodeList<Community>()

            // 3. Batch Sync Room
            val allLocal = communityDao.getAllCommunitiesSync()
            val localCounts = allLocal.associate { it.id.trim().lowercase() to it.unread_count }
            val localMap = allLocal.associateBy { it.id.trim().lowercase() }

            communityDao.insertCommunities(communities.map { 
                val cleanCommId = it.id.trim().lowercase()
                val localComm = localMap[cleanCommId]
                
                val serverTimeStr = it.last_message_at
                val localTimeStr = localComm?.last_message_at
                
                var useLocal = false
                if (localTimeStr != null && serverTimeStr != null) {
                    try {
                        val serverInstant = java.time.Instant.parse(serverTimeStr)
                        val localInstant = java.time.Instant.parse(localTimeStr)
                        if (localInstant.isAfter(serverInstant)) {
                            useLocal = true
                        }
                    } catch (e: Exception) {
                        if (localTimeStr > serverTimeStr) useLocal = true
                    }
                } else if (localTimeStr != null && serverTimeStr == null) {
                     useLocal = true
                }
                
                val finalMsg = if (useLocal) localComm?.last_message else it.last_message
                val finalTime = if (useLocal) localComm?.last_message_at else it.last_message_at

                // [FIX] Don't allow server to nullify local last_message if it provides nothing
                val actualMsg = if (finalMsg.isNullOrBlank() && !localComm?.last_message.isNullOrBlank()) localComm?.last_message else finalMsg
                val actualTime = if (finalTime.isNullOrBlank() && !localComm?.last_message_at.isNullOrBlank()) localComm?.last_message_at else finalTime

                CommunityEntity.fromDomain(
                    community = it.copy(
                        id = cleanCommId,
                        last_message = actualMsg,
                        last_message_at = actualTime,
                        is_member = joinedIds.contains(cleanCommId)
                    ),
                    unreadCount = localCounts[cleanCommId] ?: 0
                ) 
            })
            
            // 4. Update Memberships
            val validMemberships = memberships.filter { 
                // Ensure community exists locally or remotely
                communities.any { c -> c.id.trim().lowercase() == it.community_id.trim().lowercase() } 
            }
            // Upsert (Replace) current valid memberships
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
            val activeIds = communities.map { it.id.trim().lowercase() }
            if (activeIds.isNotEmpty()) {
                communityDao.markOthersAsNotJoined(activeIds)
            } else if (memberships.isEmpty() && communities.isEmpty()) {
                 communityDao.markOthersAsNotJoined(emptyList())
            }

            // [SMART SYNC] Check for Ghost Messages
            try {
                communities.forEach { comm ->
                    val cleanId = comm.id.trim().lowercase()
                    val serverTime = comm.last_message_at
                    val localComm = localMap[cleanId]
                    val localTime = localComm?.last_message_at
                    
                    if (serverTime != null) {
                        var isNewer = false
                        if (localTime == null) {
                            isNewer = true
                        } else {
                            try {
                                val s = java.time.Instant.parse(serverTime)
                                val l = java.time.Instant.parse(localTime)
                                if (s.isAfter(l)) isNewer = true
                            } catch (e: Exception) {
                                isNewer = true
                            }
                        }
                        
                        if (isNewer) {
                             android.util.Log.i("CommunityRepository", "👻 [GHOST-CHECK] Comm $cleanId has newer content. Fetching...")
                             launch { syncCommunityMessages(cleanId) }
                        }
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

    suspend fun uploadCommunityImage(communityId: String, bytes: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        try {
            val fileName = "community_${communityId}_${System.currentTimeMillis()}.jpg"
            val result = storageService.uploadFile(
                bucketName = "communities",
                fileName = fileName,
                bytes = bytes,
                contentType = "image/jpeg"
            )
            if (result.isSuccess) {
                Result.success(storageService.getPublicUrl("communities", fileName))
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("Upload failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createCommunity(
        name: String, 
        description: String?, 
        imageBytes: ByteArray?, 
        category: String?, 
        isPublic: Boolean
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val creatorId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            
            val communityId = UUID.randomUUID().toString().lowercase()
            
            var imageUrl: String? = null
            if (imageBytes != null) {
                val uploadResult = uploadCommunityImage(communityId, imageBytes)
                if (uploadResult.isSuccess) {
                    imageUrl = uploadResult.getOrNull()
                }
            }

            val now = java.time.Instant.now().toString()
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

            supabaseConfig.client.postgrest["communities"].insert(communityJson)

            val memberJson = buildJsonObject {
                put("community_id", communityId)
                put("user_id", creatorId)
                put("role", "admin") // Consolidar en 'admin' para mayor compatibilidad con SQL V9
                put("joined_at", now)
            }
            supabaseConfig.client.postgrest["community_members"].insert(memberJson)

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

            // Sincronizar localmente de inmediato para que aparezca en la lista
            scope.launch { syncCommunities() }

            Result.success(communityId)
        } catch (e: Exception) {
            android.util.Log.e("CommunityRepository", "Error en createCommunity", e)
            Result.failure(e)
        }
    }


    private suspend fun getMemberRole(communityId: String, userId: String): String? {
        val cleanId = communityId.trim().lowercase()
        return try {
            val response = supabaseConfig.client.postgrest["community_members"].select {
                filter {
                    eq("community_id", cleanId)
                    eq("user_id", userId)
                }
            }.decodeSingleOrNull<CommunityMember>()
            response?.role
        } catch (e: Exception) {
            null
        }
    }

    suspend fun deleteCommunityLocally(communityId: String) {
        communityDao.deleteCommunity(communityId)
    }

    suspend fun markAsRead(communityId: String, messageIds: Collection<String>) {
        val cleanId = communityId.trim().lowercase()
        withContext(Dispatchers.IO) {
            val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return@withContext
            val token = authService.getAccessToken()
            val now = java.time.Instant.now().toString()
            val cleanMessageIds = messageIds
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
            if (cleanMessageIds.isEmpty()) return@withContext
            val remotelyReadIds = mutableSetOf<String>()
            if (!token.isNullOrBlank()) {
                try {
                    val response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/community_messages") {
                        header("Authorization", "Bearer $token")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Content-Type", "application/json")
                        header("Prefer", "return=representation")
                        parameter("community_id", "eq.$cleanId")
                        parameter("sender_id", "neq.$currentUserId")
                        parameter("id", "in.(${cleanMessageIds.joinToString(",")})")
                        parameter("status", "in.(sent,delivered,received)")
                        parameter("select", "id")
                        setBody(
                            buildJsonObject {
                                put("status", "read")
                                put("read_at", now)
                                put("delivered_at", now)
                                put("updated_at", now)
                            }
                        )
                    }
                    if (response.status.isSuccess()) {
                        remotelyReadIds.addAll(extractUpdatedIdsFromPatchBody(response.bodyAsText()))
                    }
                } catch (e: Exception) {
                    android.util.Log.w("CommunityRepository", "markAsRead remote sync failed for $cleanId", e)
                }
            }
            if (remotelyReadIds.isEmpty() && !token.isNullOrBlank()) {
                remotelyReadIds.addAll(
                    fetchRemoteCommunityMessageIdsByStatus(
                        communityId = cleanId,
                        messageIds = cleanMessageIds,
                        currentUserId = currentUserId,
                        token = token,
                        statuses = listOf("read")
                    )
                )
            }
            val idsToMark = cleanMessageIds.filter { remotelyReadIds.contains(it) }
            if (idsToMark.isNotEmpty()) {
                communityDao.markMessagesAsReadByIds(cleanId, now, currentUserId, idsToMark)
            }
            val remainingUnread = runCatching { communityDao.countUnreadIncoming(cleanId, currentUserId) }.getOrDefault(0)
            if (currentOpenedCommunityId == cleanId) {
                communityDao.resetUnreadCount(cleanId)
            } else {
                communityDao.resetUnreadCount(cleanId)
                if (remainingUnread > 0) {
                    communityDao.incrementUnreadCount(cleanId, remainingUnread)
                }
            }
        }
    }

    suspend fun markAsRead(communityId: String) {
        val cleanId = communityId.trim().lowercase()
        withContext(Dispatchers.IO) {
            val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return@withContext
            val token = authService.getAccessToken()
            val now = java.time.Instant.now().toString()
            if (!token.isNullOrBlank()) {
                var remoteReadSynced = false
                runCatching {
                    supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_mark_community_messages_read") {
                        header("Authorization", "Bearer $token")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Content-Type", "application/json")
                        setBody(buildJsonObject { put("p_community_id", cleanId) })
                    }
                }.onSuccess { response ->
                    remoteReadSynced = response.status.isSuccess()
                    if (!remoteReadSynced) {
                        android.util.Log.w("CommunityRepository", "secure_mark_community_messages_read failed for $cleanId: ${response.status}")
                    }
                }.onFailure { e ->
                    android.util.Log.w("CommunityRepository", "secure_mark_community_messages_read exception for $cleanId", e)
                }

                if (!remoteReadSynced) {
                    val messageUpdate = buildJsonObject {
                        put("status", "read")
                        put("read_at", now)
                        put("delivered_at", now)
                        put("updated_at", now)
                    }
                    runCatching {
                        supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/community_messages") {
                            header("Authorization", "Bearer $token")
                            header("apikey", supabaseConfig.supabaseAnonKey)
                            header("Content-Type", "application/json")
                            parameter("community_id", "eq.$cleanId")
                            parameter("sender_id", "neq.$currentUserId")
                            parameter("status", "in.(sent,delivered,received)")
                            parameter("read_at", "is.null")
                            setBody(messageUpdate)
                        }
                    }.onFailure { e ->
                        android.util.Log.w("CommunityRepository", "markAsRead fallback patch failed for $cleanId", e)
                    }
                }
            } else {
                android.util.Log.w("CommunityRepository", "markAsRead: no token available, skipping remote sync for $cleanId")
            }
            communityDao.markAllAsRead(cleanId, now, currentUserId)
            communityDao.resetUnreadCount(cleanId)
        }
    }

    suspend fun markMessageAsDelivered(communityId: String, messageId: String) {
        val cleanId = communityId.trim().lowercase()
        val cleanMessageId = messageId.trim().lowercase()
        if (cleanId.isBlank() || cleanMessageId.isBlank()) return
        withContext(Dispatchers.IO) {
            val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return@withContext
            val token = authService.getAccessToken()
            val now = java.time.Instant.now().toString()
            var remoteSynced = false
            if (!token.isNullOrBlank()) {
                try {
                    val response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/community_messages") {
                        header("Authorization", "Bearer $token")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Content-Type", "application/json")
                        header("Prefer", "return=representation")
                        parameter("community_id", "eq.$cleanId")
                        parameter("id", "eq.$cleanMessageId")
                        parameter("sender_id", "neq.$currentUserId")
                        parameter("status", "eq.sent")
                        parameter("select", "id")
                        setBody(
                            buildJsonObject {
                                put("status", "delivered")
                                put("delivered_at", now)
                                put("updated_at", now)
                            }
                        )
                    }
                    if (response.status.isSuccess()) {
                        remoteSynced = extractUpdatedIdsFromPatchBody(response.bodyAsText()).contains(cleanMessageId)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("CommunityRepository", "markMessageAsDelivered remote sync failed for $cleanMessageId", e)
                }
            }
            if (!remoteSynced && !token.isNullOrBlank()) {
                remoteSynced = fetchRemoteCommunityMessageIdsByStatus(
                    communityId = cleanId,
                    messageIds = listOf(cleanMessageId),
                    currentUserId = currentUserId,
                    token = token,
                    statuses = listOf("delivered", "read", "received")
                ).contains(cleanMessageId)
            }
            communityDao.markMessageAsDelivered(cleanId, cleanMessageId, now, currentUserId)
            if (!remoteSynced) {
                android.util.Log.w("CommunityRepository", "markMessageAsDelivered could not confirm remote transition for $cleanMessageId in $cleanId")
            }
        }
    }

    suspend fun markMessagesAsDelivered(communityId: String) {
        val cleanId = communityId.trim().lowercase()
        withContext(Dispatchers.IO) {
            val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return@withContext
            val token = authService.getAccessToken()
            val now = java.time.Instant.now().toString()
            if (!token.isNullOrBlank()) {
                try {
                    supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_mark_community_messages_delivered") {
                        header("Authorization", "Bearer $token")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Content-Type", "application/json")
                        setBody(buildJsonObject { put("p_community_id", cleanId) })
                    }
                    communityDao.markAllAsDelivered(cleanId, now, currentUserId)
                } catch (e: Exception) {
                    android.util.Log.w("CommunityRepository", "markMessagesAsDelivered remote sync failed for $cleanId", e)
                }
            }
        }
    }

    private fun extractUpdatedIdsFromPatchBody(rawBody: String): Set<String> {
        if (rawBody.isBlank()) return emptySet()
        return runCatching {
            val element = Json.parseToJsonElement(rawBody)
            if (element !is JsonArray) return@runCatching emptySet<String>()
            element.mapNotNull { row ->
                row.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            }.toSet()
        }.getOrDefault(emptySet())
    }

    private suspend fun fetchRemoteCommunityMessageIdsByStatus(
        communityId: String,
        messageIds: Collection<String>,
        currentUserId: String,
        token: String,
        statuses: List<String>
    ): Set<String> {
        val cleanCommunityId = communityId.trim().lowercase()
        val cleanIds = messageIds.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
        val cleanStatuses = statuses.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
        if (cleanCommunityId.isBlank() || cleanIds.isEmpty() || cleanStatuses.isEmpty()) return emptySet()

        return runCatching {
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/community_messages") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("select", "id")
                parameter("community_id", "eq.$cleanCommunityId")
                parameter("sender_id", "neq.$currentUserId")
                parameter("id", "in.(${cleanIds.joinToString(",")})")
                parameter("status", "in.(${cleanStatuses.joinToString(",")})")
            }
            if (!response.status.isSuccess()) return@runCatching emptySet<String>()
            extractUpdatedIdsFromPatchBody(response.bodyAsText())
        }.getOrElse { emptySet() }
    }

    suspend fun isUserMember(communityId: String, userId: String): Boolean {
        val cleanId = communityId.trim().lowercase()
        return try {
            val response = supabaseConfig.client.postgrest["community_members"].select {
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
        val cleanId = id.trim().lowercase()
        return communityDao.getCommunityByIdFlow(cleanId).map { it?.toDomain() }
    }



    suspend fun searchCommunities(query: String): Result<List<Community>> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseConfig.client.postgrest["communities"].select {
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
            val cleanId = communityId.trim().lowercase()
            var imageUrl: String? = null
            
            if (imageBytes != null) {
                val uploadResult = uploadCommunityImage(cleanId, imageBytes)
                if (uploadResult.isSuccess) {
                    imageUrl = uploadResult.getOrNull()
                }
            }

            supabaseConfig.client.postgrest["communities"].update({
                set("name", name)
                set("description", description ?: "")
                set("is_public", isPublic)
                set("only_admins_can_talk", onlyAdminsCanTalk)
                if (imageUrl != null) set("image", imageUrl)
            }) {
                filter { eq("id", cleanId) }
            }
            
            syncCommunities()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    suspend fun getCommunityById(id: String): Community? {
        val cleanId = id.trim().lowercase()
        return try {
            val entity = communityDao.getCommunityById(cleanId)
            if (entity != null) return entity.toDomain()
            
            val response = supabaseConfig.client.postgrest["communities"].select {
                filter { eq("id", cleanId) }
            }.decodeSingleOrNull<Community>()
            response
        } catch (e: Exception) {
            null
        }
    }

    suspend fun leaveCommunity(communityId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            val cleanId = communityId.trim().lowercase()
            
            // 1. Optimistic local update
            communityDao.removeMember(cleanId, userId.trim().lowercase())
            communityDao.updateMembership(cleanId, false)

            // 2. Remote delete
            supabaseConfig.client.postgrest["community_members"].delete {
                filter {
                    eq("community_id", cleanId)
                    eq("user_id", userId)
                }
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadCommunityAudio(communityId: String, bytes: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        try {
            val fileName = "audio_comm_${communityId}_${System.currentTimeMillis()}.m4a"
            val result = storageService.uploadFile(
                bucketName = "community-media",
                fileName = fileName,
                bytes = bytes,
                contentType = "audio/m4a"
            )
            if (result.isSuccess) {
                Result.success(storageService.getPublicUrl("community-media", fileName))
            } else {
                storageService.uploadFile(
                    bucketName = "communities",
                    fileName = fileName,
                    bytes = bytes,
                    contentType = "audio/m4a"
                ).let { fallback ->
                    if (fallback.isSuccess) Result.success(storageService.getPublicUrl("communities", fileName))
                    else Result.failure(fallback.exceptionOrNull() ?: Exception("Upload failed"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeMember(communityId: String, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cleanId = communityId.trim().lowercase()
            val targetUserId = userId.trim().lowercase()
            
            // 1. Get Community to check creator
            val community = getCommunityById(cleanId) ?: return@withContext Result.failure(Exception("Community not found"))
            
            // 2. CREATOR PROTECTION: Cannot remove the creator
            if (targetUserId == community.creator_id.trim().lowercase()) {
                return@withContext Result.failure(Exception("No se puede eliminar al creador de la comunidad."))
            }

            // Execute delete
            supabaseConfig.client.postgrest["community_members"].delete {
                filter {
                    eq("community_id", cleanId)
                    eq("user_id", targetUserId)
                }
            }
            
            // Remove locally
            communityDao.removeMember(cleanId, targetUserId)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateMemberRole(communityId: String, userId: String, newRole: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cleanId = communityId.trim().lowercase()
            val targetUserId = userId.trim().lowercase()
            
            // 1. Get Community for creator check
            val community = getCommunityById(cleanId) ?: return@withContext Result.failure(Exception("Community not found"))

            // 2. CREATOR PROTECTION: Cannot change creator's role
            if (targetUserId == community.creator_id.trim().lowercase()) {
                return@withContext Result.failure(Exception("No se puede cambiar el rol del creador."))
            }

            supabaseConfig.client.postgrest["community_members"].update({
                set("role", newRole)
            }) {
                filter {
                    eq("community_id", cleanId)
                    eq("user_id", targetUserId)
                }
            }
            // Update local member cache
            getCommunityMembers(cleanId)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun joinCommunity(communityId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            val cleanId = communityId.trim().lowercase()
            
            // 1. Optimistic local update
            communityDao.insertMembers(listOf(CommunityMemberEntity(cleanId, userId.trim().lowercase(), "member", java.time.Instant.now().toString())))
            communityDao.updateMembership(cleanId, true)

            // 2. Remote insert
            val memberJson = buildJsonObject {
                put("community_id", cleanId)
                put("user_id", userId)
                put("role", "member")
                put("joined_at", java.time.Instant.now().toString())
            }
            
            supabaseConfig.client.postgrest["community_members"].insert(memberJson)
            
            // Force a sync to ensure metadata is correct
            syncCommunities()
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteCommunity(communityId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            val cleanId = communityId.trim().lowercase()
            val community = getCommunityById(cleanId)

            if (community?.creator_id == userId) {
                val updateJson = buildJsonObject {
                    put("is_deleted", true)
                }
                supabaseConfig.client.postgrest["communities"].update(updateJson) {
                    filter { eq("id", cleanId) }
                }
            } else {
                leaveCommunity(cleanId)
            }
            
            communityDao.deleteCommunity(cleanId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendCommunityMessage(
        communityId: String,
        content: String,
        contentType: String = "text",
        mediaUrl: String? = null,
        replyToId: String? = null,
        pollId: String? = null,
        isForwarded: Boolean = false,
        forwardedFromMessageId: String? = null,
        forwardedFromUserId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            val senderId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("Not authenticated"))
            val cleanId = communityId.trim().lowercase()

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
            
            val messageId = UUID.randomUUID().toString().lowercase()
            val now = java.time.Instant.now().toString()

            val msg = CommunityMessage(
                id = messageId,
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
            communityDao.updateMetadata(cleanId, if (contentType == "text") finalContent else "Multimedia", now)

            try {
                operationGuard.audit(
                    action = GuardedAction.COMMUNITY_MESSAGE_SEND,
                    actorId = senderId,
                    scopeId = cleanId,
                    outcome = GuardOutcome.QUEUED,
                    attributes = mapOf("message_id" to messageId)
                )
            } catch (_: Exception) {
                // Best effort audit only.
            }

            val msgJson = buildJsonObject {
                put("id", messageId)
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
            val result = insertCommunityMessageWithRetry(messageId, msgJson)
            if (result.isFailure) {
                val syncError = result.exceptionOrNull()
                android.util.Log.e("CommunityRepository", "❌ [SERVER-FAIL] Falló subida de mensaje comunidad $messageId", syncError)
                try {
                    operationGuard.audit(
                        action = GuardedAction.COMMUNITY_MESSAGE_SEND,
                        actorId = senderId,
                        scopeId = cleanId,
                        outcome = GuardOutcome.FAILED,
                        attributes = mapOf(
                            "message_id" to messageId,
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
                        attributes = mapOf("message_id" to messageId)
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
        val cleanId = communityId.trim().lowercase()
        val senderId = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: return@withContext Result.failure(Exception("Not authenticated"))

        val sanitizedQuestion = question.trim().take(300)
        val sanitizedOptions = options.map { it.trim().take(120) }.filter { it.isNotBlank() }.distinct()
        if (sanitizedQuestion.isBlank()) return@withContext Result.failure(Exception("La pregunta es obligatoria"))
        if (sanitizedOptions.size < 2) return@withContext Result.failure(Exception("La encuesta requiere al menos 2 opciones"))

        val messageId = UUID.randomUUID().toString().lowercase()
        val now = java.time.Instant.now().toString()
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
            val error = remote.exceptionOrNull() ?: Exception("No se pudo crear la encuesta")
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
    ): Result<CommunityMessage> {
        return try {
            val token = authService.getAccessToken()
                ?: return Result.failure(Exception("No token"))
            val payloadAttempts = listOf(
                buildJsonObject {
                    put("p_community_id", communityId)
                    put("p_message_id", messageId)
                    put("p_question", question)
                    put("p_options", JsonArray(options.map { JsonPrimitive(it) }))
                    put("p_allows_multiple", allowsMultiple)
                },
                buildJsonObject {
                    put("p_community_id", communityId)
                    put("p_message_id", messageId)
                    put("p_question", question)
                    put("p_options", toPostgresTextArray(options))
                    put("p_allows_multiple", allowsMultiple)
                }
            )

            var rpcError: Exception? = null
            payloadAttempts.forEach { payload ->
                val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_create_community_poll_message") {
                    header("Authorization", "Bearer $token")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Content-Type", "application/json")
                    header("Prefer", "return=representation")
                    setBody(payload)
                }

                val body = runCatching { response.bodyAsText() }.getOrDefault("")
                if (response.status.isSuccess()) {
                    val parsed = parseRpcCommunityMessage(body)
                    if (parsed != null) {
                        return Result.success(parsed)
                    }
                    rpcError = Exception("Respuesta inválida creando encuesta")
                } else {
                    rpcError = Exception("Error creando encuesta: ${response.status.value} $body")
                }
            }

            val fallback = createCommunityPollMessageFallback(
                communityId = communityId,
                messageId = messageId,
                question = question,
                options = options,
                allowsMultiple = allowsMultiple,
                token = token
            )
            if (fallback.isSuccess) {
                fallback
            } else {
                Result.failure(
                    rpcError ?: fallback.exceptionOrNull() ?: Exception("No se pudo crear la encuesta")
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun createCommunityPollMessageFallback(
        communityId: String,
        messageId: String,
        question: String,
        options: List<String>,
        allowsMultiple: Boolean,
        token: String
    ): Result<CommunityMessage> {
        return try {
            val userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return Result.failure(Exception("No user"))

            val pollResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/message_polls") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                setBody(
                    buildJsonObject {
                        put("scope_type", "community")
                        put("scope_id", communityId)
                        put("creator_id", userId)
                        put("question", question)
                        put("allows_multiple", allowsMultiple)
                    }
                )
            }
            val pollBody = runCatching { pollResponse.bodyAsText() }.getOrDefault("")
            if (!pollResponse.status.isSuccess()) {
                return Result.failure(Exception("Error creando encuesta (fallback): ${pollResponse.status.value} $pollBody"))
            }

            val pollId = extractIdFromRepresentation(pollBody)
                ?: return Result.failure(Exception("No se pudo obtener poll_id"))

            val optionsPayload = JsonArray(
                options.mapIndexed { index, value ->
                    buildJsonObject {
                        put("poll_id", pollId)
                        put("option_text", value)
                        put("display_order", index)
                    }
                }
            )

            val optionsResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/message_poll_options") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=minimal")
                setBody(optionsPayload)
            }
            if (!optionsResponse.status.isSuccess()) {
                val optionsBody = runCatching { optionsResponse.bodyAsText() }.getOrDefault("")
                return Result.failure(Exception("Error creando opciones (fallback): ${optionsResponse.status.value} $optionsBody"))
            }

            val messageResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/community_messages") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                setBody(
                    buildJsonObject {
                        put("id", messageId)
                        put("community_id", communityId)
                        put("sender_id", userId)
                        put("content", question)
                        put("content_type", "poll")
                        put("poll_id", pollId)
                    }
                )
            }
            val msgBody = runCatching { messageResponse.bodyAsText() }.getOrDefault("")
            if (!messageResponse.status.isSuccess()) {
                return Result.failure(Exception("Error enviando mensaje encuesta (fallback): ${messageResponse.status.value} $msgBody"))
            }

            val parsed = parseRpcCommunityMessage(msgBody)
            if (parsed != null) {
                Result.success(parsed)
            } else {
                Result.success(
                    CommunityMessage(
                        id = messageId,
                        community_id = communityId,
                        sender_id = userId,
                        content = question,
                        content_type = "poll",
                        poll_id = pollId,
                        created_at = java.time.Instant.now().toString()
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseRpcCommunityMessage(body: String): CommunityMessage? {
        if (body.isBlank()) return null
        return try {
            json.decodeFromString<CommunityMessage>(body)
        } catch (_: Exception) {
            runCatching { json.decodeFromString<List<CommunityMessage>>(body).firstOrNull() }.getOrNull()
        }
    }

    private fun extractIdFromRepresentation(body: String): String? {
        if (body.isBlank()) return null
        val asList = runCatching { json.decodeFromString<List<JsonObject>>(body) }.getOrNull()
        val fromList = asList?.firstOrNull()?.get("id")?.jsonPrimitive?.contentOrNull
        if (!fromList.isNullOrBlank()) return fromList

        val asObject = runCatching { json.decodeFromString<JsonObject>(body) }.getOrNull()
        return asObject?.get("id")?.jsonPrimitive?.contentOrNull
    }

    private fun toPostgresTextArray(values: List<String>): String {
        return values.joinToString(prefix = "{", postfix = "}") { raw ->
            "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
    }

    private suspend fun insertCommunityMessageWithRetry(
        messageId: String,
        payload: JsonObject,
        maxAttempts: Int = 4
    ): Result<Unit> {
        var lastError: Exception? = null

        for (attempt in 0 until maxAttempts) {
            try {
                val pushResult = sendCommunityMessageSecureOrFallback(messageId = messageId, payload = payload)
                if (pushResult.isFailure) {
                    throw (pushResult.exceptionOrNull() ?: Exception("Unknown push error"))
                }
                communityDao.updateMessageSyncStatus(messageId, isSynced = true, pendingAction = null)
                return Result.success(Unit)
            } catch (e: Exception) {
                lastError = e

                if (isDuplicateKeyError(e)) {
                    communityDao.updateMessageSyncStatus(messageId, isSynced = true, pendingAction = null)
                    return Result.success(Unit)
                }

                val isLastAttempt = attempt == maxAttempts - 1
                if (!isRetryableNetworkError(e) || isLastAttempt) {
                    communityDao.markRetryAttempt(messageId, java.time.Instant.now().toString())
                    communityDao.updateMessageSyncStatus(messageId, isSynced = false, pendingAction = "send")
                    return Result.failure(lastError)
                }

                delay(1000L * (1L shl minOf(attempt, 3)))
            }
        }

        return Result.failure(lastError ?: Exception("No se pudo sincronizar mensaje de comunidad"))
    }

    private suspend fun sendCommunityMessageSecureOrFallback(
        messageId: String,
        payload: JsonObject
    ): Result<Unit> {
        val token = authService.getAccessToken()
            ?: return Result.failure(Exception("No token"))

        val communityId = payload["community_id"]?.jsonPrimitive?.contentOrNull
            ?: return Result.failure(Exception("Missing community_id payload"))
        val content = payload["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val contentType = payload["content_type"]?.jsonPrimitive?.contentOrNull ?: "text"
        val mediaUrl = payload["media_url"]?.jsonPrimitive?.contentOrNull
        val replyToId = payload["reply_to_id"]?.jsonPrimitive?.contentOrNull
        val isForwarded = payload["is_forwarded"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val forwardedFromMessageId = payload["forwarded_from_message_id"]?.jsonPrimitive?.contentOrNull
        val forwardedFromUserId = payload["forwarded_from_user_id"]?.jsonPrimitive?.contentOrNull

        val rpcPayload = buildJsonObject {
            put("p_community_id", communityId)
            put("p_message_id", messageId)
            put("p_content", content)
            put("p_content_type", contentType)
            put("p_media_url", mediaUrl)
            put("p_reply_to_id", replyToId)
            put("p_is_forwarded", isForwarded)
            put("p_forwarded_from_message_id", forwardedFromMessageId)
            put("p_forwarded_from_user_id", forwardedFromUserId)
        }

        return try {
            val rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_send_community_message") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                setBody(rpcPayload)
            }

            if (rpcResponse.status.isSuccess()) {
                return Result.success(Unit)
            }

            val rpcError = runCatching { rpcResponse.bodyAsText() }.getOrNull().orEmpty()
            if (isMissingCommunityRpcFunction(rpcResponse.status.value, rpcError)) {
                return if (isForwarded || !forwardedFromMessageId.isNullOrBlank() || !forwardedFromUserId.isNullOrBlank()) {
                    val firstTry = runCatching {
                        supabaseConfig.client.postgrest["community_messages"].insert(payload)
                    }
                    if (firstTry.isSuccess) {
                        Result.success(Unit)
                    } else {
                        val errMsg = firstTry.exceptionOrNull()?.message.orEmpty()
                        if (isMissingForwardingColumnError(errMsg)) {
                            val legacyPayload = buildJsonObject {
                                payload["id"]?.let { put("id", it) }
                                payload["community_id"]?.let { put("community_id", it) }
                                payload["sender_id"]?.let { put("sender_id", it) }
                                payload["content"]?.let { put("content", it) }
                                payload["content_type"]?.let { put("content_type", it) }
                                payload["media_url"]?.let { put("media_url", it) }
                                payload["reply_to_id"]?.let { put("reply_to_id", it) }
                                payload["poll_id"]?.let { put("poll_id", it) }
                                payload["created_at"]?.let { put("created_at", it) }
                            }
                            runCatching { supabaseConfig.client.postgrest["community_messages"].insert(legacyPayload) }
                                .fold(
                                    onSuccess = { Result.success(Unit) },
                                    onFailure = { Result.failure(it as? Exception ?: Exception(it.message ?: "Fallback insert failed")) }
                                )
                        } else {
                            Result.failure(firstTry.exceptionOrNull() as? Exception ?: Exception(errMsg.ifBlank { "Fallback insert failed" }))
                        }
                    }
                } else {
                    supabaseConfig.client.postgrest["community_messages"].insert(payload)
                    Result.success(Unit)
                }
            } else {
                Result.failure(Exception("RPC community send failed ${rpcResponse.status.value}: $rpcError"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isMissingCommunityRpcFunction(statusCode: Int, responseBody: String): Boolean {
        if (statusCode != 400 && statusCode != 404) return false
        val cleanBody = responseBody.lowercase()
        return cleanBody.contains("secure_send_community_message") &&
            (cleanBody.contains("could not find the function") || cleanBody.contains("undefined function"))
    }

    private fun isDuplicateKeyError(error: Exception): Boolean {
        val text = error.message?.lowercase() ?: return false
        return text.contains("duplicate key") || text.contains("23505")
    }

    private fun isMissingForwardingColumnError(errorText: String): Boolean {
        val normalized = errorText.lowercase()
        return normalized.contains("column") &&
            (
                normalized.contains("is_forwarded") ||
                    normalized.contains("forwarded_from_message_id") ||
                    normalized.contains("forwarded_from_user_id")
                )
    }

    private fun isRetryableNetworkError(error: Exception): Boolean {
        val text = error.message?.lowercase() ?: return false
        return transientNetworkRetryCodes.any { code -> text.contains(code) }
    }

    private suspend fun retryPendingCommunityMessages(maxBatch: Int = 40) {
        val userId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return
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

    suspend fun uploadCommunityMedia(communityId: String, bytes: ByteArray, contentType: String): Result<String> = withContext(Dispatchers.IO) {
        try {
             val extension = when {
                contentType.equals("image/gif", ignoreCase = true) -> "gif"
                contentType.startsWith("image/") -> "jpg"
                contentType.startsWith("video/") -> "mp4"
                else -> "bin"
            }
            val fileName = "media_comm_${communityId}_${System.currentTimeMillis()}.$extension"
            val result = storageService.uploadFile(
                bucketName = "community-media",
                fileName = fileName,
                bytes = bytes,
                contentType = contentType
            )
            if (result.isSuccess) {
                Result.success(storageService.getPublicUrl("community-media", fileName))
            } else {
                 storageService.uploadFile(
                    bucketName = "communities",
                    fileName = fileName,
                    bytes = bytes,
                    contentType = contentType
                ).let { fallback ->
                    if (fallback.isSuccess) Result.success(storageService.getPublicUrl("communities", fileName))
                    else Result.failure(fallback.exceptionOrNull() ?: Exception("Upload failed"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun editCommunityMessage(messageId: String, newContent: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val updateJson = buildJsonObject {
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

    suspend fun deleteCommunityMessage(messageId: String, forEveryone: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
             val userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
             if (forEveryone) {
                val updateJson = buildJsonObject {
                    put("deleted_for_all", true)
                }
                supabaseConfig.client.postgrest["community_messages"].update(updateJson) {
                    filter { eq("id", messageId) }
                }
            } else {
                // Fetch current deleted_by
                val current = communityDao.getMessageById(messageId)?.toDomain()?.deleted_by ?: emptyList()
                val updated = (current + userId).distinct()
                val updateJson = buildJsonObject {
                    put("deleted_by", JsonArray(updated.map { JsonPrimitive(it) }))
                }
                 supabaseConfig.client.postgrest["community_messages"].update(updateJson) {
                    filter { eq("id", messageId) }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCommunityMessageReadReceipts(messageId: String): Result<List<MessageReadReceipt>> = withContext(Dispatchers.IO) {
        try {
            val token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_get_community_message_receipts") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(buildJsonObject { put("p_message_id", messageId.trim().lowercase()) })
            }
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            if (!response.status.isSuccess()) {
                return@withContext Result.failure(Exception("Error cargando recibos: ${response.status.value} $body"))
            }
            val parsed = runCatching { json.decodeFromString<List<MessageReadReceipt>>(body) }
                .getOrElse {
                    runCatching {
                        listOf(json.decodeFromString<MessageReadReceipt>(body))
                    }.getOrDefault(emptyList())
                }
            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPollState(pollId: String): Result<List<MessagePollOptionState>> = withContext(Dispatchers.IO) {
        try {
            val token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            val cleanPollId = pollId.trim().lowercase()
            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_get_poll_state") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(buildJsonObject { put("p_poll_id", cleanPollId) })
            }
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            if (response.status.isSuccess()) {
                val parsed = runCatching { json.decodeFromString<List<MessagePollOptionState>>(body) }
                    .getOrElse {
                        runCatching {
                            listOf(json.decodeFromString<MessagePollOptionState>(body))
                        }.getOrDefault(emptyList())
                    }
                if (parsed.isNotEmpty()) {
                    return@withContext Result.success(parsed.sortedBy { it.displayOrder })
                }
            }
            val fallback = loadPollStateFallback(cleanPollId, token)
            if (fallback.isNotEmpty()) {
                Result.success(fallback.sortedBy { it.displayOrder })
            } else if (!response.status.isSuccess()) {
                Result.failure(Exception("Error cargando encuesta: ${response.status.value} $body"))
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun loadPollStateFallback(
        pollId: String,
        token: String
    ): List<MessagePollOptionState> {
        if (pollId.isBlank()) return emptyList()
        val cleanUserId = authService.getCurrentUserId()?.trim()?.lowercase()

        val metaResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/message_polls") {
            header("Authorization", "Bearer $token")
            header("apikey", supabaseConfig.supabaseAnonKey)
            parameter("id", "eq.$pollId")
            parameter("select", "question,allows_multiple,is_closed")
            parameter("limit", "1")
        }
        val metaRows = if (metaResponse.status.isSuccess()) {
            runCatching { json.decodeFromString<List<JsonObject>>(metaResponse.bodyAsText()) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val meta = metaRows.firstOrNull()
        val question = meta?.get("question")?.jsonPrimitive?.contentOrNull.orEmpty()
        val allowsMultiple = meta?.get("allows_multiple")?.jsonPrimitive?.booleanOrNull ?: false
        val isClosed = meta?.get("is_closed")?.jsonPrimitive?.booleanOrNull ?: false

        val optionsResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/message_poll_options") {
            header("Authorization", "Bearer $token")
            header("apikey", supabaseConfig.supabaseAnonKey)
            parameter("poll_id", "eq.$pollId")
            parameter("select", "id,option_text,display_order")
            parameter("order", "display_order.asc")
        }
        if (!optionsResponse.status.isSuccess()) return emptyList()

        val options = runCatching { json.decodeFromString<List<JsonObject>>(optionsResponse.bodyAsText()) }.getOrDefault(emptyList())
        if (options.isEmpty()) return emptyList()

        val votesResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/message_poll_votes") {
            header("Authorization", "Bearer $token")
            header("apikey", supabaseConfig.supabaseAnonKey)
            parameter("poll_id", "eq.$pollId")
            parameter("select", "option_id,user_id")
        }
        val voteRows = if (votesResponse.status.isSuccess()) {
            runCatching { json.decodeFromString<List<JsonObject>>(votesResponse.bodyAsText()) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val voteCountByOption = mutableMapOf<String, Long>()
        val votedByMeOptionIds = mutableSetOf<String>()
        var totalVotes = 0L
        voteRows.forEach { row ->
            val votedOptionId = row["option_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (votedOptionId.isBlank()) return@forEach
            totalVotes += 1
            voteCountByOption[votedOptionId] = (voteCountByOption[votedOptionId] ?: 0L) + 1L

            val voterId = row["user_id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
            if (!cleanUserId.isNullOrBlank() && voterId == cleanUserId) {
                votedByMeOptionIds += votedOptionId
            }
        }

        return options.mapIndexedNotNull { index, row ->
            val optionId = row["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val optionText = row["option_text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (optionId.isBlank() || optionText.isBlank()) return@mapIndexedNotNull null

            MessagePollOptionState(
                pollId = pollId,
                optionId = optionId,
                optionText = optionText,
                displayOrder = row["display_order"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: index,
                voteCount = voteCountByOption[optionId] ?: 0L,
                votedByMe = votedByMeOptionIds.contains(optionId),
                totalVotes = totalVotes,
                allowsMultiple = allowsMultiple,
                isClosed = isClosed,
                question = question
            )
        }
    }

    suspend fun votePollOption(
        pollId: String,
        optionId: String,
        selected: Boolean = true
    ): Result<List<MessagePollOptionState>> = withContext(Dispatchers.IO) {
        try {
            val token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            val cleanPollId = pollId.trim().lowercase()
            val cleanOptionId = optionId.trim().lowercase()
            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_vote_poll_option") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(
                    buildJsonObject {
                        put("p_poll_id", cleanPollId)
                        put("p_option_id", cleanOptionId)
                        put("p_selected", selected)
                    }
                )
            }
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            if (!response.status.isSuccess()) {
                if (isAmbiguousPollIdRpcError(response.status.value, body)) {
                    return@withContext votePollOptionFallback(
                        pollId = cleanPollId,
                        optionId = cleanOptionId,
                        selected = selected,
                        token = token
                    )
                }
                return@withContext Result.failure(Exception("Error votando encuesta: ${response.status.value} $body"))
            }
            val parsed = runCatching { json.decodeFromString<List<MessagePollOptionState>>(body) }
                .getOrElse {
                    runCatching {
                        listOf(json.decodeFromString<MessagePollOptionState>(body))
                    }.getOrDefault(emptyList())
                }
            Result.success(parsed.sortedBy { it.displayOrder })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPollOptionVoters(
        pollId: String,
        optionId: String
    ): Result<List<PollVoteUser>> = withContext(Dispatchers.IO) {
        try {
            val token = authService.getAccessToken()
                ?: return@withContext Result.failure(Exception("No token"))
            val cleanPollId = pollId.trim().lowercase()
            val cleanOptionId = optionId.trim().lowercase()
            if (cleanPollId.isBlank() || cleanOptionId.isBlank()) {
                return@withContext Result.success(emptyList())
            }

            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/message_poll_votes") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("poll_id", "eq.$cleanPollId")
                parameter("option_id", "eq.$cleanOptionId")
                parameter("select", "user_id,profiles(username,full_name,avatar_url)")
            }

            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            if (!response.status.isSuccess()) {
                return@withContext Result.failure(Exception("Error cargando votantes: ${response.status.value} $body"))
            }

            val rows = runCatching { json.decodeFromString<List<JsonObject>>(body) }.getOrDefault(emptyList())
            val voters = rows.mapNotNull { row ->
                val userId = row["user_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (userId.isBlank()) return@mapNotNull null
                val profileElement = row["profiles"]
                val profile = when (profileElement) {
                    is JsonObject -> profileElement
                    is JsonArray -> profileElement.firstOrNull() as? JsonObject
                    else -> null
                }
                PollVoteUser(
                    userId = userId,
                    username = profile?.get("username")?.jsonPrimitive?.contentOrNull,
                    fullName = profile?.get("full_name")?.jsonPrimitive?.contentOrNull,
                    avatarUrl = profile?.get("avatar_url")?.jsonPrimitive?.contentOrNull
                )
            }.distinctBy { it.userId.trim().lowercase() }

            Result.success(voters)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun votePollOptionFallback(
        pollId: String,
        optionId: String,
        selected: Boolean,
        token: String
    ): Result<List<MessagePollOptionState>> {
        val cleanUserId = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: return Result.failure(Exception("No user"))

        if (selected) {
            val allowsMultiple = loadPollAllowsMultiple(pollId, token) ?: false
            if (!allowsMultiple) {
                val clearResponse = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/message_poll_votes") {
                    header("Authorization", "Bearer $token")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("poll_id", "eq.$pollId")
                    parameter("user_id", "eq.$cleanUserId")
                    parameter("option_id", "neq.$optionId")
                }
                if (!clearResponse.status.isSuccess()) {
                    val clearBody = runCatching { clearResponse.bodyAsText() }.getOrDefault("")
                    return Result.failure(Exception("Error limpiando voto anterior: ${clearResponse.status.value} $clearBody"))
                }
            }

            val insertResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/message_poll_votes") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "resolution=ignore-duplicates,return=minimal")
                setBody(
                    buildJsonObject {
                        put("poll_id", pollId)
                        put("option_id", optionId)
                        put("user_id", cleanUserId)
                    }
                )
            }
            val insertBody = runCatching { insertResponse.bodyAsText() }.getOrDefault("")
            val isDuplicateConflict = insertResponse.status.value == 409 &&
                (insertBody.contains("23505") || insertBody.lowercase().contains("duplicate key"))
            if (!insertResponse.status.isSuccess() && !isDuplicateConflict) {
                return Result.failure(Exception("Error guardando voto: ${insertResponse.status.value} $insertBody"))
            }
        } else {
            val deleteResponse = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/message_poll_votes") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("poll_id", "eq.$pollId")
                parameter("option_id", "eq.$optionId")
                parameter("user_id", "eq.$cleanUserId")
            }
            if (!deleteResponse.status.isSuccess()) {
                val deleteBody = runCatching { deleteResponse.bodyAsText() }.getOrDefault("")
                return Result.failure(Exception("Error quitando voto: ${deleteResponse.status.value} $deleteBody"))
            }
        }

        return getPollState(pollId)
    }

    private suspend fun loadPollAllowsMultiple(pollId: String, token: String): Boolean? {
        val metaResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/message_polls") {
            header("Authorization", "Bearer $token")
            header("apikey", supabaseConfig.supabaseAnonKey)
            parameter("id", "eq.$pollId")
            parameter("select", "allows_multiple")
            parameter("limit", "1")
        }
        if (!metaResponse.status.isSuccess()) return null
        val rows = runCatching { json.decodeFromString<List<JsonObject>>(metaResponse.bodyAsText()) }.getOrDefault(emptyList())
        return rows.firstOrNull()
            ?.get("allows_multiple")
            ?.jsonPrimitive
            ?.booleanOrNull
    }

    private fun isAmbiguousPollIdRpcError(statusCode: Int, body: String): Boolean {
        val lower = body.lowercase()
        return statusCode == 400 &&
            lower.contains("42702") &&
            lower.contains("poll_id") &&
            lower.contains("ambiguous")
    }

    data class CommunityMessageReactionToggleResult(
        val messageId: String,
        val reactions: Map<String, List<String>>
    )

    suspend fun toggleCommunityMessageReaction(messageId: String, emoji: String): Result<CommunityMessageReactionToggleResult> = withContext(Dispatchers.IO) {
        try {
            val token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            val userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("No user"))
            val local = communityDao.getMessageById(messageId)
                ?: return@withContext Result.failure(Exception("Mensaje no encontrado"))

            val normalizedEmoji = emoji.trim()
            if (normalizedEmoji.isBlank()) return@withContext Result.failure(Exception("Reacción inválida"))

            val rpcPayload = buildJsonObject {
                put("p_message_id", messageId)
                put("p_emoji", normalizedEmoji.take(16))
            }

            val rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_toggle_community_message_reaction") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(rpcPayload)
            }

            val updatedReactions = if (rpcResponse.status.isSuccess()) {
                val raw = runCatching { rpcResponse.bodyAsText() }.getOrNull().orEmpty()
                parseReactionToggleResponse(raw, messageId)?.reactions
                    ?: toggleReactionMap(local.toDomain().reactions, normalizedEmoji, userId)
            } else {
                val rpcBody = runCatching { rpcResponse.bodyAsText() }.getOrNull().orEmpty()
                if (isMissingCommunityRpcFunction(rpcResponse.status.value, rpcBody) || isMissingRpcFunctionAlias(rpcBody, "secure_toggle_community_message_reaction")) {
                    val patched = toggleReactionMap(local.toDomain().reactions, normalizedEmoji, userId)
                    val patchPayload = buildJsonObject {
                        put("reactions", reactionsToJsonObject(patched))
                    }
                    val patchResponse = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/community_messages") {
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
        if (source.deleted_for_all) {
            return@withContext Result.failure(Exception("No se puede reenviar un mensaje eliminado"))
        }

        val targets = targetCommunityIds
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { it != source.community_id.trim().lowercase() }

        if (targets.isEmpty()) return@withContext Result.failure(Exception("Selecciona al menos una comunidad"))
        if (targets.size > maxForwardTargetsPerBatch) {
            return@withContext Result.failure(Exception("Puedes reenviar a un máximo de $maxForwardTargetsPerBatch comunidades por vez"))
        }

        var success = 0
        targets.forEach { targetId ->
            val result = sendCommunityMessage(
                communityId = targetId,
                content = if (source.content.isNotBlank()) source.content else "Mensaje reenviado",
                contentType = source.content_type,
                mediaUrl = source.media_url,
                isForwarded = true,
                forwardedFromMessageId = source.id,
                forwardedFromUserId = source.sender_id
            )
            if (result.isSuccess) success++
        }

        if (success == 0) return@withContext Result.failure(Exception("No se pudo reenviar"))
        Result.success(success)
    }

    private fun isMissingRpcFunctionAlias(body: String, functionName: String): Boolean {
        val normalized = body.lowercase()
        return normalized.contains(functionName.lowercase()) &&
            (normalized.contains("could not find the function") || normalized.contains("undefined function"))
    }

    private fun toggleReactionMap(
        current: Map<String, List<String>>,
        emoji: String,
        userId: String
    ): Map<String, List<String>> {
        val normalized = current.mapValues { (_, users) ->
            users.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct().toMutableList()
        }.toMutableMap()

        var hadSameReaction = false
        normalized.keys.toList().forEach { key ->
            val users = normalized[key] ?: mutableListOf()
            val removed = users.remove(userId)
            if (key == emoji && removed) hadSameReaction = true
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
                val cleaned = users.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
                if (emoji.isNotBlank() && cleaned.isNotEmpty()) {
                    put(emoji, JsonArray(cleaned.map { JsonPrimitive(it) }))
                }
            }
        }
    }

    private fun parseReactionToggleResponse(raw: String, fallbackMessageId: String): CommunityMessageReactionToggleResult? {
        if (raw.isBlank()) return null
        return runCatching {
            val element = json.parseToJsonElement(raw)
            when (element) {
                is JsonObject -> {
                    val msgId = element["message_id"]?.jsonPrimitive?.contentOrNull ?: fallbackMessageId
                    val reactionsElement = element["reactions"] ?: element
                    CommunityMessageReactionToggleResult(msgId, parseReactionMap(reactionsElement))
                }
                is JsonArray -> {
                    val first = element.firstOrNull() as? JsonObject ?: return@runCatching null
                    val msgId = first["message_id"]?.jsonPrimitive?.contentOrNull ?: fallbackMessageId
                    val reactionsElement = first["reactions"] ?: first
                    CommunityMessageReactionToggleResult(msgId, parseReactionMap(reactionsElement))
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun parseReactionMap(element: kotlinx.serialization.json.JsonElement?): Map<String, List<String>> {
        val obj = element as? JsonObject ?: return emptyMap()
        return obj.mapNotNull { (emoji, usersElement) ->
            val usersArray = usersElement as? JsonArray ?: return@mapNotNull null
            val users = usersArray.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
            if (emoji.isBlank() || users.isEmpty()) null else emoji to users
        }.toMap()
    }

    suspend fun getCommunityMembers(communityId: String): Result<List<CommunityMember>> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseConfig.client.postgrest["community_members"].select {
                filter { eq("community_id", communityId) }
            }.decodeList<CommunityMember>()
            
            if (response.isEmpty()) return@withContext Result.success(emptyList())

            val userIds = response.map { it.user_id }.distinct()
            val profiles = userRepository.getUsersByIds(userIds).getOrNull()?.associateBy { it.id } ?: emptyMap()
            
            val enriched = response.map { member ->
                val profile = profiles[member.user_id]
                member.copy(
                    userName = profile?.displayName ?: profile?.username ?: "Usuario",
                    userImage = profile?.avatar_url
                )
            }
            
            communityDao.insertMembers(enriched.map { 
                CommunityMemberEntity(it.community_id.trim().lowercase(), it.user_id.trim().lowercase(), it.role, it.joined_at) 
            })
            
            Result.success(enriched)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getMembersFlow(communityId: String): Flow<List<CommunityMember>> {
        val cleanId = communityId.trim().lowercase()
        return communityDao.getMembersFlow(cleanId).map { list ->
            list.map { CommunityMember(it.community_id, it.user_id, it.role, it.joined_at) }
        }
    }

    private suspend fun syncCommunityMessages(communityId: String) {
        val cleanId = communityId.trim().lowercase()
        try {
            val response = supabaseConfig.client.postgrest["community_messages"].select {
                filter { eq("community_id", cleanId) }
                order("created_at", Order.ASCENDING)
            }.decodeList<CommunityMessage>()
            
            if (response.isNotEmpty()) {
                communityDao.insertMessages(response.map { 
                    conectate.com.data.local.entity.CommunityMessageEntity.fromDomain(it.copy(community_id = cleanId)) 
                })
            }
        } catch (e: Exception) {
            android.util.Log.e("CommunityRepository", "Error syncing messages", e)
        }
    }

}
