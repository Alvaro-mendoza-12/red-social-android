package conectate.com.data.repository

import conectate.com.data.model.User
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.LocalFallbackFriendRequest
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@Serializable
data class FriendRequest(
    val id: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("receiver_id") val receiverId: String,
    val status: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class CreateFriendRequest(
    @SerialName("sender_id") val senderId: String,
    @SerialName("receiver_id") val receiverId: String,
    val status: String
)

enum class FriendStatus {
    NONE,
    PENDING_SENT,
    PENDING_RECEIVED,
    FRIENDS
}

@Singleton
class FriendRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val notificationRepository: NotificationRepository,
    private val localFallbackApiClient: LocalFallbackApiClient
) {
    data class PendingSnapshot(val ownerId: String, val users: List<User>)
    @Volatile private var pendingSnapshot: PendingSnapshot? = null

    fun cachedPendingRequests(): List<User>? = pendingSnapshot
        ?.takeIf { it.ownerId == authService.getCurrentUserId() }?.users

    private fun rememberPending(ownerId: String, users: List<User>): List<User> {
        if (authService.getCurrentUserId() == ownerId) {
            pendingSnapshot = PendingSnapshot(ownerId, users)
        }
        return users
    }

    private fun removeCachedPending(ownerId: String, senderId: String) {
        val snapshot = pendingSnapshot ?: return
        if (snapshot.ownerId == ownerId && authService.getCurrentUserId() == ownerId) {
            pendingSnapshot = snapshot.copy(users = snapshot.users.filterNot { it.id == senderId })
        }
    }

    private suspend fun ubuntuToken(): String? {
        if (!authService.isLocalFallbackSession() || !localFallbackApiClient.isConfigured) return null
        return authService.getAccessTokenAwait()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun LocalFallbackFriendRequest.otherUserId(currentUserId: String): String {
        return if (senderId.equals(currentUserId, ignoreCase = true)) receiverId else senderId
    }

    private suspend fun findUbuntuFriendRequest(
        token: String,
        targetUserId: String,
        vararg tabs: String
    ): LocalFallbackFriendRequest? {
        val cleanTarget = targetUserId.trim().lowercase()
        if (cleanTarget.isBlank()) return null
        return tabs.firstNotNullOfOrNull { tab ->
            localFallbackApiClient.loadFriendRequests(token, tab).getOrNull()
                ?.firstOrNull { request ->
                    request.senderId.equals(cleanTarget, ignoreCase = true) ||
                        request.receiverId.equals(cleanTarget, ignoreCase = true)
                }
        }
    }

    private suspend fun loadUbuntuUsers(token: String, userIds: List<String>): List<User> = coroutineScope {
        userIds.distinct().chunked(8).flatMap { batch ->
            batch.map { id ->
                async { localFallbackApiClient.loadProfileUser(token, id).getOrNull() }
            }.awaitAll().filterNotNull()
        }
    }

    suspend fun getFriendStatus(targetUserId: String): Result<FriendStatus> {
        return try {
            var currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            ubuntuToken()?.let { token ->
                val cleanCurrentUserId = currentUserId.trim().lowercase()
                // A profile used to make three sequential round trips before
                // showing whether the automatic chat request could be cancelled.
                val relationships = localFallbackApiClient.loadFriendRequests(token, "all").getOrThrow()
                    .filter { request ->
                        request.senderId.equals(targetUserId, ignoreCase = true) ||
                            request.receiverId.equals(targetUserId, ignoreCase = true)
                    }
                return Result.success(when {
                    relationships.any { it.status == "accepted" } -> FriendStatus.FRIENDS
                    relationships.any { it.status == "pending" && it.senderId.equals(cleanCurrentUserId, ignoreCase = true) } -> FriendStatus.PENDING_SENT
                    relationships.any { it.status == "pending" && it.receiverId.equals(cleanCurrentUserId, ignoreCase = true) } -> FriendStatus.PENDING_RECEIVED
                    else -> FriendStatus.NONE
                })
            }
            
            // Check if I sent a request or we are friends
            var sentResponse = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/friends"
            ) {
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 parameter("sender_id", "eq.$currentUserId")
                 parameter("receiver_id", "eq.$targetUserId")
                 parameter("select", "*")
            }
            
            if (sentResponse.status.isSuccess()) {
                val sentRequests: List<FriendRequest> = sentResponse.body()
                if (sentRequests.isNotEmpty()) {
                    var req = sentRequests.first()
                    return Result.success(if (req.status == "accepted") FriendStatus.FRIENDS else FriendStatus.PENDING_SENT)
                }
            }
            
            // Check if I received a request
            var receivedResponse = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/friends"
            ) {
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 parameter("receiver_id", "eq.$currentUserId")
                 parameter("sender_id", "eq.$targetUserId")
                 parameter("select", "*")
            }
            
            if (receivedResponse.status.isSuccess()) {
                val receivedRequests: List<FriendRequest> = receivedResponse.body()
                if (receivedRequests.isNotEmpty()) {
                    var req = receivedRequests.first()
                    return Result.success(if (req.status == "accepted") FriendStatus.FRIENDS else FriendStatus.PENDING_RECEIVED)
                }
            }
            
            Result.success(FriendStatus.NONE)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendFriendRequest(targetUserId: String): Result<Unit> {
        return try {
            var currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            ubuntuToken()?.let { token ->
                localFallbackApiClient.sendFriendRequest(token, targetUserId).getOrThrow()
                notificationRepository.sendNotification(
                    recipientId = targetUserId,
                    type = "friend_request",
                    targetId = currentUserId
                )
                return Result.success(Unit)
            }
            
            val requestData = CreateFriendRequest(
                senderId = currentUserId,
                receiverId = targetUserId,
                status = "pending"
            )
            
            var response = supabaseConfig.httpClient.post(
                "${supabaseConfig.supabaseUrl}/rest/v1/friends"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer ${authService.getAccessToken()}")
                header("Content-Type", "application/json")
                setBody(requestData)
            }
            
            if (response.status.isSuccess() || response.status == HttpStatusCode.Created) {
                // Trigger notification
                notificationRepository.sendNotification(
                    recipientId = targetUserId,
                    type = "friend_request",
                    targetId = currentUserId
                )
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to send request: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun acceptFriendRequest(senderId: String): Result<Unit> {
         return try {
            var currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            ubuntuToken()?.let { token ->
                val request = findUbuntuFriendRequest(token, senderId, "incoming")
                    ?: return Result.failure(Exception("Solicitud no encontrada en Ubuntu"))
                localFallbackApiClient.updateFriendRequest(token, request.id, "accepted").getOrThrow()
                removeCachedPending(currentUserId, senderId)
                notificationRepository.sendNotification(
                    recipientId = senderId,
                    type = "friend_request_accepted",
                    targetId = currentUserId
                ).onFailure { error ->
                    android.util.Log.w(
                        "FriendRepository",
                        "Friend request accepted but acceptance notification failed",
                        error
                    )
                }
                return Result.success(Unit)
            }
            
            var response = supabaseConfig.httpClient.patch(
                "${supabaseConfig.supabaseUrl}/rest/v1/friends"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer ${authService.getAccessToken()}")
                header("Content-Type", "application/json")
                parameter("sender_id", "eq.$senderId")
                parameter("receiver_id", "eq.$currentUserId")
                setBody(mapOf("status" to "accepted", "updated_at" to java.time.Instant.now().toString()))
            }
            
            if (response.status.isSuccess()) {
                removeCachedPending(currentUserId, senderId)
                notificationRepository.sendNotification(
                    recipientId = senderId,
                    type = "friend_request_accepted",
                    targetId = currentUserId
                ).onFailure { error ->
                    android.util.Log.w(
                        "FriendRepository",
                        "Friend request accepted but acceptance notification failed",
                        error
                    )
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to accept request: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRecentFriends(limit: Int = 10): Result<List<Pair<User, String>>> {
         return try {
            var currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            ubuntuToken()?.let { token ->
                val accepted = localFallbackApiClient.loadFriendRequests(token, "accepted").getOrThrow()
                val cleanCurrentUserId = currentUserId.trim().lowercase()
                val datedIds = accepted
                    .map { request -> request.otherUserId(cleanCurrentUserId) to request.createdAt }
                    .filter { (id, _) -> id.isNotBlank() }
                    .sortedByDescending { it.second }
                    .take(limit)
                val usersById = loadUbuntuUsers(token, datedIds.map { it.first }).associateBy { it.id.lowercase() }
                return Result.success(
                    datedIds.mapNotNull { (id, date) ->
                        usersById[id.lowercase()]?.let { user -> user to date }
                    }
                )
            }
            
            // Fetch friends sorted by updated_at desc
            // We need to fetch relationships first
             var sentResponse = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/friends"
            ) {
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 parameter("sender_id", "eq.$currentUserId")
                 parameter("status", "eq.accepted")
                 parameter("order", "updated_at.desc")
                 parameter("limit", limit.toString())
                 parameter("select", "receiver_id,updated_at")
            }
            
            var receivedResponse = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/friends"
            ) {
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 parameter("receiver_id", "eq.$currentUserId")
                 parameter("status", "eq.accepted")
                 parameter("order", "updated_at.desc")
                 parameter("limit", limit.toString())
                 parameter("select", "sender_id,updated_at")
            }
            
            val friendsMap = mutableMapOf<String, String>() // ID -> updated_at
            
            if (sentResponse.status.isSuccess()) {
                val sent: List<Map<String, String>> = sentResponse.body()
                sent.forEach { row ->
                    var id = row["receiver_id"]?.trim().orEmpty()
                    var updatedAt = row["updated_at"]?.trim().orEmpty()
                    if (id.isNotBlank() && updatedAt.isNotBlank()) {
                        friendsMap[id] = updatedAt
                    }
                }
            }
            
            if (receivedResponse.status.isSuccess()) {
                val received: List<Map<String, String>> = receivedResponse.body()
                received.forEach { row ->
                    var id = row["sender_id"]?.trim().orEmpty()
                    var updatedAt = row["updated_at"]?.trim().orEmpty()
                    if (id.isNotBlank() && updatedAt.isNotBlank()) {
                        friendsMap[id] = updatedAt
                    }
                }
            }
            
            if (friendsMap.isEmpty()) return Result.success(emptyList())
            
            val sortedIds = friendsMap.entries.sortedByDescending { it.value }.take(limit).map { it.key }
            
             // Fetch profiles
            var usersResponse = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer ${authService.getAccessToken()}")
                parameter("id", "in.(${sortedIds.joinToString(",")})")
            }
            
            if (usersResponse.status.isSuccess()) {
                val users: List<User> = usersResponse.body()
                // Map back to Pair(User, updated_at) and sort again to be sure
                val result = users.mapNotNull { user ->
                    friendsMap[user.id]?.let { date -> user to date }
                }.sortedByDescending { it.second }
                Result.success(result)
            } else {
                Result.failure(Exception("Failed to get friend profiles"))
            }
         } catch (e: Exception) {
             Result.failure(e)
         }
    }
    
    suspend fun rejectFriendRequest(senderId: String): Result<Unit> {
         return try {
            var currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            ubuntuToken()?.let { token ->
                val request = findUbuntuFriendRequest(token, senderId, "incoming")
                    ?: return Result.failure(Exception("Solicitud no encontrada en Ubuntu"))
                localFallbackApiClient.deleteFriendRequest(token, request.id).getOrThrow()
                removeCachedPending(currentUserId, senderId)
                return Result.success(Unit)
            }
            
            var response = supabaseConfig.httpClient.delete(
                "${supabaseConfig.supabaseUrl}/rest/v1/friends"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer ${authService.getAccessToken()}")
                parameter("sender_id", "eq.$senderId")
                parameter("receiver_id", "eq.$currentUserId")
            }
            
            if (response.status.isSuccess()) {
                removeCachedPending(currentUserId, senderId)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to reject request: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unfriendUser(targetUserId: String): Result<Unit> {
        return try {
            var currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            ubuntuToken()?.let { token ->
                localFallbackApiClient.deleteFriendRelationship(token, targetUserId).getOrThrow()
                return Result.success(Unit)
            }
            
            // Delete calls where I am sender or receiver
            // Supabase DELETE with OR condition is tricky without RPC, but RLS allows user to delete ANY row they are part of.
            // We can try to delete by matching sender=me&receiver=target OR sender=target&receiver=me.
            // A simple way is to use "or" filter: or=(sender_id.eq.ME,receiver_id.eq.TARGET),(sender_id.eq.TARGET,receiver_id.eq.ME) -> syntax verify needed.
            // Easier approach: Delete where (sender=Me, Receiver=Target)
            val response1 = supabaseConfig.httpClient.delete(
                "${supabaseConfig.supabaseUrl}/rest/v1/friends"
            ) {
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 parameter("sender_id", "eq.$currentUserId")
                 parameter("receiver_id", "eq.$targetUserId")
            }
            
            // AND Delete where (sender=Target, Receiver=Me)
            val response2 = supabaseConfig.httpClient.delete(
                "${supabaseConfig.supabaseUrl}/rest/v1/friends"
            ) {
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 parameter("sender_id", "eq.$targetUserId")
                 parameter("receiver_id", "eq.$currentUserId")
            }
            
            if (response1.status.isSuccess() || response2.status.isSuccess()) {
                 Result.success(Unit)
            } else {
                 Result.failure(Exception("Failed to unfriend"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun cancelFriendRequest(targetUserId: String): Result<Unit> {
         return try {
            var currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            ubuntuToken()?.let { token ->
                val request = findUbuntuFriendRequest(token, targetUserId, "outgoing")
                    ?: return Result.failure(Exception("Solicitud no encontrada en Ubuntu"))
                localFallbackApiClient.deleteFriendRequest(token, request.id).getOrThrow()
                return Result.success(Unit)
            }
            
            var response = supabaseConfig.httpClient.delete(
                "${supabaseConfig.supabaseUrl}/rest/v1/friends"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer ${authService.getAccessToken()}")
                parameter("sender_id", "eq.$currentUserId")
                parameter("receiver_id", "eq.$targetUserId")
                parameter("status", "eq.pending") // Safety check
            }
            
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to cancel request: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPendingRequests(): Result<List<User>> {
        return try {
            var currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            ubuntuToken()?.let { token ->
                val incoming = localFallbackApiClient.loadFriendRequests(token, "incoming").getOrThrow()
                val users = loadUbuntuUsers(token, incoming.map { it.senderId })
                if (users.size != incoming.map { it.senderId.lowercase() }.distinct().size) {
                    return Result.failure(Exception("No se pudieron cargar todas las solicitudes"))
                }
                return Result.success(rememberPending(currentUserId, users))
            }
            
            // 1. Get sender_ids from pending requests
            var response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/friends"
            ) {
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 parameter("receiver_id", "eq.$currentUserId")
                 parameter("status", "eq.pending")
                 parameter("select", "sender_id")
            }
            
            if (!response.status.isSuccess()) return Result.failure(Exception("Failed to get requests"))
            
            val requests: List<Map<String, String>> = response.body()
            val senderIds = requests.mapNotNull { it["sender_id"] }
            
            if (senderIds.isEmpty()) return Result.success(rememberPending(currentUserId, emptyList()))
            
            // 2. Fetch profiles for these senders
            var usersResponse = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer ${authService.getAccessToken()}")
                parameter("id", "in.(${senderIds.joinToString(",")})")
            }
            
            if (usersResponse.status.isSuccess()) {
                Result.success(rememberPending(currentUserId, usersResponse.body()))
            } else {
                Result.failure(Exception("Failed to get profiles"))
            }
        } catch (e: Exception) {
            Result.failure(e)
    }
    }

    suspend fun getFriends(userId: String): Result<List<User>> {
        return try {
            ubuntuToken()?.let { token ->
                val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
                val accepted = if (currentUserId != null && userId.equals(currentUserId, ignoreCase = true)) {
                    localFallbackApiClient.loadFriendRequests(token, "accepted").getOrThrow()
                } else {
                    localFallbackApiClient.loadFriendRequests(token, "accepted").getOrThrow()
                        .filter { request ->
                            request.senderId.equals(userId, ignoreCase = true) ||
                                request.receiverId.equals(userId, ignoreCase = true)
                        }
                }
                val cleanUserId = userId.trim().lowercase()
                return Result.success(loadUbuntuUsers(token, accepted.map { it.otherUserId(cleanUserId) }))
            }
            // Friends are where (sender=userId AND status=accepted) OR (receiver=userId AND status=accepted)
            
            // 1. Where I am sender
            var sentResponse = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/friends"
            ) {
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 parameter("sender_id", "eq.$userId")
                 parameter("status", "eq.accepted")
                 parameter("select", "receiver_id")
            }
            
            // 2. Where I am receiver
            var receivedResponse = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/friends"
            ) {
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 parameter("receiver_id", "eq.$userId")
                 parameter("status", "eq.accepted")
                 parameter("select", "sender_id")
            }
            
            val friendIds = mutableListOf<String>()
            
            if (sentResponse.status.isSuccess()) {
                val sent: List<Map<String, String>> = sentResponse.body()
                friendIds.addAll(sent.mapNotNull { it["receiver_id"] })
            }
            
            if (receivedResponse.status.isSuccess()) {
                val received: List<Map<String, String>> = receivedResponse.body()
                friendIds.addAll(received.mapNotNull { it["sender_id"] })
            }
            
            if (friendIds.isEmpty()) return Result.success(emptyList())
            
            // Fetch profiles
            var usersResponse = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer ${authService.getAccessToken()}")
                parameter("id", "in.(${friendIds.distinct().joinToString(",")})")
            }
            
            if (usersResponse.status.isSuccess()) {
                Result.success(usersResponse.body())
            } else {
                Result.failure(Exception("Failed to get friend profiles"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}



