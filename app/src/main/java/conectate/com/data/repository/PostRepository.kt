package conectate.com.data.repository

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import conectate.com.core.moderation.ContentModerationService
import conectate.com.core.moderation.ModerationContext
import conectate.com.core.moderation.ModerationVerdict
import conectate.com.core.observability.AppMetrics
import conectate.com.data.cache.UpstashCacheService
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.UbuntuStorageUrl
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
    private val localFallbackApiClient: LocalFallbackApiClient,
    @ApplicationContext private val context: Context
) {
    private val cacheJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    @Serializable
    private data class PostReactionRequest(
        @SerialName("post_id") val postId: String,
        @SerialName("user_id") val userId: String,
        @SerialName("reaction_type") val reactionType: String
    )

    private data class PostReactionSnapshot(
        val myReaction: String?,
        val counts: Map<String, Int>
    )

    private data class MutablePostReactionSnapshot(
        var myReaction: String? = null,
        val counts: MutableMap<String, Int> = mutableMapOf()
    )

    private val supportedPostReactions = setOf(
        "LIKE",
        "LOVE",
        "HAHA",
        "WOW",
        "SAD",
        "ANGRY",
        "CARE",
        "FIRE"
    )

    fun getGlobalPosts(offset: Int = 0, limit: Int = 20): Flow<List<Post>> = flow {
        var startedAt = System.currentTimeMillis()
        val cacheKey = "posts:global:offset=$offset:limit=$limit"
        
        try {
            if (supabaseConfig.isLocalFallbackPrimary) {
                val accessToken = authService.getAccessToken()
                if (accessToken == null) {
                    appMetrics.recordFailure(
                        metric = "posts_fetch",
                        attributes = mapOf("source" to "ubuntu", "reason" to "missing_access_token")
                    )
                    emit(emptyList())
                    return@flow
                }
                val result = localFallbackApiClient.loadFeed(accessToken, limit, offset)
                if (result.isFailure) {
                    val error = result.exceptionOrNull()
                    android.util.Log.e("PostRepository", "Error fetching Ubuntu feed", error)
                    appMetrics.recordFailure("posts_fetch", error, mapOf("source" to "ubuntu"))
                    emit(emptyList())
                    return@flow
                }
                val posts = result.getOrThrow().items
                appMetrics.incrementCounter(
                    metric = "posts_fetch_success",
                    attributes = mapOf("source" to "ubuntu", "count" to posts.size.toString())
                )
                emit(posts)
                return@flow
            }

            if (offset == 0) {
                val cachedJson = upstashCache.get(cacheKey)
                val cachedPosts = cachedJson
                    ?.let { runCatching { cacheJson.decodeFromString<List<Post>>(it) }.getOrNull() }
                    ?.takeIf { it.isNotEmpty() }
                if (cachedPosts != null) {
                    appMetrics.incrementCounter("posts_cache_hit")
                    emit(cachedPosts)
                }
            }

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
                    posts = enrichPostReactions(accessToken, posts, currentUserId)

                    if (offset == 0 && posts.isNotEmpty()) {
                        val stored = runCatching {
                            upstashCache.set(cacheKey, cacheJson.encodeToString(posts), 300)
                        }.getOrDefault(false)
                        if (stored) {
                            appMetrics.incrementCounter("posts_cache_store")
                        }
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
                if (supabaseConfig.isLocalFallbackPrimary) {
                    val result = localFallbackApiClient.loadFeed(
                        accessToken = accessToken,
                        limit = 120,
                        offset = 0,
                        userId = userId
                    )
                    if (result.isFailure) {
                        val error = result.exceptionOrNull()
                        android.util.Log.e("PostRepository", "Error fetching Ubuntu user posts", error)
                        emit(emptyList())
                        return@flow
                    }
                    val posts = result.getOrThrow().items
                    emit(posts)
                    return@flow
                }

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
                    posts = enrichPostReactions(accessToken, posts, currentUserId)
                    
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
                if (supabaseConfig.isLocalFallbackPrimary) {
                    val result = localFallbackApiClient.loadFeed(accessToken, limit = 120, offset = 0)
                    if (result.isFailure) {
                        android.util.Log.e("PostRepository", "Error fetching Ubuntu shared posts", result.exceptionOrNull())
                        emit(emptyList())
                        return@flow
                    }
                    val posts = result.getOrThrow().items
                        .filter { it.sharedByMe || userId == currentUserId && it.sharedByMe }
                    emit(posts)
                    return@flow
                }

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
                posts = enrichPostReactions(accessToken, posts, currentUserId)
                
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
            if (supabaseConfig.isLocalFallbackPrimary) {
                return@withContext localFallbackApiClient.loadPostDetail(accessToken, postId)
                    .map { it.post }
            }

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
                
                Result.success(enrichPostReactions(accessToken, listOf(enrichedPost), currentUserId).first())
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

    private suspend fun enrichPostReactions(
        accessToken: String,
        posts: List<Post>,
        currentUserId: String?
    ): List<Post> {
        if (posts.isEmpty()) return posts

        val snapshots = getPostReactionSnapshots(
            accessToken = accessToken,
            postIds = posts.map { it.id },
            currentUserId = currentUserId
        )

        return posts.map { post ->
            val snapshot = snapshots[post.id]
            val detailedCounts = snapshot?.counts.orEmpty()
            val legacyReaction = if (post.likedByMe) "LIKE" else null
            val fallbackCounts = if (post.likeCount > 0) mapOf("LIKE" to post.likeCount) else emptyMap()
            val reactionCounts = detailedCounts.takeIf { it.isNotEmpty() } ?: fallbackCounts
            val reactionTotal = reactionCounts.values.sum()

            post.copy(
                likedByMe = post.likedByMe || snapshot?.myReaction != null,
                myReaction = snapshot?.myReaction ?: post.myReaction ?: legacyReaction,
                reactionCounts = reactionCounts,
                likeCount = maxOf(post.likeCount, reactionTotal)
            )
        }
    }

    private suspend fun getPostReactionSnapshots(
        accessToken: String,
        postIds: List<String>,
        currentUserId: String?
    ): Map<String, PostReactionSnapshot> {
        val cleanPostIds = postIds
            .map { it.trim() }
            .filter { it.matches(Regex("^[0-9a-fA-F-]{32,36}$")) }
            .distinct()

        if (cleanPostIds.isEmpty()) return emptyMap()

        return try {
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/post_reactions") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("select", "post_id,user_id,reaction_type")
                parameter("post_id", cleanPostIds.joinToString(prefix = "in.(", postfix = ")"))
            }

            if (!response.status.isSuccess()) {
                android.util.Log.w("PostRepository", "post_reactions unavailable: ${response.status}")
                return emptyMap()
            }

            val rows: List<JsonObject> = response.body()
            val snapshots = mutableMapOf<String, MutablePostReactionSnapshot>()
            rows.forEach { row ->
                val postId = row["post_id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val userId = row["user_id"]?.jsonPrimitive?.contentOrNull
                val reaction = normalizePostReaction(row["reaction_type"]?.jsonPrimitive?.contentOrNull)
                    ?: return@forEach

                val snapshot = snapshots.getOrPut(postId) { MutablePostReactionSnapshot() }
                snapshot.counts[reaction] = (snapshot.counts[reaction] ?: 0) + 1
                if (currentUserId != null && userId == currentUserId) {
                    snapshot.myReaction = reaction
                }
            }

            snapshots.mapValues { (_, snapshot) ->
                PostReactionSnapshot(
                    myReaction = snapshot.myReaction,
                    counts = snapshot.counts.toMap()
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("PostRepository", "Failed to load post reactions", e)
            emptyMap()
        }
    }

    private fun normalizePostReaction(reactionType: String?): String? {
        val normalized = reactionType
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return normalized.takeIf { it in supportedPostReactions }
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
            var firstMediaType: String? = null
            
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
                        if (firstMediaType == null) {
                            firstMediaType = when {
                                mediaPayload.contentType.startsWith("image/") -> "image"
                                mediaPayload.contentType.startsWith("video/") -> "video"
                                mediaPayload.contentType.startsWith("audio/") -> "audio"
                                else -> "file"
                            }
                        }
                        UbuntuStorageUrl.rewrite(storageService.getPublicUrl("post-media", fileName))
                            ?.let { mediaUrls.add(it) }
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
            val userImage: String? = UbuntuStorageUrl.rewrite(currentProfile?.avatar_url)
            var isVerified = currentProfile?.is_verified ?: false

            if (supabaseConfig.isLocalFallbackPrimary) {
                val created = localFallbackApiClient.createPost(
                    accessToken = accessToken,
                    content = sanitizedContent,
                    mediaUrls = mediaUrls,
                    mediaType = firstMediaType,
                    visibility = visibility
                )
                if (created.isSuccess) {
                    appMetrics.incrementCounter(
                        metric = "post_create_success",
                        attributes = mapOf("source" to "ubuntu", "media_count" to mediaUrls.size.toString())
                    )
                    appMetrics.recordLatency(
                        metric = "post_create",
                        durationMs = System.currentTimeMillis() - startedAt,
                        attributes = mapOf("source" to "ubuntu", "media_count" to mediaUrls.size.toString())
                    )
                    return created
                }
                appMetrics.recordFailure("post_create", created.exceptionOrNull(), mapOf("source" to "ubuntu"))
                return Result.failure(created.exceptionOrNull() ?: Exception("No se pudo crear la publicación en Ubuntu"))
            }

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
        return setPostReaction(
            postId = postId,
            reactionType = if (isLiked) null else "LIKE",
            wasReacted = isLiked
        )
    }

    suspend fun setPostReaction(
        postId: String,
        reactionType: String?,
        wasReacted: Boolean
    ): Result<Unit> {
        return try {
            var userId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            val normalizedReaction = normalizePostReaction(reactionType)

            if (supabaseConfig.isLocalFallbackPrimary) {
                val result = localFallbackApiClient.setPostReaction(accessToken, postId, normalizedReaction)
                if (result.isSuccess) {
                    invalidateGlobalPostCache()
                    return Result.success(Unit)
                }
                return Result.failure(result.exceptionOrNull() ?: Exception("No se pudo guardar la reacción en Ubuntu"))
            }

            if (normalizedReaction == null) {
                val detailedResult = deletePostReactionRow(accessToken, userId, postId)
                val legacyResult = if (wasReacted) {
                    deleteLegacyPostLike(accessToken, userId, postId)
                } else {
                    Result.success(Unit)
                }

                if (detailedResult.isFailure && legacyResult.isFailure) {
                    return Result.failure(
                        legacyResult.exceptionOrNull()
                            ?: detailedResult.exceptionOrNull()
                            ?: Exception("No se pudo quitar la reacción")
                    )
                }
            } else {
                val detailedResult = upsertPostReactionRow(
                    accessToken = accessToken,
                    userId = userId,
                    postId = postId,
                    reactionType = normalizedReaction
                )
                val legacyResult = if (!wasReacted) {
                    insertLegacyPostLike(accessToken, userId, postId)
                } else {
                    Result.success(Unit)
                }

                if (detailedResult.isFailure && legacyResult.isFailure) {
                    return Result.failure(
                        legacyResult.exceptionOrNull()
                            ?: detailedResult.exceptionOrNull()
                            ?: Exception("No se pudo guardar la reacción")
                    )
                }
            }

            invalidateGlobalPostCache()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun upsertPostReactionRow(
        accessToken: String,
        userId: String,
        postId: String,
        reactionType: String
    ): Result<Unit> {
        return try {
            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/post_reactions") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "resolution=merge-duplicates")
                parameter("on_conflict", "post_id,user_id")
                setBody(
                    PostReactionRequest(
                        postId = postId,
                        userId = userId,
                        reactionType = reactionType
                    )
                )
            }

            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                val body = response.bodyAsText()
                if (isPostReactionSchemaMissing(response.status.value, body)) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("post_reactions upsert failed: ${response.status} $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun deletePostReactionRow(
        accessToken: String,
        userId: String,
        postId: String
    ): Result<Unit> {
        return try {
            val response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/post_reactions") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("post_id", "eq.$postId")
                parameter("user_id", "eq.$userId")
            }

            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                val body = response.bodyAsText()
                if (isPostReactionSchemaMissing(response.status.value, body)) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("post_reactions delete failed: ${response.status} $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun insertLegacyPostLike(
        accessToken: String,
        userId: String,
        postId: String
    ): Result<Unit> {
        return try {
            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/post_likes") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "resolution=ignore-duplicates")
                parameter("on_conflict", "post_id,user_id")
                setBody(mapOf("post_id" to postId, "user_id" to userId))
            }

            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                val body = response.bodyAsText()
                Result.failure(Exception("post_likes insert failed: ${response.status} $body"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun deleteLegacyPostLike(
        accessToken: String,
        userId: String,
        postId: String
    ): Result<Unit> {
        return try {
            val response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/post_likes") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("post_id", "eq.$postId")
                parameter("user_id", "eq.$userId")
            }

            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                val body = response.bodyAsText()
                Result.failure(Exception("post_likes delete failed: ${response.status} $body"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isPostReactionSchemaMissing(statusCode: Int, body: String): Boolean {
        val lowerBody = body.lowercase()
        return statusCode == 404 ||
            lowerBody.contains("post_reactions") && (
                lowerBody.contains("does not exist") ||
                    lowerBody.contains("schema cache") ||
                    lowerBody.contains("could not find")
            )
    }

    private suspend fun invalidateGlobalPostCache() {
        runCatching {
            upstashCache.delete("posts:global:offset=0:limit=20")
        }
    }

    suspend fun deletePost(postId: String): Result<Unit> {
        return try {
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            if (supabaseConfig.isLocalFallbackPrimary) {
                return localFallbackApiClient.deletePost(accessToken, postId)
            }
            
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
            if (supabaseConfig.isLocalFallbackPrimary) {
                return localFallbackApiClient.updatePost(
                    accessToken = accessToken,
                    postId = postId,
                    visibility = visibility
                ).map { Unit }
            }

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
            if (supabaseConfig.isLocalFallbackPrimary) {
                return localFallbackApiClient.updatePost(
                    accessToken = accessToken,
                    postId = postId,
                    content = content
                ).map { Unit }
            }

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

            if (supabaseConfig.isLocalFallbackPrimary) {
                val result = localFallbackApiClient.addPostComment(
                    accessToken = accessToken,
                    postId = postId,
                    content = sanitizedContent,
                    parentId = parentId
                )
                if (result.isSuccess) {
                    appMetrics.incrementCounter(
                        metric = "post_comment_create_success",
                        attributes = mapOf("source" to "ubuntu", "has_parent" to (parentId != null).toString())
                    )
                    appMetrics.recordLatency(
                        metric = "post_comment_create",
                        durationMs = System.currentTimeMillis() - startedAt,
                        attributes = mapOf("source" to "ubuntu", "has_parent" to (parentId != null).toString())
                    )
                    return@withContext result
                }
                appMetrics.recordFailure("post_comment_create", result.exceptionOrNull(), mapOf("source" to "ubuntu"))
                return@withContext Result.failure(result.exceptionOrNull() ?: Exception("No se pudo comentar en Ubuntu"))
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
            val userId = authService.getCurrentUserId()?.trim()?.lowercase()
                ?: return Result.failure(Exception("No user"))
            
            // Upload Audio
            var bytes = audioFile.readBytes()
            var fileName = "$userId/comment_${UUID.randomUUID()}.m4a"
            var uploadResult = storageService.uploadFile("post-comments-media", fileName, bytes, "audio/mp4")
            
            if (uploadResult.isSuccess) {
                 val publicUrl = UbuntuStorageUrl.rewrite(storageService.getPublicUrl("post-comments-media", fileName)).orEmpty()
                 
                 // add_audio_to_post_comments.sql is part of the release manifest, so audio comments
                 // store both a stable text marker and the media metadata columns.
                 
                 var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))

                 if (supabaseConfig.isLocalFallbackPrimary) {
                    return localFallbackApiClient.addPostComment(
                        accessToken = accessToken,
                        postId = postId,
                        content = "[Audio]",
                        mediaUrl = publicUrl,
                        mediaType = "audio"
                    ).map { Unit }
                 }

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
                     var errorBody = response.body<String>()
                     if (errorBody.contains("undefined column")) {
                         // Graceful fallback for devices pointed to a DB that has not run the manifest yet.
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
                if (supabaseConfig.isLocalFallbackPrimary) {
                    val result = localFallbackApiClient.loadPostDetail(accessToken, postId)
                    if (result.isSuccess) {
                        emit(result.getOrThrow().comments.take(limit))
                    } else {
                        android.util.Log.e("PostRepository", "Error fetching Ubuntu post comments", result.exceptionOrNull())
                        emit(emptyList())
                    }
                    return@flow
                }

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
                        var audioUrl = UbuntuStorageUrl.rewrite(getSafeString("media_url"))
                        
                        // Fallback: If content starts with [Audio] and contains a URL, and audioUrl is null
                        if (audioUrl == null && content.startsWith("[Audio]")) {
                            val possibleUrl = content.removePrefix("[Audio]").trim()
                            if (possibleUrl.startsWith("http")) {
                                audioUrl = UbuntuStorageUrl.rewrite(possibleUrl)
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
                        var userAvatar = UbuntuStorageUrl.rewrite(getSafeString("avatar_url", profileJson ?: JsonObject(emptyMap())))
                        
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
            if (supabaseConfig.isLocalFallbackPrimary) {
                return localFallbackApiClient.deletePostComment(accessToken, commentId)
            }

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





