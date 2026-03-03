package conectate.com.data.repository

import conectate.com.data.model.User
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

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
    private val notificationRepository: NotificationRepository
) {

    suspend fun getFriendStatus(targetUserId: String): Result<FriendStatus> {
        return try {
            val currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            
            // Check if I sent a request or we are friends
            val sentResponse = supabaseConfig.httpClient.get(
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
                    val req = sentRequests.first()
                    return Result.success(if (req.status == "accepted") FriendStatus.FRIENDS else FriendStatus.PENDING_SENT)
                }
            }
            
            // Check if I received a request
            val receivedResponse = supabaseConfig.httpClient.get(
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
                    val req = receivedRequests.first()
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
            val currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            
            val requestData = CreateFriendRequest(
                senderId = currentUserId,
                receiverId = targetUserId,
                status = "pending"
            )
            
            val response = supabaseConfig.httpClient.post(
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
            val currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            
            val response = supabaseConfig.httpClient.patch(
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
            val currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            
            // Fetch friends sorted by updated_at desc
            // We need to fetch relationships first
             val sentResponse = supabaseConfig.httpClient.get(
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
            
            val receivedResponse = supabaseConfig.httpClient.get(
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
                sent.forEach { friendsMap[it["receiver_id"]!!] = it["updated_at"]!! }
            }
            
            if (receivedResponse.status.isSuccess()) {
                val received: List<Map<String, String>> = receivedResponse.body()
                received.forEach { friendsMap[it["sender_id"]!!] = it["updated_at"]!! }
            }
            
            if (friendsMap.isEmpty()) return Result.success(emptyList())
            
            val sortedIds = friendsMap.entries.sortedByDescending { it.value }.take(limit).map { it.key }
            
             // Fetch profiles
            val usersResponse = supabaseConfig.httpClient.get(
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
            val currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            
            val response = supabaseConfig.httpClient.delete(
                "${supabaseConfig.supabaseUrl}/rest/v1/friends"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer ${authService.getAccessToken()}")
                parameter("sender_id", "eq.$senderId")
                parameter("receiver_id", "eq.$currentUserId")
            }
            
            if (response.status.isSuccess()) {
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
            val currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            
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
            val currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            
            val response = supabaseConfig.httpClient.delete(
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
            val currentUserId = authService.getCurrentUserId() ?: return Result.failure(Exception("Not logged in"))
            
            // 1. Get sender_ids from pending requests
            val response = supabaseConfig.httpClient.get(
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
            
            if (senderIds.isEmpty()) return Result.success(emptyList())
            
            // 2. Fetch profiles for these senders
            val usersResponse = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer ${authService.getAccessToken()}")
                parameter("id", "in.(${senderIds.joinToString(",")})")
            }
            
            if (usersResponse.status.isSuccess()) {
                Result.success(usersResponse.body())
            } else {
                Result.failure(Exception("Failed to get profiles"))
            }
        } catch (e: Exception) {
            Result.failure(e)
    }
    }

    suspend fun getFriends(userId: String): Result<List<User>> {
        return try {
            // Friends are where (sender=userId AND status=accepted) OR (receiver=userId AND status=accepted)
            
            // 1. Where I am sender
            val sentResponse = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/friends"
            ) {
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 parameter("sender_id", "eq.$userId")
                 parameter("status", "eq.accepted")
                 parameter("select", "receiver_id")
            }
            
            // 2. Where I am receiver
            val receivedResponse = supabaseConfig.httpClient.get(
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
            val usersResponse = supabaseConfig.httpClient.get(
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
