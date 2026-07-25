package conectate.com.data.repository

import conectate.com.data.model.Post
import conectate.com.data.model.User
import conectate.com.data.model.Video
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.*
import io.ktor.http.*
import javax.inject.Inject
import javax.inject.Singleton
import io.ktor.client.call.body

import conectate.com.data.supabase.AuthService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@Singleton
class RankingRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val localFallbackApiClient: LocalFallbackApiClient
) {



    suspend fun getTopUsers(filter: String = "followers"): Result<List<User>> = withContext(Dispatchers.IO) {
        try {
            if (supabaseConfig.isLocalFallbackPrimary) {
                val token = authService.getAccessToken()
                    ?: return@withContext Result.failure(Exception("No access token available"))
                val response = supabaseConfig.httpClient.get("${localFallbackApiClient.baseUrl}/v1/data/profiles/ranking") {
                    header("Authorization", "Bearer $token")
                    parameter("metric", if (filter == "posts") "posts" else "followers")
                    parameter("limit", "100")
                }
                if (!response.status.isSuccess()) {
                    return@withContext Result.failure(Exception("Error fetching Ubuntu top users"))
                }
                val body = response.bodyAsText()
                val root = supabaseConfig.json.parseToJsonElement(body).jsonObject
                val users = (root["items"] as? JsonArray)
                    ?.mapNotNull { item ->
                        runCatching {
                            supabaseConfig.json.decodeFromJsonElement<User>(
                                item.jsonObject.normalizedStorageUrls()
                            )
                        }.getOrNull()
                    }
                    .orEmpty()
                    .sortedByDescending {
                        when (filter) {
                            "posts" -> it.post_count ?: 0
                            else -> it.followers_count ?: 0
                        }
                    }
                return@withContext Result.success(users)
            }

            var orderColumn = when (filter) {
                "likes" -> "likes_count" // Assuming these columns exist or we count them
                "views" -> "views_count" // Assuming mostly for video creators
                else -> "followers_count"
            }
            
            var response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/profiles"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("select", "*")
                parameter("order", "$orderColumn.desc")
                parameter("limit", "100")
            }
            
            if (response.status.isSuccess()) {
                val users: List<User> = response.body()
                Result.success(users)
            } else {
                Result.failure(Exception("Error fetching top users"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    suspend fun getTopVideos(filter: String = "views"): Result<List<Video>> = withContext(Dispatchers.IO) {
         try {
            if (supabaseConfig.isLocalFallbackPrimary) {
                val token = authService.getAccessToken()
                    ?: return@withContext Result.failure(Exception("No access token available"))
                val videos = localFallbackApiClient.loadVideos(token, limit = 100, offset = 0).getOrThrow()
                return@withContext Result.success(
                    videos.sortedByDescending {
                        when (filter) {
                            "likes" -> it.likeCount
                            "comments" -> it.commentCount
                            "shares" -> it.shareCount
                            else -> it.views
                        }
                    }
                )
            }

            var orderColumn = when (filter) {
                "likes" -> "like_count"
                else -> "views"
            }
            
            var response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/videos"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("select", "*")
                parameter("order", "$orderColumn.desc")
                parameter("limit", "100")
            }
            
            if (response.status.isSuccess()) {
                val videos: List<Video> = response.body()
                var currentUserId = authService.getCurrentUserId()
                var result = if (currentUserId != null) {
                    var accessToken = authService.getAccessToken() ?: ""
                    var likedIds = getUserLikedVideoIds(accessToken, currentUserId)
                    var sharedIds = getUserSharedContentIds(accessToken, currentUserId, "video")
                    videos.map { it.copy(
                        likedByMe = likedIds.contains(it.id),
                        sharedByMe = sharedIds.contains(it.id)
                    )}
                } else videos
                Result.success(result)
            } else {
                Result.failure(Exception("Error fetching top videos"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    suspend fun getTopPosts(filter: String = "likes"): Result<List<Post>> = withContext(Dispatchers.IO) {
        try {
            if (supabaseConfig.isLocalFallbackPrimary) {
                val token = authService.getAccessToken()
                    ?: return@withContext Result.failure(Exception("No access token available"))
                val posts = localFallbackApiClient.loadFeed(token, limit = 100, offset = 0).getOrThrow().items
                return@withContext Result.success(
                    posts.sortedByDescending {
                        when (filter) {
                            "shares" -> it.shareCount
                            "comments" -> it.commentCount
                            else -> it.likeCount
                        }
                    }
                )
            }

             var orderColumn = when (filter) {
                "shares" -> "share_count"
                else -> "like_count"
            }
            
            var response = supabaseConfig.httpClient.get(
                "${supabaseConfig.supabaseUrl}/rest/v1/posts"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("select", "*")
                parameter("order", "$orderColumn.desc")
                parameter("limit", "100")
            }
            
            if (response.status.isSuccess()) {
                val posts: List<Post> = response.body()
                var currentUserId = authService.getCurrentUserId()
                var result = if (currentUserId != null) {
                   var accessToken = authService.getAccessToken() ?: ""
                   var likedIds = getUserLikedPostIds(accessToken, currentUserId)
                   var sharedIds = getUserSharedContentIds(accessToken, currentUserId, "post")
                   posts.map { it.copy(
                       likedByMe = likedIds.contains(it.id),
                       sharedByMe = sharedIds.contains(it.id)
                   )}
                } else posts
                Result.success(result)
            } else {
                Result.failure(Exception("Error fetching top posts"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun getUserLikedVideoIds(accessToken: String, userId: String): Set<String> {
        return try {
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/video_likes") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("user_id", "eq.$userId")
                parameter("select", "video_id")
            }
            if (response.status.isSuccess()) {
                val data: List<JsonObject> = response.body()
                data.mapNotNull { it["video_id"]?.jsonPrimitive?.contentOrNull }.toSet()
            } else emptySet()
        } catch (e: Exception) { emptySet() }
    }

    private suspend fun getUserLikedPostIds(accessToken: String, userId: String): Set<String> {
        return try {
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/post_likes") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("user_id", "eq.$userId")
                parameter("select", "post_id")
            }
            if (response.status.isSuccess()) {
                val data: List<JsonObject> = response.body()
                data.mapNotNull { it["post_id"]?.jsonPrimitive?.contentOrNull }.toSet()
            } else emptySet()
        } catch (e: Exception) { emptySet() }
    }

    private suspend fun getUserSharedContentIds(accessToken: String, userId: String, contentType: String): Set<String> {
        return try {
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/shares") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("user_id", "eq.$userId")
                parameter("select", "post_id,video_id")
            }
            if (response.status.isSuccess()) {
                val data: List<JsonObject> = response.body()
                data.mapNotNull { 
                    if (contentType == "post") it["post_id"]?.jsonPrimitive?.contentOrNull
                    else it["video_id"]?.jsonPrimitive?.contentOrNull
                }.toSet()
            } else emptySet()
        } catch (e: Exception) { emptySet() }
    }

    private fun JsonObject.normalizedStorageUrls(): JsonObject = buildJsonObject {
        this@normalizedStorageUrls.forEach { (key, value) ->
            val normalized = if (
                key in setOf("avatar_url", "cover_url", "photo_url", "profile_image") &&
                value is JsonPrimitive &&
                value.contentOrNull != null
            ) {
                JsonPrimitive(UbuntuStorageUrl.rewrite(value.contentOrNull))
            } else {
                value
            }
            put(key, normalized)
        }
    }
}





