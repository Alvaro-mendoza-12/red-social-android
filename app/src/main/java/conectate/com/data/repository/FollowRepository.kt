package conectate.com.data.repository

import conectate.com.data.model.User
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton
import java.time.LocalDateTime

@Singleton
class FollowRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val notificationRepository: NotificationRepository
) {

    /**
     * Follow a user
     * - Creates follow relationship
     * - Increments following_count for current user
     * - Increments followers_count for target user
     */
    suspend fun followUser(userId: String): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken()
                ?: return Result.failure(Exception("No access token"))

            val currentUserId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("No user ID"))

            // 1. Create follow relationship
            val followData = mapOf(
                "follower_id" to currentUserId,
                "following_id" to userId
            )

            val response = supabaseConfig.httpClient.post(
                "${supabaseConfig.supabaseUrl}/rest/v1/follows"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                header("Content-Type", "application/json")
                header("Prefer", "return=minimal")
                setBody(followData)
            }

            if (response.status == HttpStatusCode.Conflict) {
                // Idempotency: Already following. Treat as success and skip RPCs to avoid double increment.
                return Result.success(Unit)
            }

            if (!response.status.isSuccess()) {
                return Result.failure(Exception("Failed to create follow: ${response.status}"))
            }

            // 2. Increment following_count for current user
            val incrementFollowingResponse = supabaseConfig.httpClient.post(
                "${supabaseConfig.supabaseUrl}/rest/v1/rpc/increment_following_count"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                header("Content-Type", "application/json")
                setBody(mapOf("user_id" to currentUserId))
            }

            // 3. Increment followers_count for target user
            val incrementFollowersResponse = supabaseConfig.httpClient.post(
                "${supabaseConfig.supabaseUrl}/rest/v1/rpc/increment_followers_count"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                header("Content-Type", "application/json")
                setBody(mapOf("user_id" to userId))
            }

            // 4. Trigger Notification - Handled by DB trigger notify_on_follow

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Unfollow a user
     * - Deletes follow relationship
     * - Decrements following_count for current user
     * - Decrements followers_count for target user
     */
    suspend fun unfollowUser(userId: String): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken()
                ?: return Result.failure(Exception("No access token"))

            val currentUserId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("No user ID"))

            // 1. Delete follow relationship
            val response = supabaseConfig.httpClient.delete(
                "${supabaseConfig.supabaseUrl}/rest/v1/follows"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("follower_id", "eq.$currentUserId")
                parameter("following_id", "eq.$userId")
            }

            if (!response.status.isSuccess()) {
                return Result.failure(Exception("Failed to delete follow: ${response.status}"))
            }

            // 2. Decrement following_count for current user
            val decrementFollowingResponse = supabaseConfig.httpClient.post(
                "${supabaseConfig.supabaseUrl}/rest/v1/rpc/decrement_following_count"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                header("Content-Type", "application/json")
                setBody(mapOf("user_id" to currentUserId))
            }

            // 3. Decrement followers_count for target user
            val decrementFollowersResponse = supabaseConfig.httpClient.post(
                "${supabaseConfig.supabaseUrl}/rest/v1/rpc/decrement_followers_count"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                header("Content-Type", "application/json")
                setBody(mapOf("user_id" to userId))
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get list of followers for a user
     */
    suspend fun getFollowers(userId: String): Result<List<User>> {
        return try {
            val accessToken = authService.getAccessToken()
                ?: return Result.failure(Exception("No access token"))

            // Query follows table and join with profiles
            val response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/follows"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("following_id", "eq.$userId")
                parameter("select", "follower_id")
            }

            if (!response.status.isSuccess()) {
                return Result.failure(Exception("Failed to fetch followers: ${response.status}"))
            }

            // Get follower IDs
            val follows: List<Map<String, String>> = response.body()
            val followerIds = follows.mapNotNull { it["follower_id"] }

            if (followerIds.isEmpty()) {
                return Result.success(emptyList())
            }

            // Fetch user profiles
            val usersResponse = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("id", "in.(${followerIds.joinToString(",")})")
            }

            if (usersResponse.status.isSuccess()) {
                Result.success(usersResponse.body())
            } else {
                Result.failure(Exception("Failed to fetch follower profiles"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get list of users that this user is following
     */
    suspend fun getFollowing(userId: String): Result<List<User>> {
        return try {
            val accessToken = authService.getAccessToken()
                ?: return Result.failure(Exception("No access token"))

            // Query follows table
            val response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/follows"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("follower_id", "eq.$userId")
                parameter("select", "following_id")
            }

            if (!response.status.isSuccess()) {
                return Result.failure(Exception("Failed to fetch following: ${response.status}"))
            }

            // Get following IDs
            val follows: List<Map<String, String>> = response.body()
            val followingIds = follows.mapNotNull { it["following_id"] }

            if (followingIds.isEmpty()) {
                return Result.success(emptyList())
            }

            // Fetch user profiles
            val usersResponse = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("id", "in.(${followingIds.joinToString(",")})")
            }

            if (usersResponse.status.isSuccess()) {
                Result.success(usersResponse.body())
            } else {
                Result.failure(Exception("Failed to fetch following profiles"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check if current user is following a specific user
     */
    suspend fun isFollowing(userId: String): Result<Boolean> {
        return try {
            val accessToken = authService.getAccessToken()
                ?: return Result.failure(Exception("No access token"))

            val currentUserId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("No user ID"))

            val response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/follows"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer $accessToken")
                parameter("follower_id", "eq.$currentUserId")
                parameter("following_id", "eq.$userId")
            }

            if (response.status.isSuccess()) {
                val follows: List<Map<String, Any>> = response.body()
                Result.success(follows.isNotEmpty())
            } else {
                Result.success(false)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get followers count flow for realtime updates
     */
    fun getFollowersFlow(userId: String): Flow<List<User>> = flow {
        while (true) {
            val result = getFollowers(userId)
            if (result.isSuccess) {
                emit(result.getOrThrow())
            }
            kotlinx.coroutines.delay(5000) // Poll every 5 seconds
        }
    }

    /**
     * Get following count flow for realtime updates
     */
    fun getFollowingFlow(userId: String): Flow<List<User>> = flow {
        while (true) {
            val result = getFollowing(userId)
            if (result.isSuccess) {
                emit(result.getOrThrow())
            }
            kotlinx.coroutines.delay(5000) // Poll every 5 seconds
        }
    }
}
