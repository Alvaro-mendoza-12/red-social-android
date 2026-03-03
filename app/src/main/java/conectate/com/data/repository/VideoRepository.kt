package conectate.com.data.repository

import conectate.com.data.model.Video
import conectate.com.data.model.VideoComment
import conectate.com.data.model.VideoReport
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.awaitClose
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.filter.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*
import kotlin.io.readBytes
import javax.inject.Inject
import javax.inject.Singleton

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import conectate.com.data.supabase.StorageService
import java.util.UUID
import conectate.com.data.local.dao.VideoStatsDao
import conectate.com.util.VideoUtils
import conectate.com.data.local.dao.VideoCommentDao
import conectate.com.data.local.entity.VideoStatsEntity
import conectate.com.data.local.entity.VideoCommentEntity
import io.github.jan.supabase.realtime.*
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow

// Type alias to avoid conflict if any (just standard Uri)
typealias VideoUriType = android.net.Uri

enum class SuspensionDurationUnit(val wireValue: String) {
    SECONDS("seconds"),
    MINUTES("minutes"),
    HOURS("hours"),
    DAYS("days"),
    YEARS("years");

    companion object {
        fun fromRaw(raw: String?): SuspensionDurationUnit {
            return when (raw?.trim()?.lowercase()) {
                "seconds", "segundos", "second", "segundo" -> SECONDS
                "minutes", "minutos", "minute", "minuto" -> MINUTES
                "hours", "horas", "hour", "hora" -> HOURS
                "years", "anos", "a\u00f1os", "year", "ano", "a\u00f1o" -> YEARS
                else -> DAYS
            }
        }
    }
}


