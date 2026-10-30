package conectate.com.data.repository

import android.content.Context
import conectate.com.util.isRetiredNotification
import conectate.com.core.concurrency.newSafeScope
import conectate.com.core.repository.RepositoryCallHandler
import conectate.com.core.result.AppResult
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.NotificationDigestOverview
import conectate.com.data.localfallback.NotificationDigestPreferences
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.localfallback.AccountDeletedEvents
import conectate.com.data.model.Notification
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlinx.serialization.decodeFromString
import io.github.jan.supabase.realtime.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import android.util.Log
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

@Singleton
class NotificationRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val localFallbackApiClient: LocalFallbackApiClient,
    private val ubuntuRealtimeClient: UbuntuRealtimeClient,
    private val repositoryCallHandler: RepositoryCallHandler,
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS = "notification_repository_cache"
        private const val KEY_LAST_REGISTERED_FCM = "last_registered_fcm"
    }

    private val scope = newSafeScope("NotificationRepository", Dispatchers.IO)
    private var momentExpiryRefreshJob: Job? = null
    private var realtimeUnreadRefreshJob: Job? = null
    private val unreadCounter = NotificationUnreadCounter { authService.getCurrentUserId() }
    private val pushSession = SessionWriteGuard { authService.getCurrentUserId() }
    private val pushRegistrationMutex = kotlinx.coroutines.sync.Mutex()
    val unreadCount: StateFlow<Int> = unreadCounter.unreadCount
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun clearData() {
        momentExpiryRefreshJob?.cancel()
        realtimeUnreadRefreshJob?.cancel()
        unreadCounter.clear()
        pushSession.invalidate()
    }

    private fun scheduleRealtimeUnreadRefresh(userId: String) {
        if (!userId.equals(authService.getCurrentUserId(), ignoreCase = true)) return
        realtimeUnreadRefreshJob?.cancel()
        realtimeUnreadRefreshJob = scope.launch {
            delay(150)
            if (userId.equals(authService.getCurrentUserId(), ignoreCase = true)) updateUnreadCount()
        }
    }

    private fun scheduleMomentExpiryRefresh(notifications: List<Notification>) {
        val now = System.currentTimeMillis()
        val nearest = notifications.asSequence()
            .filter { it.type.equals("new_moment", ignoreCase = true) }
            .mapNotNull { notification ->
                notification.extraData?.get("expires_at")?.jsonPrimitive?.contentOrNull
                    ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            }
            .filter { it > now }
            .minOrNull()
        momentExpiryRefreshJob?.cancel()
        if (nearest != null) {
            momentExpiryRefreshJob = scope.launch {
                delay((nearest - now + 250L).coerceAtMost(7 * 24 * 60 * 60 * 1000L))
                updateUnreadCount()
            }
        }
    }

    init {
        scope.launch {
            authService.currentSession.map { authService.getCurrentUserId() }.distinctUntilChanged().collectLatest { userId ->
                clearData()
                if (userId != null) updateUnreadCount()
            }
        }
    }

    private fun Throwable.isMissingFunctionError(): Boolean {
        val message = (message ?: "").lowercase()
        return message.contains("could not find the function") ||
            message.contains("42883") ||
            message.contains("function")
    }

    private suspend fun <T> safeRepositoryCall(
        operation: String,
        block: suspend () -> T
    ): Result<T> {
        return when (val result = repositoryCallHandler.io(operation, block)) {
            is AppResult.Success -> Result.success(result.data)
            is AppResult.Error -> {
                val throwable = result.error.cause ?: IllegalStateException(result.error.message)
                Result.failure(throwable)
            }
        }
    }

    private suspend fun secureMarkRead(
        token: String,
        notificationIds: List<String>,
        markAll: Boolean
    ): Result<Unit> {
        return runCatching {
            val rpcPayload = buildJsonObject {
                put("p_notification_ids", JsonArray(notificationIds.map { JsonPrimitive(it) }))
                put("p_mark_all", markAll)
            }

            val rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_mark_notifications_read") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(rpcPayload)
            }

            if (rpcResponse.status.isSuccess()) {
                return@runCatching
            }

            val rpcError = rpcResponse.bodyAsText()
            if (!rpcError.lowercase().contains("function")) {
                throw IllegalStateException("secure_mark_notifications_read failed: ${rpcResponse.status}")
            }

            throw IllegalStateException("could not find function secure_mark_notifications_read")
        }
    }
    /**
     * Fetch notifications for the current user, joining with the actor's profile info.
     */
    suspend fun getNotifications(): Result<List<conectate.com.data.model.Notification>> {
        return try {
            if (authService.isLocalFallbackSession()) {
                val request = unreadCounter.begin()
                val token = authService.getAccessToken()
                    ?: return Result.failure(Exception("No token"))
                return localFallbackApiClient.loadNotifications(token)
                    .map { response ->
                        val visibleUnread = (response.unreadCount - response.items.count {
                                it.isRetiredNotification() && !it.isRead
                            }).coerceAtLeast(0)
                        if (unreadCounter.publish(request, visibleUnread)) {
                            scheduleMomentExpiryRefresh(response.items)
                        }
                        response.items.filterNot { it.isRetiredNotification() }
                    }
            }

            var userId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            var token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))

            // We perform a join to get actor details (display_name, avatar_url)
            // Query: notifications?recipient_id=eq.userId&select=*,actor:profiles!actor_id(display_name,avatar_url)&order=created_at.desc
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/notifications") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("recipient_id", "eq.$userId")
                parameter("select", "*,actor:profiles!actor_id(display_name,username,full_name,avatar_url)")
                parameter("type", "neq.view_moment")
                parameter("order", "created_at.desc")
            }

            if (response.status.isSuccess()) {
                var jsonBody = response.bodyAsText()
                // PERFORMANCE FIX: Move heavy JSON decoding and mapping to IO thread
                val notifications = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val jsonArray = supabaseConfig.json.decodeFromString<JsonArray>(jsonBody)
                    
                    fun getSafeString(key: String, base: JsonObject): String? {
                        var element = base[key]
                        if (element == null || element is kotlinx.serialization.json.JsonNull) return null
                        val str = try {
                            element.jsonPrimitive.contentOrNull ?: element.toString().removeSurrounding("\"")
                        } catch (e: Exception) {
                            element.toString().removeSurrounding("\"")
                        }
                        return if (str.equals("null", ignoreCase = true) || str.isBlank()) null else str
                    }

                    fun extractBestName(obj: JsonObject): String? {
                        val dName = getSafeString("display_name", obj)
                        val fName = getSafeString("full_name", obj)
                        val uName = getSafeString("username", obj)
                        return dName ?: fName ?: uName
                    }

                     jsonArray.map { element ->
                        var obj = element as JsonObject
                        val profileElement = obj["actor"]
                        val actorObj = when {
                            profileElement is JsonObject -> profileElement
                            profileElement is JsonArray -> profileElement.firstOrNull()?.jsonObject
                            else -> null
                        }
                        
                        try {
                            supabaseConfig.json.decodeFromJsonElement<conectate.com.data.model.Notification>(obj).copy(
                                actorName = actorObj?.let { extractBestName(it) },
                                actorAvatarUrl = actorObj?.let { getSafeString("avatar_url", it) }
                            )
                        } catch (e: Exception) {
                            Log.e("NotificationRepository", "❌ Error decoding individual notification", e)
                            // Fallback minimal
                            conectate.com.data.model.Notification(
                                recipientId = userId,
                                actorId = "",
                                type = "unknown"
                            )
                        }
                    }
                }
                Log.d("NotificationRepository", "✅ Successfully returning ${notifications.size} notifications")
                Result.success(enrichPostNotificationPreviews(token,
                    notifications.filterNot { it.isRetiredNotification() }))
            } else {
                val errorBody = response.bodyAsText()
                Log.e("NotificationRepository", "❌ HTTP Error: ${response.status}")
                Log.e("NotificationRepository", "❌ Error body: $errorBody")
                Result.failure(Exception("Error fetching notifications: ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("NotificationRepository", "🚨 Exception in getNotifications", e)
            Result.failure(e)
        }
    }

    /** Old notifications only store the post ID. Resolve previews in batches, respecting post RLS. */
    private suspend fun enrichPostNotificationPreviews(
        token: String,
        notifications: List<Notification>
    ): List<Notification> {
        fun postId(item: Notification): String? {
            val explicit = (item.extraData?.get("post_id") as? JsonPrimitive)?.contentOrNull
            val id = explicit ?: item.targetId.takeIf {
                item.targetType.equals("post", true) || item.type.contains("post", true)
            }
            return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,160}")) }
        }
        val ids = notifications.mapNotNull(::postId).distinct()
        if (ids.isEmpty()) return notifications
        val posts = coroutineScope {
            ids.chunked(50).map { batch -> async(Dispatchers.IO) {
                try {
                    val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/posts") {
                        header("Authorization", "Bearer $token")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        parameter("select", "*")
                        parameter("id", "in.(${batch.joinToString(",")})")
                    }
                    if (response.status.isSuccess())
                        supabaseConfig.json.decodeFromString<List<JsonObject>>(response.bodyAsText())
                    else emptyList()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { emptyList<JsonObject>() }
            } }.awaitAll().flatten()
        }.associateBy { (it["id"] as? JsonPrimitive)?.contentOrNull }
        fun text(row: JsonObject, key: String) = (row[key] as? JsonPrimitive)
            ?.contentOrNull?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        fun mediaUrl(value: JsonElement?): String? = when (value) {
            is JsonObject -> text(value, "thumbnail_url") ?: text(value, "url") ?: text(value, "media_url")
            is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
            else -> null
        }
        return notifications.map { item ->
            val post = posts[postId(item)] ?: return@map item
            val media = post["media_urls"].let { value ->
                when (value) {
                    is JsonArray -> value
                    is JsonPrimitive -> runCatching {
                        supabaseConfig.json.parseToJsonElement(value.content) as? JsonArray
                    }.getOrNull()
                    else -> null
                }
            }
            val thumbnail = text(post, "thumbnail_url") ?: text(post, "image_url")
                ?: media?.firstNotNullOfOrNull(::mediaUrl) ?: text(post, "media_url")
            val extra = buildJsonObject {
                item.extraData?.forEach { (key, value) -> put(key, value) }
                thumbnail?.let { put("target_thumbnail_url", it) }
                text(post, "content")?.let { put("target_preview_text", it.take(180)) }
            }
            item.copy(extraData = extra)
        }
    }

    suspend fun getNotificationDigest(): Result<NotificationDigestOverview> {
        if (!authService.isLocalFallbackSession()) {
            return Result.failure(IllegalStateException("El resumen requiere el servidor actual de Novvin."))
        }
        val token = authService.getAccessToken()
            ?: return Result.failure(IllegalStateException("Tu sesión expiró. Inicia sesión nuevamente."))
        return localFallbackApiClient.loadNotificationDigest(token)
    }

    suspend fun getMomentNotificationPreferences(): Result<conectate.com.data.localfallback.MomentNotificationPreferences> {
        if (!authService.isLocalFallbackSession()) {
            return Result.failure(IllegalStateException("Las alertas de momentos requieren el servidor actual de Novvin."))
        }
        val token = authService.getAccessToken()
            ?: return Result.failure(IllegalStateException("Tu sesión expiró. Inicia sesión nuevamente."))
        return localFallbackApiClient.loadMomentNotificationPreferences(token)
    }

    suspend fun setMomentNotificationPreferences(enabled: Boolean): Result<conectate.com.data.localfallback.MomentNotificationPreferences> {
        if (!authService.isLocalFallbackSession()) {
            return Result.failure(IllegalStateException("Las alertas de momentos requieren el servidor actual de Novvin."))
        }
        val token = authService.getAccessToken()
            ?: return Result.failure(IllegalStateException("Tu sesión expiró. Inicia sesión nuevamente."))
        return localFallbackApiClient.updateMomentNotificationPreferences(token, enabled)
    }

    suspend fun updateNotificationDigestPreferences(
        preferences: NotificationDigestPreferences
    ): Result<NotificationDigestPreferences> {
        if (!authService.isLocalFallbackSession()) {
            return Result.failure(IllegalStateException("El resumen requiere el servidor actual de Novvin."))
        }
        val token = authService.getAccessToken()
            ?: return Result.failure(IllegalStateException("Tu sesión expiró. Inicia sesión nuevamente."))
        return localFallbackApiClient.updateNotificationDigestPreferences(token, preferences)
    }

    suspend fun markAsRead(notificationId: String): Result<Unit> {
        if (authService.isLocalFallbackSession()) {
            val token = authService.getAccessToken()
                ?: return Result.failure(Exception("No token"))
            return localFallbackApiClient.markNotificationRead(token, notificationId)
                .onSuccess { updateUnreadCount() }
        }

        return safeRepositoryCall("notifications.markAsRead") {
            var userId = authService.getCurrentUserId() ?: throw IllegalStateException("Not logged in")
            var token = authService.getAccessToken() ?: throw IllegalStateException("No token")
            val cleanId = notificationId.trim()
            if (cleanId.isBlank()) throw IllegalArgumentException("Notification id is required")

            var secureResult = secureMarkRead(token, listOf(cleanId), markAll = false)
            if (secureResult.isFailure) {
                var secureError = secureResult.exceptionOrNull()
                if (secureError == null || !secureError.isMissingFunctionError()) {
                    throw secureError ?: IllegalStateException("secure_mark_notifications_read failed")
                }

                var update = buildJsonObject { put("is_read", true) }
                var response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/notifications") {
                    header("Authorization", "Bearer $token")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("id", "eq.$cleanId")
                    parameter("recipient_id", "eq.$userId")
                    header("Content-Type", "application/json")
                    setBody(update)
                }
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("Error marking as read: ${response.status}")
                }
            }

            updateUnreadCount()
        }
    }

    suspend fun markAllAsRead(): Result<Unit> {
        if (authService.isLocalFallbackSession()) {
            val request = unreadCounter.begin()
            val token = authService.getAccessToken()
                ?: return Result.failure(Exception("No token"))
            return localFallbackApiClient.markAllNotificationsRead(token)
                .onSuccess { if (unreadCounter.isCurrent(request)) updateUnreadCount() }
        }

        return safeRepositoryCall("notifications.markAllAsRead") {
            var userId = authService.getCurrentUserId() ?: throw IllegalStateException("Not logged in")
            var token = authService.getAccessToken() ?: throw IllegalStateException("No token")

            var secureResult = secureMarkRead(token, emptyList(), markAll = true)
            if (secureResult.isFailure) {
                var secureError = secureResult.exceptionOrNull()
                if (secureError == null || !secureError.isMissingFunctionError()) {
                    throw secureError ?: IllegalStateException("secure_mark_notifications_read failed")
                }

                var update = buildJsonObject { put("is_read", true) }
                var response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/notifications") {
                    header("Authorization", "Bearer $token")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("recipient_id", "eq.$userId")
                    parameter("is_read", "eq.false")
                    header("Content-Type", "application/json")
                    setBody(update)
                }
                if (!response.status.isSuccess()) {
                    throw IllegalStateException("Error marking all as read: ${response.status}")
                }
            }

            updateUnreadCount()
        }
    }

    suspend fun markMatchingAsRead(
        types: List<String>,
        actorId: String? = null,
        targetId: String? = null
    ): Result<Unit> {
        if (authService.isLocalFallbackSession()) {
            val token = authService.getAccessToken()
                ?: return Result.failure(Exception("No token"))
            val normalizedTypes = types
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
            if (normalizedTypes.isEmpty()) return Result.success(Unit)
            return localFallbackApiClient.loadNotifications(token, unreadOnly = true, limit = 500)
                .mapCatching { response ->
                    response.items
                        .asSequence()
                        .filter { notification -> notification.type.trim().lowercase() in normalizedTypes }
                        .filter { notification -> actorId.isNullOrBlank() || notification.actorId.equals(actorId.trim(), ignoreCase = true) }
                        .filter { notification -> targetId.isNullOrBlank() || notification.targetId.equals(targetId.trim(), ignoreCase = true) }
                        .forEach { notification ->
                            localFallbackApiClient.markNotificationRead(token, notification.id).getOrThrow()
                        }
                    updateUnreadCount()
                }
        }

        return safeRepositoryCall("notifications.markMatchingAsRead") {
            val normalizedTypes = types
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .distinct()
            if (normalizedTypes.isEmpty()) return@safeRepositoryCall

            var userId = authService.getCurrentUserId() ?: throw IllegalStateException("Not logged in")
            var token = authService.getAccessToken() ?: throw IllegalStateException("No token")

            var update = buildJsonObject { put("is_read", true) }
            var response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/notifications") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                parameter("recipient_id", "eq.$userId")
                parameter("is_read", "eq.false")
                if (normalizedTypes.size == 1) {
                    parameter("type", "eq.${normalizedTypes.first()}")
                } else {
                    parameter("type", "in.(${normalizedTypes.joinToString(",")})")
                }
                actorId
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { parameter("actor_id", "eq.$it") }
                targetId
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { parameter("target_id", "eq.$it") }
                setBody(update)
            }

            if (!response.status.isSuccess()) {
                throw IllegalStateException("Error marking matching notifications as read: ${response.status}")
            }

            updateUnreadCount()
        }
    }

    suspend fun updateUnreadCount(): Result<Int> {
        if (authService.isLocalFallbackSession()) {
            return getUnreadCount()
        }

        return getUnreadCount()
    }

    suspend fun getUnreadCount(): Result<Int> {
        val request = unreadCounter.begin()
        if (authService.isLocalFallbackSession()) {
            val token = authService.getAccessToken()
                ?: return Result.failure(Exception("No token"))
            return localFallbackApiClient.loadNotifications(token, unreadOnly = true, limit = 500)
                .map { response ->
                    val visibleUnread = (response.unreadCount - response.items.count {
                        it.isRetiredNotification() && !it.isRead
                    }).coerceAtLeast(0)
                    if (unreadCounter.publish(request, visibleUnread)) {
                        scheduleMomentExpiryRefresh(response.items)
                    }
                    visibleUnread
                }
        }

        return safeRepositoryCall("notifications.getUnreadCount") {
            var userId = authService.getCurrentUserId() ?: throw IllegalStateException("Not logged in")
            var token = authService.getAccessToken() ?: throw IllegalStateException("No token")

            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/notifications") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("recipient_id", "eq.$userId")
                parameter("is_read", "eq.false")
                parameter("select", "id")
                parameter("type", "neq.view_moment")
                header("Prefer", "count=exact")
            }

            if (!response.status.isSuccess()) {
                throw IllegalStateException("Error fetching unread count: ${response.status}")
            }

            val contentRange = response.headers["Content-Range"]
            val count = contentRange?.substringAfter("/")?.toIntOrNull() ?: 0
            unreadCounter.publish(request, count)
            count
        }
    }

    /**
     * Observable flow of incoming notifications for the current user.
     */
    fun observeNotifications(): Flow<conectate.com.data.model.Notification> = flow {
        if (authService.isLocalFallbackSession()) {
            val userId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return@flow
            val token = authService.getAccessToken()?.trim().orEmpty()
            if (token.isBlank()) return@flow
            ubuntuRealtimeClient.updateAccessToken(token)
            ubuntuRealtimeClient.subscribe("user:$userId")
            val gate = NotificationEventGate(userId)
            ubuntuRealtimeClient.events.collect { envelope ->
                if (!envelope.channel.equals("user:$userId", ignoreCase = true) ||
                    !userId.equals(authService.getCurrentUserId()?.trim(), ignoreCase = true)) return@collect
                if (envelope.event == "account_deleted") {
                    AccountDeletedEvents.notifyAccountDeleted()
                    return@collect
                }
                if (envelope.event != "notification") return@collect
                val notification = gate.accept(envelope, authService.getCurrentUserId(), supabaseConfig.json) ?: return@collect
                if (notification.recipientId.equals(userId, ignoreCase = true) &&
                    userId.equals(authService.getCurrentUserId(), ignoreCase = true)) {
                    // Deliveries can be retried and multiple screens can subscribe. Read the authoritative
                    // count once for a short burst instead of adding one for each duplicate/subscriber.
                    scheduleRealtimeUnreadRefresh(userId)
                    emit(notification)
                }
            }
        }

        var userId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return@flow
        Log.d("NotificationRepository", "🏠 Starting observeNotifications for user: $userId")
        
        val client = supabaseConfig.client
        var channel = client.realtime.channel("notifications-realtime-$userId")
        try {
            val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "notifications"
                this.filter = "recipient_id=eq.$userId"
            }

            // Subscribe before collection. This is a suspend call.
            supabaseConfig.subscribeRealtimeChannel(
                channel = channel,
                reason = "notifications-$userId"
            )
            Log.d("NotificationRepository", "📡 Subscribed to 'notifications' channel")

            changes.collect { action ->
                Log.d("NotificationRepository", "⚡ Received Realtime Action: ${action::class.simpleName}")
                if (action is PostgresAction.Insert) {
                    try {
                        val record = action.record
                        var notification = supabaseConfig.json.decodeFromJsonElement<conectate.com.data.model.Notification>(record)
                        if (notification.isRetiredNotification()) return@collect
                        Log.d("NotificationRepository", "📥 New Notification received: ID=${notification.id}, recipient=${notification.recipientId}")
                        
                        // Use case-insensitive comparison for safety
                        if (notification.recipientId.equals(userId, ignoreCase = true)) {
                            // Fetch actor profile for realtime notifications
                            try {
                                val actorProfile = fetchActorProfile(notification.actorId)
                                notification = notification.copy(
                                    actorName = actorProfile.first,
                                    actorAvatarUrl = actorProfile.second
                                )
                            } catch (e: Exception) {
                                Log.e("NotificationRepository", "Failed to fetch actor profile for realtime notification", e)
                            }

                            Log.d("NotificationRepository", "✅ Notification matches current user. Emitting!")
                            emit(notification)
                        } else {
                            Log.d("NotificationRepository", "⚠️ Notification is for a different user (${notification.recipientId}). Ignoring.")
                        }
                    } catch (e: Exception) {
                        Log.e("NotificationRepository", "❌ Error decoding notification record", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("NotificationRepository", "🚨 Error in observeNotifications flow", e)
        } finally {
            runCatching { channel.unsubscribe() }
            runCatching { client.realtime.removeChannel(channel) }
        }
    }

    /**
     * Dispatched by other repositories to create the notification in Supabase
     */
    suspend fun sendNotification(
        recipientId: String,
        type: String,
        targetId: String? = null,
        content: String? = null,
        extraData: kotlinx.serialization.json.JsonObject = buildJsonObject {}
    ): Result<Unit> {
        return try {
            var actorId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            if (actorId == recipientId) return Result.success(Unit) // Don't notify self

            var token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))

            if (authService.isLocalFallbackSession()) {
                return localFallbackApiClient.createNotification(
                    accessToken = token,
                    recipientId = recipientId,
                    type = type,
                    targetId = targetId,
                    targetType = extraData["target_type"]?.jsonPrimitive?.contentOrNull,
                    content = content,
                    extraData = extraData
                ).map { Unit }
            }

            val notificationData = buildJsonObject {
                put("recipient_id", recipientId)
                put("actor_id", actorId)
                put("type", type)
                targetId?.let { put("target_id", it) }
                content?.let { put("content", it) }
                put("extra_data", extraData)
            }

            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/notifications") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(notificationData)
            }

            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(Exception("Error sending notification: ${response.status}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerFcmToken(token: String): Result<Unit> = pushRegistrationMutex.withLock {
        val session = pushSession.capture()
        return try {
            val cleanToken = token.trim()
            if (cleanToken.isBlank()) {
                return Result.failure(IllegalArgumentException("FCM token is blank"))
            }

            val userId = authService.getCurrentUserId() ?: return Result.success(Unit)
            // A Play Store update or a backend migration can remove the remote row while the
            // device still has the same Firebase token. Always upsert it for the active session;
            // the local cache is only useful for logout bookkeeping, never as proof that the
            // server still owns the token.
            val accessToken = authService.getAccessTokenAwait()
            currentCoroutineContext().ensureActive()
            // Obsolete work must finish normally so an appended current-session pass can run.
            if (!pushSession.isValid(session)) return Result.success(Unit)
            if (accessToken == null) return Result.failure(Exception("No token"))
            val normalizedUserId = userId.trim().lowercase()
            val cacheKey = buildFcmRegistrationCacheKey(normalizedUserId, cleanToken)

            if (authService.isLocalFallbackSession()) {
                return localFallbackApiClient.registerFcmToken(accessToken, cleanToken)
                    .onSuccess {
                        if (pushSession.isValid(session)) prefs.edit().putString(KEY_LAST_REGISTERED_FCM, cacheKey).apply()
                    }
            }

            var body = buildJsonObject {
                put("user_id", normalizedUserId)
                put("token", cleanToken)
            }

            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/fcm_tokens") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "resolution=merge-duplicates")
                setBody(body)
            }

            if (response.status.isSuccess()) {
                if (pushSession.isValid(session)) prefs.edit().putString(KEY_LAST_REGISTERED_FCM, cacheKey).apply()
                Result.success(Unit)
            }
            else Result.failure(Exception("Error registering token: ${response.status}"))
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!pushSession.isValid(session)) return Result.success(Unit)
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun capturedFcmToken(): String? = prefs.getString(KEY_LAST_REGISTERED_FCM, null)
        ?.substringAfter('|', "")?.trim()?.ifBlank { null }

    fun clearAccountAlerts(userId: String?) {
        conectate.com.util.MessageNotificationFactory.cancelAccount(context, userId)
    }

    suspend fun unregisterFcmToken(
        userId: String? = authService.getCurrentUserId()?.trim()?.lowercase(),
        accessToken: String? = authService.getAccessToken(),
        lastRegisteredToken: String? = capturedFcmToken(),
        localFallback: Boolean = authService.isLocalFallbackSession()
    ): Result<Unit> {
        return try {
            if (userId.isNullOrBlank() || accessToken.isNullOrBlank()) {
                return Result.success(Unit)
            }

            if (lastRegisteredToken == null) {
                return Result.success(Unit)
            }

            fun clearCapturedRegistration() {
                if (prefs.getString(KEY_LAST_REGISTERED_FCM, null) == buildFcmRegistrationCacheKey(userId, lastRegisteredToken)) {
                    prefs.edit().remove(KEY_LAST_REGISTERED_FCM).apply()
                }
            }
            if (localFallback) {
                return localFallbackApiClient.unregisterFcmToken(accessToken, lastRegisteredToken)
                    .onSuccess { clearCapturedRegistration() }
            }

            var response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/fcm_tokens") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("user_id", "eq.$userId")
                parameter("token", "eq.$lastRegisteredToken")
            }

            if (response.status.isSuccess()) {
                clearCapturedRegistration()
                Log.d("NotificationRepository", "FCM token unregistered for current session")
                Result.success(Unit)
            } else {
                Log.e("NotificationRepository", "Error unregistering token: ${response.status}")
                Result.failure(Exception("Error unregistering token: ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e("NotificationRepository", "Exception in unregisterFcmToken", e)
            Result.failure(e)
        }
    }

    private suspend fun fetchActorProfile(actorId: String): Pair<String?, String?> {
        return try {
            var token = authService.getAccessToken() ?: return null to null
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("id", "eq.$actorId")
                parameter("select", "display_name,username,full_name,avatar_url")
            }
            
            if (response.status.isSuccess()) {
                var jsonBody = response.bodyAsText()
                val profiles = supabaseConfig.json.decodeFromString<List<JsonObject>>(jsonBody)
                val profile = profiles.firstOrNull() ?: return null to null
                
                fun getSafe(key: String): String? {
                    var element = profile[key]
                    if (element == null || element is JsonNull) return null
                    var s = element.jsonPrimitive.contentOrNull
                    return if (s == "null" || s.isNullOrBlank()) null else s
                }

                val name = getSafe("display_name") ?: getSafe("full_name") ?: getSafe("username")
                val avatar = getSafe("avatar_url")
                name to avatar
            } else {
                null to null
            }
        } catch (e: Exception) {
            Log.e("NotificationRepository", "Error inside fetchActorProfile", e)
            null to null
        }
    }

    private fun buildFcmRegistrationCacheKey(userId: String, token: String): String {
        return "${userId.trim().lowercase()}|${token.trim()}"
    }

    suspend fun deleteNotification(id: String): Result<Unit> {
        if (authService.isLocalFallbackSession()) {
            val token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            return localFallbackApiClient.deleteNotification(token, id)
        }

        return safeRepositoryCall("notifications.delete") {
            var userId = authService.getCurrentUserId() ?: throw IllegalStateException("Not logged in")
            var token = authService.getAccessToken() ?: throw IllegalStateException("No token")
            
            var response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/notifications") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("id", "eq.$id")
                parameter("recipient_id", "eq.$userId")
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Error deleting notification: ${response.status}")
            }
        }
    }

    suspend fun deleteAllNotifications(): Result<Unit> {
        if (authService.isLocalFallbackSession()) {
            val request = unreadCounter.begin()
            val token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            return localFallbackApiClient.deleteAllNotifications(token)
                .onSuccess { if (unreadCounter.isCurrent(request)) updateUnreadCount() }
        }

        return safeRepositoryCall("notifications.deleteAll") {
            var userId = authService.getCurrentUserId() ?: throw IllegalStateException("Not logged in")
            var token = authService.getAccessToken() ?: throw IllegalStateException("No token")
            
            var response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/notifications") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("recipient_id", "eq.$userId")
            }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("Error deleting all notifications: ${response.status}")
            }
            updateUnreadCount()
        }
    }
}



