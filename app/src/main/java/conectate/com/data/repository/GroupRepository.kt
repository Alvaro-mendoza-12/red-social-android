package conectate.com.data.repository

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.app.NotificationManager
import android.app.NotificationChannel
import conectate.com.BuildConfig
import conectate.com.core.messaging.MessageStatusNormalizer
import conectate.com.core.moderation.ContentModerationService
import conectate.com.core.moderation.ModerationContext
import conectate.com.core.moderation.ModerationVerdict
import conectate.com.core.observability.AppMetrics
import conectate.com.core.security.GuardedAction
import conectate.com.core.security.GuardOutcome
import conectate.com.core.security.InputSecurityPolicy
import conectate.com.core.security.OperationGuard
import conectate.com.data.local.dao.GroupDao
import conectate.com.data.local.entity.GroupEntity
import conectate.com.data.local.entity.GroupMemberEntity
import conectate.com.data.local.entity.GroupMessageEntity
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
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.gotrue.auth
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import android.widget.Toast
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
    private val storageService: conectate.com.data.supabase.StorageService,
    private val moderationService: ContentModerationService,
    private val appMetrics: AppMetrics,
    private val operationGuard: OperationGuard,
    @ApplicationContext private val context: Context
) : GroupSyncDataSource {
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
                android.util.Log.d("GroupRepository", "[GOD] Initial Session found ($userId). Starting listeners...")
                startRealtimeSubscriptions(userId ?: "")
                
                // Show Proof of Life Log (Debug only - No Toast)
                android.util.Log.d("GroupRepository", "🟢 [G-REALTIME] Sistema Iniciado")
            }

            // Listen for future changes
            supabaseConfig.client.auth.sessionStatus.collect { status ->
                android.util.Log.d("GroupRepository", "[GOD] sessionStatus update: ${status::class.simpleName}")
                if (status is io.github.jan.supabase.gotrue.SessionStatus.Authenticated) {
                    val userId = status.session.user?.id
                    android.util.Log.d("GroupRepository", "[GOD] User authenticated ($userId). Starting listeners...")
                    startRealtimeSubscriptions(userId ?: "")
                } else if (status is io.github.jan.supabase.gotrue.SessionStatus.NotAuthenticated) {
                    android.util.Log.d("GroupRepository", "[GOD] User not authenticated. Stopping listeners...")
                    stopRealtimeSubscriptions()
                }
            }
        }
    }

    private var realtimeJob: Job? = null
    private var groupListenerJob: Job? = null
    private var subscribedGroupIds: Set<String> = emptySet()

    private fun startRealtimeSubscriptions(userId: String) {
        if (userId.isBlank()) return
        
        realtimeJob?.cancel()
        realtimeJob = scope.launch {
            // [CRITICAL] Force a sync first to ensure we know our groups before subscribing
            syncGroups()
            retryPendingGroupMessages()
            
            // Recalculate joined group IDs to filter Realtime
            val myGroups = groupDao.getAllGroupsSync().filter { it.is_member }.map { it.id.trim().lowercase() }.toSet()
            subscribedGroupIds = myGroups

            launch {
                while (isActive) {
                    delay(45_000)
                    retryPendingGroupMessages()
                }
            }
            
            android.util.Log.d("GroupRepository", "🚀 [REALTIME] Starting subscriptions for ${myGroups.size} groups")
            subscribeToGroupChanges(userId, myGroups.toList())
        }
    }

    private fun stopRealtimeSubscriptions() {
        realtimeJob?.cancel()
        realtimeJob = null
        groupListenerJob?.cancel()
        groupListenerJob = null
        android.util.Log.d("GroupRepository", "[GOD] Realtime subscriptions stopped")

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
            val cleanId = groupId.trim().lowercase()
            // Clear system notifications for this group
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
                            val client = supabaseConfig.client
                            val cleanUserId = userId.trim().lowercase()
                            val channelName = "groups-messages-$cleanUserId"
                            val channel = client.channel(channelName)
                            
                            // [REALTIME FIX] Filter by current joined IDs if possible to reduce noise, 
                            // but RLS should handle it.
                            val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                                table = "group_messages"
                            }

                            coroutineScope {
                                val msgScope = this
                                changes.onEach { change: PostgresAction ->
                                    android.util.Log.d("GroupRepository", "📡 [REALTIME-MSG] Change received: $change")
                                    when (change) {
                                        is PostgresAction.Insert -> {
                                            val msg = json.decodeFromJsonElement<GroupMessage>(change.record)
                                            handleIncomingMessage(msg)
                                        }
                                        is PostgresAction.Update -> {
                                            val msg = json.decodeFromJsonElement<GroupMessage>(change.record)
                                            handleIncomingGroupUpdate(msg)
                                        }
                                        else -> {}
                                    }
                                }.launchIn(this)
                                
                                launch {
                                    channel.subscribe()
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
                        val client = supabaseConfig.client
                        val cleanUserId = userId.trim().lowercase()
                        val channelName = "groups-members-$cleanUserId"
                        val channel = client.channel(channelName)
                        
                        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                            table = "group_members"
                            this.filter = "user_id=eq.$cleanUserId"
                        }

                        coroutineScope {
                            val memberScope = this
                            changes.onEach { change: PostgresAction ->
                                android.util.Log.d("GroupRepository", "👥 Membership changed ($change). Syncing and Refreshing Realtime...")
                                
                                val normalizedUserId = userId.trim().lowercase()
                                when (change) {
                                    is PostgresAction.Insert -> {
                                        try {
                                            val member = json.decodeFromJsonElement<GroupMember>(change.record)
                                            groupDao.insertMembers(listOf(GroupMemberEntity(member.group_id.trim().lowercase(), member.user_id.trim().lowercase(), member.role, member.joined_at)))
                                        } catch (e: Exception) {
                                            android.util.Log.e("GroupRepository", "Error decoding member insert", e)
                                        }
                                    }
                                    is PostgresAction.Update -> {
                                        try {
                                            val member = json.decodeFromJsonElement<GroupMember>(change.record)
                                            val cleanGId = member.group_id.trim().lowercase()
                                            val cleanUId = member.user_id.trim().lowercase()
                                            android.util.Log.i("GroupRepository", "🔄 [ROLE-UPDATE] User $cleanUId in $cleanGId is now ${member.role}")
                                            groupDao.insertMembers(listOf(GroupMemberEntity(cleanGId, cleanUId, member.role, member.joined_at)))
                                        } catch (e: Exception) {
                                            android.util.Log.e("GroupRepository", "Error decoding member update. Check REPLICA IDENTITY FULL.", e)
                                        }
                                    }
                                    is PostgresAction.Delete -> {
                                        val oldData = change.oldRecord
                                        val gId = oldData["group_id"]?.jsonPrimitive?.contentOrNull
                                        if (gId != null) {
                                            groupDao.removeMember(gId.trim().lowercase(), normalizedUserId)
                                        }
                                    }
                                    else -> {}
                                }

                                val result = syncGroups()
                                if (result.isSuccess) {
                                    val newIds = groupDao.getAllGroupsSync().filter { it.is_member }.map { it.id.trim().lowercase() }.toSet()
                                    if (newIds != subscribedGroupIds) {
                                        android.util.Log.i("GroupRepository", "🔄 Group List changed from ${subscribedGroupIds.size} to ${newIds.size}. Full Restart.")
                                        startRealtimeSubscriptions(userId)
                                    }
                                }
                            }.launchIn(this)
                            
                            launch {
                                channel.subscribe()
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
                        val client = supabaseConfig.client
                        val cleanUserId = userId.trim().lowercase()
                        val channelName = "groups-meta-$cleanUserId"
                        val channel = client.channel(channelName)
                        
                        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                            table = "groups"
                        }

                        coroutineScope {
                            val metaScope = this
                            changes.onEach { change: PostgresAction ->
                                android.util.Log.d("GroupRepository", "📝 [REALTIME-META] Group table change: $change")
                                when (change) {
                                    is PostgresAction.Update -> {
                                        try {
                                            val group = json.decodeFromJsonElement<conectate.com.data.model.Group>(change.record)
                                            val cleanGroupId = group.id.trim().lowercase()
                                            
                                            // Only update if we are members or have it locally
                                            val local = groupDao.getGroupById(cleanGroupId)
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
                                        val oldData = change.oldRecord
                                        val gId = oldData["id"]?.jsonPrimitive?.contentOrNull
                                        if (gId != null) {
                                            groupDao.deleteGroup(gId.trim().lowercase())
                                        }
                                    }
                                    else -> {}
                                }
                            }.launchIn(this)
                            launch {
                                channel.subscribe()
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
    
    suspend fun handleIncomingMessage(msg: GroupMessage) {
        withContext(Dispatchers.IO) {
            val cleanId = msg.group_id.trim().lowercase()
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
                    val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
                    
                    if (currentUserId != null && msg.sender_id.trim().lowercase() != currentUserId) {
                        scope.launch { markMessageAsDelivered(cleanId, msg.id) }
                    }

                    // Hydrate reply_to manually since Realtime payload allows it to be null/missing joins
                    val normalizedMsg = if (msg.reply_to_id != null && msg.reply_to == null) {
                        val reply = groupDao.getMessageById(msg.reply_to_id)?.toDomain()
                        msg.copy(reply_to = reply)
                    } else msg.copy(group_id = cleanId) // Ensure clean ID

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

                    groupDao.insertMessages(listOf(conectate.com.data.local.entity.GroupMessageEntity.fromDomain(hydratedMsg)))
                    android.util.Log.d("GroupRepository", "💾 [DB-SAVE] Mensaje ${msg.id} guardado en Room DB")
                    
                    // 5. Actualizar Flow Activo (Chat Screen)
                    updateGroupMessageInFlow(cleanId, hydratedMsg)
                    android.util.Log.d("GroupRepository", "🔄 [FLOW-UPDATE] Flow actualizado para $cleanId")
                    
                    // 6. Actualizar Metadatos del Grupo (Lista de Chats) y Notificar
                    val cleanSenderId = msg.sender_id.trim().lowercase()
                    val isMyMessage = cleanSenderId == currentUserId
                    
                    if (!isMyMessage) {
                        // CRITICAL FIX: Only increment unread if this GROUP is NOT currently open
                        val isGroupCurrentlyOpen = (cleanId == currentOpenedGroupId)
                        
                        if (!isGroupCurrentlyOpen) {
                            android.util.Log.d("GroupRepository", "🔔 [UNREAD+1] Incrementando contador para $cleanId (grupo cerrado)")
                            groupDao.incrementUnreadCount(cleanId)
                            showNotification(msg, localGroup.name)
                        } else {
                            android.util.Log.d("GroupRepository", "👁️ [UNREAD-SKIP] Grupo $cleanId está abierto. NO incrementar contador.")
                        }
                    } else {
                        android.util.Log.d("GroupRepository", "✍️ [MY-MSG] Mensaje enviado por mí. No incrementar unread.")
                    }
                    
                    // Actualizar last_message siempre (para todos los mensajes, incluso los míos)
                    groupDao.updateMetadata(cleanId, 
                        when(msg.content_type) {
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

    private suspend fun handleIncomingGroupUpdate(msg: GroupMessage) {
        withContext(Dispatchers.IO) {
            try {
                val existing = groupDao.getMessageById(msg.id)
                if (existing != null) {
                    val currentDomain = existing.toDomain()
                    val mergedReceipt = mergeGroupReceiptState(
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
                    
                    groupDao.insertMessages(listOf(conectate.com.data.local.entity.GroupMessageEntity.fromDomain(finalMsg)))
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
        val current = normalizeGroupReceiptStatus(currentStatus)
        val incoming = normalizeGroupReceiptStatus(incomingStatus)
        var mergedReadAt = incomingReadAt ?: currentReadAt
        var mergedDeliveredAt = incomingDeliveredAt ?: currentDeliveredAt

        val candidate = when {
            !mergedReadAt.isNullOrBlank() || incoming == "read" -> "read"
            !mergedDeliveredAt.isNullOrBlank() || incoming == "delivered" -> "delivered"
            else -> incoming
        }
        val finalStatus = if (groupReceiptRank(candidate) >= groupReceiptRank(current)) candidate else current
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
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                
                // CRITICAL: Use unique channel ID for groups (different from 1-to-1 and communities)
                val channelId = "group_messages_channel_v2"
                
                val channel = android.app.NotificationChannel(
                    channelId,
                    "Mensajes de Grupos",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notificaciones de mensajes en grupos"
                    enableVibration(true)
                    enableLights(true)
                    lightColor = android.graphics.Color.RED
                    setShowBadge(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                notificationManager.createNotificationChannel(channel)
                android.util.Log.d("GroupRepository", "🔔 [NOTIFICATION] Channel created/verified: $channelId")

                val intent = android.content.Intent(context, conectate.com.MainActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("chat_id", msg.group_id)
                    putExtra("group_id", msg.group_id)
                    putExtra("is_group", true)
                    putExtra("target_type", "group")
                    putExtra("type", "new_message")
                }
                val pendingIntent = android.app.PendingIntent.getActivity(
                    context, 
                    msg.group_id.hashCode(), 
                    intent, 
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )

                val contentText = "${msg.sender_name ?: "Alguien"}: ${if(msg.content_type == "text") msg.content else "📎 Multimedia"}"

                val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(conectate.com.R.drawable.ic_notification)
                    .setContentTitle(groupName)
                    .setContentText(contentText)
                    .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(contentText))
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX) // MAXIMUM priority
                    .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .setGroup("GROUP_MESSAGES") // Group all group messages together
                    .setGroupSummary(false) // Individual notifications, not summary
                    .setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION))
                    .setVibrate(longArrayOf(0, 250, 250, 250))
                    .setDefaults(android.app.Notification.DEFAULT_ALL)
                    .build()

                notificationManager.notify(msg.group_id.hashCode(), notification)
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
    fun getGroupsFlow(): Flow<List<GroupWithLastMessage>> {
        return groupDao.getAllGroupsFlow()
            .distinctUntilChanged()
            .map { entities ->
                entities.map { entity ->
                    GroupWithLastMessage(
                        group = entity.toDomain(),
                        unreadCount = entity.unread_count
                    )
                }.sortedByDescending { it.group.last_message_at }
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * Obtiene un grupo por su ID (Optimizado).
     */
    suspend fun getGroupById(id: String): Group? = withContext(Dispatchers.IO) {
        val cleanId = id.trim().lowercase()
        val group = groupDao.getGroupById(cleanId)?.toDomain() ?: try {
            val remote = supabaseConfig.client.postgrest["groups"].select {
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
        val cleanId = id.trim().lowercase()
        return groupDao.getGroupByIdFlow(cleanId).map { it?.toDomain() }
    }

    fun getGroupByIdFlow(groupId: String): Flow<conectate.com.data.local.entity.GroupEntity?> {
        val cleanId = groupId.trim().lowercase()
        return groupDao.getGroupByIdFlow(cleanId)
    }

    
    fun isUserMemberFlow(groupId: String, userId: String): Flow<Boolean> {
        return groupDao.isUserMemberFlow(groupId.trim().lowercase(), userId.trim().lowercase())
    }


    // --- MESSAGES FLOW ---
    
    fun getMessagesFlow(groupId: String): Flow<List<GroupMessage>> {
        val cleanId = groupId.trim().lowercase()
        val currentUserId = authService.getCurrentUserId()
        
        return _activeGroupMessages.getOrPut(cleanId) {
            val state = MutableStateFlow<List<GroupMessage>>(emptyList())
            
            // 1. Room Collection
            scope.launch {
                groupDao.getMessagesFlow(cleanId)
                    .distinctUntilChanged()
                    .collect { entities ->
                        val localMessages = entities.map { it.toDomain() }
                        state.update { current -> unifyGroupMessages(localMessages + current, currentUserId) }
                    }
            }
            // 2. Initial Sync
            scope.launch { syncGroupMessages(cleanId) }
            
            state
        }
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
        val cleanId = groupId.trim().lowercase()
        val flow = _activeGroupMessages[cleanId] ?: return
        val currentUserId = authService.getCurrentUserId()
        
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
        val cleanId = groupId.trim().lowercase()
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
                    val response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/group_messages") {
                        header("Authorization", "Bearer $token")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Content-Type", "application/json")
                        header("Prefer", "return=representation")
                        parameter("group_id", "eq.$cleanId")
                        parameter("sender_id", "neq.$currentUserId")
                        parameter("id", "in.(${cleanMessageIds.joinToString(",")})")
                        parameter("read_at", "is.null")
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
                    android.util.Log.w("GroupRepository", "markAsRead remote sync failed for $cleanId", e)
                }
            }
            if (remotelyReadIds.isEmpty() && !token.isNullOrBlank()) {
                remotelyReadIds.addAll(
                    fetchRemoteGroupMessageIdsByStatus(
                        groupId = cleanId,
                        messageIds = cleanMessageIds,
                        currentUserId = currentUserId,
                        token = token,
                        statuses = listOf("read")
                    )
                )
            }
            val idsToMark = cleanMessageIds.filter { remotelyReadIds.contains(it) }
            if (idsToMark.isNotEmpty()) {
                groupDao.markMessagesAsReadByIds(cleanId, now, currentUserId, idsToMark)
            }
            val remainingUnread = runCatching { groupDao.countUnreadIncoming(cleanId, currentUserId) }.getOrDefault(0)
            if (currentOpenedGroupId == cleanId || remainingUnread <= 0) {
                groupDao.resetUnreadCount(cleanId)
            }
        }
    }

    suspend fun markAsRead(groupId: String) {
        val cleanId = groupId.trim().lowercase()
        withContext(Dispatchers.IO) {
            val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return@withContext
            val token = authService.getAccessToken()
            val now = java.time.Instant.now().toString()
            if (!token.isNullOrBlank()) {
                var remoteReadSynced = false
                runCatching {
                    supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_mark_group_messages_read") {
                        header("Authorization", "Bearer $token")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Content-Type", "application/json")
                        setBody(buildJsonObject { put("p_group_id", cleanId) })
                    }
                }.onSuccess { response ->
                    remoteReadSynced = response.status.isSuccess()
                    if (!remoteReadSynced) {
                        android.util.Log.w("GroupRepository", "secure_mark_group_messages_read failed for $cleanId: ${response.status}")
                    }
                }.onFailure { e ->
                    android.util.Log.w("GroupRepository", "secure_mark_group_messages_read exception for $cleanId", e)
                }

                if (!remoteReadSynced) {
                    val messageUpdate = buildJsonObject {
                        put("status", "read")
                        put("read_at", now)
                        put("delivered_at", now)
                        put("updated_at", now)
                    }
                    runCatching {
                        supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/group_messages") {
                            header("Authorization", "Bearer $token")
                            header("apikey", supabaseConfig.supabaseAnonKey)
                            header("Content-Type", "application/json")
                            parameter("group_id", "eq.$cleanId")
                            parameter("sender_id", "neq.$currentUserId")
                            parameter("read_at", "is.null")
                            setBody(messageUpdate)
                        }
                    }.onFailure { e ->
                        android.util.Log.w("GroupRepository", "markAsRead fallback patch failed for $cleanId", e)
                    }
                }
            } else {
                android.util.Log.w("GroupRepository", "markAsRead: no token available, skipping remote sync for $cleanId")
            }
            groupDao.markAllAsRead(cleanId, now, currentUserId)
            groupDao.resetUnreadCount(cleanId)
            runCatching {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(cleanId.hashCode())
            }
        }
    }

    suspend fun markMessageAsDelivered(groupId: String, messageId: String) {
        val cleanId = groupId.trim().lowercase()
        val cleanMessageId = messageId.trim().lowercase()
        if (cleanId.isBlank() || cleanMessageId.isBlank()) return
        withContext(Dispatchers.IO) {
            val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return@withContext
            val token = authService.getAccessToken()
            val now = java.time.Instant.now().toString()
            var remoteSynced = false
            if (!token.isNullOrBlank()) {
                try {
                    val response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/group_messages") {
                        header("Authorization", "Bearer $token")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Content-Type", "application/json")
                        header("Prefer", "return=representation")
                        parameter("group_id", "eq.$cleanId")
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
                    android.util.Log.w("GroupRepository", "markMessageAsDelivered remote sync failed for $cleanMessageId", e)
                }
            }
            if (!remoteSynced && !token.isNullOrBlank()) {
                remoteSynced = fetchRemoteGroupMessageIdsByStatus(
                    groupId = cleanId,
                    messageIds = listOf(cleanMessageId),
                    currentUserId = currentUserId,
                    token = token,
                    statuses = listOf("delivered", "read", "received")
                ).contains(cleanMessageId)
            }
            groupDao.markMessageAsDelivered(cleanId, cleanMessageId, now, currentUserId)
            if (!remoteSynced) {
                android.util.Log.w("GroupRepository", "markMessageAsDelivered could not confirm remote transition for $cleanMessageId in $cleanId")
            }
        }
    }

    suspend fun markMessagesAsDelivered(groupId: String) {
        val cleanId = groupId.trim().lowercase()
        withContext(Dispatchers.IO) {
            val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return@withContext
            val token = authService.getAccessToken()
            val now = java.time.Instant.now().toString()
            if (!token.isNullOrBlank()) {
                try {
                    supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_mark_group_messages_delivered") {
                        header("Authorization", "Bearer $token")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Content-Type", "application/json")
                        setBody(buildJsonObject { put("p_group_id", cleanId) })
                    }
                    groupDao.markAllAsDelivered(cleanId, now, currentUserId)
                } catch (e: Exception) {
                    android.util.Log.w("GroupRepository", "markMessagesAsDelivered remote sync failed for $cleanId", e)
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

    private suspend fun fetchRemoteGroupMessageIdsByStatus(
        groupId: String,
        messageIds: Collection<String>,
        currentUserId: String,
        token: String,
        statuses: List<String>
    ): Set<String> {
        val cleanGroupId = groupId.trim().lowercase()
        val cleanIds = messageIds.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
        val cleanStatuses = statuses.map { it.trim().lowercase() }.filter { it.isNotBlank() }.distinct()
        if (cleanGroupId.isBlank() || cleanIds.isEmpty() || cleanStatuses.isEmpty()) return emptySet()

        return runCatching {
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/group_messages") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("select", "id")
                parameter("group_id", "eq.$cleanGroupId")
                parameter("sender_id", "neq.$currentUserId")
                parameter("id", "in.(${cleanIds.joinToString(",")})")
                parameter("status", "in.(${cleanStatuses.joinToString(",")})")
            }
            if (!response.status.isSuccess()) return@runCatching emptySet<String>()
            extractUpdatedIdsFromPatchBody(response.bodyAsText())
        }.getOrElse { emptySet() }
    }

    suspend fun sendGroupPollMessage(
        groupId: String,
        question: String,
        options: List<String>,
        allowsMultiple: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanId = groupId.trim().lowercase()
        val userId = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: return@withContext Result.failure(Exception("No user"))

        val sanitizedQuestion = question.trim().take(300)
        val sanitizedOptions = options.map { it.trim().take(120) }.filter { it.isNotBlank() }.distinct()
        if (sanitizedQuestion.isBlank()) return@withContext Result.failure(Exception("La pregunta es obligatoria"))
        if (sanitizedOptions.size < 2) return@withContext Result.failure(Exception("La encuesta requiere al menos 2 opciones"))

        val msgId = UUID.randomUUID().toString()
        val now = java.time.Instant.now().toString()
        val optimistic = GroupMessage(
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

        val remote = createGroupPollMessageSecure(
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
            val error = remote.exceptionOrNull() ?: Exception("No se pudo crear la encuesta")
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
    ): Result<GroupMessage> {
        return try {
            val token = authService.getAccessToken()
                ?: return Result.failure(Exception("No token"))
            val payloadAttempts = listOf(
                buildJsonObject {
                    put("p_group_id", groupId)
                    put("p_message_id", messageId)
                    put("p_question", question)
                    put("p_options", JsonArray(options.map { JsonPrimitive(it) }))
                    put("p_allows_multiple", allowsMultiple)
                },
                buildJsonObject {
                    put("p_group_id", groupId)
                    put("p_message_id", messageId)
                    put("p_question", question)
                    put("p_options", toPostgresTextArray(options))
                    put("p_allows_multiple", allowsMultiple)
                }
            )

            var rpcError: Exception? = null
            payloadAttempts.forEach { payload ->
                val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_create_group_poll_message") {
                    header("Authorization", "Bearer $token")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Content-Type", "application/json")
                    header("Prefer", "return=representation")
                    setBody(payload)
                }

                val body = runCatching { response.bodyAsText() }.getOrDefault("")
                if (response.status.isSuccess()) {
                    val parsed = parseRpcGroupMessage(body)
                    if (parsed != null) {
                        return Result.success(parsed)
                    }
                    rpcError = Exception("Respuesta inválida creando encuesta")
                } else {
                    rpcError = Exception("Error creando encuesta: ${response.status.value} $body")
                }
            }

            val fallback = createGroupPollMessageFallback(
                groupId = groupId,
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

    private suspend fun createGroupPollMessageFallback(
        groupId: String,
        messageId: String,
        question: String,
        options: List<String>,
        allowsMultiple: Boolean,
        token: String
    ): Result<GroupMessage> {
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
                        put("scope_type", "group")
                        put("scope_id", groupId)
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

            val messageResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/group_messages") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                setBody(
                    buildJsonObject {
                        put("id", messageId)
                        put("group_id", groupId)
                        put("sender_id", userId)
                        put("content", question)
                        put("content_type", "poll")
                        put("poll_id", pollId)
                        put("status", "sent")
                    }
                )
            }
            val msgBody = runCatching { messageResponse.bodyAsText() }.getOrDefault("")
            if (!messageResponse.status.isSuccess()) {
                return Result.failure(Exception("Error enviando mensaje encuesta (fallback): ${messageResponse.status.value} $msgBody"))
            }

            val parsed = parseRpcGroupMessage(msgBody)
            if (parsed != null) {
                Result.success(parsed)
            } else {
                Result.success(
                    GroupMessage(
                        id = messageId,
                        group_id = groupId,
                        sender_id = userId,
                        content = question,
                        content_type = "poll",
                        poll_id = pollId,
                        created_at = java.time.Instant.now().toString(),
                        status = "sent"
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseRpcGroupMessage(body: String): GroupMessage? {
        if (body.isBlank()) return null
        return try {
            json.decodeFromString<GroupMessage>(body)
        } catch (_: Exception) {
            runCatching { json.decodeFromString<List<GroupMessage>>(body).firstOrNull() }.getOrNull()
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
    
    suspend fun sendGroupMessage(
        groupId: String,
        content: String,
        replyToId: String? = null,
        contentType: String = "text",
        mediaUrl: String? = null,
        pollId: String? = null,
        isForwarded: Boolean = false,
        forwardedFromMessageId: String? = null,
        forwardedFromUserId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            val userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("No user"))
            val cleanId = groupId.trim().lowercase()

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
            
            val msgId = java.util.UUID.randomUUID().toString()
            val now = java.time.Instant.now().toString()
            
            val msg = GroupMessage(
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
            groupDao.updateMetadata(cleanId, if (contentType == "text") finalContent else "Multimedia", now)

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

            val syncResult = insertGroupMessageWithRetry(msgId, msgJson)
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
        var lastError: Exception? = null

        for (attempt in 0 until maxAttempts) {
            try {
                val pushResult = sendGroupMessageSecureOrFallback(msgId = msgId, payload = payload)
                if (pushResult.isFailure) {
                    throw (pushResult.exceptionOrNull() ?: Exception("Unknown push error"))
                }
                groupDao.updateMessageSyncStatus(msgId, status = "sent", isSynced = true, pendingAction = null)
                groupDao.getMessageById(msgId)?.toDomain()?.let { synced -> updateGroupMessageInFlow(synced.group_id, synced) }
                return Result.success(Unit)
            } catch (e: Exception) {
                lastError = e

                // If message was inserted previously, duplicate-key means delivery already happened.
                if (isDuplicateKeyError(e)) {
                    android.util.Log.w("GroupRepository", "⚠️ Duplicate insert for $msgId detected; treating as delivered")
                    groupDao.updateMessageSyncStatus(msgId, status = "sent", isSynced = true, pendingAction = null)
                    groupDao.getMessageById(msgId)?.toDomain()?.let { synced -> updateGroupMessageInFlow(synced.group_id, synced) }
                    return Result.success(Unit)
                }

                val isLastAttempt = attempt == maxAttempts - 1
                val retryable = isRetryableNetworkError(e)
                if (!retryable || isLastAttempt) {
                    groupDao.markRetryAttempt(msgId, lastRetryAt = java.time.Instant.now().toString(), status = "failed")
                    groupDao.updateMessageSyncStatus(msgId, status = "failed", isSynced = false, pendingAction = "send")
                    groupDao.getMessageById(msgId)?.toDomain()?.let { failed -> updateGroupMessageInFlow(failed.group_id, failed) }
                    return Result.failure(lastError)
                }

                val backoffMs = 1000L * (1L shl minOf(attempt, 3))
                delay(backoffMs)
            }
        }

        return Result.failure(lastError ?: Exception("No se pudo sincronizar mensaje de grupo"))
    }

    private suspend fun sendGroupMessageSecureOrFallback(
        msgId: String,
        payload: JsonObject
    ): Result<Unit> {
        val token = authService.getAccessToken()
            ?: return Result.failure(Exception("No token"))

        val groupId = payload["group_id"]?.jsonPrimitive?.contentOrNull
            ?: return Result.failure(Exception("Missing group_id payload"))
        val content = payload["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val contentType = payload["content_type"]?.jsonPrimitive?.contentOrNull ?: "text"
        val mediaUrl = payload["media_url"]?.jsonPrimitive?.contentOrNull
        val replyToId = payload["reply_to_id"]?.jsonPrimitive?.contentOrNull
        val isForwarded = payload["is_forwarded"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val forwardedFromMessageId = payload["forwarded_from_message_id"]?.jsonPrimitive?.contentOrNull
        val forwardedFromUserId = payload["forwarded_from_user_id"]?.jsonPrimitive?.contentOrNull

        val rpcPayload = buildJsonObject {
            put("p_group_id", groupId)
            put("p_message_id", msgId)
            put("p_content", content)
            put("p_content_type", contentType)
            put("p_media_url", mediaUrl)
            put("p_reply_to_id", replyToId)
            put("p_is_forwarded", isForwarded)
            put("p_forwarded_from_message_id", forwardedFromMessageId)
            put("p_forwarded_from_user_id", forwardedFromUserId)
        }

        return try {
            val rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_send_group_message") {
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
            if (isMissingGroupRpcFunction(rpcResponse.status.value, rpcError)) {
                return if (isForwarded || !forwardedFromMessageId.isNullOrBlank() || !forwardedFromUserId.isNullOrBlank()) {
                    val firstTry = runCatching {
                        supabaseConfig.client.postgrest["group_messages"].insert(payload)
                    }
                    if (firstTry.isSuccess) {
                        Result.success(Unit)
                    } else {
                        val errMsg = firstTry.exceptionOrNull()?.message.orEmpty()
                        if (isMissingForwardingColumnError(errMsg)) {
                            val legacyPayload = buildJsonObject {
                                payload["id"]?.let { put("id", it) }
                                payload["group_id"]?.let { put("group_id", it) }
                                payload["sender_id"]?.let { put("sender_id", it) }
                                payload["content"]?.let { put("content", it) }
                                payload["content_type"]?.let { put("content_type", it) }
                                payload["media_url"]?.let { put("media_url", it) }
                                payload["reply_to_id"]?.let { put("reply_to_id", it) }
                                payload["poll_id"]?.let { put("poll_id", it) }
                            }
                            runCatching { supabaseConfig.client.postgrest["group_messages"].insert(legacyPayload) }
                                .fold(
                                    onSuccess = { Result.success(Unit) },
                                    onFailure = { Result.failure(it as? Exception ?: Exception(it.message ?: "Fallback insert failed")) }
                                )
                        } else {
                            Result.failure(firstTry.exceptionOrNull() as? Exception ?: Exception(errMsg.ifBlank { "Fallback insert failed" }))
                        }
                    }
                } else {
                    supabaseConfig.client.postgrest["group_messages"].insert(payload)
                    Result.success(Unit)
                }
            } else {
                Result.failure(Exception("RPC group send failed ${rpcResponse.status.value}: $rpcError"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isMissingGroupRpcFunction(statusCode: Int, responseBody: String): Boolean {
        if (statusCode != 400 && statusCode != 404) return false
        val cleanBody = responseBody.lowercase()
        return cleanBody.contains("secure_send_group_message") &&
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

    private suspend fun retryPendingGroupMessages(maxBatch: Int = 40) {
        val userId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return
        val pending = groupDao.getPendingOutgoingMessages(maxBatch)
        if (pending.isEmpty()) return

        pending.forEach { pendingEntity ->
            if (pendingEntity.sender_id.trim().lowercase() != userId) return@forEach

            val payload = buildJsonObject {
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

            insertGroupMessageWithRetry(msgId = pendingEntity.id, payload = payload)
        }
    }

    suspend fun editGroupMessage(messageId: String, newContent: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("No user"))
            
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
            val existing = groupDao.getMessageById(messageId)
            if (existing != null) {
                val updated = existing.toDomain().copy(content = newContent, is_edited = true)
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
            val userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("No user"))
            
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
                 val existing = groupDao.getMessageById(messageId) ?: return@withContext Result.success(Unit)
                 val currentDeleted = existing.toDomain().deleted_by.toMutableList()

                 if (!currentDeleted.contains(userId)) {
                     currentDeleted.add(userId)
                     
                     supabaseConfig.client.postgrest["group_messages"].update({
                        set("deleted_by", currentDeleted.toList())
                    }) {
                        filter { eq("id", messageId) }
                    }
                 }

            }
             // Optimistic 
            val existingLocal = groupDao.getMessageById(messageId)
            if (existingLocal != null) {
                val domain = existingLocal.toDomain()
                val updated = if (forEveryone) domain.copy(deleted_for_all = true) 
                              else domain.copy(deleted_by = (domain.deleted_by + userId).distinct())
                
                groupDao.insertMessages(listOf(conectate.com.data.local.entity.GroupMessageEntity.fromDomain(updated)))
                updateGroupMessageInFlow(domain.group_id, updated)
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getGroupMessageReadReceipts(messageId: String): Result<List<MessageReadReceipt>> = withContext(Dispatchers.IO) {
        try {
            val token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_get_group_message_receipts") {
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

    data class GroupMessageReactionToggleResult(
        val messageId: String,
        val reactions: Map<String, List<String>>
    )

    suspend fun toggleGroupMessageReaction(messageId: String, emoji: String): Result<GroupMessageReactionToggleResult> = withContext(Dispatchers.IO) {
        try {
            val token = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            val userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return@withContext Result.failure(Exception("No user"))
            val local = groupDao.getMessageById(messageId)
                ?: return@withContext Result.failure(Exception("Mensaje no encontrado"))

            val normalizedEmoji = emoji.trim()
            if (normalizedEmoji.isBlank()) {
                return@withContext Result.failure(Exception("Reacción inválida"))
            }

            val rpcPayload = buildJsonObject {
                put("p_message_id", messageId)
                put("p_emoji", normalizedEmoji.take(16))
            }

            val rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_toggle_group_message_reaction") {
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
                if (isMissingGroupRpcFunction(rpcResponse.status.value, rpcBody) || isMissingRpcFunctionAlias(rpcBody, "secure_toggle_group_message_reaction")) {
                    val patched = toggleReactionMap(local.toDomain().reactions, normalizedEmoji, userId)
                    val patchPayload = buildJsonObject {
                        put("reactions", reactionsToJsonObject(patched))
                    }
                    val patchResponse = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/group_messages") {
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
        if (source.deleted_for_all) {
            return@withContext Result.failure(Exception("No se puede reenviar un mensaje eliminado"))
        }

        val targets = targetGroupIds
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { it != source.group_id.trim().lowercase() }

        if (targets.isEmpty()) return@withContext Result.failure(Exception("Selecciona al menos un grupo"))
        if (targets.size > maxForwardTargetsPerBatch) {
            return@withContext Result.failure(Exception("Puedes reenviar a un máximo de $maxForwardTargetsPerBatch grupos por vez"))
        }

        var success = 0
        targets.forEach { targetId ->
            val result = sendGroupMessage(
                groupId = targetId,
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

    private fun parseReactionToggleResponse(raw: String, fallbackMessageId: String): GroupMessageReactionToggleResult? {
        if (raw.isBlank()) return null
        return runCatching {
            val element = json.parseToJsonElement(raw)
            when (element) {
                is JsonObject -> {
                    val msgId = element["message_id"]?.jsonPrimitive?.contentOrNull ?: fallbackMessageId
                    val reactionsElement = element["reactions"] ?: element
                    GroupMessageReactionToggleResult(msgId, parseReactionMap(reactionsElement))
                }
                is JsonArray -> {
                    val first = element.firstOrNull() as? JsonObject ?: return@runCatching null
                    val msgId = first["message_id"]?.jsonPrimitive?.contentOrNull ?: fallbackMessageId
                    val reactionsElement = first["reactions"] ?: first
                    GroupMessageReactionToggleResult(msgId, parseReactionMap(reactionsElement))
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

    suspend fun syncGroupMessages(groupId: String) {
        val cleanId = groupId.trim().lowercase()
        val currentUserId = authService.getCurrentUserId() ?: return
        
        withContext(Dispatchers.IO) {
            try {
                // 1. Fetch Remote Messages
                val response = supabaseConfig.client.postgrest["group_messages"].select {
                    filter { eq("group_id", cleanId) }
                    order("created_at", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                }.decodeList<GroupMessage>()
                
                if (response.isNotEmpty()) {
                    // 2. Update flow cache if active
                    if (_activeGroupMessages.containsKey(cleanId)) {
                         val current = _activeGroupMessages[cleanId]?.value ?: emptyList()
                         val combined = (current + response).distinctBy { it.id }.sortedBy { it.created_at }
                         _activeGroupMessages[cleanId]?.value = combined
                    }
                    
                    // 3. Insert all messages first, then derive unread from persisted status.
                    groupDao.insertMessages(response.map { 
                        GroupMessageEntity.fromDomain(it.copy(group_id = cleanId)) 
                    })

                    val normalizedCurrentUserId = currentUserId.trim().lowercase()
                    val exactUnread = runCatching {
                        groupDao.countUnreadIncoming(cleanId, normalizedCurrentUserId)
                    }.getOrDefault(0)
                    val isGroupCurrentlyOpen = (cleanId == currentOpenedGroupId)
                    groupDao.resetUnreadCount(cleanId)
                    if (!isGroupCurrentlyOpen && exactUnread > 0) {
                        android.util.Log.d("GroupRepository", "🔔 [SYNC-UNREAD] Reconciled unread=$exactUnread for group $cleanId")
                        groupDao.incrementUnreadCount(cleanId, exactUnread)
                    }
                    
                    // 4. Update metadata logic (pick the absolute latest by timestamp).
                    val lastMsg = response.maxByOrNull { it.created_at }
                    if (lastMsg != null) {
                        groupDao.updateMetadata(
                            cleanId,
                            if(lastMsg.content_type == "text") lastMsg.content else "Archivo multimedia",
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


    suspend fun uploadGroupAudio(groupId: String, bytes: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        try {
            val fileName = "group_audio_${groupId}_${System.currentTimeMillis()}.mp3"
             val result = storageService.uploadFile(
                bucketName = "group-audio", 
                fileName = fileName,
                bytes = bytes,
                contentType = "audio/mpeg"
            )
             if (result.isSuccess) {
                 Result.success(storageService.getPublicUrl("group-audio", fileName))
             } else {
                 Result.failure(result.exceptionOrNull() ?: Exception("Upload failed"))
             }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun syncGroups(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = (authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))).trim().lowercase()
            
            android.util.Log.d("GroupRepository", "🔄 [SYNC] Iniciando sincronización de grupos para $userId")
            
            // 1. Fetch memberships first
            val memberships = supabaseConfig.client.postgrest["group_members"].select {
                filter { eq("user_id", userId) }
            }.decodeList<GroupMember>()
            
            val ids = memberships.map { it.group_id.trim().lowercase() }
            
            if (ids.isEmpty()) {
                android.util.Log.d("GroupRepository", "⚠️ [SYNC] Usuario no tiene grupos. Limpiando local.")
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
            val localCounts = allLocalGroups.associate { it.id.trim().lowercase() to it.unread_count }
            val localGroupsMap = allLocalGroups.associateBy { it.id.trim().lowercase() }

            groupDao.upsertGroups(groupsResponse.map { 
                val cleanGroupId = it.id.trim().lowercase()
                val localGroup = localGroupsMap[cleanGroupId]
                
                // Merge Last Message Logic (Server vs Local)
                val serverTimeStr = it.last_message_at
                val localTimeStr = localGroup?.last_message_at

                var useLocal = false
                if (localTimeStr != null && serverTimeStr != null) {
                    try {
                        val serverInstant = java.time.Instant.parse(serverTimeStr)
                        val localInstant = java.time.Instant.parse(localTimeStr)
                        if (localInstant.isAfter(serverInstant)) {
                            useLocal = true
                        }
                    } catch (e: Exception) {
                        // If parse fails (e.g. legacy format), fall back to string comparison or server authority
                        if (localTimeStr > serverTimeStr) useLocal = true
                    }
                } else if (localTimeStr != null && serverTimeStr == null) {
                     useLocal = true
                }

                val finalMsg = if (useLocal) localGroup?.last_message else it.last_message
                val finalTime = if (useLocal) localGroup?.last_message_at else it.last_message_at

                // [FIX] Don't allow server to nullify local last_message if it provides nothing
                val actualMsg = if (finalMsg.isNullOrBlank() && !localGroup?.last_message.isNullOrBlank()) localGroup?.last_message else finalMsg
                val actualTime = if (finalTime.isNullOrBlank() && !localGroup?.last_message_at.isNullOrBlank()) localGroup?.last_message_at else finalTime

                GroupEntity.fromDomain(
                    group = it.copy(
                        id = cleanGroupId,
                        is_member = true,
                        last_message = actualMsg,
                        last_message_at = actualTime
                    ),
                    unreadCount = localCounts[cleanGroupId] ?: 0
                ) 
            })
            
            // 4. Update Memberships
            // 4. Update Memberships (Smart Update)
            val validMemberships = memberships.filter { activeIds.contains(it.group_id.trim().lowercase()) }
            
            // Upsert (Replace) current valid memberships
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
            groupDao.markOthersAsNotMembers(activeIds)
            
            // [SMART SYNC] Check for Ghost Messages after sync
            try {
                groupsResponse.forEach { group ->
                    val cleanId = group.id.trim().lowercase()
                    val serverTime = group.last_message_at
                    val localGroup = localGroupsMap[cleanId]
                    val localTime = localGroup?.last_message_at
                    
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
                             android.util.Log.i("GroupRepository", "👻 [GHOST-CHECK] Group $cleanId has newer content on server. Fetching...")
                             launch { syncGroupMessages(cleanId) }
                        }
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

    suspend fun uploadGroupImage(groupId: String, bytes: ByteArray): Result<String> = withContext(Dispatchers.IO) {
        try {
            val fileName = "group_${groupId}_${System.currentTimeMillis()}.jpg"
            val result = storageService.uploadFile(
                bucketName = "groups",
                fileName = fileName,
                bytes = bytes,
                contentType = "image/jpeg"
            )
            if (result.isSuccess) {
                Result.success(storageService.getPublicUrl("groups", fileName))
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("Upload failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Crea un nuevo grupo en Supabase e inserta al creador como admin.
     */
    suspend fun createGroup(name: String, description: String?, imageBytes: ByteArray?, members: List<String>): Result<String> = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("GroupRepository", "[GOD] Creando grupo: $name para integrantes: $members")
            val creatorId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            
            val groupId = UUID.randomUUID().toString().lowercase()
            android.util.Log.d("GroupRepository", "[GOD] Generando nuevo groupId: $groupId")
            
            var imageUrl: String? = null
            if (imageBytes != null) {
                val uploadResult = uploadGroupImage(groupId, imageBytes)
                if (uploadResult.isSuccess) {
                    imageUrl = uploadResult.getOrNull()
                }
            }

            // Usar JsonObject explícito para evitar fallos por campos nulos o inesperados
            val now = java.time.Instant.now().toString()
            val groupJson = buildJsonObject {
                put("id", groupId)
                put("name", name)
                put("description", description)
                put("image", imageUrl)
                put("created_at", now)
                put("creator_id", creatorId)
                put("is_deleted", false)
            }

            // 1. Insertar el grupo
            android.util.Log.d("GroupRepository", "[GOD] Insertando grupo en Supabase...")
            supabaseConfig.client.postgrest["groups"].insert(groupJson)

            // 2. Insertar miembros (incluyendo al creador)
            val allMembers = (members + creatorId).distinct()
            android.util.Log.d("GroupRepository", "[GOD] Insertando ${allMembers.size} miembros en Supabase...")
            val memberJsonList = allMembers.map { uid ->
                buildJsonObject {
                    put("group_id", groupId)
                    put("user_id", uid)
                    put("role", if (uid == creatorId) "admin" else "member")
                    put("joined_at", now)
                }
            }
            supabaseConfig.client.postgrest["group_members"].insert(memberJsonList)

            // 3. INSERCIÓN LOCAL OPTIMISTA (WhatsApp Style)
            // Esto evita que el creador vea "No formas parte" mientras espera al Realtime
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
                
                // También insertamos la membresía localmente
                groupDao.insertMembers(allMembers.map { uid ->
                    conectate.com.data.local.entity.GroupMemberEntity(
                        group_id = groupId,
                        user_id = uid.trim().lowercase(),
                        role = if (uid == creatorId) "admin" else "member",
                        joined_at = now
                    )
                })
            android.util.Log.d("GroupRepository", "[GOD] Inserción local optimista exitosa para $groupId")
        } catch (e: Exception) {
            android.util.Log.e("GroupRepository", "Fallo inserción local optimista", e)
        }

        // NO sincronizar inmediatamente - dejar que Realtime lo maneje
        // syncGroups() puede borrar los miembros locales si el servidor aún no responde
        android.util.Log.d("GroupRepository", "[GOD] Creación completada. Realtime sincronizará automáticamente.")

        Result.success(groupId)
        } catch (e: Exception) {
            android.util.Log.e("GroupRepository", "[GOD] ERROR AL CREAR GRUPO", e)
            Result.failure(e)
        }
    }

    suspend fun getGroupMembers(groupId: String): Result<List<GroupMember>> = withContext(Dispatchers.IO) {
        try {
            val response = supabaseConfig.client.postgrest["group_members"].select {
                filter { eq("group_id", groupId) }
            }.decodeList<GroupMember>()
            
            if (response.isEmpty()) return@withContext Result.success(emptyList())

            // Fetch user profiles in parallel using Postgrest client
            val userIds = response.map { it.user_id }.distinct()
            val profiles = supabaseConfig.client.postgrest["profiles"].select {
                filter {
                    isIn("id", userIds)
                }
            }.decodeList<kotlinx.serialization.json.JsonObject>()

            val enrichedMembers = if (profiles.isNotEmpty()) {
                val profileMap = profiles.associate { 
                    val id = it["id"]?.jsonPrimitive?.content ?: ""
                    val name = it["display_name"]?.jsonPrimitive?.contentOrNull 
                        ?: it["full_name"]?.jsonPrimitive?.contentOrNull 
                        ?: it["username"]?.jsonPrimitive?.contentOrNull ?: "Usuario"
                    val img = it["avatar_url"]?.jsonPrimitive?.contentOrNull
                    id to (name to img)
                }
                
                response.map { member ->
                    val (name, img) = profileMap[member.user_id] ?: ("Usuario" to null)
                    member.copy(userName = name, userImage = img)
                }
            } else response

            // Update local cache (simplified, Entity doesn't have names yet, but it's okay for now as detail screen uses VM)
            groupDao.insertMembers(enrichedMembers.map { 
                GroupMemberEntity(it.group_id, it.user_id, it.role, it.joined_at) 
            })
            
            Result.success(enrichedMembers)
        } catch (e: Exception) {
            // Fallback to local
            val local = groupDao.getMembersFlow(groupId).first().map {
                GroupMember(it.group_id, it.user_id, it.role, it.joined_at)
            }
            if (local.isNotEmpty()) Result.success(local)
            else Result.failure(e)
        }
    }

    fun getMembersFlow(groupId: String): Flow<List<GroupMember>> {
        val cleanId = groupId.trim().lowercase()
        return groupDao.getMembersFlow(cleanId).map { list ->
            list.map { GroupMember(it.group_id, it.user_id, it.role, it.joined_at) }
        }
    }

    suspend fun leaveGroup(groupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            val cleanId = groupId.trim().lowercase()
            
            // 4. Optimistic Delete locally
            groupDao.removeMember(cleanId, userId.trim().lowercase())
            groupDao.updateMembership(cleanId, false)
            
            // 5. Delete membership from Supabase
            supabaseConfig.client.postgrest["group_members"].delete {
                filter {
                    eq("group_id", cleanId)
                    eq("user_id", userId)
                }
            }
            
            // Remove locally
            groupDao.removeMember(cleanId, userId.trim().lowercase())
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addMember(groupId: String, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cleanId = groupId.trim().lowercase()
            
            // Fix: serialize only table columns
            val memberJson = buildJsonObject {
                put("group_id", cleanId)
                put("user_id", userId)
                put("role", "member")
                put("joined_at", java.time.Instant.now().toString())
            }
            
            supabaseConfig.client.postgrest["group_members"].insert(memberJson)
            
            // Local update will be handled by realtime or refresh, but we can do it optimistically if needed
            // For now, let's rely on syncing
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeMember(groupId: String, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val me = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            val cleanId = groupId.trim().lowercase()
            val targetUserId = userId.trim().lowercase()
            
            // 1. Get Group to check creator
            val group = getGroupById(cleanId) ?: return@withContext Result.failure(Exception("Group not found"))
            
            // 2. CREATOR PROTECTION: Cannot remove the creator
            if (targetUserId == group.creator_id.trim().lowercase()) {
                return@withContext Result.failure(Exception("No se puede eliminar al creador del grupo."))
            }
            
            // 3. Check Permissions (My Role)
            val myRole = getMemberRole(cleanId, me)
            val isCreator = me.trim().lowercase() == group.creator_id.trim().lowercase()
            val isAdmin = myRole == "admin" || myRole == "owner"
            
            if (!isCreator && !isAdmin) {
                return@withContext Result.failure(Exception("No tienes permisos para eliminar miembros."))
            }

            // Execute delete
            supabaseConfig.client.postgrest["group_members"].delete {
                filter {
                    eq("group_id", cleanId)
                    eq("user_id", targetUserId)
                }
            }
            
            // Remove locally
            groupDao.removeMember(cleanId, targetUserId)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteGroup(groupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            val cleanId = groupId.trim().lowercase()
            val group = getGroupById(cleanId)
            
            // 1. Obtener mi perfil para ver si soy Admin Global
            val userProfile = userRepository.getUserById(userId).getOrNull()
            val isGlobalAdmin = userProfile?.is_admin ?: false
            
            // 2. Obtener mi rol en este grupo
            val memberRole = getMemberRole(cleanId, userId)
            val isAuthorized = isGlobalAdmin || group?.creator_id == userId || memberRole == "admin" || memberRole == "owner"
            
            if (isAuthorized) {
                // Borrado GLOBAL (Soft delete)
                val updateJson = kotlinx.serialization.json.buildJsonObject {
                    put("is_deleted", true)
                }
                supabaseConfig.client.postgrest["groups"].update(updateJson) {
                    filter { eq("id", cleanId) }
                }
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
        val cleanId = groupId.trim().lowercase()
        return try {
            val response = supabaseConfig.client.postgrest["group_members"].select {
                filter {
                    eq("group_id", cleanId)
                    eq("user_id", userId)
                }
            }.decodeSingleOrNull<GroupMember>()
            response?.role
        } catch (e: Exception) {
            null
        }
    }

    suspend fun deleteGroupLocally(groupId: String) {
        val cleanId = groupId.trim().lowercase()
        groupDao.deleteGroup(cleanId)
    }

    suspend fun updateMemberRole(groupId: String, userId: String, newRole: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val me = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("Not authenticated"))
            val cleanId = groupId.trim().lowercase()
            val targetUserId = userId.trim().lowercase()
            
            // 1. Get Group for creator check
            val group = getGroupById(cleanId) ?: return@withContext Result.failure(Exception("Group not found"))

            // 2. CREATOR PROTECTION: Cannot change creator's role (they are always owner/admin)
            if (targetUserId == group.creator_id.trim().lowercase()) {
                return@withContext Result.failure(Exception("No se puede cambiar el rol del creador."))
            }
            
            // 3. Check Permissions
            val myRole = getMemberRole(cleanId, me)
            val isCreator = me.trim().lowercase() == group.creator_id.trim().lowercase()
            val isAdmin = myRole == "admin" || myRole == "owner"
            
            if (!isCreator && !isAdmin) {
                return@withContext Result.failure(Exception("No tienes permisos para gestionar roles."))
            }

            supabaseConfig.client.postgrest["group_members"].update({
                set("role", newRole)
            }) {
                filter {
                    eq("group_id", cleanId)
                    eq("user_id", targetUserId)
                }
            }
            
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
            val cleanId = groupId.trim().lowercase()
            var imageUrl: String? = null
            
            if (imageBytes != null) {
                val uploadResult = uploadGroupImage(cleanId, imageBytes)
                if (uploadResult.isSuccess) {
                    imageUrl = uploadResult.getOrNull()
                }
            }

            supabaseConfig.client.postgrest["groups"].update({
                set("name", name)
                set("description", description ?: "")
                set("only_admins_can_talk", onlyAdminsCanTalk)
                if (imageUrl != null) set("image", imageUrl)
            }) {
                filter { eq("id", cleanId) }
            }
            
            syncGroups()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadGroupMedia(groupId: String, bytes: ByteArray, contentType: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val extension = when {
                contentType.equals("image/gif", ignoreCase = true) -> "gif"
                contentType.startsWith("image/") -> "jpg"
                contentType.startsWith("video/") -> "mp4"
                else -> "bin"
            }
            val fileName = "media_group_${groupId}_${System.currentTimeMillis()}.$extension"
            val result = storageService.uploadFile(
                bucketName = "group-media",
                fileName = fileName,
                bytes = bytes,
                contentType = contentType
            )
            if (result.isSuccess) {
                Result.success(storageService.getPublicUrl("group-media", fileName))
            } else {
                // Fallback to old groups bucket if new one fails or isn't ready
                storageService.uploadFile(
                    bucketName = "groups",
                    fileName = fileName,
                    bytes = bytes,
                    contentType = contentType
                ).let { fallback ->
                    if (fallback.isSuccess) Result.success(storageService.getPublicUrl("groups", fileName))
                    else Result.failure(fallback.exceptionOrNull() ?: Exception("Upload failed"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
