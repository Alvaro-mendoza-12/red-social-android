package conectate.com.data.repository

import androidx.compose.runtime.Composable

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import conectate.com.core.moderation.ContentModerationService
import conectate.com.core.moderation.ModerationContext
import conectate.com.core.moderation.ModerationVerdict
import conectate.com.core.observability.AppMetrics
import conectate.com.data.cache.UpstashCacheService
import conectate.com.data.model.Post
import conectate.com.data.repository.HashtagRepository
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.data.supabase.StorageService
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.*
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.*
import java.io.File

@Singleton
class PostRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val storageService: StorageService,
    private val moderationService: ContentModerationService,
    private val appMetrics: AppMetrics,
    private val notificationRepository: NotificationRepository,
    private val userRepository: UserRepository,
    private val hashtagRepository: HashtagRepository,
    private val upstashCache: UpstashCacheService,
    @ApplicationContext private val context: Context
) {

    fun getGlobalPosts(offset: Int = 0, limit: Int = 20): Flow<List<Post>> = flow {
        var startedAt = System.currentTimeMillis()
        val cacheKey = "posts:global:offset=$offset:limit=$limit"
        
        try {
            // 1. Intentar cache primero (solo para primera página)
            if (offset == 0) {
                val cachedJson = upstashCache.get(cacheKey)
                if (cachedJson != null) {
                    // Parse JSON a lista de Posts (requiere serialización)
                    // Por ahora, skip por complejidad de deserialización sin Json object
                    // En producción, usar kotlinx.serialization.decodeFromString<List<Post>>()
                    // Por ahora solo emitimos vacío y recargamos
                    // emit(emptyList()) // No emitimos cache hasta tener deserialización
                }
            }

            // 2. Fetch desde Supabase
            var accessToken = authService.getAccessToken()
            if (accessToken != null) {
                var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/posts") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("limit", "$limit")
                    parameter("offset", "$offset")
                    parameter("order", "created_at.desc")
                    parameter("select", "*")
                    parameter("visibility", "eq.public")
                }

                if (response.status.isSuccess()) {
                    val rawPosts: List<Post> = response.body()
                    var currentUserId = authService.getCurrentUserId()
                    
                    var posts = if (currentUserId != null) {
                        var likedIds = getUserLikedPostIds(accessToken, currentUserId)
                        var sharedIds = getUserSharedContentIds(currentUserId, "post")
                        rawPosts.map { it.copy(likedByMe = likedIds.contains(it.id), sharedByMe = sharedIds.contains(it.id)) }
                    } else rawPosts

                    // 3. Cachear si es primera página
                    if (offset == 0 && posts.isNotEmpty()) {
                        // Serializar a JSON (pendiente: implementar con kotlinx.serialization)
                        // var json = Json.encodeToString(posts)
                        // upstashCache.set(cacheKey, json, 300) // 5 min TTL
                        // Por ahora solo metric
                        appMetrics.incrementCounter("posts_cache_store")
                    }

                    appMetrics.incrementCounter(
                        metric = "posts_fetch_success",
                        attributes = mapOf("source" to "global", "count" to posts.size.toString())
                    )
                    appMetrics.recordLatency(
                        metric = "posts_fetch",
                        durationMs = System.currentTimeMillis() - startedAt,
                        attributes = mapOf("source" to "global", "count" to posts.size.toString())
                    )
                    emit(posts)
                } else {
                    appMetrics.recordFailure(
                        metric = "posts_fetch",
                        attributes = mapOf("source" to "global", "http_status" to response.status.value.toString())
                    )
                    emit(emptyList())
                }
            } else {
                appMetrics.recordFailure(
                    metric = "posts_fetch",
                    attributes = mapOf("source" to "global", "reason" to "missing_access_token")
                )
                emit(emptyList())
            }
            } catch (e: Exception) {
                android.util.Log.e("PostRepository", "Error fetching global posts", e)
                appMetrics.recordFailure("posts_fetch", e, mapOf("source" to "global"))
                emit(emptyList())
            }
        }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    fun getUserPosts(userId: String): Flow<List<Post>> = flow {
        try {
            var accessToken = authService.getAccessToken()
            if (accessToken != null) {
                var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/posts") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("user_id", "eq.$userId")
                    parameter("order", "created_at.desc")
                    parameter("select", "*")
                    
                    var currentUserId = authService.getCurrentUserId()
                    if (userId != currentUserId) {
                        parameter("visibility", "eq.public")
                    }
                }

                if (response.status.isSuccess()) {
                    val rawPosts: List<Post> = response.body()
                    var currentUserId = authService.getCurrentUserId()
                    
                    var posts = if (currentUserId != null) {
                        var likedIds = getUserLikedPostIds(accessToken, currentUserId)
                        var sharedIds = getUserSharedContentIds(currentUserId, "post")
                        rawPosts.map { it.copy(likedByMe = likedIds.contains(it.id), sharedByMe = sharedIds.contains(it.id)) }
                    } else rawPosts
                    
                    emit(posts)
                } else {
                    emit(emptyList())
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PostRepository", "Error fetching user posts", e)
            emit(emptyList())
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    fun getSharedPosts(userId: String): Flow<List<Post>> = flow {
        try {
            var accessToken = authService.getAccessToken()
            var currentUserId = authService.getCurrentUserId()
            
            if (accessToken != null) {
                // Step 1: Get shared post IDs for this user
                var sharedIds = getUserSharedContentIds(userId, "post")
                
                if (sharedIds.isEmpty()) {
                    emit(emptyList())
                    return@flow
                }

                // Step 2: Fetch the actual posts using those IDs
                var response = supabaseConfig.client.from("posts").select {
                    filter {
                        isIn("id", sharedIds.toList())
                        if (userId != currentUserId) {
                            eq("visibility", "public")
                        }
                    }
                    order("created_at", order = Order.DESCENDING)
                }

                val rawPosts: List<Post> = response.decodeList()
                
                var posts = if (currentUserId != null) {
                    var likedIds = getUserLikedPostIds(accessToken, currentUserId)
                    // Note: sharedIds already contains the IDs for this user, so sharedByMe is true for all
                    rawPosts.map { it.copy(likedByMe = likedIds.contains(it.id), sharedByMe = true) }
                } else rawPosts
                
                emit(posts)
            }
        } catch (e: Exception) {
            android.util.Log.e("PostRepository", "Error fetching shared posts", e)
            emit(emptyList())
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    suspend fun getPostById(postId: String): Result<Post> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            var accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/posts") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("id", "eq.$postId")
                parameter("select", "*")
            }

            if (response.status.isSuccess()) {
                val posts: List<Post> = response.body()
                var post = posts.firstOrNull() ?: return@withContext Result.failure(Exception("Publicación no encontrada"))
                
                var currentUserId = authService.getCurrentUserId()
                    val enrichedPost = if (currentUserId != null) {
                        var likedIds = getUserLikedPostIds(accessToken, currentUserId)
                        var sharedIds = getUserSharedContentIds(currentUserId, "post")
                        post.copy(likedByMe = likedIds.contains(post.id), sharedByMe = sharedIds.contains(post.id))
                    } else post
                
                Result.success(enrichedPost)
            } else {
                Result.failure(Exception("Error al buscar publicación: ${response.status}"))
            }
        } catch (e: Exception) {
            android.util.Log.e("PostRepository", "Error fetching post by id", e)
            Result.failure(e)
        }
    }

    private suspend fun getUserSharedContentIds(userId: String, contentType: String): Set<String> {
        return try {
            // Fetch all shares for this user, we will filter nulls in Kotlin which is safer
            var response = supabaseConfig.client.from("shares").select(Columns.list("post_id", "video_id")) {
                filter {
                    eq("user_id", userId)
                }
            }
            var data = response.decodeList<JsonObject>()
            var ids = data.mapNotNull { item -> 
                if (contentType == "post") item["post_id"]?.jsonPrimitive?.contentOrNull
                else item["video_id"]?.jsonPrimitive?.contentOrNull
            }.toSet()
            
            android.util.Log.d("PostRepository", "getUserSharedContentIds: Found ${ids.size} shared items for type $contentType (userId=$userId)")
            ids
        } catch (e: Exception) {
            android.util.Log.e("PostRepository", "getUserSharedContentIds: Exception", e)
            emptySet()
        }
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
                val likes: List<Map<String, String>> = response.body()
                likes.mapNotNull { it["post_id"] }.toSet()
            } else {
                emptySet()
            }
        } catch (e: Exception) {
            emptySet()
        }
    }

    suspend fun createPost(content: String, mediaUris: List<Uri>, visibility: String = "public"): Result<Post> {
        var startedAt = System.currentTimeMillis()
        return try {
            var userId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("Usuario no identificado"))

            var accessToken = authService.getAccessToken()
                ?: return Result.failure(Exception("No access token"))
            val policyAccepted = userRepository.hasAcceptedPrivacyPolicy(userId).getOrElse { false }
            if (!policyAccepted) {
                return Result.failure(
                    Exception("Debes aceptar las políticas de privacidad desde Configuración > Políticas de privacidad antes de publicar.")
                )
            }

            var moderation = moderationService.moderate(
                text = content,
                context = ModerationContext.POST_CONTENT
            )
            if (moderation.verdict == ModerationVerdict.BLOCK) {
                appMetrics.recordFailure(
                    metric = "post_create",
                    attributes = mapOf("reason" to "moderation_block")
                )
                return Result.failure(Exception("El contenido incumple nuestras politicas de comunidad"))
            }

            var sanitizedContent = moderation.sanitizedText
            if (sanitizedContent.isBlank() && mediaUris.isEmpty()) {
                return Result.failure(Exception("La publicacion esta vacia"))
            }
            if (mediaUris.size > 10) {
                return Result.failure(Exception("Maximo 10 archivos por publicacion"))
            }

            // Extraer hashtags del contenido (antes de subir media)
            val extractedHashtags = hashtagRepository.extractHashtagsFromText(sanitizedContent)

            var mediaUrls = mutableListOf<String>()
            
            // Upload media sequentially to reduce memory pressure on lower-end devices.
            for (uri in mediaUris) {
                if (uri != Uri.EMPTY) {
                    val mediaPayload = readMediaPayload(uri)
                        ?: return Result.failure(Exception("No se pudo leer un archivo multimedia"))

                    var fileName = "${userId}/${UUID.randomUUID()}.${mediaPayload.extension}"
                    var uploadResult = storageService.uploadFile(
                        bucketName = "post-media",
                        fileName = fileName,
                        bytes = mediaPayload.bytes,
                        contentType = mediaPayload.contentType
                    )

                    if (uploadResult.isSuccess) {
                        storageService.getPublicUrl("post-media", fileName).let { mediaUrls.add(it) }
                    } else {
                        val error = uploadResult.exceptionOrNull()
                        appMetrics.recordFailure(
                            metric = "post_media_upload",
                            throwable = error,
                            attributes = mapOf("content_type" to mediaPayload.contentType)
                        )
                        return Result.failure(error ?: Exception("Fallo al subir un archivo"))
                    }
                }
            }

            val currentProfile = userRepository.getUserById(userId).getOrNull()
                ?: userRepository.ensureCurrentProfile().getOrNull()

            var userName = currentProfile?.username
                ?.takeIf { it.isNotBlank() }
                ?: currentProfile?.display_name
                    ?.takeIf { it.isNotBlank() }
                ?: currentProfile?.full_name
                    ?.takeIf { it.isNotBlank() }
                ?: currentProfile?.email
                    ?.substringBefore("@")
                    ?.takeIf { it.isNotBlank() }
                ?: "Usuario"
            val userImage: String? = currentProfile?.avatar_url
            var isVerified = currentProfile?.is_verified ?: false

            val postRequest = PostInsertRequest(
                userId = userId,
                userName = userName,
                userProfileImage = userImage,
                content = sanitizedContent,
                mediaUrls = mediaUrls,
                isVerified = isVerified,
                visibility = visibility,
                tags = extractedHashtags
            )

            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/posts") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                setBody(postRequest)
            }

            if (response.status.isSuccess()) {
                val createdPost: List<Post> = response.body()
                var postId = createdPost.first().id
                
                // Sincronizar hashtags localmente (cache) - llamada suspend directa
                if (extractedHashtags.isNotEmpty()) {
                    try {
                        hashtagRepository.attachHashtagsToPost(postId, extractedHashtags)
                    } catch (e: Exception) {
                        android.util.Log.e("PostRepository", "Failed to sync hashtags locally", e)
                    }
                }

                appMetrics.incrementCounter(
                    metric = "post_create_success",
                    attributes = mapOf("media_count" to mediaUrls.size.toString(), "hashtag_count" to extractedHashtags.size.toString())
                )
                appMetrics.recordLatency(
                    metric = "post_create",
                    durationMs = System.currentTimeMillis() - startedAt,
                    attributes = mapOf("media_count" to mediaUrls.size.toString())
                )
                Result.success(createdPost.first())
            } else {
                var errorBody = response.body<String>()
                appMetrics.recordFailure(
                    metric = "post_create",
                    attributes = mapOf("http_status" to response.status.value.toString())
                )
                Result.failure(Exception("Error creando publicación: ${response.status} - $errorBody"))
            }
        } catch (e: Exception) {
            appMetrics.recordFailure("post_create", e)
            Result.failure(e)
        }
    }

    suspend fun toggleLike(postId: String, isLiked: Boolean): Result<Unit> {
        return try {
            var userId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))

            if (isLiked) {
                // Unlike
                supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/post_likes") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("post_id", "eq.$postId")
                    parameter("user_id", "eq.$userId")
                }
            } else {
                // Like
                supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/post_likes") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Content-Type", "application/json")
                    setBody(mapOf("post_id" to postId, "user_id" to userId))
                }
                
                // Trigger notification removed - handled by DB trigger notify_on_post_like
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePost(postId: String): Result<Unit> {
        return try {
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            
            supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/posts") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("id", "eq.$postId")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updatePostVisibility(postId: String, visibility: String): Result<Unit> {
        return try {
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))

            supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/posts") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                parameter("id", "eq.$postId")
                setBody(mapOf("visibility" to visibility))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updatePostContent(postId: String, content: String): Result<Unit> {
        return try {
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))

            supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/posts") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                parameter("id", "eq.$postId")
                setBody(mapOf("content" to content))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun addComment(postId: String, content: String, parentId: String? = null): Result<conectate.com.data.model.Comment> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        var startedAt = System.currentTimeMillis()
        try {
            var userId = authService.getCurrentUserId() ?: return@withContext Result.failure(Exception("No user"))
            var accessToken = authService.getAccessToken() ?: return@withContext Result.failure(Exception("No token"))

            var moderation = moderationService.moderate(
                text = content,
                context = ModerationContext.COMMENT_CONTENT
            )
            if (moderation.verdict == ModerationVerdict.BLOCK) {
                appMetrics.recordFailure(
                    metric = "post_comment_create",
                    attributes = mapOf("reason" to "moderation_block")
                )
                return@withContext Result.failure(Exception("Tu comentario incumple nuestras politicas"))
            }

            var sanitizedContent = moderation.sanitizedText
            if (sanitizedContent.isBlank()) {
                return@withContext Result.failure(Exception("El comentario no puede estar vacio"))
            }

            var queryParams = mutableMapOf(
                "post_id" to postId,
                "user_id" to userId,
                "content" to sanitizedContent
            )
            if (parentId != null) queryParams["parent_id"] = parentId

            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/post_comments") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                setBody(queryParams)
            }
            
            if (response.status.isSuccess()) {
                val insertedComments: List<kotlinx.serialization.json.JsonObject> = response.body()
                var json = insertedComments.first()
                var id = json["id"]?.jsonPrimitive?.contentOrNull ?: java.util.UUID.randomUUID().toString()
                var createdAt = json["created_at"]?.jsonPrimitive?.contentOrNull ?: java.time.OffsetDateTime.now().toString()
                
                 val newComment = conectate.com.data.model.Comment(
                    id = id,
                    postId = postId,
                    userId = userId,
                    content = sanitizedContent,
                    createdAt = createdAt,
                    userName = "Tú", // Fallback local faster than re-fetching profile
                    isMine = true,
                    parentId = parentId
                )

                // Trigger owner/reply notification and mentions.
                val primaryRecipients = mutableSetOf<String>()
                val trimmedPreview = sanitizedContent.take(160)
                if (!parentId.isNullOrBlank()) {
                    resolvePostCommentOwnerId(parentId)?.let { parentOwnerId ->
                        if (primaryRecipients.add(parentOwnerId)) {
                            notificationRepository.sendNotification(
                                recipientId = parentOwnerId,
                                type = "reply_post",
                                targetId = postId,
                                content = trimmedPreview,
                                extraData = buildJsonObject {
                                    put("comment_id", JsonPrimitive(id))
                                    put("parent_id", JsonPrimitive(parentId))
                                }
                            )
                        }
                    }
                }
                getPostById(postId).onSuccess { post ->
                    if (primaryRecipients.add(post.userId)) {
                        notificationRepository.sendNotification(
                            recipientId = post.userId,
                            type = if (parentId == null) "comment_post" else "reply_post",
                            targetId = postId,
                            content = trimmedPreview,
                            extraData = buildJsonObject { 
                                put("comment_id", JsonPrimitive(id))
                                if (parentId != null) put("parent_id", JsonPrimitive(parentId))
                            }
                        )
                    }
                }
                notifyMentionsForPostComment(
                    postId = postId,
                    commentId = id,
                    parentId = parentId,
                    commentContent = sanitizedContent,
                    actorId = userId,
                    alreadyNotifiedUserIds = primaryRecipients
                )

                appMetrics.incrementCounter(
                    metric = "post_comment_create_success",
                    attributes = mapOf("has_parent" to (parentId != null).toString())
                )
                appMetrics.recordLatency(
                    metric = "post_comment_create",
                    durationMs = System.currentTimeMillis() - startedAt,
                    attributes = mapOf("has_parent" to (parentId != null).toString())
                )
                Result.success(newComment)
            } else {
                appMetrics.recordFailure(
                    metric = "post_comment_create",
                    attributes = mapOf("http_status" to response.status.value.toString())
                )
                Result.failure(Exception("Error adding comment: ${response.status}"))
            }
        } catch (e: Exception) {
            android.util.Log.e("PostRepository", "Error adding comment", e)
            appMetrics.recordFailure("post_comment_create", e)
            Result.failure(e)
        }
    }

    suspend fun addAudioComment(postId: String, audioFile: File): Result<Unit> {
        return try {
            authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))
            
            // Upload Audio
            var bytes = audioFile.readBytes()
            var fileName = "comment_${UUID.randomUUID()}.m4a"
            var uploadResult = storageService.uploadFile("post-comments-media", fileName, bytes, "audio/mp4")
            
            if (uploadResult.isSuccess) {
                 val publicUrl = storageService.getPublicUrl("post-comments-media", fileName)
                 
                 // Reuse addComment but currently it only takes content text.
                 // We will overload content to include the audio marker or url.
                 // Ideally we should have media_url column. 
                 // For now, we put [Audio] as content and try to put media_url in JSON if possible or just in content.
                 // However, without schema change, we can't save media_url column.
                 
                 // If the schema update (add_audio_to_post_comments.sql) is applied, we can send media_url.
                 // We'll try to send it in the body map.
                 
                 var userId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))
                 var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))

                 var queryParams = mutableMapOf(
                    "post_id" to postId,
                    "user_id" to userId,
                    "content" to "[Audio]",
                    "media_url" to publicUrl,
                    "content_type" to "audio"
                 )

                 var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/post_comments") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Content-Type", "application/json")
                    header("Prefer", "return=representation")
                    setBody(queryParams)
                 }
                 
                 if (response.status.isSuccess()) {
                     // Notify
                     getPostById(postId).onSuccess { post ->
                        notificationRepository.sendNotification(
                            recipientId = post.userId,
                            type = "comment_post",
                            targetId = postId,
                            content = "Envió un audio"
                        )
                     }
                     Result.success(Unit)
                 } else {
                     // Fallback if media_url column doesn't exist?
                     // If 400 error about undefined column, we might need a fallback. 
                     // But for now let's hope the user applies the SQL.
                     var errorBody = response.body<String>()
                     if (errorBody.contains("undefined column")) {
                         // Fallback: Embed in content
                         return addComment(postId, "[Audio]$publicUrl").map { Unit }
                     }
                     Result.failure(Exception("Error adding audio comment: ${response.status}"))
                 }
            } else {
                Result.failure(uploadResult.exceptionOrNull() ?: Exception("Upload failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPostComments(postId: String, limit: Int = 50): Flow<List<conectate.com.data.model.Comment>> = flow {
        try {
            var accessToken = authService.getAccessToken()
            if (accessToken != null) {
                // We need to fetch comments and join with profiles to get user info.
                // Supabase syntax for joining: select=*,profiles(username,full_name,avatar_url,is_verified)
                // Note: This requires foreign key from post_comments.user_id to profiles.id (or whatever table profiles are in)
                
                // If direct join is simpler:
                var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/post_comments") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("post_id", "eq.$postId")
                    parameter("order", "created_at.asc") // Changed to asc for chronological threads
                    parameter("limit", "$limit")
                    parameter("select", "*,profiles(username,full_name,avatar_url,is_verified)")
                }

                if (!response.status.isSuccess()) {
                    android.util.Log.w("PostRepository", "getPostComments: Join failed (${response.status}), trying fallback without join")
                    // Fallback: fetch without join to at least get the content
                    response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/post_comments") {
                        header("Authorization", "Bearer $accessToken")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        parameter("post_id", "eq.$postId")
                        parameter("order", "created_at.asc")
                        parameter("limit", "$limit")
                        parameter("select", "*")
                    }
                }

                if (response.status.isSuccess()) {
                    val rawComments: List<kotlinx.serialization.json.JsonObject> = response.body()
                    var currentUserId = authService.getCurrentUserId()
                    
                    val comments = rawComments.map { json ->
                        fun getSafeString(key: String, base: JsonObject = json): String? {
                            var element = base[key]
                            if (element == null || element is kotlinx.serialization.json.JsonNull) return null
                            var str = try {
                                element.jsonPrimitive.contentOrNull ?: element.toString().removeSurrounding("\"")
                            } catch (e: Exception) {
                                element.toString().removeSurrounding("\"")
                            }
                            return if (str == "null" || str.isEmpty()) null else str
                        }

                        var id = getSafeString("id") ?: ""
                        var content = getSafeString("content") ?: ""
                        var createdAt = getSafeString("created_at") ?: ""
                        var cUserId = getSafeString("user_id") ?: ""
                        var parentId = getSafeString("parent_id")
                        var audioUrl = getSafeString("media_url")
                        
                        // Fallback: If content starts with [Audio] and contains a URL, and audioUrl is null
                        if (audioUrl == null && content.startsWith("[Audio]")) {
                            val possibleUrl = content.removePrefix("[Audio]").trim()
                            if (possibleUrl.startsWith("http")) {
                                audioUrl = possibleUrl
                                content = "[Audio]" // Clean up content for UI
                            }
                        }
                        
                        // Handle Profile Join (either Object or Array or Null)
                        val profileElement = json["profiles"]
                        val profileJson = when (profileElement) {
                            is kotlinx.serialization.json.JsonObject -> profileElement
                            is kotlinx.serialization.json.JsonArray -> profileElement.firstOrNull() as? kotlinx.serialization.json.JsonObject
                            else -> null
                        }
                        
                        var userName = getSafeString("username", profileJson ?: JsonObject(emptyMap())) 
                            ?: getSafeString("full_name", profileJson ?: JsonObject(emptyMap())) 
                            ?: "Usuario"
                        var userAvatar = getSafeString("avatar_url", profileJson ?: JsonObject(emptyMap()))
                        
                        var isVerified = try {
                            profileJson?.get("is_verified")?.jsonPrimitive?.booleanOrNull ?: false
                        } catch (e: Exception) { false }

                        conectate.com.data.model.Comment(
                            id = id,
                            postId = postId,
                            userId = cUserId,
                            content = content,
                            createdAt = createdAt,
                            userName = userName,
                            userAvatar = userAvatar,
                            isVerified = isVerified,
                            isMine = cUserId == currentUserId,
                            parentId = parentId,
                            audioUrl = audioUrl
                        )
                    }
                    emit(comments)
                } else {
                    emit(emptyList())
                }
            } else {
                emit(emptyList())
            }
        } catch (e: Exception) {
            emit(emptyList())
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    suspend fun deleteComment(commentId: String): Result<Unit> {
        return try {
            android.util.Log.d("PostRepository", "deleteComment: commentId=$commentId")
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))

            val rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_delete_post_comment") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(buildJsonObject { put("p_comment_id", JsonPrimitive(commentId)) })
            }
            val rpcBody = runCatching { rpcResponse.body<String>() }.getOrDefault("")
            if (rpcResponse.status.isSuccess()) {
                val deletedCount = rpcBody.trim().trim('"').toIntOrNull() ?: 1
                if (deletedCount > 0) {
                    android.util.Log.d("PostRepository", "deleteComment RPC success: deleted=$deletedCount")
                    return Result.success(Unit)
                }
                return Result.failure(Exception("No se pudo eliminar el comentario."))
            }

            val missingRpc = (rpcResponse.status.value == 400 || rpcResponse.status.value == 404) &&
                rpcBody.lowercase().contains("secure_delete_post_comment")
            if (!missingRpc) {
                return Result.failure(Exception("No tienes permisos para eliminar este comentario."))
            }

            // Legacy fallback for environments without secure_delete_post_comment
            var response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/post_comments") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Prefer", "return=representation")
                parameter("id", "eq.$commentId")
            }

            if (response.status.isSuccess()) {
                val deletedItems: List<kotlinx.serialization.json.JsonObject> = response.body()
                if (deletedItems.isNotEmpty()) {
                    android.util.Log.d("PostRepository", "deleteComment: SUCCESS - deleted count=${deletedItems.size}")
                    Result.success(Unit)
                } else {
                    android.util.Log.w("PostRepository", "deleteComment: FAILED - Item not found or permission denied (RLS)")
                    Result.failure(Exception("No se pudo eliminar el comentario. Debes ser autor o dueño de la publicación."))
                }
            } else {
                android.util.Log.e("PostRepository", "deleteComment: FAILED - status=${response.status}")
                Result.failure(Exception("Error al eliminar: ${response.status}"))
            }
        } catch (e: Exception) {
            android.util.Log.e("PostRepository", "deleteComment: EXCEPTION", e)
            Result.failure(e)
        }
    }

    private suspend fun notifyMentionsForPostComment(
        postId: String,
        commentId: String,
        parentId: String?,
        commentContent: String,
        actorId: String,
        alreadyNotifiedUserIds: Set<String>
    ) {
        val mentionUsernames = extractMentionUsernames(commentContent)
        if (mentionUsernames.isEmpty()) return

        val mentionUsers = resolveMentionUsers(mentionUsernames)
        if (mentionUsers.isEmpty()) return

        mentionUsers
            .asSequence()
            .filter { it.id.isNotBlank() && it.id != actorId }
            .filterNot { alreadyNotifiedUserIds.contains(it.id) }
            .forEach { mentionedUser ->
                val blocked = userRepository.isBlocked(mentionedUser.id).getOrElse { false }
                if (blocked) return@forEach

                notificationRepository.sendNotification(
                    recipientId = mentionedUser.id,
                    type = "mention_post",
                    targetId = postId,
                    content = commentContent.take(160),
                    extraData = buildJsonObject {
                        put("comment_id", JsonPrimitive(commentId))
                        if (parentId != null) put("parent_id", JsonPrimitive(parentId))
                    }
                )
            }
    }

    private suspend fun resolveMentionUsers(mentionUsernames: Set<String>): List<conectate.com.data.model.User> {
        val resolved = mutableListOf<conectate.com.data.model.User>()
        mentionUsernames.take(8).forEach { username ->
            userRepository.searchUsers(username).onSuccess { users ->
                val exact = users.firstOrNull {
                    !it.username.isNullOrBlank() && it.username.equals(username, ignoreCase = true)
                }
                if (exact != null && userRepository.isMentionableUser(exact)) {
                    resolved += exact
                }
            }
        }
        return resolved.distinctBy { it.id }
    }

    private suspend fun resolvePostCommentOwnerId(commentId: String): String? {
        val token = authService.getAccessToken() ?: return null
        val cleanId = commentId.trim()
        if (cleanId.isBlank()) return null

        return runCatching {
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/post_comments") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("id", "eq.$cleanId")
                parameter("select", "user_id")
                parameter("limit", "1")
            }
            if (!response.status.isSuccess()) return@runCatching null
            val jsonArray = Json.parseToJsonElement(response.bodyAsText()) as? JsonArray ?: return@runCatching null
            jsonArray.firstOrNull()
                ?.jsonObject
                ?.get("user_id")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.ifBlank { null }
        }.getOrNull()
    }

    private fun extractMentionUsernames(content: String): Set<String> {
        val mentionRegex = Regex("(?<![A-Za-z0-9_])@([A-Za-z0-9._]{2,30})")
        return mentionRegex.findAll(content)
            .map { it.groupValues[1].trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private data class MediaPayload(
        val bytes: ByteArray,
        val contentType: String,
        val extension: String
    )

    private fun readMediaPayload(uri: Uri): MediaPayload? {
        var bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty()) return null

        val detectedContentType = context.contentResolver.getType(uri)
            ?: guessMimeFromUri(uri)
            ?: "application/octet-stream"

        var extension = MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(detectedContentType)
            ?.ifBlank { null }
            ?: uri.lastPathSegment?.substringAfterLast('.', "")
                ?.lowercase()
                ?.ifBlank { null }
            ?: "bin"

        return MediaPayload(
            bytes = bytes,
            contentType = detectedContentType,
            extension = extension
        )
    }

    private fun guessMimeFromUri(uri: Uri): String? {
        var extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            ?.lowercase()
            ?.ifBlank { null }
            ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    @Serializable
    private data class PostInsertRequest(
        @SerialName("user_id") val userId: String,
        @SerialName("user_name") val userName: String,
        @SerialName("user_profile_image") val userProfileImage: String?,
        @SerialName("content") val content: String,
        @SerialName("media_urls") val mediaUrls: List<String>,
        @SerialName("is_verified") val isVerified: Boolean,
        @SerialName("visibility") val visibility: String = "public",
        @SerialName("tags") val tags: List<String> = emptyList()
    )
}





