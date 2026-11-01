package conectate.com.data.repository

import conectate.com.core.media.readMediaBytesWithLimit

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import conectate.com.core.moderation.ContentModerationService
import conectate.com.core.moderation.ModerationContext
import conectate.com.core.moderation.ModerationVerdict
import conectate.com.core.observability.AppMetrics
import conectate.com.data.cache.UpstashCacheService
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.SavedCollection
import conectate.com.data.localfallback.SavedLibraryResponse
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.model.Post
import conectate.com.data.model.PostReactionUser
import conectate.com.data.repository.HashtagRepository
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.data.supabase.StorageService
import conectate.com.util.canonicalizeMediaContentType
import conectate.com.util.inferMimeTypeFromBytes
import conectate.com.util.preferredExtensionForMime
import conectate.com.util.uploadReportEvidence
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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

sealed interface PostMutationEvent {
    val postId: String

    data class Deleted(override val postId: String) : PostMutationEvent
    data class ContentUpdated(override val postId: String, val content: String) : PostMutationEvent
    data class VisibilityUpdated(override val postId: String, val visibility: String) : PostMutationEvent
    data class RemixPermissionUpdated(
        override val postId: String,
        val allowed: Boolean,
        val audience: String
    ) : PostMutationEvent
}

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
    @param:ApplicationContext private val context: Context
) {
    /**
     * Keep a local-fallback session on its own API even during the short
     * startup window in which endpoint discovery has not yet promoted the
     * runtime URL to the primary Supabase facade.
     */
    private fun usesLocalFallbackBackend(): Boolean =
        localFallbackApiClient.isConfigured &&
            (supabaseConfig.isLocalFallbackPrimary || authService.isLocalFallbackSession())

    private val cacheJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }
    // The feed must remain useful when the API is slow or temporarily offline.
    // Keep metadata account-scoped so one signed-in account never sees another
    // account's personalized state on the same device.
    private val localFeedCache by lazy {
        context.getSharedPreferences("social_feed_metadata_cache", Context.MODE_PRIVATE)
    }
    private val _mutationEvents = MutableSharedFlow<PostMutationEvent>(extraBufferCapacity = 32)
    val mutationEvents: SharedFlow<PostMutationEvent> = _mutationEvents.asSharedFlow()
    private val _globalFeedError = MutableStateFlow<String?>(null)
    val globalFeedError: StateFlow<String?> = _globalFeedError.asStateFlow()

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

    private data class PostReactionEntry(
        val postId: String,
        val userId: String,
        val reactionType: String,
        val reactedAt: String?
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
    private val maxPostMediaBytes = 25L * 1024 * 1024

    private fun localFeedKey(mode: String, ownerId: String? = authService.getCurrentUserId()): String {
        val accountId = ownerId?.trim()?.lowercase().orEmpty().ifBlank { "guest" }
        return "$mode:$accountId"
    }

    private fun readLocalFeed(mode: String, ownerId: String? = authService.getCurrentUserId()): List<Post> = runCatching {
        localFeedCache.getString(localFeedKey(mode, ownerId), null)
            ?.let { cacheJson.decodeFromString<List<Post>>(it) }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun persistLocalFeed(mode: String, posts: List<Post>, ownerId: String? = authService.getCurrentUserId()) {
        if (ownerId != authService.getCurrentUserId()) return
        runCatching {
            localFeedCache.edit()
                .putString(localFeedKey(mode, ownerId), cacheJson.encodeToString(posts.take(60)))
                .apply()
        }.onFailure { error ->
            android.util.Log.w("PostRepository", "No se pudo guardar la caché local del feed", error)
        }
    }

    /** Returns the last usable feed snapshot without touching the network. */
    suspend fun getCachedGlobalPosts(mode: String = "discover"): List<Post> {
        val normalizedMode = mode.trim().lowercase().takeIf { it in setOf("discover", "following") }
            ?: "discover"
        return conectate.com.core.cache.readLocalAccountSnapshot(authService::getCurrentUserId) { owner ->
            readLocalFeed(normalizedMode, owner)
        }.orEmpty()
    }

    suspend fun searchPublicContent(
        query: String,
        limit: Int = 24
    ): Result<List<conectate.com.data.model.SemanticSearchResult>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val accessToken = authService.getAccessToken()
                ?: return@withContext Result.failure(
                    IllegalStateException("Necesitas iniciar sesión para buscar contenido.")
                )
            localFallbackApiClient.searchPublicContent(accessToken, query, limit)
                .map { response -> response.items }
        }

    suspend fun searchPublicContentPage(
        query: String,
        limit: Int = 20,
        offset: Int = 0,
    ): Result<PublicContentSearchPage> = withContext(Dispatchers.IO) {
        val account = authService.getCurrentUserId()
            ?: return@withContext Result.failure(IllegalStateException("Necesitas iniciar sesión para buscar contenido."))
        val accessToken = authService.ensureValidAccessToken().getOrElse { return@withContext Result.failure(it) }
        if (account != authService.getCurrentUserId()) throw CancellationException("La cuenta cambió durante la búsqueda.")
        val result = localFallbackApiClient.searchPublicContentPage(accessToken, query, limit, offset)
        if (account != authService.getCurrentUserId()) throw CancellationException("La cuenta cambió durante la búsqueda.")
        result.map { response ->
            PublicContentSearchPage(
                response.items.distinctBy { "${it.type}:${it.id}" },
                response.nextOffset?.takeIf { response.hasMore && it > offset },
            )
        }
    }

    suspend fun getSavedLibrary(query: String = ""): Result<SavedLibraryResponse> {
        val accessToken = authService.getAccessToken()
            ?: return Result.failure(IllegalStateException("Necesitas iniciar sesión para ver tus guardados."))
        return localFallbackApiClient.loadSavedLibrary(accessToken, query)
    }

    suspend fun createSavedCollection(name: String): Result<SavedCollection> {
        val accessToken = authService.getAccessToken()
            ?: return Result.failure(IllegalStateException("Necesitas iniciar sesión para crear una colección."))
        return localFallbackApiClient.createSavedCollection(accessToken, name)
    }

    suspend fun savePostToCollection(
        postId: String,
        collectionId: String,
        tags: List<String>,
        note: String
    ): Result<Unit> {
        val accessToken = authService.getAccessToken()
            ?: return Result.failure(IllegalStateException("Necesitas iniciar sesión para guardar publicaciones."))
        return localFallbackApiClient.savePostToCollection(
            accessToken = accessToken,
            collectionId = collectionId,
            postId = postId,
            tags = tags,
            note = note
        )
    }

    suspend fun removeSavedPost(postId: String, collectionId: String): Result<Unit> {
        val accessToken = authService.getAccessToken()
            ?: return Result.failure(IllegalStateException("Necesitas iniciar sesión para editar tus guardados."))
        return localFallbackApiClient.removeSavedPost(accessToken, collectionId, postId)
    }

    internal suspend fun getGlobalPostPage(offset: Int = 0, limit: Int = 60,
        mode: String = "discover"): Result<GlobalPostPage> = withContext(Dispatchers.IO) {
        try {
            if (usesLocalFallbackBackend()) {
                val owner = authService.getCurrentUserId()
                val token = authService.getAccessToken()
                    ?: return@withContext Result.failure(IllegalStateException("Tu sesión no está disponible. Vuelve a iniciar sesión."))
                val result = localFallbackApiClient.loadFeed(token, limit, offset, mode = mode)
                result.exceptionOrNull()?.let {
                    _globalFeedError.value = it.message ?: "No se pudo cargar el feed público."
                    return@withContext Result.failure(it)
                }
                val page = result.getOrThrow()
                _globalFeedError.value = null
                if (offset == 0) persistLocalFeed(mode, page.items, owner)
                Result.success(GlobalPostPage(page.items, page.nextOffset))
            } else {
                val items = getGlobalPosts(offset, limit, mode, emitCachedSnapshots = false).last()
                val error = _globalFeedError.value
                if (error != null) Result.failure(IllegalStateException(error))
                else Result.success(GlobalPostPage(items,
                    (offset + items.size).takeIf { items.size >= limit && it <= 10_000 }))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }

    fun getGlobalPosts(
        offset: Int = 0,
        limit: Int = 20,
        mode: String = "discover",
        emitCachedSnapshots: Boolean = true,
    ): Flow<List<Post>> = flow {
        var startedAt = System.currentTimeMillis()
        val feedOwnerId = authService.getCurrentUserId()
        val normalizedMode = mode.trim().lowercase().takeIf { it in setOf("discover", "following") }
            ?: "discover"
        val cacheKey = "posts:$normalizedMode:offset=$offset:limit=$limit"
        val localSnapshot = if (offset == 0 && emitCachedSnapshots) readLocalFeed(normalizedMode) else emptyList()
        var hasFallbackEmission = false

        if (localSnapshot.isNotEmpty()) {
            emit(localSnapshot)
            hasFallbackEmission = true
        }
        
        try {
            _globalFeedError.value = null
            if (usesLocalFallbackBackend()) {
                val accessToken = authService.getAccessToken()
                if (accessToken == null) {
                    _globalFeedError.value = "Tu sesión no está disponible. Vuelve a iniciar sesión."
                    appMetrics.recordFailure(
                        metric = "posts_fetch",
                        attributes = mapOf("source" to "ubuntu", "reason" to "missing_access_token")
                    )
                    if (!hasFallbackEmission) emit(emptyList())
                    return@flow
                }
                val result = localFallbackApiClient.loadFeed(
                    accessToken = accessToken,
                    limit = limit,
                    offset = offset,
                    mode = normalizedMode
                )
                if (result.isFailure) {
                    val error = result.exceptionOrNull()
                    _globalFeedError.value =
                        error?.message?.takeIf { it.isNotBlank() }
                            ?: "No se pudo cargar el feed público."
                    android.util.Log.e("PostRepository", "Error fetching Ubuntu feed", error)
                    appMetrics.recordFailure("posts_fetch", error, mapOf("source" to "ubuntu"))
                    if (!hasFallbackEmission) emit(emptyList())
                    return@flow
                }
                val posts = result.getOrThrow().items
                _globalFeedError.value = null
                if (offset == 0) persistLocalFeed(normalizedMode, posts, feedOwnerId)
                appMetrics.incrementCounter(
                    metric = "posts_fetch_success",
                    attributes = mapOf("source" to "ubuntu", "count" to posts.size.toString())
                )
                if (posts.isNotEmpty() || !hasFallbackEmission) emit(posts)
                return@flow
            }

            if (offset == 0 && emitCachedSnapshots) {
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

                    if (offset == 0) {
                        persistLocalFeed(normalizedMode, posts, feedOwnerId)
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
                    _globalFeedError.value = "El servidor no pudo cargar las publicaciones."
                    appMetrics.recordFailure(
                        metric = "posts_fetch",
                        attributes = mapOf("source" to "global", "http_status" to response.status.value.toString())
                    )
                    if (!hasFallbackEmission) emit(emptyList())
                }
            } else {
                _globalFeedError.value = "Tu sesión no está disponible. Vuelve a iniciar sesión."
                appMetrics.recordFailure(
                    metric = "posts_fetch",
                    attributes = mapOf("source" to "global", "reason" to "missing_access_token")
                )
                if (!hasFallbackEmission) emit(emptyList())
            }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.e("PostRepository", "Error fetching global posts", e)
                _globalFeedError.value =
                    e.message?.takeIf { it.isNotBlank() } ?: "No se pudo cargar el feed público."
                appMetrics.recordFailure("posts_fetch", e, mapOf("source" to "global"))
                if (!hasFallbackEmission) emit(emptyList())
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
                    val result = localFallbackApiClient.loadFeed(accessToken, limit = 40, offset = 0, sharedBy = userId)
                    if (result.isFailure) {
                        android.util.Log.e("PostRepository", "Error fetching Ubuntu shared posts", result.exceptionOrNull())
                        throw result.exceptionOrNull() ?: IllegalStateException("No se pudieron cargar los compartidos")
                    }
                    val posts = result.getOrThrow().items
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
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            android.util.Log.e("PostRepository", "Error fetching shared posts", e)
            throw e
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.IO)

    suspend fun getSharedPostsPage(userId: String, offset: Int): Result<conectate.com.data.localfallback.LocalFallbackFeedResponse> =
        withContext(Dispatchers.IO) {
            try {
                if (usesLocalFallbackBackend()) {
                    val token = authService.getAccessTokenAwait()
                        ?: return@withContext Result.failure(IllegalStateException("Inicia sesión para ver los compartidos."))
                    localFallbackApiClient.loadFeed(token, limit = 40, offset = offset, sharedBy = userId)
                } else {
                    val all = getSharedPosts(userId).last()
                    val items = all.drop(offset).take(40)
                    val next = (offset + items.size).takeIf { it < all.size }
                    Result.success(conectate.com.data.localfallback.LocalFallbackFeedResponse(items, next != null, next))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
        }



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
                // post_likes is authoritative for whether the account liked
                // this post. A stale optional reaction row must not revive
                // a like after the canonical row was removed.
                likedByMe = post.likedByMe,
                myReaction = if (post.likedByMe) snapshot?.myReaction ?: post.myReaction ?: legacyReaction else null,
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

        val entries = fetchPostReactionEntries(accessToken, cleanPostIds)
            .getOrElse { error ->
                android.util.Log.w("PostRepository", "Failed to load post reactions", error)
                return emptyMap()
            }
        val snapshots = mutableMapOf<String, MutablePostReactionSnapshot>()
        entries.forEach { entry ->
            val snapshot = snapshots.getOrPut(entry.postId) { MutablePostReactionSnapshot() }
            snapshot.counts[entry.reactionType] = (snapshot.counts[entry.reactionType] ?: 0) + 1
            if (currentUserId != null && entry.userId.equals(currentUserId, ignoreCase = true)) {
                snapshot.myReaction = entry.reactionType
            }
        }

        return snapshots.mapValues { (_, snapshot) ->
            PostReactionSnapshot(
                myReaction = snapshot.myReaction,
                counts = snapshot.counts.toMap()
            )
        }
    }

    private suspend fun fetchPostReactionEntries(
        accessToken: String,
        postIds: List<String>
    ): Result<List<PostReactionEntry>> {
        return try {
            suspend fun fetchRows(table: String, select: String): Pair<Boolean, List<JsonObject>> {
                val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/$table") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("select", select)
                    parameter("post_id", postIds.joinToString(prefix = "in.(", postfix = ")"))
                    parameter("limit", "10000")
                }
                if (!response.status.isSuccess()) {
                    android.util.Log.w("PostRepository", "$table unavailable: ${response.status}")
                    return false to emptyList()
                }
                return true to response.body<List<JsonObject>>()
            }

            val (likesReadable, likeRows) = fetchRows("post_likes", "post_id,user_id,created_at")
            val (detailsReadable, detailedRows) = fetchRows(
                "post_reactions",
                "post_id,user_id,reaction_type,created_at,updated_at"
            )
            if (!likesReadable && !detailsReadable) {
                return Result.failure(Exception("No se pudieron leer las reacciones de la publicación."))
            }

            val byPostAndUser = linkedMapOf<Pair<String, String>, PostReactionEntry>()
            likeRows.forEach { row ->
                val postId = row["post_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val userId = row["user_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (postId.isBlank() || userId.isBlank()) return@forEach
                byPostAndUser[postId.lowercase() to userId.lowercase()] = PostReactionEntry(
                    postId = postId,
                    userId = userId,
                    reactionType = "LIKE",
                    reactedAt = row["created_at"]?.jsonPrimitive?.contentOrNull
                )
            }
            detailedRows.forEach { row ->
                val postId = row["post_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val userId = row["user_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val reaction = normalizePostReaction(row["reaction_type"]?.jsonPrimitive?.contentOrNull)
                    ?: return@forEach
                if (postId.isBlank() || userId.isBlank()) return@forEach
                val key = postId.lowercase() to userId.lowercase()
                // post_likes is the durable cross-client source of truth. If
                // that table can be read, only active likes may contribute;
                // otherwise old detail rows could revive a removed reaction.
                if (likesReadable && key !in byPostAndUser) return@forEach
                byPostAndUser[key] = PostReactionEntry(
                    postId = postId,
                    userId = userId,
                    reactionType = reaction,
                    reactedAt = row["updated_at"]?.jsonPrimitive?.contentOrNull
                        ?: row["created_at"]?.jsonPrimitive?.contentOrNull
                )
            }
            Result.success(byPostAndUser.values.toList())
        } catch (error: Exception) {
            Result.failure(error)
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

    suspend fun createPost(
        content: String,
        mediaUris: List<Uri>,
        visibility: String = "public",
        remixAllowed: Boolean = false,
        remixAudience: String = "public",
        isSensitive: Boolean = false
    ): Result<Post> {
        var startedAt = System.currentTimeMillis()
        val uploadedObjects = mutableListOf<Pair<String, String>>()
        var metadataPersisted = false
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
                    var uploadResult = if (mediaPayload.sizeBytes > 0L) {
                        storageService.uploadFileFromUri(
                            bucketName = "post-media",
                            fileName = fileName,
                            sourceUri = uri,
                            sizeBytes = mediaPayload.sizeBytes,
                            contentType = mediaPayload.contentType,
                            allowOverwrite = true,
                            onProgress = {}
                        )
                    } else {
                        storageService.uploadFile(
                            bucketName = "post-media",
                            fileName = fileName,
                            bytes = mediaPayload.bytes ?: return Result.failure(Exception("No se pudo leer el archivo multimedia")),
                            contentType = mediaPayload.contentType
                        )
                    }

                    if (uploadResult.isSuccess) {
                        if (firstMediaType == null) {
                            firstMediaType = when {
                                mediaPayload.contentType.startsWith("image/") -> "image"
                                mediaPayload.contentType.startsWith("video/") -> "video"
                                mediaPayload.contentType.startsWith("audio/") -> "audio"
                                else -> "file"
                            }
                        }
                        val remoteKey = uploadResult.getOrThrow().Key ?: fileName
                        uploadedObjects += "post-media" to remoteKey
                        val publicUrl = UbuntuStorageUrl.rewrite(
                            storageService.getPublicUrl("post-media", remoteKey)
                        ).orEmpty()
                        if (publicUrl.isBlank()) {
                            return Result.failure(Exception("No se pudo obtener la URL del archivo subido"))
                        }
                        mediaUrls.add(publicUrl)
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
                    visibility = visibility,
                    remixAllowed = remixAllowed,
                    remixAudience = remixAudience,
                    isSensitive = isSensitive
                )
                if (created.isSuccess) {
                    metadataPersisted = true
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
                isSensitive = isSensitive,
                remixAllowed = remixAllowed,
                remixAudience = remixAudience,
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
                metadataPersisted = true
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
        } finally {
            if (!metadataPersisted) {
                cleanupUploadedPostObjects(uploadedObjects)
            }
        }
    }

    private suspend fun cleanupUploadedPostObjects(objects: List<Pair<String, String>>) {
        objects.asReversed().forEach { (bucket, key) ->
            storageService.deleteFile(bucket, key).onFailure { error ->
                android.util.Log.w("PostRepository", "No se pudo limpiar $bucket/$key", error)
            }
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
            val userId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))
            val accessToken = authService.ensureValidAccessToken().getOrThrow()
            if (authService.getCurrentUserId() != userId) {
                return Result.failure(IllegalStateException("La sesión cambió. Vuelve a intentar la reacción."))
            }
            val normalizedReaction = normalizePostReaction(reactionType)

            if (usesLocalFallbackBackend()) {
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

                // post_likes is the cross-client source of truth used by the
                // web feed and older Android clients. A missing optional
                // post_reactions table must not make an unlike look saved
                // when the canonical delete failed.
                if (legacyResult.isFailure) {
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

                // Do not report success based only on post_reactions: that
                // table is optional and is not read by the web Social feed.
                if (legacyResult.isFailure) {
                    return Result.failure(
                        legacyResult.exceptionOrNull()
                            ?: detailedResult.exceptionOrNull()
                            ?: Exception("No se pudo guardar la reacción")
                    )
                }
            }

            invalidateGlobalPostCache()
            Result.success(Unit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun summarizePostThread(
        postId: String,
        maxBullets: Int = 5
    ): Result<conectate.com.data.model.PostThreadSummary> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val accessToken = authService.getAccessToken()
                ?: return@withContext Result.failure(Exception("No hay una sesión activa."))
            if (!supabaseConfig.isLocalFallbackPrimary) {
                return@withContext Result.failure(
                    Exception("El resumen de conversaciones aún no está disponible en este servidor.")
                )
            }
            localFallbackApiClient.summarizePostThread(
                accessToken = accessToken,
                postId = postId,
                maxBullets = maxBullets
            )
        }

    suspend fun getPostReactions(
        postId: String,
        reactionType: String? = null
    ): Result<List<PostReactionUser>> {
        return try {
            val accessToken = authService.getAccessToken()
                ?: return Result.failure(Exception("Inicia sesión para ver las reacciones."))
            if (supabaseConfig.isLocalFallbackPrimary) {
                return localFallbackApiClient
                    .loadPostReactions(accessToken, postId, reactionType)
                    .map { it.items }
            }

            val normalizedType = normalizePostReaction(reactionType)
            val entries = fetchPostReactionEntries(accessToken, listOf(postId.trim()))
                .getOrElse { return Result.failure(it) }
                .filter { normalizedType == null || it.reactionType == normalizedType }
                .sortedByDescending { it.reactedAt.orEmpty() }
                .take(200)
            if (entries.isEmpty()) return Result.success(emptyList())

            val profileIds = entries.map { it.userId }.distinct()
            val profilesById = fetchReactionProfiles(accessToken, profileIds)
            Result.success(entries.map { entry ->
                val profile = profilesById[entry.userId.lowercase()]
                val name = sequenceOf(
                    profile?.get("display_name")?.jsonPrimitive?.contentOrNull,
                    profile?.get("full_name")?.jsonPrimitive?.contentOrNull,
                    profile?.get("username")?.jsonPrimitive?.contentOrNull,
                ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }.firstOrNull() ?: "Usuario"
                PostReactionUser(
                    userId = entry.userId,
                    userName = name,
                    userAvatarUrl = UbuntuStorageUrl.rewrite(
                        profile?.get("avatar_url")?.jsonPrimitive?.contentOrNull
                    ),
                    isVerified = profile?.get("is_verified")?.jsonPrimitive?.booleanOrNull ?: false,
                    reactionType = entry.reactionType,
                    reactedAt = entry.reactedAt
                )
            })
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun fetchReactionProfiles(
        accessToken: String,
        userIds: List<String>
    ): Map<String, JsonObject> {
        val cleanIds = userIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (cleanIds.isEmpty()) return emptyMap()

        suspend fun fetch(select: String): List<JsonObject> {
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("select", select)
                parameter("id", cleanIds.joinToString(prefix = "in.(", postfix = ")"))
                parameter("limit", cleanIds.size.toString())
            }
            if (!response.status.isSuccess()) return emptyList()
            return response.body<List<JsonObject>>()
        }

        return runCatching {
            val fullRows = fetch("id,display_name,full_name,username,avatar_url,is_verified")
            val rows = if (fullRows.isNotEmpty()) fullRows else fetch("id,username,avatar_url,is_verified")
            rows.mapNotNull { row ->
                val id = row["id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                    ?: row["user_id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                    ?: return@mapNotNull null
                id to row
            }.toMap()
        }.getOrDefault(emptyMap())
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
                val result = localFallbackApiClient.deletePost(accessToken, postId)
                result.onSuccess { _mutationEvents.tryEmit(PostMutationEvent.Deleted(postId)) }
                return result
            }
            
            val response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/posts") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Prefer", "return=representation")
                parameter("id", "eq.$postId")
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                if (body.trim() == "[]") {
                    Result.failure(Exception("No se pudo eliminar (la publicación no existe o no tienes permisos)"))
                } else {
                    invalidateGlobalPostCache()
                    _mutationEvents.tryEmit(PostMutationEvent.Deleted(postId))
                    Result.success(Unit)
                }
            } else {
                Result.failure(Exception("Error HTTP ${response.status.value}: $body"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updatePostVisibility(postId: String, visibility: String): Result<Unit> {
        return try {
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            if (supabaseConfig.isLocalFallbackPrimary) {
                val result = localFallbackApiClient.updatePost(
                    accessToken = accessToken,
                    postId = postId,
                    visibility = visibility
                ).map { Unit }
                result.onSuccess {
                    _mutationEvents.tryEmit(PostMutationEvent.VisibilityUpdated(postId, visibility))
                }
                return result
            }

            val response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/posts") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                parameter("id", "eq.$postId")
                setBody(mapOf("visibility" to visibility))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                invalidateGlobalPostCache()
                _mutationEvents.tryEmit(PostMutationEvent.VisibilityUpdated(postId, visibility))
                Result.success(Unit)
            } else {
                Result.failure(Exception("Error HTTP ${response.status.value}: $body"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createRemix(
        postId: String,
        content: String,
        visibility: String = "public"
    ): Result<Post> {
        return try {
            val accessToken = authService.getAccessToken()
                ?: return Result.failure(Exception("No token"))
            if (!supabaseConfig.isLocalFallbackPrimary) {
                return Result.failure(Exception("Los remixes requieren el servidor Novvin actualizado."))
            }
            localFallbackApiClient.createRemix(
                accessToken = accessToken,
                postId = postId,
                content = content.trim(),
                visibility = visibility
            )
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun updateRemixPermission(
        postId: String,
        allowed: Boolean,
        audience: String = "public"
    ): Result<Post> {
        return try {
            val accessToken = authService.getAccessToken()
                ?: return Result.failure(Exception("No token"))
            if (!supabaseConfig.isLocalFallbackPrimary) {
                return Result.failure(Exception("Los permisos de remix requieren el servidor Novvin actualizado."))
            }
            val result = localFallbackApiClient.updateRemixPermission(
                accessToken = accessToken,
                postId = postId,
                allowed = allowed,
                audience = audience
            )
            result.onSuccess {
                _mutationEvents.tryEmit(
                    PostMutationEvent.RemixPermissionUpdated(postId, it.remixAllowed, it.remixAudience)
                )
            }
            result
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun updatePostContent(postId: String, content: String): Result<Unit> {
        return try {
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            if (supabaseConfig.isLocalFallbackPrimary) {
                val result = localFallbackApiClient.updatePost(
                    accessToken = accessToken,
                    postId = postId,
                    content = content
                ).map { Unit }
                result.onSuccess {
                    _mutationEvents.tryEmit(PostMutationEvent.ContentUpdated(postId, content))
                }
                return result
            }

            val response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/posts") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                parameter("id", "eq.$postId")
                setBody(mapOf("content" to content))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess() && body.trim() != "[]") {
                invalidateGlobalPostCache()
                _mutationEvents.tryEmit(PostMutationEvent.ContentUpdated(postId, content))
                Result.success(Unit)
            } else {
                Result.failure(Exception("No se pudo actualizar la publicación: HTTP ${response.status.value} $body"))
            }
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


    // The API already returns the complete bounded snapshot. Display paging belongs
    // to the UI; truncating it to 50 hid later replies and notification targets.
    suspend fun getPostComments(postId: String, limit: Int = 50_000): Flow<List<conectate.com.data.model.Comment>> = flow {
        try {
            val accessToken = authService.getAccessTokenAwait()
            if (accessToken != null) {
                if (usesLocalFallbackBackend()) {
                    val result = localFallbackApiClient.loadPostDetail(accessToken, postId)
                    emit(result.getOrThrow().comments.take(limit))
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
                            audioUrl = audioUrl,
                            mediaType = getSafeString("media_type")
                        )
                    }
                    emit(comments)
                } else {
                    throw IllegalStateException("No se pudieron cargar los comentarios.")
                }
            } else {
                throw IllegalStateException("Inicia sesión para ver los comentarios.")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) {
            android.util.Log.w("PostRepository", "Comment read failed", e)
            throw e
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

    suspend fun addStickerComment(
        postId: String,
        stickerUrl: String,
        parentId: String? = null
    ): Result<conectate.com.data.model.Comment> {
        val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
        if (!supabaseConfig.isLocalFallbackPrimary) {
            return Result.failure(Exception("Los stickers requieren el servidor principal."))
        }
        return localFallbackApiClient.addPostComment(
            accessToken = accessToken,
            postId = postId,
            content = "[Sticker]",
            parentId = parentId,
            mediaUrl = stickerUrl,
            mediaType = "sticker"
        )
    }

    suspend fun submitContentReport(
        contentType: String,
        contentId: String,
        reportedUserId: String,
        reportedUserName: String,
        reason: String,
        details: String?,
        evidenceUri: Uri?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        var uploadedEvidenceKey: String? = null
        var reportPersisted = false
        try {
            val normalizedContentType = contentType.trim().lowercase()
            if (normalizedContentType !in setOf("post", "comment")) {
                return@withContext Result.failure(Exception("Tipo de contenido inválido"))
            }

            val currentUserId = authService.getCurrentUserId()?.trim()
                ?: return@withContext Result.failure(Exception("No user"))
            val accessToken = authService.getAccessToken()
                ?: return@withContext Result.failure(Exception("No token"))

            val cleanContentId = contentId.trim()
            val cleanReportedUserId = reportedUserId.trim()
            if (cleanContentId.isBlank() || cleanReportedUserId.isBlank()) {
                return@withContext Result.failure(Exception("Reporte incompleto"))
            }
            if (cleanReportedUserId.equals(currentUserId, ignoreCase = true)) {
                return@withContext Result.failure(Exception("No puedes reportar tu propio contenido"))
            }

            val moderatedReason = moderationService.moderate(
                text = reason.trim(),
                context = ModerationContext.REPORT_REASON
            )
            val moderatedDetails = moderationService.moderate(
                text = details.orEmpty().trim(),
                context = ModerationContext.REPORT_DESCRIPTION
            )
            if (moderatedReason.verdict == ModerationVerdict.BLOCK ||
                moderatedDetails.verdict == ModerationVerdict.BLOCK
            ) {
                appMetrics.recordFailure(
                    metric = "content_report_submit",
                    attributes = mapOf("reason" to "moderation_block", "content_type" to normalizedContentType)
                )
                return@withContext Result.failure(Exception("El reporte contiene contenido no permitido"))
            }

            val sanitizedReason = moderatedReason.sanitizedText.trim()
            val sanitizedDetails = moderatedDetails.sanitizedText.trim().ifBlank { null }
            if (sanitizedReason.isBlank()) {
                return@withContext Result.failure(Exception("El motivo del reporte es obligatorio"))
            }

            var evidenceUrl: String? = null
            if (evidenceUri != null) {
                val uploadedEvidence = uploadReportEvidence(
                    context = context,
                    storageService = storageService,
                    userId = currentUserId,
                    sourceUri = evidenceUri
                ).getOrElse { error ->
                    return@withContext Result.failure(error)
                }
                uploadedEvidenceKey = uploadedEvidence.objectKey
                evidenceUrl = uploadedEvidence.publicUrl
            }

            val currentUser = userRepository.getUserById(currentUserId).getOrNull()
            val reporterName = currentUser?.username
                ?: currentUser?.display_name
                ?: currentUser?.full_name
                ?: "Usuario"
            val safeReportedName = reportedUserName.trim().take(80).ifBlank { "Usuario" }

            if (supabaseConfig.isLocalFallbackPrimary) {
                val result = localFallbackApiClient.submitContentReport(
                    accessToken = accessToken,
                    contentType = normalizedContentType,
                    contentId = cleanContentId,
                    reportedUserId = cleanReportedUserId,
                    reportedUserName = safeReportedName,
                    reason = sanitizedReason,
                    description = sanitizedDetails,
                    evidenceUrl = evidenceUrl
                )
                if (result.isSuccess) {
                    reportPersisted = true
                    appMetrics.incrementCounter(
                        metric = "content_report_submit_success",
                        attributes = mapOf("source" to "ubuntu", "content_type" to normalizedContentType)
                    )
                    appMetrics.recordLatency(
                        metric = "content_report_submit",
                        durationMs = System.currentTimeMillis() - startedAt,
                        attributes = mapOf("source" to "ubuntu", "content_type" to normalizedContentType)
                    )
                }
                return@withContext result
            }

            val payload = mutableMapOf<String, String?>(
                "reporter_id" to currentUserId,
                "reporter_name" to reporterName,
                "reported_user_id" to cleanReportedUserId,
                "reported_user_name" to safeReportedName,
                "reason" to sanitizedReason,
                "description" to sanitizedDetails,
                "evidence_url" to evidenceUrl,
                "content_type" to normalizedContentType,
                "content_id" to cleanContentId,
                "status" to "pendiente"
            )

            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/reports") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                setBody(payload)
            }

            if (response.status.isSuccess()) {
                reportPersisted = true
                appMetrics.incrementCounter(
                    metric = "content_report_submit_success",
                    attributes = mapOf("content_type" to normalizedContentType)
                )
                appMetrics.recordLatency(
                    metric = "content_report_submit",
                    durationMs = System.currentTimeMillis() - startedAt,
                    attributes = mapOf("content_type" to normalizedContentType)
                )
                Result.success(Unit)
            } else {
                val errorBody = runCatching { response.bodyAsText() }.getOrDefault("")
                appMetrics.recordFailure(
                    metric = "content_report_submit",
                    attributes = mapOf(
                        "http_status" to response.status.value.toString(),
                        "content_type" to normalizedContentType
                    )
                )
                Result.failure(Exception("Error enviando reporte: ${response.status} $errorBody"))
            }
        } catch (e: Exception) {
            android.util.Log.e("PostRepository", "Error submitting content report", e)
            appMetrics.recordFailure("content_report_submit", e)
            Result.failure(e)
        } finally {
            if (!reportPersisted) {
                uploadedEvidenceKey?.let { key ->
                    storageService.deleteFile("report-evidence", key).onFailure { cleanupError ->
                        android.util.Log.w("PostRepository", "Could not clean report evidence", cleanupError)
                    }
                }
            }
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
            userRepository.resolvePublicMention(username)?.let(resolved::add)
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
        val mentionRegex = conectate.com.data.model.publicMentionPattern
        return mentionRegex.findAll(content)
            .map { it.groupValues[1].trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private data class MediaPayload(
        val bytes: ByteArray?,
        val sizeBytes: Long,
        val contentType: String,
        val extension: String
    )

    private fun readMediaPayload(uri: Uri): MediaPayload? {
        val header = context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(512)
            val count = stream.read(buffer)
            if (count > 0) buffer.copyOf(count) else null
        } ?: return null
        val sizeBytes = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it > 0L }
            }
        }.getOrNull() ?: 0L
        if (sizeBytes > maxPostMediaBytes) return null

        val detectedContentType = canonicalizeMediaContentType(inferMimeTypeFromBytes(header))
            ?: canonicalizeMediaContentType(context.contentResolver.getType(uri))
            ?: canonicalizeMediaContentType(guessMimeFromUri(uri))
            ?: return null
        if (detectedContentType !in setOf("image/jpeg", "image/png", "image/webp", "video/mp4")) {
            return null
        }

        val extension = preferredExtensionForMime(detectedContentType)
            ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(detectedContentType)
            ?: return null
        val bytes = if (sizeBytes > 0L) {
            null
        } else {
            context.contentResolver.openInputStream(uri)?.use { it.readMediaBytesWithLimit(maxPostMediaBytes) }
                ?.takeIf { it.isNotEmpty() && it.size.toLong() <= maxPostMediaBytes }
                ?: return null
        }

        return MediaPayload(
            bytes = bytes,
            sizeBytes = sizeBytes,
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
        @SerialName("is_sensitive") val isSensitive: Boolean = false,
        @SerialName("remix_allowed") val remixAllowed: Boolean = false,
        @SerialName("remix_audience") val remixAudience: String = "public",
        @SerialName("tags") val tags: List<String> = emptyList()
    )

    suspend fun updateCommentContent(commentId: String, newContent: String): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
            if (supabaseConfig.isLocalFallbackPrimary) {
                return localFallbackApiClient.updatePostComment(accessToken, commentId, newContent)
            }

            val response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/post_comments") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                header("Prefer", "return=representation")
                parameter("id", "eq.$commentId")
                setBody(buildJsonObject { put("content", JsonPrimitive(newContent)) })
            }

            if (response.status.isSuccess()) {
                val updatedItems: List<kotlinx.serialization.json.JsonObject> = response.body()
                if (updatedItems.isNotEmpty()) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Not found or unauthorized"))
                }
            } else {
                Result.failure(Exception("HTTP ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setCommentReaction(commentId: String, reactionType: String?): Result<Unit> {
        val normalized = reactionType?.trim()?.uppercase()
        if (normalized != null && normalized !in setOf("LIKE", "DISLIKE")) {
            return Result.failure(IllegalArgumentException("Reacción inválida"))
        }
        val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))
        if (!supabaseConfig.isLocalFallbackPrimary) {
            return Result.failure(Exception("Las reacciones de comentarios requieren el servidor principal."))
        }
        return localFallbackApiClient.setPostCommentReaction(accessToken, commentId, normalized)
    }
}





