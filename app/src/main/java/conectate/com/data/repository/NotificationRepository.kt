package conectate.com.data.repository

import conectate.com.core.repository.RepositoryCallHandler
import conectate.com.core.result.AppResult
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
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
import android.util.Log

@Singleton
class NotificationRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val repositoryCallHandler: RepositoryCallHandler
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    init {
        // Automatically start observing if we have a user
        scope.launch {
            authService.getCurrentUserId()?.let {
                updateUnreadCount()
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
            val userId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            val token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))

            Log.d("NotificationRepository", "🔍 Fetching notifications for user: $userId")
            Log.d("NotificationRepository", "🔑 Token (first 20 chars): ${token.take(20)}...")
            
            // We perform a join to get actor details (display_name, avatar_url)
            // Query: notifications?recipient_id=eq.userId&select=*,actor:profiles!actor_id(display_name,avatar_url)&order=created_at.desc
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/notifications") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("recipient_id", "eq.$userId")
                parameter("select", "*,actor:profiles!actor_id(display_name,username,full_name,avatar_url)")
                parameter("order", "created_at.desc")
            }

            Log.d("NotificationRepository", "📡 HTTP Status: ${response.status.value} ${response.status.description}")
            Log.d("NotificationRepository", "📋 Response Headers: ${response.headers.entries().joinToString { "${it.key}: ${it.value}" }}")

            if (response.status.isSuccess()) {
                val jsonBody = response.bodyAsText()
                Log.d("NotificationRepository", "📄 Response body length: ${jsonBody.length} chars")
                Log.d("NotificationRepository", "📄 Response body preview: ${jsonBody.take(200)}...")
                
                // PERFORMANCE FIX: Move heavy JSON decoding and mapping to IO thread
                val notifications = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val jsonArray = supabaseConfig.json.decodeFromString<JsonArray>(jsonBody)
                    Log.d("NotificationRepository", "📦 Parsed ${jsonArray.size} notification items")
                    
                    fun getSafeString(key: String, base: JsonObject): String? {
                        val element = base[key]
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
                        val obj = element as JsonObject
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
                Result.success(notifications)
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

    suspend fun markAsRead(notificationId: String): Result<Unit> {
        return safeRepositoryCall("notifications.markAsRead") {
            val userId = authService.getCurrentUserId() ?: throw IllegalStateException("Not logged in")
            val token = authService.getAccessToken() ?: throw IllegalStateException("No token")
            val cleanId = notificationId.trim()
            if (cleanId.isBlank()) throw IllegalArgumentException("Notification id is required")

            val secureResult = secureMarkRead(token, listOf(cleanId), markAll = false)
            if (secureResult.isFailure) {
                val secureError = secureResult.exceptionOrNull()
                if (secureError == null || !secureError.isMissingFunctionError()) {
                    throw secureError ?: IllegalStateException("secure_mark_notifications_read failed")
                }

                val update = buildJsonObject { put("is_read", true) }
                val response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/notifications") {
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
        return safeRepositoryCall("notifications.markAllAsRead") {
            val userId = authService.getCurrentUserId() ?: throw IllegalStateException("Not logged in")
            val token = authService.getAccessToken() ?: throw IllegalStateException("No token")

            val secureResult = secureMarkRead(token, emptyList(), markAll = true)
            if (secureResult.isFailure) {
                val secureError = secureResult.exceptionOrNull()
                if (secureError == null || !secureError.isMissingFunctionError()) {
                    throw secureError ?: IllegalStateException("secure_mark_notifications_read failed")
                }

                val update = buildJsonObject { put("is_read", true) }
                val response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/notifications") {
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

    suspend fun updateUnreadCount(): Result<Int> {
        return getUnreadCount()
    }

    suspend fun getUnreadCount(): Result<Int> {
        return safeRepositoryCall("notifications.getUnreadCount") {
            val userId = authService.getCurrentUserId() ?: throw IllegalStateException("Not logged in")
            val token = authService.getAccessToken() ?: throw IllegalStateException("No token")

            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/notifications") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("recipient_id", "eq.$userId")
                parameter("is_read", "eq.false")
                parameter("select", "id")
                header("Prefer", "count=exact")
            }

            if (!response.status.isSuccess()) {
                throw IllegalStateException("Error fetching unread count: ${response.status}")
            }

            val contentRange = response.headers["Content-Range"]
            val count = contentRange?.substringAfter("/")?.toIntOrNull() ?: 0
            _unreadCount.value = count
            count
        }
    }

    /**
     * Observable flow of incoming notifications for the current user.
     */
    fun observeNotifications(): Flow<conectate.com.data.model.Notification> = flow {
        val userId = authService.getCurrentUserId() ?: return@flow
        Log.d("NotificationRepository", "🏠 Starting observeNotifications for user: $userId")
        
        try {
            val client = supabaseConfig.client
            val channel = client.realtime.channel("notifications-realtime-$userId")
            
            val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "notifications"
            }

            // Subscribe before collection. This is a suspend call.
            channel.subscribe()
            Log.d("NotificationRepository", "📡 Subscribed to 'notifications' channel")

            changes.collect { action ->
                Log.d("NotificationRepository", "⚡ Received Realtime Action: ${action::class.simpleName}")
                if (action is PostgresAction.Insert) {
                    try {
                        val record = action.record
                        var notification = supabaseConfig.json.decodeFromJsonElement<conectate.com.data.model.Notification>(record)
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
            val actorId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            if (actorId == recipientId) return Result.success(Unit) // Don't notify self

            val token = authService.getAccessToken() ?: return Result.failure(Exception("No token"))

            val notificationData = buildJsonObject {
                put("recipient_id", recipientId)
                put("actor_id", actorId)
                put("type", type)
                targetId?.let { put("target_id", it) }
                content?.let { put("content", it) }
                put("extra_data", extraData)
            }

            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/notifications") {
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

    suspend fun registerFcmToken(token: String): Result<Unit> {
        return try {
            val userId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))

            val body = buildJsonObject {
                put("user_id", userId)
                put("token", token)
            }

            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/fcm_tokens") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "resolution=merge-duplicates")
                setBody(body)
            }

            if (response.status.isSuccess()) Result.success(Unit)
            else Result.failure(Exception("Error registering token: ${response.status}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unregisterFcmToken(): Result<Unit> {
        return try {
            val userId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))

            // Delete all tokens for this user on this device (simplified: delete by user_id)
            // In a better system, we'd delete only THIS specific token, but this ensures no phantom notifications.
            val response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/fcm_tokens") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("user_id", "eq.$userId")
            }

            if (response.status.isSuccess()) {
                Log.d("NotificationRepository", "FCM Token unregistered for user $userId")
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
            val token = authService.getAccessToken() ?: return null to null
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("id", "eq.$actorId")
                parameter("select", "display_name,username,full_name,avatar_url")
            }
            
            if (response.status.isSuccess()) {
                val jsonBody = response.bodyAsText()
                val profiles = supabaseConfig.json.decodeFromString<List<JsonObject>>(jsonBody)
                val profile = profiles.firstOrNull() ?: return null to null
                
                fun getSafe(key: String): String? {
                    val element = profile[key]
                    if (element == null || element is JsonNull) return null
                    val s = element.jsonPrimitive.contentOrNull
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
}