@Singleton
class VideoRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val storageService: StorageService,
    private val videoStatsDao: VideoStatsDao,
    private val videoCommentDao: VideoCommentDao,
    private val notificationRepository: NotificationRepository,
    private val userRepository: UserRepository,
    @ApplicationContext private val context: Context
) {
    /**
     * Repository-level coroutine scope
     * Uses SupervisorJob so failures in one coroutine don't cancel others
     * Lives for the lifetime of this Singleton repository
     * Can be cancelled if needed for cleanup
     */
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun getSafeString(key: String, base: JsonObject): String? {
        val element = base[key]
        if (element == null || element is kotlinx.serialization.json.JsonNull) return null
        val str = try {
            element.jsonPrimitive.contentOrNull ?: element.toString().removeSurrounding("\"")
        } catch (e: Exception) {
            element.toString().removeSurrounding("\"")
        }
        return if (str == "null" || str.isEmpty()) null else str
    }

    fun getVideos(offset: Int = 0, limit: Int = 20, random: Boolean = false): Flow<List<Video>> = flow {
        try {
            val videosResponse = supabaseConfig.client.from("videos")
                .select {
                    filter {
                        eq("is_public", true)
                    }
                    order("created_at", Order.DESCENDING)
                    limit(limit.toLong())
                    range(offset.toLong(), (offset + limit - 1).toLong())
                }
            
            val rawVideos: List<Video> = videosResponse.decodeList()
            val currentUserId = authService.getCurrentUserId()

            var videos = if (rawVideos.isNotEmpty()) {
                coroutineScope {
                    val userIds = rawVideos.map { it.userId }.distinct()
                    val profilesResponse = supabaseConfig.client.from("profiles")
                        .select(Columns.list("id", "is_verified")) {
                            filter {
                                isIn("id", userIds)
                            }
                        }
                    
                    val profileData = try {
                        profilesResponse.decodeList<JsonObject>()
                    } catch (e: Exception) { emptyList<JsonObject>() }

                    val verifications = profileData.associate {
                        it["id"]?.jsonPrimitive?.contentOrNull to (it["is_verified"]?.jsonPrimitive?.booleanOrNull ?: false)
                    }

                    rawVideos.map { video ->
                        val isV = verifications[video.userId] ?: false
                        video.copy(isVerified = isV)
                    }
                }
            } else rawVideos

            if (currentUserId != null) {
                val accessToken = authService.getAccessToken() ?: ""
                val likedIds = getUserLikedVideoIds(accessToken, currentUserId)
                val sharedIds = getUserSharedContentIds(currentUserId, "video")
                val realCounts = fetchRealLikeCounts(videos.map { it.id })
                
                videos = videos.map { 
                    it.copy(
                        likedByMe = likedIds.contains(it.id),
                        sharedByMe = sharedIds.contains(it.id),
                        likeCount = realCounts[it.id] ?: it.likeCount
                    ) 
                }
            }

            if (random) {
                videos = videos.shuffled()
            }

            emit(videos)
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error fetching videos", e)
            emit(emptyList()) 
        }
    }

    private suspend fun fetchRealLikeCounts(videoIds: List<String>): Map<String, Int> {
        if (videoIds.isEmpty()) return emptyMap()
        return try {
            val accessToken = authService.getAccessToken() ?: ""
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/video_likes") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("video_id", "in.(${videoIds.joinToString(",")})")
                parameter("select", "video_id")
            }
            if (response.status.isSuccess()) {
                val likes: List<Map<String, String>> = response.body()
                likes.mapNotNull { it["video_id"] }
                    .groupBy { it }
                    .mapValues { it.value.size }
            } else emptyMap()
        } catch (e: Exception) { 
            android.util.Log.e("VideoRepository", "Error fetching real like counts", e)
            emptyMap() 
        }
    }

    private suspend fun getUserLikedVideoIds(accessToken: String, userId: String): Set<String> {
        return try {
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/video_likes") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("user_id", "eq.$userId")
                parameter("select", "video_id")
            }
            if (response.status.isSuccess()) {
                val likes: List<Map<String, String>> = response.body()
                likes.mapNotNull { it["video_id"] }.toSet()
            } else {
                emptySet()
            }
        } catch (e: Exception) {
            emptySet()
        }
    }

    private suspend fun getUserSharedContentIds(userId: String, contentType: String): Set<String> {
        return try {
             // Fetch all shares for this user, we will filter nulls in Kotlin which is safer
            val response = supabaseConfig.client.from("shares").select(Columns.list("post_id", "video_id")) {
                filter {
                    eq("user_id", userId)
                }
            }
            val data = response.decodeList<JsonObject>()
            val ids = data.mapNotNull { 
                if (contentType == "post") it["post_id"]?.jsonPrimitive?.contentOrNull
                else it["video_id"]?.jsonPrimitive?.contentOrNull
            }.toSet()
            
            android.util.Log.d("VideoRepository", "getUserSharedContentIds: Found ${ids.size} shared items for type $contentType")
            ids
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "getUserSharedContentIds: Exception", e)
            emptySet()
        }
    }


    fun getUserVideos(userId: String): Flow<List<Video>> = flow {
        try {
            val response = supabaseConfig.client.from("videos")
                .select {
                    filter {
                        eq("user_id", userId)
                        val currentId = authService.getCurrentUserId()
                        if (userId != currentId) {
                            eq("is_public", true)
                        }
                    }
                    order("created_at", order = Order.DESCENDING)
                }
            
            var videos: List<Video> = response.decodeList()
            val currentUserId = authService.getCurrentUserId()
            if (currentUserId != null) {
                val accessToken = authService.getAccessToken() ?: ""
                val likedIds = getUserLikedVideoIds(accessToken, currentUserId)
                val sharedIds = getUserSharedContentIds(currentUserId, "video")
                val realCounts = fetchRealLikeCounts(videos.map { it.id })

                videos = videos.map { 
                    it.copy(
                        likedByMe = likedIds.contains(it.id),
                        sharedByMe = sharedIds.contains(it.id),
                        likeCount = realCounts[it.id] ?: it.likeCount
                    ) 
                }
            }
            emit(videos)
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error fetching user videos", e)
            emit(emptyList())
        }
    }

    fun getSharedVideos(userId: String): Flow<List<Video>> = callbackFlow<List<Video>> {
        val channel = supabaseConfig.client.realtime.channel("shared_videos_$userId")
        
        suspend fun fetchAndEmit() {
            try {
                val currentUserId = authService.getCurrentUserId()
                val sharedIds = getUserSharedContentIds(userId, "video")
                
                if (sharedIds.isEmpty()) {
                    trySend(emptyList<Video>())
                    return
                }

                val response = supabaseConfig.client.from("videos").select {
                    filter { isIn("id", sharedIds.toList()) }
                    order("created_at", order = Order.DESCENDING)
                }

                val rawVideos: List<Video> = response.decodeList()
                val videos = if (currentUserId != null) {
                    rawVideos.map { it.copy(sharedByMe = true) }
                } else rawVideos
                
                trySend(videos)
            } catch (e: Exception) {
                android.util.Log.e("VideoRepository", "Error fetching shared videos in flow", e)
                trySend(emptyList<Video>())
            }
        }

        // 1. Initial Fetch
        launch { fetchAndEmit() }

        // 2. Setup Realtime Listener
        val changes = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            this.table = "shares"
            this.filter = "user_id=eq.$userId"
        }

        val job = launch {
            changes.collect {
                fetchAndEmit()
            }
        }

        channel.subscribe()

        awaitClose {
            job.cancel()
            launch {
                try {
                    channel.unsubscribe()
                } catch (e: Exception) {
                    android.util.Log.e("VideoRepository", "Error in unsubscribe", e)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun getVideo(videoId: String): Result<Video> = withContext(Dispatchers.IO) {
        try {
            val video = supabaseConfig.client.from("videos").select {
                filter { eq("id", videoId) }
            }.decodeList<Video>().firstOrNull() ?: throw Exception("Video not found")

            // Fetch verification status
            val profileResponse = supabaseConfig.client.from("profiles")
                .select(Columns.list("is_verified")) {
                    filter { eq("id", video.userId) }
                }
            
            val isVerified = try {
                 val data = profileResponse.decodeList<JsonObject>().firstOrNull()
                 data?.get("is_verified")?.jsonPrimitive?.booleanOrNull ?: false
            } catch (e: Exception) { false }

            Result.success(video.copy(isVerified = isVerified))
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "getVideo error", e)
            Result.failure(e)
        }
    }


    fun getFollowingVideos(): Flow<List<Video>> = flow {
        try {
            val currentUserId = authService.getCurrentUserId() ?: run { emit(emptyList()); return@flow }

            val idResponse = supabaseConfig.client.from("follows")
                .select(Columns.list("following_id")) {
                    filter {
                        eq("follower_id", currentUserId)
                    }
                }
            
            val followingIds = try {
                val data = idResponse.decodeList<JsonObject>()
                data.mapNotNull { it["following_id"]?.jsonPrimitive?.contentOrNull }
            } catch(e: Exception) {
                emptyList<String>()
            }

            if (followingIds.isNotEmpty()) {
                val videoResponse = supabaseConfig.client.from("videos")
                    .select {
                        filter {
                            isIn("user_id", followingIds)
                            eq("is_public", true)
                        }
                        order("created_at", order = Order.DESCENDING)
                    }
                
                var videos: List<Video> = videoResponse.decodeList()
                val sharedIds = getUserSharedContentIds(currentUserId, "video")
                videos = videos.map { it.copy(sharedByMe = sharedIds.contains(it.id)) }
                emit(videos)
            } else {
                emit(emptyList())
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error fetching following videos", e)
            emit(emptyList())
        }
    }

    suspend fun uploadVideo(
        title: String,
        description: String,
        videoUri: VideoUriType, // Placeholder name if I can't know the exact type, but it's Uri
        thumbnailUri: android.net.Uri?,
        isPublic: Boolean,
        tags: List<String>
    ): Result<String> {
        return try {
            val userId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("Usuario no identificado"))
            val policyAccepted = userRepository.hasAcceptedPrivacyPolicy(userId).getOrElse { false }
            if (!policyAccepted) {
                return Result.failure(
                    Exception("Debes aceptar las políticas de privacidad desde Configuración > Políticas de privacidad antes de subir videos.")
                )
            }

            // 1. Upload Video
            val videoBytes = context.contentResolver.openInputStream(videoUri)?.use { it.readBytes() }
                ?: return Result.failure(Exception("No se pudo leer el video"))
            
            val videoFileName = "${userId}/${UUID.randomUUID()}.mp4"
            val videoUploadResult = storageService.uploadFile("videos", videoFileName, videoBytes, "video/mp4")
            
            if (videoUploadResult.isFailure) {
                return Result.failure(videoUploadResult.exceptionOrNull() ?: Exception("Error subiendo video"))
            }
            
            val videoPublicUrl = storageService.getPublicUrl("videos", videoFileName)

            // 2. Upload Thumbnail (Manual or Auto-generated)
            var thumbPublicUrl = ""
            
            // Try manual thumbnail first
            var thumbBytes: ByteArray? = null
            if (thumbnailUri != null && thumbnailUri != Uri.EMPTY) {
                try {
                    thumbBytes = context.contentResolver.openInputStream(thumbnailUri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    android.util.Log.e("VideoRepository", "Error reading manual thumbnail", e)
                }
            }
            
            // If no manual thumbnail, generate one from the video
            if (thumbBytes == null) {
                try {
                    thumbBytes = VideoUtils.generateVideoThumbnail(context, videoUri)
                    if (thumbBytes != null) {
                        android.util.Log.d("VideoRepository", "Auto-generated thumbnail successfully")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VideoRepository", "Error auto-generating thumbnail", e)
                }
            }
            
            // Upload thumbnail if we have one (manual or auto-generated)
            if (thumbBytes != null) {
                try {
                    val thumbFileName = "${userId}/${UUID.randomUUID()}.jpg"
                    val thumbUploadResult = storageService.uploadFile("thumbnails", thumbFileName, thumbBytes, "image/jpeg")
                    if (thumbUploadResult.isSuccess) {
                        thumbPublicUrl = storageService.getPublicUrl("thumbnails", thumbFileName)
                        android.util.Log.d("VideoRepository", "Thumbnail uploaded: $thumbPublicUrl")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VideoRepository", "Error uploading thumbnail", e)
                }
            }

            // 2.5 Fetch User Profile to get Name and Image (crucial for some DB schemas)
            var userNameStr = ""
            var userImageStr = ""
            var isVerifiedVal = false
            try {
                val profileResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                    header("Authorization", "Bearer ${authService.getAccessToken()}")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("id", "eq.$userId")
                    parameter("select", "full_name,username,avatar_url,is_verified")
                }
                if (profileResponse.status.isSuccess()) {
                    val profiles: List<kotlinx.serialization.json.JsonObject> = profileResponse.body()
                    val profile = profiles.firstOrNull()
                    userNameStr = getSafeString("username", profile ?: JsonObject(emptyMap())) 
                        ?: getSafeString("full_name", profile ?: JsonObject(emptyMap())) ?: ""
                    userImageStr = getSafeString("avatar_url", profile ?: JsonObject(emptyMap())) ?: ""
                    isVerifiedVal = profile?.get("is_verified")?.jsonPrimitive?.booleanOrNull ?: false
                }
            } catch (e: Exception) { e.printStackTrace() }

            // 3. Save Metadata
            // Using DTO for robust serialization
            val videoRequest = VideoInsertRequest(
                id = UUID.randomUUID().toString(),
                title = title,
                description = description,
                videoUrl = videoPublicUrl,
                thumbnailUrl = thumbPublicUrl,
                userId = userId,
                isPublic = isPublic,
                tags = tags,
                username = userNameStr,
                userProfileImage = userImageStr,
                isVerified = isVerifiedVal
            )

            // Insert into Supabase 'videos' table
             val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/videos") {
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 header("Content-Type", "application/json")
                 header("Prefer", "return=minimal")
                 setBody(videoRequest)
             }
             
             if (response.status.isSuccess()) {
                 // 4. Increment user's post_count
                 try {
                     supabaseConfig.httpClient.post(
                         "${supabaseConfig.supabaseUrl}/rest/v1/rpc/increment_post_count"
                     ) {
                         header("apikey", supabaseConfig.supabaseAnonKey)
                         header("Authorization", "Bearer ${authService.getAccessToken()}")
                         header("Content-Type", "application/json")
                         setBody(mapOf("user_id" to userId))
                     }
                 } catch (e: Exception) {
                     android.util.Log.e("VideoRepository", "Failed to increment post_count", e)
                 }
                 
                 Result.success("Video publicado exitosamente")
             } else {
                 val errorBody = response.body<String>()
                 Result.failure(Exception("Error guardando metadatos: ${response.status} - $errorBody"))
             }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    @Serializable
    private data class VideoInsertRequest(
        val id: String,
        val title: String,
        val description: String,
        @SerialName("video_url") val videoUrl: String,
        @SerialName("thumbnail_url") val thumbnailUrl: String,
        @SerialName("user_id") val userId: String,
        @SerialName("is_public") val isPublic: Boolean,
        val tags: List<String>,
        @SerialName("user_name") val username: String,
        @SerialName("user_profile_image") val userProfileImage: String,
        @SerialName("is_verified") val isVerified: Boolean
    )





    suspend fun toggleLike(videoId: String, isLiked: Boolean): Result<Boolean> {
        return if (isLiked) {
             unlikeVideo(videoId).map { false }
        } else {
             likeVideo(videoId).map { true }
        }
    }

    suspend fun searchVideos(query: String): Result<List<Video>> = withContext(Dispatchers.IO) {
        try {
            // Using Postgrest 'or' for Title OR Description OR Tags
            val response = supabaseConfig.client.from("videos").select {
                filter {
                    val q = "%$query%"
                    or {
                        ilike("title", q)
                        ilike("description", q)
                        // Also search in tags if query matches any tag
                        cs("tags", listOf(query))
                    }
                    eq("is_public", true)
                }
                order("created_at", Order.DESCENDING)
                limit(50)
            }

            val rawVideos: List<Video> = response.decodeList()
            
            // Enrich with verification status (consistent with getVideos)
            if (rawVideos.isEmpty()) return@withContext Result.success(emptyList())

            val userIds = rawVideos.map { it.userId }.distinct()
            val profilesResponse = supabaseConfig.client.from("profiles")
                .select(Columns.list("id", "is_verified")) {
                    filter { isIn("id", userIds) }
                }
            
            val verifications = try {
                profilesResponse.decodeList<JsonObject>().associate {
                    it["id"]?.jsonPrimitive?.contentOrNull to (it["is_verified"]?.jsonPrimitive?.booleanOrNull ?: false)
                }
            } catch (e: Exception) { emptyMap() }

            val currentUserId = authService.getCurrentUserId()
            val likes = if (currentUserId != null) {
                val accessToken = authService.getAccessToken() ?: ""
                getUserLikedVideoIds(accessToken, currentUserId)
            } else emptySet()

            val enrichedVideos = rawVideos.map { video ->
                video.copy(
                    isVerified = verifications[video.userId] ?: false,
                    likedByMe = likes.contains(video.id)
                )
            }

            Result.success(enrichedVideos)
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "searchVideos error", e)
            Result.failure(e)
        }
    }

    suspend fun getVideoById(videoId: String): Result<Video> {
        return try {
            val accessToken = authService.getAccessToken()
                ?: return Result.failure(Exception("No access token available"))

            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/videos") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("id", "eq.$videoId")
                parameter("select", "*")
            }

            if (response.status.isSuccess()) {
                val videos: List<Video> = response.body()
                var video = videos.firstOrNull()
                    ?: return Result.failure(Exception("Video not found"))
                
                // Set liked and shared status
                val currentUserId = authService.getCurrentUserId()
                if (currentUserId != null) {
                    val likedIds = getUserLikedVideoIds(accessToken, currentUserId)
                    val sharedIds = getUserSharedContentIds(currentUserId, "video")
                    video = video.copy(
                        likedByMe = likedIds.contains(video.id),
                        sharedByMe = sharedIds.contains(video.id)
                    )
                }
                
                Result.success(video)
            } else {
                Result.failure(Exception("Failed to fetch video: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun incrementViews(videoId: String): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken() // RPC might work with anon too if configured, but auth is safer
            
            // Use RPC to bypass RLS UPDATE restriction for views
            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/increment_video_views") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                if (accessToken != null) {
                    header("Authorization", "Bearer $accessToken")
                }
                header("Content-Type", "application/json")
                setBody(mapOf("video_id" to videoId))
            }

            if (response.status.isSuccess()) {
            android.util.Log.d("VideoRepository", "incrementViews SUCCESS for $videoId")
            // Update local Room database immediately
            updateLocalVideoViewCount(videoId, 1)
            Result.success(Unit)
        } else {
            val errBody = try { response.body<String>() } catch (e: Exception) { "no body" }
            android.util.Log.e("VideoRepository", "incrementViews FAILURE for $videoId: ${response.status} - $errBody")
            Result.failure(Exception("Failed to increment views: ${response.status}"))
        }
    } catch (e: Exception) {
        android.util.Log.e("VideoRepository", "incrementViews CRASH for $videoId", e)
        Result.failure(e)
    }
}

    fun getCommentsFlow(videoId: String): Flow<List<VideoComment>> = kotlinx.coroutines.flow.channelFlow {
        android.util.Log.d("VideoRepository", "getCommentsFlow called for videoId: $videoId")
        // 1. Initial Fetch (Async)
        launch { 
            android.util.Log.d("VideoRepository", "Launching initial network fetch for comments")
            refreshCommentsFromNetwork(videoId) 
        }

        // 2. Realtime Subscription (Async)
        launch { 
            android.util.Log.d("VideoRepository", "Launching Realtime subscription for comments")
            subscribeToVideoComments(videoId) 
        }
        
        // 3. Emit local data from Room
        android.util.Log.d("VideoRepository", "Starting to collect from Room for videoId: $videoId")
        videoCommentDao.getCommentsFlow(videoId).collect { entities ->
            android.util.Log.d("VideoRepository", "Room emitted ${entities.size} comments for videoId: $videoId")
            send(entities.map { it.toDomain() })
        }
    }

    private suspend fun refreshCommentsFromNetwork(videoId: String) {
        try {
            android.util.Log.d("VideoRepository", "Fetching comments from network for videoId: $videoId")
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/video_comments") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                if (authService.getAccessToken() != null) {
                    header("Authorization", "Bearer ${authService.getAccessToken()}")
                }
                parameter("video_id", "eq.$videoId")
                parameter("order", "created_at.desc")
                // CRITICAL: Join with profiles to get LIVE name and avatar
                parameter("select", "*,profiles(username,full_name,avatar_url)")
            }
            
            if (response.status.isSuccess()) {
                val comments = response.body<List<VideoComment>>()
                android.util.Log.d("VideoRepository", "Fetched ${comments.size} comments from network")
                val entities = comments.map { VideoCommentEntity.fromDomain(it) }
                videoCommentDao.deleteCommentsByVideo(videoId)
                videoCommentDao.insertComments(entities)
                android.util.Log.d("VideoRepository", "Inserted ${entities.size} comments to Room")
            } else {
                android.util.Log.e("VideoRepository", "Failed to fetch comments: ${response.status}")
            }
        } catch (e: Exception) {
             android.util.Log.e("VideoRepository", "Error fetching comments history", e)
        }
    }

    private suspend fun subscribeToVideoComments(videoId: String) {
        android.util.Log.d("VideoRepository", "Starting Realtime subscription for video: $videoId")
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            var realtimeChannel: RealtimeChannel? = null
            try {
                realtimeChannel = supabaseConfig.client.realtime.channel("video_comments_$videoId")
                
                android.util.Log.d("VideoRepository", "Creating Realtime channel: video_comments_$videoId")

                val commentFlow = realtimeChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    this.table = "video_comments"
                    this.filter = "video_id=eq.$videoId"
                }

                realtimeChannel.subscribe()
                android.util.Log.d("VideoRepository", "Subscribed to Realtime channel for video: $videoId")

                commentFlow.collect { change ->
                    android.util.Log.d("VideoRepository", "Realtime change received: ${change::class.simpleName}")
                    when (change) {
                        is PostgresAction.Insert -> {
                            val comment = change.decodeRecord<VideoComment>()
                            // Insert into Room
                            repositoryScope.launch {
                                videoCommentDao.insertComment(VideoCommentEntity.fromDomain(comment))
                                updateLocalVideoCommentCount(videoId, 1)
                            }
                        }
                        is PostgresAction.Delete -> {
                            val id = change.oldRecord["id"]?.jsonPrimitive?.contentOrNull
                            if (id != null) {
                                repositoryScope.launch {
                                    videoCommentDao.deleteComment(id)
                                    updateLocalVideoCommentCount(videoId, -1)
                                }
                            }
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoRepository", "Error in Realtime subscription for video: $videoId", e)
                kotlinx.coroutines.delay(3000)
                android.util.Log.d("VideoRepository", "Refreshing comments from network after error")
                refreshCommentsFromNetwork(videoId)
            } finally {
                try {
                    android.util.Log.d("VideoRepository", "Unsubscribing from Realtime channel: comments:$videoId")
                    realtimeChannel?.unsubscribe()
                 } catch (e: Exception) {
                    android.util.Log.e("VideoRepository", "Error unsubscribing from channel", e)
                }
            }
        }
        android.util.Log.d("VideoRepository", "Realtime subscription ended for video: $videoId")
    }

    fun getVideoStatsFlow(videoId: String): Flow<VideoStatsEntity?> = kotlinx.coroutines.flow.channelFlow {
        // 1. Initial Fetch
        launch { 
            val realLikes = getRealLikeCount(videoId)
            val realComments = getRealCommentCount(videoId)
            val realViews = getRealViewCount(videoId)
            val realShares = getRealShareCount(videoId)

            // Update initial state
            videoStatsDao.insertStats(
                VideoStatsEntity(
                    videoId = videoId,
                    likeCount = realLikes,
                    commentCount = realComments,
                    viewCount = realViews,
                    shareCount = realShares
                )
            )
        }

        // 2. Realtime Subscription (Likes & Comments)
        launch {
            subscribeToVideoStats(videoId)
        }
        
        // 3. Emit local data
        videoStatsDao.getStatsFlow(videoId).collect {
            send(it)
        }
    }

    private suspend fun subscribeToVideoStats(videoId: String) {
        val channel = supabaseConfig.client.realtime.channel("video_stats_$videoId")
        
        try {
            channel.subscribe()
            
            kotlinx.coroutines.coroutineScope {
                // Listen for changes in 'videos' table (like_count, views, comment_count)
                launch {
                    val videoStatsChanges = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                        this.table = "videos"
                        this.filter = "id=eq.$videoId"
                    }
                    
                    videoStatsChanges.collect { update ->
                        val rawData = update.record
                        val views = rawData["views"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                        val likeCount = rawData["like_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                        val commentCount = rawData["comment_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                        val shareCount = rawData["share_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                        
                        android.util.Log.d("VideoRepository", "Stats update received for $videoId: views=$views, likes=$likeCount, shares=$shareCount")
                        updateLocalVideoStats(videoId, views, likeCount, commentCount, shareCount)
                    }
                }

                // Listen for individual likes/unlikes to trigger a refresh of the exact count
                launch {
                    val likeChanges = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                        this.table = "video_likes"
                        this.filter = "video_id=eq.$videoId"
                    }
                    
                    likeChanges.collect { _ ->
                        val newCount = getRealLikeCount(videoId)
                        if (newCount >= 0) {
                            val current = videoStatsDao.getStats(videoId) ?: VideoStatsEntity(videoId)
                            videoStatsDao.insertStats(current.copy(likeCount = newCount))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error in subscribeToVideoStats for $videoId", e)
        } finally {
            channel.unsubscribe()
        }
    }

    private suspend fun getRealLikeCount(videoId: String): Int {
         return try {
             val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/video_likes") {
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 parameter("video_id", "eq.$videoId")
                 header("Prefer", "count=exact")
                 parameter("select", "id")
                 parameter("limit", "1")
             }
             if (response.status.isSuccess()) {
                 val contentRange = response.headers["Content-Range"]
                 contentRange?.substringAfterLast("/")?.toIntOrNull() ?: 0
             } else 0
         } catch (e: Exception) { 0 }
    }

    private suspend fun getRealCommentCount(videoId: String): Int {
         return try {
             val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/video_comments") {
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 parameter("video_id", "eq.$videoId")
                 header("Prefer", "count=exact")
                 parameter("select", "id")
                 parameter("limit", "1")
             }
             if (response.status.isSuccess()) {
                 val contentRange = response.headers["Content-Range"]
                 contentRange?.substringAfterLast("/")?.toIntOrNull() ?: 0
             } else 0
         } catch (e: Exception) { 0 }
    }

    private suspend fun getRealViewCount(videoId: String): Int {
         return try {
             val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/videos") {
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 parameter("id", "eq.$videoId")
                 parameter("select", "views")
             }
             if (response.status.isSuccess()) {
                 val videos = response.body<List<Map<String, Int>>>()
                 val views = videos.firstOrNull()?.get("views") ?: 0
                 android.util.Log.d("VideoRepository", "Fetched views for $videoId: $views")
                 views
             } else {
                 android.util.Log.e("VideoRepository", "Failed to fetch views for $videoId: ${response.status}")
                 0
             }
         } catch (e: Exception) {
             android.util.Log.e("VideoRepository", "Exception fetching views for $videoId", e)
             0 
         }
    }

    private suspend fun getRealShareCount(videoId: String): Int {
         return try {
             val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/videos") {
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 parameter("id", "eq.$videoId")
                 parameter("select", "share_count")
             }
             if (response.status.isSuccess()) {
                 val videos = response.body<List<Map<String, Int>>>()
                 videos.firstOrNull()?.get("share_count") ?: 0
             } else 0
         } catch (e: Exception) { 0 }
    }

    suspend fun addComment(
        videoId: String, 
        content: String,
        contentType: String = "text",
        mediaUrl: String? = null,
        parentId: String? = null
    ): Result<Unit> {
        return try {
            android.util.Log.d("VideoRepository", "addComment called for videoId=$videoId, content=$content")
            val accessToken = authService.getAccessToken()
                ?: return Result.failure(Exception("No access token available"))

            val userId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("No user ID available"))

            // Fetch User Profile for metadata
            var userName = "Usuario"
            var userAvatar = ""
            try {
                val profileRes = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("id", "eq.$userId")
                    parameter("select", "username,full_name,display_name,avatar_url")
                }
                if (profileRes.status.isSuccess()) {
                     val profiles = profileRes.body<List<kotlinx.serialization.json.JsonObject>>()
                     val p = profiles.firstOrNull()
                     // Try multiple fields for name
                     userName = getSafeString("display_name", p ?: JsonObject(emptyMap()))
                         ?: getSafeString("username", p ?: JsonObject(emptyMap()))
                         ?: getSafeString("full_name", p ?: JsonObject(emptyMap()))
                         ?: "Usuario"
                     userAvatar = getSafeString("avatar_url", p ?: JsonObject(emptyMap())) ?: ""
                     android.util.Log.d("VideoRepository", "User profile fetched: userName=$userName")
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoRepository", "Error fetching user profile", e)
            }

            val commentId = java.util.UUID.randomUUID().toString()
            
            // Log exactly what we are about to send
            android.util.Log.d("VideoRepository", "Sending comment for video: $videoId, user: $userId")
            
            val data = buildJsonObject {
                put("id", commentId)
                put("video_id", videoId)
                put("user_id", userId)
                put("content", content)
                put("created_at", java.time.OffsetDateTime.now().toString())
                if (userName.isNotBlank()) put("user_name", userName)
                if (userAvatar.isNotBlank()) put("user_profile_image", userAvatar)
                if (contentType.isNotBlank()) put("content_type", contentType)
                if (mediaUrl != null) put("media_url", mediaUrl)
                if (parentId != null) put("parent_comment_id", parentId)
            }

            var response = try {
                supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/video_comments") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Content-Type", "application/json")
                    header("Prefer", "return=representation")
                    setBody(data)
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoRepository", "Network error during first attempt", e)
                return Result.failure(e)
            }

            // FALLBACK: If 'content' is undefined, try 'comment' (just in case)
            if (response.status.value == 400) {
                val errorBody = response.body<String>()
                if (errorBody.contains("undefined column: 'content'")) {
                    android.util.Log.w("VideoRepository", "Column 'content' undefined, trying 'comment' fallback...")
                    
                    val fallbackData = buildJsonObject {
                        put("id", commentId)
                        put("video_id", videoId)
                        put("user_id", userId)
                        put("comment", content) // Fallback column
                        put("created_at", java.time.OffsetDateTime.now().toString())
                        if (userName.isNotBlank()) put("user_name", userName)
                        if (userAvatar.isNotBlank()) put("user_profile_image", userAvatar)
                        if (contentType.isNotBlank()) put("content_type", contentType)
                        if (mediaUrl != null) put("media_url", mediaUrl)
                    }
                    
                    response = try {
                        supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/video_comments") {
                            header("Authorization", "Bearer $accessToken")
                            header("apikey", supabaseConfig.supabaseAnonKey)
                            header("Content-Type", "application/json")
                            header("Prefer", "return=representation")
                            setBody(fallbackData)
                        }
                    } catch (e: Exception) {
                        return Result.failure(e)
                    }
                }
            }

            android.util.Log.d("VideoRepository", "Supabase response status: ${response.status}")
            if (response.status.isSuccess()) {
                val rawBody = response.body<String>()
                android.util.Log.d("VideoRepository", "Supabase response body: $rawBody")
                
                try {
                    val insertedComments = Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString<List<VideoComment>>(rawBody)
                    val insertedComment = insertedComments.firstOrNull() ?: VideoComment(
                        id = commentId,
                        videoId = videoId,
                        userId = userId,
                        userName = userName,
                        userProfileImage = userAvatar,
                        content = content,
                        contentType = contentType,
                        mediaUrl = mediaUrl
                    )
                    
                    android.util.Log.d("VideoRepository", "Inserting to Room: ${insertedComment.id}")
                    withContext(Dispatchers.IO) {
                        videoCommentDao.insertComment(VideoCommentEntity.fromDomain(insertedComment))
                    }
                    
                    refreshCommentsFromNetwork(videoId)
                    
                    // Trigger owner/reply notification and mentions.
                    val primaryRecipients = mutableSetOf<String>()
                    getVideo(videoId).onSuccess { video ->
                        primaryRecipients += video.userId
                        notificationRepository.sendNotification(
                            recipientId = video.userId,
                            type = if (parentId == null) "comment_video" else "reply_video",
                            targetId = videoId,
                            extraData = buildJsonObject { 
                                put("comment_id", JsonPrimitive(commentId))
                                if (parentId != null) put("parent_id", JsonPrimitive(parentId))
                            }
                        )
                    }
                    notifyMentionsForVideoComment(
                        videoId = videoId,
                        commentId = commentId,
                        parentId = parentId,
                        commentContent = content,
                        actorId = userId,
                        alreadyNotifiedUserIds = primaryRecipients
                    )
                    
                    Result.success(Unit)
                } catch (e: Exception) {
                    android.util.Log.e("VideoRepository", "Error processing success response", e)
                    refreshCommentsFromNetwork(videoId)
                    Result.success(Unit)
                }
            } else {
                val errorBody = response.body<String>()
                android.util.Log.e("VideoRepository", "Final Supabase error: ${response.status} - $errorBody")
                Result.failure(Exception("Supabase error: ${response.status} - $errorBody"))
            }

        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Exception in addComment", e)
            Result.failure(e)
        }
    }

    suspend fun sendAudioComment(videoId: String, audioFile: java.io.File): Result<Unit> {
        return try {
            android.util.Log.d("VideoRepository", "sendAudioComment called for videoId=$videoId, file=${audioFile.name}")
            authService.getCurrentUserId() ?: return Result.failure(Exception("No user"))
            
            // Upload Audio
            val bytes = audioFile.readBytes()
            val fileName = "comment_${UUID.randomUUID()}.m4a"
            android.util.Log.d("VideoRepository", "Uploading audio file: $fileName, size=${bytes.size} bytes")
            val uploadResult = storageService.uploadFile("video-comments-media", fileName, bytes, "audio/mp4")
            
            if (uploadResult.isSuccess) {
                 val publicUrl = storageService.getPublicUrl("video-comments-media", fileName)
                 android.util.Log.d("VideoRepository", "Audio uploaded successfully: $publicUrl")
                 // Return the result of addComment directly
                 return addComment(
                     videoId = videoId,
                     content = "[Audio]",
                     contentType = "audio",
                     mediaUrl = publicUrl
                 )
            } else {
                val error = uploadResult.exceptionOrNull() ?: Exception("Upload failed")
                android.util.Log.e("VideoRepository", "Audio upload failed: ${error.message}", error)
                return Result.failure(error)
            }
        } catch (e: Exception) { 
            android.util.Log.e("VideoRepository", "Exception in sendAudioComment", e)
            Result.failure(e) 
        }
    }

    suspend fun getCommentAudioUrl(pathOrUrl: String): String {
        return if (pathOrUrl.startsWith("http")) pathOrUrl 
        else storageService.getPublicUrl("video-comments-media", pathOrUrl)
    }

    suspend fun deleteComment(commentId: String): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No token"))

            android.util.Log.d("VideoRepository", "Deleting comment from Supabase: $commentId")
            val rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_delete_video_comment") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(buildJsonObject { put("p_comment_id", JsonPrimitive(commentId)) })
            }
            val rpcBody = runCatching { rpcResponse.bodyAsText() }.getOrDefault("")
            val missingRpc = (rpcResponse.status.value == 400 || rpcResponse.status.value == 404) &&
                rpcBody.lowercase().contains("secure_delete_video_comment")

            val deletedRemotely = if (rpcResponse.status.isSuccess()) {
                true
            } else if (missingRpc) {
                val fallbackResponse = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/video_comments") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("id", "eq.$commentId")
                }
                fallbackResponse.status.isSuccess()
            } else {
                return Result.failure(Exception("No tienes permisos para eliminar este comentario."))
            }

            if (!deletedRemotely) {
                return Result.failure(Exception("No se pudo eliminar el comentario."))
            }

            android.util.Log.d("VideoRepository", "Deleting comment from Room: $commentId")
            withContext(Dispatchers.IO) {
                videoCommentDao.deleteComment(commentId)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun likeVideo(videoId: String): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            val userId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user ID"))

            val likeData = mapOf("video_id" to videoId, "user_id" to userId)
            
            // Insert into video_likes
            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/video_likes") {
                 header("Authorization", "Bearer $accessToken")
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 header("Content-Type", "application/json") // ✅ FIX: Added missing Content-Type header
                 header("Prefer", "return=minimal")
                 setBody(likeData)
            }
            
            if (response.status.isSuccess()) {
                // Trigger increment on videos table
                incrementLikeCount(videoId, 1)
                
                // Trigger notification removed - handled by DB trigger notify_on_video_like
                
                Result.success(Unit)
            } else {
                val errorBody = try { response.body<String>() } catch (e: Exception) { "" }
                // Ignore duplicate key error (already liked)
                if (errorBody.contains("duplicate key") || errorBody.contains("23505")) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Failed to like: ${response.status}"))
                }
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun unlikeVideo(videoId: String): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            val userId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user ID"))

            val response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/video_likes") {
                 header("Authorization", "Bearer $accessToken")
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 header("Prefer", "return=representation")
                 parameter("video_id", "eq.$videoId")
                 parameter("user_id", "eq.$userId")
            }
            
            if (response.status.isSuccess()) {
                // Decrement only when we actually deleted one existing like.
                val deletedRows = runCatching {
                    val raw = response.bodyAsText()
                    if (raw.isBlank()) 0 else Json.decodeFromString<List<JsonObject>>(raw).size
                }.getOrDefault(1) // keep backward compatibility when backend ignores return=representation

                if (deletedRows > 0) {
                    incrementLikeCount(videoId, -1)
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to unlike: ${response.status}"))
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    private suspend fun incrementLikeCount(videoId: String, delta: Int) {
         try {
            val accessToken = authService.getAccessToken() ?: return
            
            // Use RPC for atomic update (avoids race conditions)
            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/increment_like_count") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(mapOf("video_id" to videoId, "delta" to delta))
            }
            
            // Fallback to manual update if RPC doesn't exist
            if (!response.status.isSuccess()) {
                // Get current count
                val getResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/videos") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("id", "eq.$videoId")
                    parameter("select", "like_count")
                }
                
                if (getResponse.status.isSuccess()) {
                    val videos: List<Video> = getResponse.body()
                    val currentLikes = videos.firstOrNull()?.likeCount ?: 0
                    val newLikes = maxOf(0, currentLikes + delta)
                    
                    supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/videos?id=eq.$videoId") {
                        header("Authorization", "Bearer $accessToken")
                        header("apikey", supabaseConfig.supabaseAnonKey)
                        header("Content-Type", "application/json")
                        setBody(mapOf("like_count" to newLikes))
                    }
                }
            }
        } catch (e: Exception) { 
            android.util.Log.e("VideoRepository", "Error updating like count", e)
        }
    }

    suspend fun incrementShareCount(videoId: String, delta: Int): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))

            val rpcParams = buildJsonObject {
                put("video_id", videoId)
                put("delta", delta)
            }

            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/increment_share_count") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(rpcParams)
            }

            if (response.status.isSuccess()) {
                android.util.Log.d("VideoRepository", "incrementShareCount: SUCCESS for $videoId")
                updateLocalVideoShareCount(videoId, delta)

                // Trigger notification if it's a new share (delta > 0)
                if (delta > 0) {
                    getVideo(videoId).onSuccess { video ->
                        notificationRepository.sendNotification(
                            recipientId = video.userId,
                            type = "share_video",
                            targetId = videoId
                        )
                    }
                }
                Result.success(Unit)
            } else {
                val error = response.bodyAsText()
                android.util.Log.e("VideoRepository", "incrementShareCount: FAILED status=${response.status}, error=$error")
                Result.failure(Exception("Error incrementando compartidos: ${response.status.value}"))
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "incrementShareCount: EXCEPTION", e)
            Result.failure(e)
        }
    }

    suspend fun hasReportedVideo(videoId: String): Result<Boolean> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            val reporterId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user ID"))
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/video_reports") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("video_id", "eq.$videoId")
                parameter("reporter_id", "eq.$reporterId")
                parameter("select", "id")
                parameter("limit", "1")
            }
            if (response.status.isSuccess()) {
                val rows = Json.decodeFromString<List<JsonObject>>(response.bodyAsText())
                Result.success(rows.isNotEmpty())
            } else {
                Result.failure(Exception("Failed checking report status: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitVideoReport(videoId: String, reason: String, details: String?): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            val reporterId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user ID"))
            val normalizedReason = reason.trim()
            if (normalizedReason.isBlank()) {
                return Result.failure(Exception("El motivo del reporte es obligatorio"))
            }

            val rpcPayload = buildJsonObject {
                put("p_video_id", JsonPrimitive(videoId))
                put("p_reason", JsonPrimitive(normalizedReason))
                put("p_details", JsonPrimitive(details?.trim().orEmpty()))
            }
            val rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_submit_video_report") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(rpcPayload)
            }

            if (rpcResponse.status.isSuccess()) {
                return Result.success(Unit)
            }

            val rpcError = runCatching { rpcResponse.bodyAsText() }.getOrDefault("")
            if (!isMissingRpcFunction(rpcResponse.status.value, rpcError, "secure_submit_video_report")) {
                if (rpcError.contains("SELF_REPORT_FORBIDDEN", ignoreCase = true)) {
                    return Result.failure(Exception("No puedes reportar tu propio video"))
                }
                if (rpcError.contains("duplicate key", ignoreCase = true) || rpcError.contains("23505")) {
                    return Result.failure(Exception("Ya reportaste este video"))
                }
                return Result.failure(Exception("Error enviando reporte: ${rpcResponse.status}"))
            }

            // Fallback for projects without RPC deployed yet.
            val video = getVideo(videoId).getOrNull() ?: return Result.failure(Exception("Video no encontrado"))
            if (video.userId == reporterId) return Result.failure(Exception("No puedes reportar tu propio video"))

            val reporter = userRepository.getUserById(reporterId).getOrNull()
            val reporterName = reporter?.displayName ?: reporter?.username ?: "Usuario"
            val owner = userRepository.getUserById(video.userId).getOrNull()
            val ownerName = owner?.displayName ?: owner?.username ?: video.userName

            val insertBody = buildJsonObject {
                put("video_id", JsonPrimitive(videoId))
                put("video_owner_id", JsonPrimitive(video.userId))
                put("video_owner_name", JsonPrimitive(ownerName))
                put("video_title", JsonPrimitive(video.title))
                put("video_thumbnail_url", JsonPrimitive(video.thumbnailUrl))
                put("reporter_id", JsonPrimitive(reporterId))
                put("reporter_name", JsonPrimitive(reporterName))
                put("reason", JsonPrimitive(normalizedReason))
                put("details", JsonPrimitive(details?.trim().orEmpty()))
                put("status", JsonPrimitive("pendiente"))
            }

            val insertResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/video_reports") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(insertBody)
            }

            if (insertResponse.status.isSuccess()) {
                Result.success(Unit)
            } else {
                val insertError = runCatching { insertResponse.bodyAsText() }.getOrDefault("")
                if (insertError.contains("duplicate key", ignoreCase = true) || insertError.contains("23505")) {
                    Result.failure(Exception("Ya reportaste este video"))
                } else {
                    Result.failure(Exception("Error enviando reporte: ${insertResponse.status}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMyVideoReports(): Result<List<VideoReport>> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            val reporterId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user ID"))
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/video_reports") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("reporter_id", "eq.$reporterId")
                parameter("order", "created_at.desc")
                parameter("select", "*")
            }
            if (response.status.isSuccess()) {
                Result.success(Json.decodeFromString(response.bodyAsText()))
            } else {
                Result.failure(Exception("Error cargando reportes de video: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getAllVideoReports(): Result<List<VideoReport>> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/video_reports") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("order", "created_at.desc")
                parameter("select", "*")
            }
            if (response.status.isSuccess()) {
                Result.success(Json.decodeFromString(response.bodyAsText()))
            } else {
                Result.failure(Exception("Error cargando reportes de video: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateVideoReportStatus(
        reportId: String,
        newStatus: String,
        actionNote: String? = null,
        warnSent: Boolean? = null,
        strikeApplied: Boolean? = null
    ): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            val normalizedStatus = normalizeVideoReportStatus(newStatus)
            val rpcPayload = buildJsonObject {
                put("p_report_id", JsonPrimitive(reportId))
                put("p_status", JsonPrimitive(normalizedStatus))
                actionNote?.let { put("p_action_note", JsonPrimitive(it)) }
            }

            val rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_admin_update_video_report_status") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(rpcPayload)
            }
            if (rpcResponse.status.isSuccess()) {
                return Result.success(Unit)
            }

            val rpcError = runCatching { rpcResponse.bodyAsText() }.getOrDefault("")
            if (!isMissingRpcFunction(rpcResponse.status.value, rpcError, "secure_admin_update_video_report_status")) {
                return Result.failure(Exception("Error actualizando reporte: ${rpcResponse.status.value}"))
            }

            val candidateStatuses = patchStatusCandidates(normalizedStatus)
            var lastFailure: Exception? = null

            candidateStatuses.forEach { statusCandidate ->
                val payload = buildJsonObject {
                    put("status", JsonPrimitive(statusCandidate))
                    put("reviewed_at", JsonPrimitive(java.time.OffsetDateTime.now().toString()))
                    authService.getCurrentUserId()?.let { put("reviewed_by", JsonPrimitive(it)) }
                    actionNote?.let { put("action_note", JsonPrimitive(it)) }
                    warnSent?.let { put("warn_sent", JsonPrimitive(it)) }
                    strikeApplied?.let { put("strike_applied", JsonPrimitive(it)) }
                }

                val response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/video_reports") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    header("Content-Type", "application/json")
                    parameter("id", "eq.$reportId")
                    setBody(payload)
                }

                if (response.status.isSuccess()) {
                    return Result.success(Unit)
                }
                lastFailure = Exception("Error actualizando reporte: ${response.status.value}")
            }

            Result.failure(lastFailure ?: Exception("Error actualizando reporte de video"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendVideoWarning(
        report: VideoReport,
        warningMessage: String = "Tu video fue reportado por incumplir las normas. Revisa y corrige para evitar sanciones."
    ): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            val rpcPayload = buildJsonObject {
                put("p_report_id", JsonPrimitive(report.id))
                put("p_message", JsonPrimitive(warningMessage))
            }

            val rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_admin_warn_video_owner_from_report") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(rpcPayload)
            }
            if (rpcResponse.status.isSuccess()) {
                return Result.success(Unit)
            }

            val rpcError = runCatching { rpcResponse.bodyAsText() }.getOrDefault("")
            if (!isMissingRpcFunction(rpcResponse.status.value, rpcError, "secure_admin_warn_video_owner_from_report")) {
                return Result.failure(Exception("No se pudo enviar advertencia (${rpcResponse.status.value})"))
            }

            val primary = notificationRepository.sendNotification(
                recipientId = report.videoOwnerId,
                type = "admin_warning_video",
                targetId = report.videoId,
                content = warningMessage,
                extraData = buildJsonObject {
                    put("report_id", JsonPrimitive(report.id))
                    put("video_id", JsonPrimitive(report.videoId))
                    put("notification_type", JsonPrimitive("admin_warning_video"))
                }
            )
            if (primary.isSuccess) {
                return Result.success(Unit)
            }

            // Compatibility fallback for databases that still restrict notification type checks.
            notificationRepository.sendNotification(
                recipientId = report.videoOwnerId,
                type = "message",
                targetId = report.videoId,
                content = warningMessage,
                extraData = buildJsonObject {
                    put("report_id", JsonPrimitive(report.id))
                    put("video_id", JsonPrimitive(report.videoId))
                    put("notification_type", JsonPrimitive("admin_warning_video"))
                }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun suspendVideoOwnerAccount(
        report: VideoReport,
        durationValue: Int = 7,
        durationUnit: SuspensionDurationUnit = SuspensionDurationUnit.DAYS,
        reason: String = "Incumplimiento de normas de la comunidad",
        permanent: Boolean = false
    ): Result<String> {
        val normalizedDuration = durationValue.coerceAtLeast(1)
        return setUserSuspension(
            userId = report.videoOwnerId,
            suspend = true,
            durationValue = normalizedDuration,
            durationUnit = durationUnit,
            reason = reason,
            permanent = permanent
        )
    }

    suspend fun enableUserAccount(userId: String): Result<Unit> {
        val result = setUserSuspension(
            userId = userId,
            suspend = false,
            durationValue = 1,
            durationUnit = SuspensionDurationUnit.DAYS,
            reason = null,
            permanent = false
        )
        return if (result.isSuccess) Result.success(Unit) else Result.failure(result.exceptionOrNull() ?: Exception("No se pudo habilitar la cuenta"))
    }

    private suspend fun setUserSuspension(
        userId: String,
        suspend: Boolean,
        durationValue: Int,
        durationUnit: SuspensionDurationUnit,
        reason: String?,
        permanent: Boolean
    ): Result<String> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            val normalizedDuration = durationValue.coerceAtLeast(1)
            val legacyDays = toLegacyDays(normalizedDuration, durationUnit)
            val extendedRpcPayload = buildJsonObject {
                put("p_user_id", JsonPrimitive(userId))
                put("p_suspend", JsonPrimitive(suspend))
                put("p_days", JsonPrimitive(legacyDays))
                if (reason == null) {
                    put("p_reason", JsonNull)
                } else {
                    put("p_reason", JsonPrimitive(reason))
                }
                put("p_duration_value", JsonPrimitive(normalizedDuration))
                put("p_duration_unit", JsonPrimitive(durationUnit.wireValue))
                put("p_permanent", JsonPrimitive(permanent))
            }

            val rpcResponse = callAdminSuspensionRpc(accessToken, extendedRpcPayload)

            if (rpcResponse.status.isSuccess()) {
                val body = runCatching { rpcResponse.bodyAsText() }.getOrDefault("")
                val until = extractSuspendedUntil(body)
                if (suspend && permanent) return Result.success("PERMANENT")
                return Result.success(until ?: "")
            }

            var rpcError = runCatching { rpcResponse.bodyAsText() }.getOrDefault("")

            // Backward compatibility: old DB function without duration/permanent parameters.
            if (requiresLegacySuspensionRpc(rpcResponse.status.value, rpcError)) {
                val legacyRpcPayload = buildJsonObject {
                    put("p_user_id", JsonPrimitive(userId))
                    put("p_suspend", JsonPrimitive(suspend))
                    put("p_days", JsonPrimitive(legacyDays))
                    if (reason == null) {
                        put("p_reason", JsonNull)
                    } else {
                        put("p_reason", JsonPrimitive(reason))
                    }
                }
                val legacyRpcResponse = callAdminSuspensionRpc(accessToken, legacyRpcPayload)
                if (legacyRpcResponse.status.isSuccess()) {
                    val body = runCatching { legacyRpcResponse.bodyAsText() }.getOrDefault("")
                    val until = extractSuspendedUntil(body)
                    if (suspend && permanent) return Result.success("PERMANENT")
                    return Result.success(until ?: "")
                }
                rpcError = runCatching { legacyRpcResponse.bodyAsText() }.getOrDefault(rpcError)
            }

            val missingSuspensionCountColumn =
                rpcError.contains("suspension_count", ignoreCase = true) &&
                    rpcError.contains("does not exist", ignoreCase = true)
            if (!isMissingRpcFunction(rpcResponse.status.value, rpcError, "secure_admin_set_user_suspension")) {
                android.util.Log.w(
                    "VideoRepository",
                    "secure_admin_set_user_suspension failed (${rpcResponse.status.value}), using fallback patch. body=$rpcError"
                )
            }

            val fallbackPayload = buildJsonObject {
                put("is_suspended", JsonPrimitive(suspend))
                if (suspend) {
                    val until = calculateSuspensionEndIso(
                        durationValue = normalizedDuration,
                        durationUnit = durationUnit,
                        permanent = permanent
                    )
                    if (until == null) {
                        put("suspended_until", JsonNull)
                        put("suspension_end_date", JsonNull)
                    } else {
                        put("suspended_until", JsonPrimitive(until))
                        put("suspension_end_date", JsonPrimitive(until))
                    }
                    put("suspension_reason", JsonPrimitive(reason ?: "Moderación administrativa"))
                } else {
                    put("suspended_until", JsonNull)
                    put("suspension_end_date", JsonNull)
                    put("suspension_reason", JsonNull)
                }
            }
            val fallbackResponse = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                parameter("id", "eq.$userId")
                setBody(fallbackPayload)
            }
            if (fallbackResponse.status.isSuccess()) {
                val until = fallbackPayload["suspended_until"]?.jsonPrimitive?.contentOrNull
                notifyUserSuspensionStatus(
                    userId = userId,
                    suspended = suspend,
                    suspendedUntil = until,
                    reason = reason,
                    permanent = permanent
                )
                if (suspend && permanent) {
                    Result.success("PERMANENT")
                } else {
                    Result.success(until ?: "")
                }
            } else {
                val fallbackError = runCatching { fallbackResponse.bodyAsText() }.getOrDefault("")
                Result.failure(
                    Exception(
                        when {
                            missingSuspensionCountColumn ->
                                "Falta migración en Supabase: ejecuta el hotfix de suspensión (profiles.suspension_count)."
                            suspend -> "No se pudo suspender la cuenta (${fallbackResponse.status.value})."
                            else -> "No se pudo habilitar la cuenta (${fallbackResponse.status.value})."
                        } + if (fallbackError.isNotBlank()) " $fallbackError" else ""
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun callAdminSuspensionRpc(
        accessToken: String,
        payload: JsonObject
    ): HttpResponse {
        return supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_admin_set_user_suspension") {
            header("Authorization", "Bearer $accessToken")
            header("apikey", supabaseConfig.supabaseAnonKey)
            header("Content-Type", "application/json")
            setBody(payload)
        }
    }

    private fun requiresLegacySuspensionRpc(statusCode: Int, body: String): Boolean {
        if (statusCode != 400 && statusCode != 404) return false
        val lower = body.lowercase()
        return lower.contains("p_duration_value") ||
            lower.contains("p_duration_unit") ||
            lower.contains("p_permanent") ||
            lower.contains("function secure_admin_set_user_suspension") ||
            lower.contains("could not find the function")
    }

    private fun toLegacyDays(
        durationValue: Int,
        durationUnit: SuspensionDurationUnit
    ): Int {
        return when (durationUnit) {
            SuspensionDurationUnit.SECONDS -> 1
            SuspensionDurationUnit.MINUTES -> 1
            SuspensionDurationUnit.HOURS -> ((durationValue + 23) / 24).coerceAtLeast(1)
            SuspensionDurationUnit.DAYS -> durationValue.coerceAtLeast(1)
            SuspensionDurationUnit.YEARS -> {
                val days = durationValue.toLong() * 365L
                days.coerceAtLeast(1L).coerceAtMost(36500L).toInt()
            }
        }
    }

    private fun calculateSuspensionEndIso(
        durationValue: Int,
        durationUnit: SuspensionDurationUnit,
        permanent: Boolean
    ): String? {
        if (permanent) return null
        val now = java.time.OffsetDateTime.now()
        val end = when (durationUnit) {
            SuspensionDurationUnit.SECONDS -> now.plusSeconds(durationValue.toLong())
            SuspensionDurationUnit.MINUTES -> now.plusMinutes(durationValue.toLong())
            SuspensionDurationUnit.HOURS -> now.plusHours(durationValue.toLong())
            SuspensionDurationUnit.DAYS -> now.plusDays(durationValue.toLong())
            SuspensionDurationUnit.YEARS -> now.plusYears(durationValue.toLong())
        }
        return end.toString()
    }

    suspend fun deleteVideoFromReport(
        report: VideoReport,
        actionNote: String = "Video eliminado por moderación"
    ): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            val rpcPayload = buildJsonObject {
                put("p_report_id", JsonPrimitive(report.id))
                put("p_action_note", JsonPrimitive(actionNote))
            }

            val rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_admin_delete_reported_video") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(rpcPayload)
            }
            if (rpcResponse.status.isSuccess()) {
                runCatching { videoCommentDao.deleteCommentsByVideo(report.videoId) }
                return Result.success(Unit)
            }

            val rpcError = runCatching { rpcResponse.bodyAsText() }.getOrDefault("")
            if (!isMissingRpcFunction(rpcResponse.status.value, rpcError, "secure_admin_delete_reported_video")) {
                return Result.failure(Exception("No se pudo eliminar el video (${rpcResponse.status.value})"))
            }

            val fallback = deleteVideo(report.videoId)
            if (fallback.isSuccess) {
                runCatching { videoCommentDao.deleteCommentsByVideo(report.videoId) }
            }
            fallback
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isMissingRpcFunction(statusCode: Int, body: String, functionName: String): Boolean {
        if (statusCode != 400 && statusCode != 404) return false
        val lower = body.lowercase()
        return lower.contains(functionName.lowercase()) &&
            (lower.contains("could not find") || lower.contains("does not exist"))
    }

    private fun extractSuspendedUntil(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val element = Json.parseToJsonElement(body)
            when (element) {
                is JsonArray -> {
                    val obj = element.firstOrNull()?.jsonObject
                    obj?.get("suspension_end_date")?.jsonPrimitive?.contentOrNull
                        ?: obj?.get("suspended_until")?.jsonPrimitive?.contentOrNull
                }
                is JsonObject -> {
                    element["suspension_end_date"]?.jsonPrimitive?.contentOrNull
                        ?: element["suspended_until"]?.jsonPrimitive?.contentOrNull
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun normalizeVideoReportStatus(rawStatus: String): String {
        val normalized = rawStatus.trim().lowercase()
            .replace(" ", "_")
            .replace("ó", "o")
            .replace("í", "i")
        return when (normalized) {
            "pendiente" -> "pendiente"
            "en_revision", "en_proceso", "revisado" -> "en_revision"
            "finalizado" -> "finalizado"
            "eliminado" -> "eliminado"
            "advertido" -> "advertido"
            "rechazado" -> "rechazado"
            else -> normalized
        }
    }

    private fun patchStatusCandidates(normalizedStatus: String): List<String> {
        return when (normalizedStatus) {
            "en_revision" -> listOf("en_revision", "en_proceso", "revisado")
            "finalizado" -> listOf("finalizado", "revisado")
            else -> listOf(normalizedStatus)
        }
    }

    private suspend fun notifyUserSuspensionStatus(
        userId: String,
        suspended: Boolean,
        suspendedUntil: String?,
        reason: String?,
        permanent: Boolean
    ) {
        val content = if (suspended) {
            val cleanReason = reason?.trim().takeUnless { it.isNullOrBlank() }
            val untilText = suspendedUntil?.replace("T", " ")?.take(16)
            if (permanent) {
                if (cleanReason.isNullOrBlank()) {
                    "Tu cuenta fue suspendida permanentemente por el equipo de moderación."
                } else {
                    "Tu cuenta fue suspendida permanentemente. Motivo: $cleanReason."
                }
            } else if (untilText.isNullOrBlank()) {
                if (cleanReason.isNullOrBlank()) {
                    "Tu cuenta fue suspendida temporalmente por el equipo de moderación."
                } else {
                    "Tu cuenta fue suspendida temporalmente. Motivo: $cleanReason."
                }
            } else {
                if (cleanReason.isNullOrBlank()) {
                    "Tu cuenta fue suspendida hasta $untilText por el equipo de moderación."
                } else {
                    "Tu cuenta fue suspendida hasta $untilText. Motivo: $cleanReason."
                }
            }
        } else {
            "Tu cuenta ha sido habilitada nuevamente."
        }
        runCatching {
            notificationRepository.sendNotification(
                recipientId = userId,
                type = "message",
                content = content,
                extraData = buildJsonObject {
                    put("notification_type", JsonPrimitive(if (suspended) "account_suspended" else "account_enabled"))
                    suspendedUntil?.let { put("suspended_until", JsonPrimitive(it)) }
                    reason?.takeIf { it.isNotBlank() }?.let { put("suspension_reason", JsonPrimitive(it)) }
                    put("permanent", JsonPrimitive(permanent))
                }
            )
        }
    }


    suspend fun deleteVideo(videoId: String): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            
            val response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/videos") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("id", "eq.$videoId")
            }
            
            if (response.status.isSuccess()) {
                 Result.success(Unit)
            } else {
                 Result.failure(Exception("Failed to delete video: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateVideo(
        videoId: String,
        title: String,
        description: String,
        isPublic: Boolean
    ): Result<Unit> {
        return try {
            val accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            
            val updateBody = mapOf(
                "title" to title,
                "description" to description,
                "is_public" to isPublic
            )
            
            val response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/videos") {
                 header("Authorization", "Bearer $accessToken")
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 header("Content-Type", "application/json")
                 parameter("id", "eq.$videoId")
                 setBody(updateBody)
            }
            
            if (response.status.isSuccess()) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to update video: ${response.status}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun updateLocalVideoStats(videoId: String, views: Int, likes: Int, comments: Int, shares: Int) {
        val current = videoStatsDao.getStats(videoId) ?: VideoStatsEntity(videoId)
        videoStatsDao.insertStats(current.copy(
            viewCount = views,
            likeCount = likes,
            commentCount = comments,
            shareCount = shares,
            lastUpdated = System.currentTimeMillis()
        ))
    }

    private suspend fun updateLocalVideoCommentCount(videoId: String, delta: Int) {
        try {
            val currentStats = videoStatsDao.getStats(videoId)
            if (currentStats != null) {
                val newCount = (currentStats.commentCount + delta).coerceAtLeast(0)
                videoStatsDao.insertStats(currentStats.copy(commentCount = newCount))
            } 
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error updating local comment count", e)
        }
    }

    private suspend fun updateLocalVideoViewCount(videoId: String, delta: Int) {
        try {
            val currentStats = videoStatsDao.getStats(videoId)
            if (currentStats != null) {
                val newCount = (currentStats.viewCount + delta).coerceAtLeast(0)
                videoStatsDao.insertStats(currentStats.copy(viewCount = newCount))
                android.util.Log.d("VideoRepository", "Local view count updated to $newCount for $videoId")
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error updating local view count", e)
        }
    }

    private suspend fun updateLocalVideoShareCount(videoId: String, delta: Int) {
        try {
            val currentStats = videoStatsDao.getStats(videoId)
            if (currentStats != null) {
                val newCount = (currentStats.shareCount + delta).coerceAtLeast(0)
                videoStatsDao.insertStats(currentStats.copy(shareCount = newCount))
            } 
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error updating local share count", e)
        }
    }

    private suspend fun notifyMentionsForVideoComment(
        videoId: String,
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
                    type = "mention_video",
                    targetId = videoId,
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

    private fun extractMentionUsernames(content: String): Set<String> {
        val mentionRegex = Regex("(?<![A-Za-z0-9_])@([A-Za-z0-9._]{2,30})")
        return mentionRegex.findAll(content)
            .map { it.groupValues[1].trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
    }
}
