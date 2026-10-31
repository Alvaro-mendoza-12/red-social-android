package conectate.com.data.repository

import conectate.com.data.supabase.SupabaseConfig
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.filter.PostgrestFilterBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

import kotlinx.serialization.Serializable
import conectate.com.data.supabase.AuthService
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import conectate.com.data.localfallback.LocalFallbackApiClient

data class PostShareChange(val actorId: String, val postId: String, val shared: Boolean, val count: Int? = null) {
    fun applyTo(post: conectate.com.data.model.Post): conectate.com.data.model.Post = if (post.id != postId) post else
        post.copy(sharedByMe = shared, shareCount = count ?: (post.shareCount +
            if (post.sharedByMe == shared) 0 else if (shared) 1 else -1).coerceAtLeast(0))
}

@Serializable
data class Share(
    val id: String = java.util.UUID.randomUUID().toString(),
    val user_id: String,
    val post_id: String? = null,
    val video_id: String? = null,
    val created_at: String? = null
)

@Singleton
class ShareRepository @Inject constructor(
    private val supabase: SupabaseConfig,
    private val authService: AuthService,
    private val localFallbackApiClient: LocalFallbackApiClient
) {
    private val _postChanges = MutableSharedFlow<PostShareChange>(extraBufferCapacity = 32)
    val postChanges = _postChanges.asSharedFlow()
    private fun changed(user: String, type: String, id: String, shared: Boolean, count: Int? = null) {
        if (type == "post" && user == authService.getCurrentUserId()) _postChanges.tryEmit(PostShareChange(user, id, shared, count))
    }

    private fun shouldUseUbuntuBackend(): Boolean =
        localFallbackApiClient.isConfigured &&
            (supabase.isLocalFallbackPrimary || authService.isLocalFallbackSession())


    suspend fun shareContent(userId: String, contentType: String, targetId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("ShareRepository", "shareContent: userId=$userId, contentType=$contentType, targetId=$targetId")
            val accessToken = authService.getAccessTokenAwait() ?: return@withContext Result.failure(Exception("Inicia sesión para compartir."))
            if (userId != authService.getCurrentUserId()) return@withContext Result.failure(Exception("La cuenta cambió. Abre Compartir de nuevo."))

            if (shouldUseUbuntuBackend()) {
                return@withContext when (contentType.trim().lowercase()) {
                    "post" -> localFallbackApiClient.sharePost(accessToken, targetId).map { changed(userId, "post", targetId, it.sharedByMe, it.shareCount) }
                    "video" -> localFallbackApiClient.shareVideo(accessToken, targetId).map { Unit }
                    else -> Result.failure(Exception("Tipo de contenido no compatible para compartir."))
                }
            }
            
            val shareData = buildJsonObject {
                put("user_id", userId)
            if (contentType.trim().lowercase() == "post") put("post_id", targetId)
                else put("video_id", targetId)
            }
            
            var response = supabase.httpClient.post("${supabase.supabaseUrl}/rest/v1/shares") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabase.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(shareData)
            }
            
            if (response.status.isSuccess()) {
                changed(userId, contentType, targetId, true)
                android.util.Log.d("ShareRepository", "shareContent: SUCCESS")
                Result.success(Unit)
            } else {
                val errorBody = response.bodyAsText()
                android.util.Log.e("ShareRepository", "shareContent: FAILED status=${response.status}, error=$errorBody")
                if (errorBody.contains("duplicate key")) {
                    changed(userId, contentType, targetId, true)
                    android.util.Log.d("ShareRepository", "shareContent: Already shared, treating as success")
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Failed to share: ${response.status}"))
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            android.util.Log.e("ShareRepository", "shareContent: EXCEPTION", e)
            Result.failure(e)
        }
    }



    suspend fun unshareContent(userId: String, contentType: String, targetId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("ShareRepository", "unshareContent: userId=$userId, contentType=$contentType, targetId=$targetId")
            val accessToken = authService.getAccessTokenAwait() ?: return@withContext Result.failure(Exception("Inicia sesión para compartir."))
            if (userId != authService.getCurrentUserId()) return@withContext Result.failure(Exception("La cuenta cambió. Abre Compartir de nuevo."))

            if (shouldUseUbuntuBackend()) {
                return@withContext when (contentType.trim().lowercase()) {
                    "post" -> localFallbackApiClient.unsharePost(accessToken, targetId).map { changed(userId, "post", targetId, it.sharedByMe, it.shareCount) }
                    "video" -> localFallbackApiClient.unshareVideo(accessToken, targetId).map { Unit }
                    else -> Result.failure(Exception("Tipo de contenido no compatible para dejar de compartir."))
                }
            }
            
            var response = supabase.httpClient.delete("${supabase.supabaseUrl}/rest/v1/shares") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabase.supabaseAnonKey)
                parameter("user_id", "eq.$userId")
                if (contentType.trim().lowercase() == "post") parameter("post_id", "eq.$targetId")
                else parameter("video_id", "eq.$targetId")
            }
            
            if (response.status.isSuccess()) {
                changed(userId, contentType, targetId, false)
                android.util.Log.d("ShareRepository", "unshareContent: SUCCESS")
                Result.success(Unit)
            } else {
                android.util.Log.e("ShareRepository", "unshareContent: FAILED status=${response.status}")
                Result.failure(Exception("Failed to unshare: ${response.status}"))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            android.util.Log.e("ShareRepository", "unshareContent: EXCEPTION", e)
            Result.failure(e)
        }
    }



    suspend fun isShared(userId: String, contentType: String, targetId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            var response = supabase.client.from("shares").select {
                filter {
                    eq("user_id", userId)
                    if (contentType == "post") eq("post_id", targetId)
                    else eq("video_id", targetId)
                }
            }
            val sharedList = response.decodeList<Share>()
            Result.success(sharedList.isNotEmpty())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}





