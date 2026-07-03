package conectate.com.data.repository

import conectate.com.core.concurrency.newSafeScope
import conectate.com.data.model.Video
import conectate.com.data.model.VideoComment
import conectate.com.data.model.VideoReport
import conectate.com.data.cache.UpstashCacheService
import conectate.com.data.repository.HashtagRepository
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.io.readBytes
import javax.inject.Inject
import javax.inject.Singleton

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.supabase.StorageService
import java.io.File
import java.util.Locale
import java.util.UUID
import conectate.com.data.local.dao.VideoStatsDao
import conectate.com.util.VideoUtils
import conectate.com.util.VideoWatermarkExporter
import conectate.com.util.VideoWatermarkDetector
import conectate.com.data.local.dao.VideoCommentDao
import conectate.com.data.local.entity.VideoStatsEntity
import conectate.com.data.local.entity.VideoCommentEntity
import conectate.com.core.observability.AppMetrics
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
    private val hashtagRepository: HashtagRepository,
    private val upstashCache: UpstashCacheService,
    private val appMetrics: AppMetrics,
    private val localFallbackApiClient: LocalFallbackApiClient,
    @ApplicationContext private val context: Context
) {
    /**
     * Repository-level coroutine scope
     * Uses SupervisorJob so failures in one coroutine don't cancel others
     * Lives for the lifetime of this Singleton repository
     * Can be cancelled if needed for cleanup
     */
    private val repositoryScope = newSafeScope("VideoRepository", Dispatchers.IO)
    private val commentSubscriptionsMutex = Mutex()
    private val statsSubscriptionsMutex = Mutex()
    private val activeCommentSubscriptionJobs = mutableMapOf<String, Job>()
    private val activeCommentCollectors = mutableMapOf<String, Int>()
    private val activeStatsSubscriptionJobs = mutableMapOf<String, Job>()
    private val activeStatsCollectors = mutableMapOf<String, Int>()
    private val cacheJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private fun shouldUseUbuntuBackend(): Boolean =
        localFallbackApiClient.isConfigured &&
            (supabaseConfig.isLocalFallbackPrimary || authService.isLocalFallbackSession())

    private companion object {
        private const val MAX_UPLOAD_DURATION_SECONDS = 45L
        private const val MIN_UPLOAD_DURATION_SECONDS = 3L
        private const val MIN_UPLOAD_DIMENSION = 360
        private const val MAX_UPLOAD_FILE_SIZE_BYTES = 100L * 1024L * 1024L
    }

    private data class UploadVideoMetadata(
        val durationSeconds: Long,
        val width: Int,
        val height: Int,
        val fileSizeBytes: Long
    )

    private fun canonicalVideoId(videoId: String): String =
        videoId.trim().lowercase()

    private fun normalizeCommentParentId(parentId: String?): String? {
        val clean = parentId?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return clean.takeUnless { it == "0" || it == "null" || it == "undefined" || it == "none" }
    }

    private suspend fun currentCanonicalVideoStats(videoId: String): Pair<String, VideoStatsEntity>? {
        val cleanVideoId = canonicalVideoId(videoId)
        if (cleanVideoId.isBlank()) return null
        videoStatsDao.deleteStatsAliases(cleanVideoId)
        val current = videoStatsDao.getStats(cleanVideoId) ?: VideoStatsEntity(cleanVideoId)
        return cleanVideoId to current
    }

    private suspend fun mergeWithLocalVideoStats(video: Video): Video {
        val cleanVideoId = canonicalVideoId(video.id)
        if (cleanVideoId.isBlank()) return video
        val stats = videoStatsDao.getStats(cleanVideoId) ?: return video
        return video.copy(
            views = maxOf(video.views, stats.viewCount),
            likeCount = maxOf(video.likeCount, stats.likeCount),
            commentCount = maxOf(video.commentCount, stats.commentCount),
            shareCount = maxOf(video.shareCount, stats.shareCount)
        )
    }

    private suspend fun validateUploadVideo(videoUri: VideoUriType): Result<UploadVideoMetadata> = withContext(Dispatchers.IO) {
        val fileSizeBytes = runCatching {
            context.contentResolver.openFileDescriptor(videoUri, "r")?.use { it.statSize }
        }.getOrNull() ?: -1L

        if (fileSizeBytes > MAX_UPLOAD_FILE_SIZE_BYTES) {
            return@withContext Result.failure(
                Exception(
                    "El video pesa ${formatUploadBytes(fileSizeBytes)}. Máximo permitido: ${formatUploadBytes(MAX_UPLOAD_FILE_SIZE_BYTES)}."
                )
            )
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            val durationSeconds = kotlin.math.ceil(durationMs / 1000.0).toLong()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
                ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
                ?: 0
            val fpsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            val fps = fpsStr?.toFloatOrNull() ?: 30f

            val isTooLarge = width > 1920 || height > 1920 || (width * height > 1930000)

            when {
                durationSeconds <= 0L -> Result.failure(Exception("No se pudo validar la duración del video."))
                durationSeconds > MAX_UPLOAD_DURATION_SECONDS -> Result.failure(
                    Exception("El video dura ${durationSeconds}s. El límite obligatorio es ${MAX_UPLOAD_DURATION_SECONDS}s.")
                )
                durationSeconds < MIN_UPLOAD_DURATION_SECONDS -> Result.failure(
                    Exception("El video es muy corto (${durationSeconds}s). Mínimo permitido: ${MIN_UPLOAD_DURATION_SECONDS}s.")
                )
                width < MIN_UPLOAD_DIMENSION || height < MIN_UPLOAD_DIMENSION -> Result.failure(
                    Exception("Resolución muy baja (${width}x$height). Mínimo: ${MIN_UPLOAD_DIMENSION}x${MIN_UPLOAD_DIMENSION}.")
                )
                isTooLarge -> Result.failure(
                    Exception("El video es de muy alta resolución (${width}x${height}). El máximo permitido es 1080p. Usa el editor para adaptarlo.")
                )
                fps > 31f -> Result.failure(
                    Exception("El video tiene demasiados FPS (${fps.toInt()}). El máximo permitido es 30fps. Usa el editor para adaptarlo.")
                )
                else -> Result.success(
                    UploadVideoMetadata(
                        durationSeconds = durationSeconds,
                        width = width,
                        height = height,
                        fileSizeBytes = fileSizeBytes.coerceAtLeast(0L)
                    )
                )
            }
        } catch (error: Exception) {
            Result.failure(Exception("No se pudo analizar el video antes de subirlo: ${error.message}", error))
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun formatUploadBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }

    private fun getSafeString(key: String, base: JsonObject): String? {
        var element = base[key]
        if (element == null || element is kotlinx.serialization.json.JsonNull) return null
        var str = try {
            element.jsonPrimitive.contentOrNull ?: element.toString().removeSurrounding("\"")
        } catch (e: Exception) {
            element.toString().removeSurrounding("\"")
        }
        return if (str == "null" || str.isEmpty()) null else str
    }

    private suspend fun fetchPublicVideosPage(offset: Int, limit: Int): List<Video> {
        var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/videos") {
            header("apikey", supabaseConfig.supabaseAnonKey)
            header("Authorization", "Bearer ${supabaseConfig.supabaseAnonKey}")
            parameter(
                "select",
                "id,user_id,user_name,user_profile_image,title,description,video_url,thumbnail_url," +
                    "duration,views,like_count,comment_count,share_count,country,city,created_at,updated_at," +
                    "is_public,tags,is_verified"
            )
            parameter("is_public", "eq.true")
            parameter("order", "created_at.desc")
            parameter("limit", limit)
            parameter("offset", offset)
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("Public video feed request failed with ${response.status.value}")
        }

        return response.body<List<Video>>().map { it.normalizedStorageUrls() }
    }

    fun getVideos(offset: Int = 0, limit: Int = 20, random: Boolean = false): Flow<List<Video>> = flow {
        val startedAt = System.currentTimeMillis()
        val cacheKey = "videos:public:offset=$offset:limit=$limit:random=$random"
        
        try {
            if (offset == 0 && !random) {
                val cached = upstashCache.get(cacheKey)
                val cachedVideos = cached
                    ?.let { runCatching { cacheJson.decodeFromString<List<Video>>(it) }.getOrNull() }
                    ?.map { it.normalizedStorageUrls() }
                    ?.takeIf { it.isNotEmpty() }
                if (cachedVideos != null) {
                    appMetrics.incrementCounter("videos_cache_hit")
                    emit(cachedVideos)
                }
            }

            var accessToken = authService.getAccessTokenAwait()
            if (accessToken != null) {
                if (shouldUseUbuntuBackend()) {
                    val result = localFallbackApiClient.loadVideos(accessToken, limit, offset)
                    if (result.isSuccess) {
                        val videos = if (random) {
                            result.getOrThrow().sortedByDescending { 
                                (it.likeCount * 3 + it.commentCount * 5 + it.shareCount * 10 + it.views) * Math.random() 
                            }
                        } else result.getOrThrow()
                        videos.forEach { video ->
                            updateLocalVideoStats(
                                video.id,
                                video.views,
                                video.likeCount,
                                video.commentCount,
                                video.shareCount
                            )
                        }
                        emit(videos.map { mergeWithLocalVideoStats(it.normalizedStorageUrls()) })
                    } else {
                        appMetrics.recordFailure("videos_fetch", result.exceptionOrNull(), mapOf("source" to "ubuntu"))
                        emit(emptyList())
                    }
                    return@flow
                }

                var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/videos") {
                    header("Authorization", "Bearer $accessToken")
                    header("apikey", supabaseConfig.supabaseAnonKey)
                    parameter("limit", limit.toString())
                    parameter("offset", offset.toString())
                    parameter("order", "created_at.desc")
                    parameter("select", "*")
                    parameter("is_public", "eq.true")
                }

                if (response.status.isSuccess()) {
                    val videos: List<Video> = response.body<List<Video>>().map { it.normalizedStorageUrls() }
                    val deliveredVideos = if (random) {
                        videos.sortedByDescending { 
                            (it.likeCount * 3 + it.commentCount * 5 + it.shareCount * 10 + it.views) * Math.random() 
                        }
                    } else videos
                    
                    if (offset == 0 && !random && deliveredVideos.isNotEmpty()) {
                        val stored = runCatching {
                            upstashCache.set(cacheKey, cacheJson.encodeToString(deliveredVideos), 300)
                        }.getOrDefault(false)
                        if (stored) {
                            appMetrics.incrementCounter("videos_cache_store")
                        }
                    }

                    appMetrics.incrementCounter(
                        metric = "videos_fetch_success",
                        attributes = mapOf("source" to "public", "count" to deliveredVideos.size.toString())
                    )
                    appMetrics.recordLatency(
                        metric = "videos_fetch",
                        durationMs = System.currentTimeMillis() - startedAt,
                        attributes = mapOf("source" to "public")
                    )
                    emit(deliveredVideos)
                } else {
                    appMetrics.recordFailure(
                        metric = "videos_fetch",
                        attributes = mapOf("source" to "public", "http_status" to response.status.value.toString())
                    )
                    emit(emptyList())
                }
            } else {
                appMetrics.recordFailure(
                    metric = "videos_fetch",
                    attributes = mapOf("source" to "public", "reason" to "no_token")
                )
                emit(emptyList())
            }
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error fetching public videos", e)
            appMetrics.recordFailure("videos_fetch", e, mapOf("source" to "public"))
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    suspend fun getVideo(videoId: String): Result<Video> = withContext(Dispatchers.IO) {
        try {
            if (shouldUseUbuntuBackend()) {
                val token = authService.getAccessTokenAwait()
                    ?: return@withContext Result.failure(Exception("No access token available"))
                val result = localFallbackApiClient.loadVideo(token, videoId)
                if (result.isFailure) return@withContext result
                val video = result.getOrThrow().normalizedStorageUrls()
                updateLocalVideoStats(video.id, video.views, video.likeCount, video.commentCount, video.shareCount)
                return@withContext Result.success(mergeWithLocalVideoStats(video))
            }

            var video = supabaseConfig.client.from("videos").select {
                filter { eq("id", videoId) }
            }.decodeList<Video>().firstOrNull() ?: throw Exception("Video not found")

            // Fetch verification status
            val profileResponse = supabaseConfig.client.from("profiles")
                .select(Columns.list("is_verified")) {
                    filter { eq("id", video.userId) }
                }
            
            var isVerified = try {
                 var data = profileResponse.decodeList<JsonObject>().firstOrNull()
                 data?.get("is_verified")?.jsonPrimitive?.booleanOrNull ?: false
            } catch (e: Exception) { false }

            Result.success(video.copy(isVerified = isVerified).normalizedStorageUrls())
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "getVideo error", e)
            Result.failure(e)
        }
    }


    fun getFollowingVideos(): Flow<List<Video>> = flow {
        try {
            val currentUserId = authService.getCurrentUserId()
            if (currentUserId == null) {
                emit(emptyList())
                return@flow
            }

            val idResponse = supabaseConfig.client.from("follows")
                .select(Columns.list("following_id")) {
                    filter {
                        eq("follower_id", currentUserId)
                    }
                }
            
            val followingIds = try {
                var data = idResponse.decodeList<JsonObject>()
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
                
                var videos: List<Video> = videoResponse.decodeList<Video>().map { it.normalizedStorageUrls() }
                var sharedIds = getUserSharedContentIds(currentUserId, "video")
                videos = videos.map { it.copy(sharedByMe = sharedIds.contains(it.id)).normalizedStorageUrls() }
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
        videoUri: VideoUriType,
        thumbnailUri: android.net.Uri?,
        isPublic: Boolean,
        tags: List<String>
    ): Result<String> {
        return try {
            var userId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("Usuario no identificado"))
            val policyAccepted = userRepository.hasAcceptedPrivacyPolicy(userId).getOrElse { false }
            if (!policyAccepted) {
                return Result.failure(
                    Exception("Debes aceptar las políticas de privacidad antes de subir videos.")
                )
            }

            val uploadMetadata = validateUploadVideo(videoUri).getOrElse { error ->
                return Result.failure(error)
            }

            // Extraer hashtags de la descripción si no se proveyeron tags
            val finalTags = if (tags.isNotEmpty()) {
                tags
            } else {
                hashtagRepository.extractHashtagsFromText(description)
            }

            val watermarkDetection = VideoWatermarkDetector.detect(context, videoUri)
            if (watermarkDetection != null) {
                return Result.failure(
                    Exception(
                        "Marca de agua detectada: ${watermarkDetection.brand}. Descarga el video sin marca de agua antes de subirlo."
                    )
                )
            }

            // 1. Upload Video
            val videoBytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(videoUri)?.use { it.readBytes() }
            } ?: return Result.failure(Exception("No se pudo leer el video"))
            
            val videoFileName = "${userId}/${UUID.randomUUID()}.mp4"
            val videoUploadResult = storageService.uploadFile("videos", videoFileName, videoBytes, "video/mp4")
            
            if (videoUploadResult.isFailure) {
                return Result.failure(videoUploadResult.exceptionOrNull() ?: Exception("Error subiendo video"))
            }
            
            val videoPublicUrl = UbuntuStorageUrl.rewrite(storageService.getPublicUrl("videos", videoFileName)).orEmpty()

            // 2. Upload Thumbnail (Manual or Auto-generated)
            var thumbPublicUrl = ""
            
            // Try manual thumbnail first
            var thumbBytes: ByteArray? = null
            if (thumbnailUri != null && thumbnailUri != Uri.EMPTY) {
                try {
                    thumbBytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(thumbnailUri)?.use { it.readBytes() }
                    }
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
                        thumbPublicUrl = UbuntuStorageUrl.rewrite(storageService.getPublicUrl("thumbnails", thumbFileName)).orEmpty()
                        android.util.Log.d("VideoRepository", "Thumbnail uploaded: $thumbPublicUrl")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("VideoRepository", "Error uploading thumbnail", e)
                }
            }

            val currentProfile = userRepository.getUserById(userId).getOrNull()
                ?: userRepository.ensureCurrentProfile().getOrNull()

            val userNameStr = currentProfile?.username
                ?.takeIf { it.isNotBlank() }
                ?: currentProfile?.display_name
                    ?.takeIf { it.isNotBlank() }
                ?: currentProfile?.full_name
                    ?.takeIf { it.isNotBlank() }
                ?: currentProfile?.email
                    ?.substringBefore("@")
                    ?.takeIf { it.isNotBlank() }
                ?: "Usuario"
            val userImageStr = UbuntuStorageUrl.rewrite(currentProfile?.avatar_url).orEmpty()
            val isVerifiedVal = currentProfile?.is_verified ?: false

            // 3. Save Metadata
            // Using DTO for robust serialization
            var videoId = UUID.randomUUID().toString()
            val videoRequest = VideoInsertRequest(
                id = videoId,
                title = title,
                description = description,
                videoUrl = videoPublicUrl,
                thumbnailUrl = thumbPublicUrl,
                userId = userId,
                isPublic = isPublic,
                tags = finalTags,
                username = userNameStr,
                userProfileImage = userImageStr,
                isVerified = isVerifiedVal,
                duration = uploadMetadata.durationSeconds
            )

            if (shouldUseUbuntuBackend()) {
                val accessToken = authService.getAccessTokenAwait()
                    ?: return Result.failure(Exception("No access token available"))
                val created = localFallbackApiClient.createVideo(
                    accessToken = accessToken,
                    title = videoRequest.title,
                    description = videoRequest.description,
                    videoUrl = videoRequest.videoUrl,
                    thumbnailUrl = videoRequest.thumbnailUrl,
                    durationSeconds = videoRequest.duration,
                    isPublic = videoRequest.isPublic,
                    tags = videoRequest.tags
                )
                if (created.isFailure) {
                    return Result.failure(
                        created.exceptionOrNull() ?: Exception("Error guardando metadatos en Ubuntu")
                    )
                }
                val createdVideo = created.getOrThrow()
                updateLocalVideoStats(
                    createdVideo.id,
                    createdVideo.views,
                    createdVideo.likeCount,
                    createdVideo.commentCount,
                    createdVideo.shareCount
                )
                if (finalTags.isNotEmpty()) {
                    repositoryScope.launch {
                        runCatching { hashtagRepository.attachHashtagsToVideo(createdVideo.id, finalTags) }
                    }
                }
                return Result.success("Video publicado exitosamente")
            }

            // Insert into Supabase 'videos' table
             var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/videos") {
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
                  
                  // 5. Sincronizar hashtags localmente (cache) - NO bloquea UI
                  if (finalTags.isNotEmpty()) {
                      repositoryScope.launch {
                          try {
                              hashtagRepository.attachHashtagsToVideo(videoId, finalTags)
                          } catch (e: Exception) {
                              android.util.Log.e("VideoRepository", "Failed to sync hashtags locally", e)
                          }
                      }
                  }
                  
                  Result.success("Video publicado exitosamente")
              } else {
                 var errorBody = response.body<String>()
                 Result.failure(Exception("Error guardando metadatos: ${response.status} - $errorBody"))
             }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadVideoToGallery(video: Video): Result<String> = withContext(Dispatchers.IO) {
        try {
            val targetUrl = UbuntuStorageUrl.rewrite(video.videoUrl) ?: video.videoUrl
            var response = supabaseConfig.httpClient.get(targetUrl) {
                UbuntuStorageUrl.publicRequestHeaders.forEach { (name, value) -> header(name, value) }
            }
            if (!response.status.isSuccess()) {
                return@withContext Result.failure(
                    Exception("No se pudo descargar el video (${response.status.value}).")
                )
            }

            val bytes: ByteArray = response.body()
            if (bytes.isEmpty()) {
                return@withContext Result.failure(Exception("El video descargado está vacío."))
            }

            val sourceFile = File(context.cacheDir, "download_${System.currentTimeMillis()}.mp4")
            sourceFile.outputStream().use { stream ->
                stream.write(bytes)
                stream.flush()
            }

            val watermarkedFile = VideoWatermarkExporter.exportWithBrandWatermark(
                context = context,
                inputFile = sourceFile,
                userName = video.userName
            ).getOrElse { error ->
                sourceFile.delete()
                return@withContext Result.failure(error)
            }

            val safeUserName = sanitizeDownloadFilePart(video.userName.ifBlank { "usuario" })
            var filename = "Conectate_@${safeUserName}_${System.currentTimeMillis()}.mp4"
            val relativePath = "${Environment.DIRECTORY_MOVIES}/Conectate"
            val finalBytes = watermarkedFile.readBytes()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    put(MediaStore.MediaColumns.TITLE, "Conectate @${video.userName}")
                    put(MediaStore.Video.Media.DESCRIPTION, "Video descargado desde Conectate | @${video.userName}")
                }

                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return@withContext Result.failure(Exception("No se pudo crear el archivo en galería."))

                resolver.openOutputStream(uri)?.use { stream ->
                    stream.write(finalBytes)
                    stream.flush()
                } ?: return@withContext Result.failure(Exception("No se pudo escribir el video descargado."))

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            } else {
                val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                val conectateDir = java.io.File(moviesDir, "Conectate").apply {
                    if (!exists()) {
                        mkdirs()
                    }
                }
                val outputFile = java.io.File(conectateDir, filename)
                outputFile.outputStream().use { stream ->
                    stream.write(finalBytes)
                    stream.flush()
                }
            }

            sourceFile.delete()
            watermarkedFile.delete()

            Result.success(filename)
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error downloading video", e)
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
        @SerialName("is_verified") val isVerified: Boolean,
        val duration: Long
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
            if (shouldUseUbuntuBackend()) {
                val token = authService.getAccessTokenAwait()
                    ?: return@withContext Result.failure(Exception("No access token available"))
                return@withContext localFallbackApiClient.loadVideos(token, limit = 80, offset = 0, query = query)
            }

            // Using Postgrest 'or' for Title OR Description OR Tags
            var response = supabaseConfig.client.from("videos").select {
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

            var userIds = rawVideos.map { it.userId }.distinct()
            val profilesResponse = supabaseConfig.client.from("profiles")
                .select(Columns.list("id", "is_verified")) {
                    filter { isIn("id", userIds) }
                }
            
            val verifications = try {
                profilesResponse.decodeList<JsonObject>().associate {
                    it["id"]?.jsonPrimitive?.contentOrNull to (it["is_verified"]?.jsonPrimitive?.booleanOrNull ?: false)
                }
            } catch (e: Exception) { emptyMap() }

            var currentUserId = authService.getCurrentUserId()
            var likes = if (currentUserId != null) {
                var accessToken = authService.getAccessToken() ?: ""
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
            var accessToken = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("No access token available"))

            if (shouldUseUbuntuBackend()) {
                val result = localFallbackApiClient.loadVideo(accessToken, videoId)
                if (result.isFailure) return result
                val video = result.getOrThrow().normalizedStorageUrls()
                updateLocalVideoStats(video.id, video.views, video.likeCount, video.commentCount, video.shareCount)
                return Result.success(mergeWithLocalVideoStats(video))
            }

            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/videos") {
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
                var currentUserId = authService.getCurrentUserId()
                if (currentUserId != null) {
                    val likedIds = getUserLikedVideoIds(accessToken, currentUserId)
                    var sharedIds = getUserSharedContentIds(currentUserId, "video")
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
            var accessToken = authService.getAccessTokenAwait() // RPC might work with anon too if configured, but auth is safer
            if (shouldUseUbuntuBackend()) {
                val token = accessToken ?: return Result.failure(Exception("No access token"))
                val result = localFallbackApiClient.incrementVideoView(token, videoId)
                if (result.isSuccess) {
                    val exactCount = result.getOrNull()
                    if (exactCount != null) {
                        setLocalVideoViewCount(videoId, exactCount)
                    } else {
                        updateLocalVideoViewCount(videoId, 1)
                    }
                }
                return result.map { Unit }
            }
            
            // Use RPC to bypass RLS UPDATE restriction for views
            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/increment_video_views") {
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

    fun getCommentsFlow(videoId: String): Flow<List<VideoComment>> = callbackFlow {
        android.util.Log.d("VideoRepository", "getCommentsFlow called for videoId: $videoId")
        acquireCommentSubscription(videoId)

        val roomJob = launch {
            android.util.Log.d("VideoRepository", "Starting to collect from Room for videoId: $videoId")
            videoCommentDao.getCommentsFlow(videoId).collect { entities ->
                android.util.Log.d("VideoRepository", "Room emitted ${entities.size} comments for videoId: $videoId")
                trySend(entities.map { it.toDomain() })
            }
        }

        awaitClose {
            roomJob.cancel()
            repositoryScope.launch {
                releaseCommentSubscription(videoId)
            }
        }
    }.distinctUntilChanged()

    private suspend fun acquireCommentSubscription(videoId: String) {
        commentSubscriptionsMutex.withLock {
            var currentCollectors = activeCommentCollectors[videoId] ?: 0
            activeCommentCollectors[videoId] = currentCollectors + 1
            if (currentCollectors == 0) {
                activeCommentSubscriptionJobs[videoId] = repositoryScope.launch {
                    refreshCommentsFromNetwork(videoId)
                    subscribeToVideoComments(videoId)
                }
            }
        }
    }

    private suspend fun releaseCommentSubscription(videoId: String) {
        var jobToCancel = commentSubscriptionsMutex.withLock {
            var currentCollectors = activeCommentCollectors[videoId] ?: return@withLock null
            var remainingCollectors = currentCollectors - 1
            if (remainingCollectors > 0) {
                activeCommentCollectors[videoId] = remainingCollectors
                null
            } else {
                activeCommentCollectors.remove(videoId)
                activeCommentSubscriptionJobs.remove(videoId)
            }
        }
        jobToCancel?.cancel()
    }

    private suspend fun refreshCommentsFromNetwork(videoId: String) {
        try {
            android.util.Log.d("VideoRepository", "Fetching comments from network for videoId: $videoId")
            if (shouldUseUbuntuBackend()) {
                val token = authService.getAccessTokenAwait() ?: return
                val comments = localFallbackApiClient.loadVideoComments(token, videoId)
                    .getOrElse { error ->
                        android.util.Log.e("VideoRepository", "Failed to fetch Ubuntu comments", error)
                        return
                    }
                val entities = comments.map { VideoCommentEntity.fromDomain(it.normalizedStorageUrls()) }
                videoCommentDao.insertComments(entities)
                val mergedCount = maxOf(comments.size, videoCommentDao.getComments(videoId).size)
                setLocalVideoCommentCount(videoId, mergedCount)
                return
            }

            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/video_comments") {
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
                var comments = response.body<List<VideoComment>>().map { it.normalizedStorageUrls() }
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
        if (supabaseConfig.isLocalFallbackPrimary) {
            android.util.Log.d("VideoRepository", "Ubuntu primary backend: Supabase Realtime video comments disabled")
            return
        }
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            var realtimeChannel: RealtimeChannel? = null
            try {
                realtimeChannel = supabaseConfig.client.realtime.channel("video_comments_$videoId")
                
                android.util.Log.d("VideoRepository", "Creating Realtime channel: video_comments_$videoId")

                val commentFlow = realtimeChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
                    this.table = "video_comments"
                    this.filter = "video_id=eq.$videoId"
                }

                supabaseConfig.subscribeRealtimeChannel(
                    channel = realtimeChannel,
                    reason = "video-comments-$videoId"
                )
                android.util.Log.d("VideoRepository", "Subscribed to Realtime channel for video: $videoId")

                commentFlow.collect { change ->
                    android.util.Log.d("VideoRepository", "Realtime change received: ${change::class.simpleName}")
                    when (change) {
                        is PostgresAction.Insert -> {
                            var comment = change.decodeRecord<VideoComment>().normalizedStorageUrls()
                            // Insert into Room
                            repositoryScope.launch {
                                videoCommentDao.insertComment(VideoCommentEntity.fromDomain(comment))
                                updateLocalVideoCommentCount(videoId, 1)
                            }
                        }
                        is PostgresAction.Delete -> {
                            var id = change.oldRecord["id"]?.jsonPrimitive?.contentOrNull
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
            } catch (e: CancellationException) {
                android.util.Log.d("VideoRepository", "Comments subscription cancelled for video: $videoId")
                throw e
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

    fun getVideoStatsFlow(videoId: String): Flow<VideoStatsEntity?> = callbackFlow {
        val cleanVideoId = canonicalVideoId(videoId)
        if (cleanVideoId.isBlank()) {
            trySend(null)
            close()
            return@callbackFlow
        }

        videoStatsDao.deleteStatsAliases(cleanVideoId)
        acquireStatsSubscription(cleanVideoId)

        val statsJob = launch {
            videoStatsDao.getStatsFlow(cleanVideoId).collect {
                trySend(it)
            }
        }

        awaitClose {
            statsJob.cancel()
            repositoryScope.launch {
                releaseStatsSubscription(cleanVideoId)
            }
        }
    }.distinctUntilChanged()

    suspend fun loadVideoCommentsNow(videoId: String): Result<List<VideoComment>> = withContext(Dispatchers.IO) {
        try {
            val cleanVideoId = canonicalVideoId(videoId)
            if (cleanVideoId.isBlank()) {
                return@withContext Result.failure(Exception("Video invalido"))
            }

            if (shouldUseUbuntuBackend()) {
                val token = authService.getAccessTokenAwait()
                    ?: return@withContext Result.failure(Exception("No access token available"))
                val remoteComments = localFallbackApiClient.loadVideoComments(token, cleanVideoId)
                    .getOrElse { error ->
                        val localOnly = videoCommentDao.getComments(cleanVideoId)
                            .map { it.toDomain().normalizedStorageUrls() }
                        return@withContext if (localOnly.isNotEmpty()) {
                            Result.success(localOnly)
                        } else {
                            Result.failure(error)
                        }
                    }
                    .map {
                        it.copy(
                            videoId = cleanVideoId,
                            parentId = normalizeCommentParentId(it.parentId)
                        ).normalizedStorageUrls()
                    }
                val localComments = videoCommentDao.getComments(cleanVideoId)
                    .map { it.toDomain().normalizedStorageUrls() }
                val merged = mergeVideoComments(remoteComments + localComments)
                videoCommentDao.insertComments(merged.map { VideoCommentEntity.fromDomain(it) })
                setLocalVideoCommentCount(cleanVideoId, merged.size)
                return@withContext Result.success(merged)
            }

            refreshCommentsFromNetwork(cleanVideoId)
            val comments = videoCommentDao.getComments(cleanVideoId)
                .map { it.toDomain().normalizedStorageUrls() }
            Result.success(mergeVideoComments(comments))
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "loadVideoCommentsNow failed for $videoId", e)
            Result.failure(e)
        }
    }

    private fun mergeVideoComments(comments: List<VideoComment>): List<VideoComment> {
        return comments
            .asSequence()
            .map {
                it.copy(
                    id = it.id.trim(),
                    videoId = canonicalVideoId(it.videoId),
                    parentId = normalizeCommentParentId(it.parentId)
                ).normalizedStorageUrls()
            }
            .filter { it.id.isNotBlank() && it.videoId.isNotBlank() }
            .distinctBy { it.id.trim().lowercase() }
            .sortedByDescending { commentCreatedMillis(it.created_at) }
            .toList()
    }

    private fun commentCreatedMillis(value: String): Long {
        val clean = value.trim()
        if (clean.isBlank()) return 0L
        return runCatching { java.time.OffsetDateTime.parse(clean).toInstant().toEpochMilli() }
            .getOrElse {
                runCatching { java.time.Instant.parse(clean).toEpochMilli() }.getOrDefault(0L)
            }
    }

    private suspend fun acquireStatsSubscription(videoId: String) {
        statsSubscriptionsMutex.withLock {
            var currentCollectors = activeStatsCollectors[videoId] ?: 0
            activeStatsCollectors[videoId] = currentCollectors + 1
            if (currentCollectors == 0) {
                activeStatsSubscriptionJobs[videoId] = repositoryScope.launch {
                    refreshVideoStatsFromNetwork(videoId)
                    subscribeToVideoStats(videoId)
                }
            }
        }
    }

    private suspend fun releaseStatsSubscription(videoId: String) {
        var jobToCancel = statsSubscriptionsMutex.withLock {
            var currentCollectors = activeStatsCollectors[videoId] ?: return@withLock null
            var remainingCollectors = currentCollectors - 1
            if (remainingCollectors > 0) {
                activeStatsCollectors[videoId] = remainingCollectors
                null
            } else {
                activeStatsCollectors.remove(videoId)
                activeStatsSubscriptionJobs.remove(videoId)
            }
        }
        jobToCancel?.cancel()
    }

    private suspend fun refreshVideoStatsFromNetwork(videoId: String) {
        if (shouldUseUbuntuBackend()) {
            val token = authService.getAccessTokenAwait() ?: return
            val video = localFallbackApiClient.loadVideo(token, videoId)
                .getOrElse { error ->
                    android.util.Log.e("VideoRepository", "Ubuntu video stats refresh failed for $videoId", error)
                    return
                }
            updateLocalVideoStats(video.id, video.views, video.likeCount, video.commentCount, video.shareCount)
            return
        }

        val realLikes = getRealLikeCount(videoId)
        val realComments = getRealCommentCount(videoId)
        val realViews = getRealViewCount(videoId)
        val realShares = getRealShareCount(videoId)

        val cleanVideoId = canonicalVideoId(videoId)
        if (cleanVideoId.isBlank()) return
        videoStatsDao.deleteStatsAliases(cleanVideoId)
        videoStatsDao.insertStats(
            VideoStatsEntity(
                videoId = cleanVideoId,
                likeCount = realLikes,
                commentCount = realComments,
                viewCount = realViews,
                shareCount = realShares
            )
        )
    }



    private suspend fun subscribeToVideoStats(videoId: String) {
        if (supabaseConfig.isLocalFallbackPrimary) {
            android.util.Log.d("VideoRepository", "Ubuntu primary backend: Supabase Realtime video stats disabled")
            return
        }
        while (currentCoroutineContext().isActive) {
            var channel = supabaseConfig.client.realtime.channel("video_stats_$videoId")

            try {
                supabaseConfig.subscribeRealtimeChannel(
                    channel = channel,
                    reason = "video-stats-$videoId"
                )

                kotlinx.coroutines.coroutineScope {
                    launch {
                        val videoStatsChanges = channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                            this.table = "videos"
                            this.filter = "id=eq.$videoId"
                        }

                        videoStatsChanges.collect { update ->
                            val rawData = update.record
                            var views = rawData["views"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                            var likeCount = rawData["like_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                            var commentCount = rawData["comment_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                            var shareCount = rawData["share_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0

                            android.util.Log.d("VideoRepository", "Stats update received for $videoId: views=$views, likes=$likeCount, shares=$shareCount")
                            updateLocalVideoStats(videoId, views, likeCount, commentCount, shareCount)
                        }
                    }

                    launch {
                        val likeChanges = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                            this.table = "video_likes"
                            this.filter = "video_id=eq.$videoId"
                        }

                        likeChanges.collect {
                            var newCount = getRealLikeCount(videoId)
                            if (newCount >= 0) {
                                val (cleanVideoId, current) = currentCanonicalVideoStats(videoId) ?: return@collect
                                videoStatsDao.insertStats(
                                    current.copy(
                                        videoId = cleanVideoId,
                                        likeCount = newCount,
                                        lastUpdated = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                android.util.Log.d(
                    "VideoRepository",
                    "subscribeToVideoStats cancelled for $videoId: ${e.javaClass.simpleName}"
                )
                throw e
            } catch (e: Exception) {
                android.util.Log.e("VideoRepository", "Error in subscribeToVideoStats for $videoId", e)
                delay(3000)
                refreshVideoStatsFromNetwork(videoId)
            } finally {
                runCatching { channel.unsubscribe() }
                    .onFailure { unsubscribeError ->
                        android.util.Log.e("VideoRepository", "Error unsubscribing video stats channel for $videoId", unsubscribeError)
                    }
            }
        }
    }

    private suspend fun getRealLikeCount(videoId: String): Int {
         return try {
             var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/video_likes") {
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 parameter("video_id", "eq.$videoId")
                 header("Prefer", "count=exact")
                 parameter("select", "id")
                 parameter("limit", "1")
             }
             if (response.status.isSuccess()) {
                 var contentRange = response.headers["Content-Range"]
                 contentRange?.substringAfterLast("/")?.toIntOrNull() ?: 0
             } else 0
         } catch (e: CancellationException) {
             android.util.Log.d("VideoRepository", "Like count refresh cancelled for $videoId")
             throw e
         } catch (e: Exception) { 0 }
    }

    private suspend fun getRealCommentCount(videoId: String): Int {
         return try {
             var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/video_comments") {
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 parameter("video_id", "eq.$videoId")
                 header("Prefer", "count=exact")
                 parameter("select", "id")
                 parameter("limit", "1")
             }
             if (response.status.isSuccess()) {
                 var contentRange = response.headers["Content-Range"]
                 contentRange?.substringAfterLast("/")?.toIntOrNull() ?: 0
             } else 0
         } catch (e: CancellationException) {
             android.util.Log.d("VideoRepository", "Comment count refresh cancelled for $videoId")
             throw e
         } catch (e: Exception) { 0 }
    }

    private suspend fun getRealViewCount(videoId: String): Int {
         return try {
             var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/videos") {
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 parameter("id", "eq.$videoId")
                 parameter("select", "views")
             }
             if (response.status.isSuccess()) {
                 var videos = response.body<List<Map<String, Int>>>()
                 var views = videos.firstOrNull()?.get("views") ?: 0
                 android.util.Log.d("VideoRepository", "Fetched views for $videoId: $views")
                 views
             } else {
                 android.util.Log.e("VideoRepository", "Failed to fetch views for $videoId: ${response.status}")
                 0
             }
         } catch (e: CancellationException) {
             android.util.Log.d("VideoRepository", "View count refresh cancelled for $videoId")
             throw e
         } catch (e: Exception) {
             android.util.Log.e("VideoRepository", "Exception fetching views for $videoId", e)
             0 
         }
    }

    private suspend fun getRealShareCount(videoId: String): Int {
         return try {
             var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/videos") {
                 header("Authorization", "Bearer ${authService.getAccessToken()}")
                 header("apikey", supabaseConfig.supabaseAnonKey)
                 parameter("id", "eq.$videoId")
                 parameter("select", "share_count")
             }
             if (response.status.isSuccess()) {
                 var videos = response.body<List<Map<String, Int>>>()
                 videos.firstOrNull()?.get("share_count") ?: 0
             } else 0
         } catch (e: CancellationException) {
             android.util.Log.d("VideoRepository", "Share count refresh cancelled for $videoId")
             throw e
         } catch (e: Exception) { 0 }
    }

    suspend fun addComment(
        videoId: String, 
        content: String,
        contentType: String = "text",
        mediaUrl: String? = null,
        parentId: String? = null
    ): Result<VideoComment> {
        return try {
            android.util.Log.d(
                "VideoRepository",
                "addComment called for videoId=$videoId, contentType=$contentType, contentLength=${content.length}"
            )
            var accessToken = authService.getAccessTokenAwait()
                ?: return Result.failure(Exception("No access token available"))

            var userId = authService.getCurrentUserId()
                ?: return Result.failure(Exception("No user ID available"))

            if (shouldUseUbuntuBackend()) {
                val inserted = localFallbackApiClient.addVideoComment(
                    accessToken = accessToken,
                    videoId = videoId,
                    content = content,
                    contentType = contentType,
                    mediaUrl = UbuntuStorageUrl.rewrite(mediaUrl),
                    parentId = parentId
                )
                if (inserted.isFailure) {
                    return Result.failure(inserted.exceptionOrNull() ?: Exception("No se pudo comentar en Ubuntu"))
                }
                val comment = inserted.getOrThrow()
                    .copy(videoId = videoId, parentId = normalizeCommentParentId(parentId))
                    .normalizedStorageUrls()
                withContext(Dispatchers.IO) {
                    videoCommentDao.insertComment(VideoCommentEntity.fromDomain(comment))
                }
                updateLocalVideoCommentCount(videoId, 1)
                refreshCommentsFromNetwork(videoId)
                return Result.success(comment)
            }

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
                     userAvatar = UbuntuStorageUrl.rewrite(getSafeString("avatar_url", p ?: JsonObject(emptyMap()))).orEmpty()
                     android.util.Log.d("VideoRepository", "User profile fetched for video comment author")
                }
            } catch (e: Exception) {
                android.util.Log.e("VideoRepository", "Error fetching user profile", e)
            }
            val normalizedMediaUrl = UbuntuStorageUrl.rewrite(mediaUrl)

            var commentId = java.util.UUID.randomUUID().toString()
            
            android.util.Log.d("VideoRepository", "Sending video comment for videoId=$videoId with commentId=$commentId")
            
            var data = buildJsonObject {
                put("id", commentId)
                put("video_id", videoId)
                put("user_id", userId)
                put("content", content)
                put("created_at", java.time.OffsetDateTime.now().toString())
                if (userName.isNotBlank()) put("user_name", userName)
                if (userAvatar.isNotBlank()) put("user_profile_image", userAvatar)
                if (contentType.isNotBlank()) put("content_type", contentType)
                if (normalizedMediaUrl != null) put("media_url", normalizedMediaUrl)
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
                var errorBody = response.body<String>()
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
                        if (normalizedMediaUrl != null) put("media_url", normalizedMediaUrl)
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
                android.util.Log.d("VideoRepository", "Video comment insert succeeded for commentId=$commentId")
                
                try {
                    val insertedComments = Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString<List<VideoComment>>(rawBody)
                    val insertedComment = (insertedComments.firstOrNull() ?: VideoComment(
                        id = commentId,
                        videoId = videoId,
                        userId = userId,
                        userName = userName,
                        userProfileImage = userAvatar,
                        content = content,
                        contentType = contentType,
                        mediaUrl = normalizedMediaUrl
                    )).copy(parentId = normalizeCommentParentId(parentId)).normalizedStorageUrls()
                    
                    android.util.Log.d("VideoRepository", "Inserting to Room: ${insertedComment.id}")
                    withContext(Dispatchers.IO) {
                        videoCommentDao.insertComment(VideoCommentEntity.fromDomain(insertedComment.normalizedStorageUrls()))
                    }
                    
                    refreshCommentsFromNetwork(videoId)
                    
                    // Trigger owner/reply notification and mentions.
                    val primaryRecipients = mutableSetOf<String>()
                    val trimmedPreview = content.trim().take(160)
                    if (!parentId.isNullOrBlank()) {
                        resolveVideoCommentOwnerId(parentId)?.let { parentOwnerId ->
                            if (primaryRecipients.add(parentOwnerId)) {
                                notificationRepository.sendNotification(
                                    recipientId = parentOwnerId,
                                    type = "reply_video",
                                    targetId = videoId,
                                    content = trimmedPreview,
                                    extraData = buildJsonObject { 
                                        put("comment_id", JsonPrimitive(commentId))
                                        put("parent_id", JsonPrimitive(parentId))
                                    }
                                )
                            }
                        }
                    }
                    getVideo(videoId).onSuccess { video ->
                        if (primaryRecipients.add(video.userId)) {
                            notificationRepository.sendNotification(
                                recipientId = video.userId,
                                type = if (parentId == null) "comment_video" else "reply_video",
                                targetId = videoId,
                                content = trimmedPreview,
                                extraData = buildJsonObject { 
                                    put("comment_id", JsonPrimitive(commentId))
                                    if (parentId != null) put("parent_id", JsonPrimitive(parentId))
                                }
                            )
                        }
                    }
                    notifyMentionsForVideoComment(
                        videoId = videoId,
                        commentId = commentId,
                        parentId = parentId,
                        commentContent = content,
                        actorId = userId,
                        alreadyNotifiedUserIds = primaryRecipients
                    )
                    
                    Result.success(insertedComment)
                } catch (e: Exception) {
                    android.util.Log.e("VideoRepository", "Error processing success response", e)
                    val fallbackComment = VideoComment(
                        id = commentId,
                        videoId = videoId,
                        userId = userId,
                        userName = userName,
                        userProfileImage = userAvatar,
                        content = content,
                        created_at = java.time.OffsetDateTime.now().toString(),
                        parentId = normalizeCommentParentId(parentId),
                        contentType = contentType,
                        mediaUrl = normalizedMediaUrl
                    ).normalizedStorageUrls()
                    withContext(Dispatchers.IO) {
                        videoCommentDao.insertComment(VideoCommentEntity.fromDomain(fallbackComment))
                    }
                    refreshCommentsFromNetwork(videoId)
                    Result.success(fallbackComment)
                }
            } else {
                var errorBody = response.body<String>()
                android.util.Log.e(
                    "VideoRepository",
                    "Final Supabase error while adding comment: ${response.status} - ${errorBody.take(200)}"
                )
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
            var filename = "comment_${UUID.randomUUID()}.m4a"
            android.util.Log.d("VideoRepository", "Uploading audio file: $filename, size=${bytes.size} bytes")
            val uploadResult = storageService.uploadFile("video-comments-media", filename, bytes, "audio/mp4")
            
            if (uploadResult.isSuccess) {
                 val publicUrl = UbuntuStorageUrl.rewrite(storageService.getPublicUrl("video-comments-media", filename)).orEmpty()
                 android.util.Log.d("VideoRepository", "Audio uploaded successfully: $publicUrl")
                 // Return the result of addComment directly
                 return addComment(
                     videoId = videoId,
                     content = "[Audio]",
                     contentType = "audio",
                     mediaUrl = publicUrl
                 ).map { Unit }
            } else {
                var error = uploadResult.exceptionOrNull() ?: Exception("Upload failed")
                android.util.Log.e("VideoRepository", "Audio upload failed: ${error.message}", error)
                return Result.failure(error)
            }
        } catch (e: Exception) { 
            android.util.Log.e("VideoRepository", "Exception in sendAudioComment", e)
            Result.failure(e) 
        }
    }

    suspend fun getCommentAudioUrl(pathOrUrl: String): String {
        return if (pathOrUrl.startsWith("http")) {
            UbuntuStorageUrl.rewrite(pathOrUrl).orEmpty()
        } else {
            UbuntuStorageUrl.rewrite(storageService.getPublicUrl("video-comments-media", pathOrUrl)).orEmpty()
        }
    }

    suspend fun deleteComment(commentId: String): Result<Unit> {
        return try {
            var accessToken = authService.getAccessTokenAwait() ?: return Result.failure(Exception("No token"))

            if (shouldUseUbuntuBackend()) {
                val result = localFallbackApiClient.deleteVideoComment(accessToken, commentId)
                if (result.isSuccess) {
                    withContext(Dispatchers.IO) {
                        videoCommentDao.deleteComment(commentId)
                    }
                }
                return result
            }

            android.util.Log.d("VideoRepository", "Deleting comment from Supabase: $commentId")
            var rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_delete_video_comment") {
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
                var fallbackResponse = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/video_comments") {
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
            var accessToken = authService.getAccessTokenAwait() ?: return Result.failure(Exception("No access token"))
            var userId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user ID"))

            if (shouldUseUbuntuBackend()) {
                val result = localFallbackApiClient.setVideoReaction(accessToken, videoId, liked = true)
                if (result.isSuccess) incrementLikeCount(videoId, 1)
                return result
            }

            val likeData = mapOf("video_id" to videoId, "user_id" to userId)
            
            // Insert into video_likes
            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/video_likes") {
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
                var errorBody = try { response.body<String>() } catch (e: Exception) { "" }
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
            var accessToken = authService.getAccessTokenAwait() ?: return Result.failure(Exception("No access token"))
            var userId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user ID"))

            if (shouldUseUbuntuBackend()) {
                val result = localFallbackApiClient.setVideoReaction(accessToken, videoId, liked = false)
                if (result.isSuccess) incrementLikeCount(videoId, -1)
                return result
            }

            var response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/video_likes") {
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
            if (shouldUseUbuntuBackend()) {
                val cleanVideoId = canonicalVideoId(videoId)
                if (cleanVideoId.isBlank()) return
                val current = currentCanonicalVideoStats(cleanVideoId)?.second ?: return
                videoStatsDao.insertStats(
                    current.copy(
                        videoId = cleanVideoId,
                        likeCount = (current.likeCount + delta).coerceAtLeast(0),
                        lastUpdated = System.currentTimeMillis()
                    )
                )
                return
            }

            var accessToken = authService.getAccessTokenAwait() ?: return
            
            // Use RPC for atomic update (avoids race conditions)
            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/increment_like_count") {
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
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))

            if (shouldUseUbuntuBackend()) {
                val shareResult = if (delta > 0) {
                    localFallbackApiClient.shareVideo(accessToken, videoId).getOrThrow()
                } else {
                    localFallbackApiClient.unshareVideo(accessToken, videoId).getOrThrow()
                }
                val exactCount = shareResult.shareCount
                if (exactCount != null) {
                    setLocalVideoShareCount(videoId, exactCount)
                } else {
                    updateLocalVideoShareCount(videoId, delta)
                }
                if (delta > 0) {
                    getVideo(videoId).onSuccess { video ->
                        notificationRepository.sendNotification(
                            recipientId = video.userId,
                            type = "share_video",
                            targetId = videoId
                        )
                    }
                }
                return Result.success(Unit)
            }

            val rpcParams = buildJsonObject {
                put("video_id", videoId)
                put("delta", delta)
            }

            var response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/increment_share_count") {
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
                var error = response.bodyAsText()
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
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            var reporterId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user ID"))
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/video_reports") {
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
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            var reporterId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user ID"))
            val normalizedReason = reason.trim()
            if (normalizedReason.isBlank()) {
                return Result.failure(Exception("El motivo del reporte es obligatorio"))
            }

            var rpcPayload = buildJsonObject {
                put("p_video_id", JsonPrimitive(videoId))
                put("p_reason", JsonPrimitive(normalizedReason))
                put("p_details", JsonPrimitive(details?.trim().orEmpty()))
            }
            var rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_submit_video_report") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(rpcPayload)
            }

            if (rpcResponse.status.isSuccess()) {
                return Result.success(Unit)
            }

            var rpcError = runCatching { rpcResponse.bodyAsText() }.getOrDefault("")
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
            var video = getVideo(videoId).getOrNull() ?: return Result.failure(Exception("Video no encontrado"))
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
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            var reporterId = authService.getCurrentUserId() ?: return Result.failure(Exception("No user ID"))
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/video_reports") {
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
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/video_reports") {
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
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            val normalizedStatus = normalizeVideoReportStatus(newStatus)
            var rpcPayload = buildJsonObject {
                put("p_report_id", JsonPrimitive(reportId))
                put("p_status", JsonPrimitive(normalizedStatus))
                actionNote?.let { put("p_action_note", JsonPrimitive(it)) }
            }

            var rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_admin_update_video_report_status") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(rpcPayload)
            }
            if (rpcResponse.status.isSuccess()) {
                return Result.success(Unit)
            }

            var rpcError = runCatching { rpcResponse.bodyAsText() }.getOrDefault("")
            if (!isMissingRpcFunction(rpcResponse.status.value, rpcError, "secure_admin_update_video_report_status")) {
                return Result.failure(Exception("Error actualizando reporte: ${rpcResponse.status.value}"))
            }

            val candidateStatuses = patchStatusCandidates(normalizedStatus)
            var lastFailure: Exception? = null

            candidateStatuses.forEach { statusCandidate ->
                var payload = buildJsonObject {
                    put("status", JsonPrimitive(statusCandidate))
                    put("reviewed_at", JsonPrimitive(java.time.OffsetDateTime.now().toString()))
                    authService.getCurrentUserId()?.let { put("reviewed_by", JsonPrimitive(it)) }
                    actionNote?.let { put("action_note", JsonPrimitive(it)) }
                    warnSent?.let { put("warn_sent", JsonPrimitive(it)) }
                    strikeApplied?.let { put("strike_applied", JsonPrimitive(it)) }
                }

                var response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/video_reports") {
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
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            var rpcPayload = buildJsonObject {
                put("p_report_id", JsonPrimitive(report.id))
                put("p_message", JsonPrimitive(warningMessage))
            }

            var rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_admin_warn_video_owner_from_report") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(rpcPayload)
            }
            if (rpcResponse.status.isSuccess()) {
                return Result.success(Unit)
            }

            var rpcError = runCatching { rpcResponse.bodyAsText() }.getOrDefault("")
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
        var normalizedDuration = durationValue.coerceAtLeast(1)
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
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            var normalizedDuration = durationValue.coerceAtLeast(1)
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

            var rpcResponse = callAdminSuspensionRpc(accessToken, extendedRpcPayload)

            if (rpcResponse.status.isSuccess()) {
                var body = runCatching { rpcResponse.bodyAsText() }.getOrDefault("")
                var until = extractSuspendedUntil(body)
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
                    var body = runCatching { legacyRpcResponse.bodyAsText() }.getOrDefault("")
                    var until = extractSuspendedUntil(body)
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
                    var until = calculateSuspensionEndIso(
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
            var fallbackResponse = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/profiles") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                parameter("id", "eq.$userId")
                setBody(fallbackPayload)
            }
            if (fallbackResponse.status.isSuccess()) {
                var until = fallbackPayload["suspended_until"]?.jsonPrimitive?.contentOrNull
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
        var lower = body.lowercase()
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
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            var rpcPayload = buildJsonObject {
                put("p_report_id", JsonPrimitive(report.id))
                put("p_action_note", JsonPrimitive(actionNote))
            }

            var rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_admin_delete_reported_video") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Content-Type", "application/json")
                setBody(rpcPayload)
            }
            if (rpcResponse.status.isSuccess()) {
                runCatching { videoCommentDao.deleteCommentsByVideo(report.videoId) }
                return Result.success(Unit)
            }

            var rpcError = runCatching { rpcResponse.bodyAsText() }.getOrDefault("")
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
        var lower = body.lowercase()
        return lower.contains(functionName.lowercase()) &&
            (lower.contains("could not find") || lower.contains("does not exist"))
    }

    private fun extractSuspendedUntil(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            var element = Json.parseToJsonElement(body)
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
        var content = if (suspended) {
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
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))

            if (shouldUseUbuntuBackend()) {
                val result = localFallbackApiClient.deleteVideo(accessToken, videoId)
                if (result.isSuccess) {
                    videoCommentDao.deleteCommentsByVideo(videoId)
                    val cleanVideoId = canonicalVideoId(videoId)
                    if (cleanVideoId.isNotBlank()) {
                        videoStatsDao.deleteStatsAliases(cleanVideoId)
                        videoStatsDao.insertStats(VideoStatsEntity(cleanVideoId))
                    }
                }
                return result
            }
            
            var response = supabaseConfig.httpClient.delete("${supabaseConfig.supabaseUrl}/rest/v1/videos") {
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
            var accessToken = authService.getAccessToken() ?: return Result.failure(Exception("No access token"))
            
            val updateBody = mapOf(
                "title" to title,
                "description" to description,
                "is_public" to isPublic
            )
            
            var response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/videos") {
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
        val (cleanVideoId, current) = currentCanonicalVideoStats(videoId) ?: return
        videoStatsDao.insertStats(current.copy(
            videoId = cleanVideoId,
            viewCount = maxOf(current.viewCount, views),
            likeCount = likes,
            commentCount = maxOf(current.commentCount, comments),
            shareCount = maxOf(current.shareCount, shares),
            lastUpdated = System.currentTimeMillis()
        ))
    }

    private suspend fun updateLocalVideoCommentCount(videoId: String, delta: Int) {
        try {
            val (cleanVideoId, currentStats) = currentCanonicalVideoStats(videoId) ?: return
            val newCount = (currentStats.commentCount + delta).coerceAtLeast(0)
            videoStatsDao.insertStats(
                currentStats.copy(
                    videoId = cleanVideoId,
                    commentCount = newCount,
                    lastUpdated = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error updating local comment count", e)
        }
    }

    private suspend fun setLocalVideoCommentCount(videoId: String, count: Int) {
        try {
            val (cleanVideoId, currentStats) = currentCanonicalVideoStats(videoId) ?: return
            videoStatsDao.insertStats(
                currentStats.copy(
                    videoId = cleanVideoId,
                    commentCount = maxOf(currentStats.commentCount, count.coerceAtLeast(0)),
                    lastUpdated = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error setting local comment count", e)
        }
    }

    private suspend fun updateLocalVideoViewCount(videoId: String, delta: Int) {
        try {
            val (cleanVideoId, currentStats) = currentCanonicalVideoStats(videoId) ?: return
            val newCount = (currentStats.viewCount + delta).coerceAtLeast(0)
            videoStatsDao.insertStats(
                currentStats.copy(
                    videoId = cleanVideoId,
                    viewCount = newCount,
                    lastUpdated = System.currentTimeMillis()
                )
            )
            android.util.Log.d("VideoRepository", "Local view count updated to $newCount for $videoId")
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error updating local view count", e)
        }
    }

    private suspend fun setLocalVideoViewCount(videoId: String, count: Int) {
        try {
            val (cleanVideoId, currentStats) = currentCanonicalVideoStats(videoId) ?: return
            videoStatsDao.insertStats(
                currentStats.copy(
                    videoId = cleanVideoId,
                    viewCount = maxOf(currentStats.viewCount, count.coerceAtLeast(0)),
                    lastUpdated = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error setting local view count", e)
        }
    }

    private suspend fun updateLocalVideoShareCount(videoId: String, delta: Int) {
        try {
            val (cleanVideoId, currentStats) = currentCanonicalVideoStats(videoId) ?: return
            val newCount = (currentStats.shareCount + delta).coerceAtLeast(0)
            videoStatsDao.insertStats(
                currentStats.copy(
                    videoId = cleanVideoId,
                    shareCount = newCount,
                    lastUpdated = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error updating local share count", e)
        }
    }

    private suspend fun setLocalVideoShareCount(videoId: String, count: Int) {
        try {
            val (cleanVideoId, currentStats) = currentCanonicalVideoStats(videoId) ?: return
            videoStatsDao.insertStats(
                currentStats.copy(
                    videoId = cleanVideoId,
                    shareCount = count.coerceAtLeast(0),
                    lastUpdated = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error setting local share count", e)
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

    private suspend fun resolveVideoCommentOwnerId(commentId: String): String? {
        val cleanId = commentId.trim()
        if (cleanId.isBlank()) return null

        val local = runCatching { videoCommentDao.getCommentById(cleanId) }.getOrNull()
        if (local != null) {
            return local.userId.trim().ifBlank { null }
        }

        var token = authService.getAccessToken() ?: return null
        return runCatching {
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/video_comments") {
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

    private fun sanitizeDownloadFilePart(value: String): String {
        return value
            .trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "usuario" }
    }

    fun getUserVideos(userId: String): Flow<List<Video>> = flow {
        var cleanUserId = userId.trim().lowercase()
        if (cleanUserId.isBlank()) {
            emit(emptyList<Video>())
            return@flow
        }

        try {
            if (shouldUseUbuntuBackend()) {
                val token = authService.getAccessToken()
                if (token.isNullOrBlank()) {
                    emit(emptyList<Video>())
                    return@flow
                }
                val videos = localFallbackApiClient.loadVideos(
                    accessToken = token,
                    limit = 100,
                    offset = 0,
                    userId = cleanUserId
                ).getOrThrow()
                val normalized = videos.map { it.normalizedStorageUrls() }
                normalized.forEach { video ->
                    updateLocalVideoStats(
                        video.id,
                        video.views,
                        video.likeCount,
                        video.commentCount,
                        video.shareCount
                    )
                }
                emit(normalized.map { mergeWithLocalVideoStats(it) })
                return@flow
            }

            var currentUserId = authService.getCurrentUserId()?.trim()?.lowercase()
            val canViewPrivate = currentUserId == cleanUserId

            var response = supabaseConfig.client.from("videos").select {
                filter {
                    eq("user_id", cleanUserId)
                    if (!canViewPrivate) {
                        eq("is_public", true)
                    }
                }
                order("created_at", Order.DESCENDING)
            }

            val videos: List<Video> = response.decodeList<Video>().map { it.normalizedStorageUrls() }
            emit(videos)
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error loading user videos for $cleanUserId", e)
            emit(emptyList<Video>())
        }
    }.flowOn(Dispatchers.IO)

    fun getSharedVideos(userId: String): Flow<List<Video>> = flow {
        var cleanUserId = userId.trim().lowercase()
        if (cleanUserId.isBlank()) {
            emit(emptyList<Video>())
            return@flow
        }

        var token = authService.getAccessToken()
        if (token.isNullOrBlank()) {
            emit(emptyList<Video>())
            return@flow
        }

        try {
            if (shouldUseUbuntuBackend()) {
                val videos = localFallbackApiClient.loadVideos(
                    accessToken = token,
                    limit = 100,
                    offset = 0,
                    sharedBy = cleanUserId
                ).getOrThrow()
                val normalized = videos.map {
                    it.copy(sharedByMe = cleanUserId == authService.getCurrentUserId()?.trim()?.lowercase())
                        .normalizedStorageUrls()
                }
                normalized.forEach { video ->
                    updateLocalVideoStats(
                        video.id,
                        video.views,
                        video.likeCount,
                        video.commentCount,
                        video.shareCount
                    )
                }
                emit(normalized.map { mergeWithLocalVideoStats(it) })
                return@flow
            }

            var sharedIds = getUserSharedContentIds(cleanUserId, "video", token)
            if (sharedIds.isEmpty()) {
                emit(emptyList<Video>())
                return@flow
            }

            var response = supabaseConfig.client.from("videos").select {
                filter {
                    isIn("id", sharedIds.toList())
                    eq("is_public", true)
                }
                order("created_at", Order.DESCENDING)
            }

            val videos: List<Video> = response.decodeList()
            emit(videos.map {
                it.copy(sharedByMe = cleanUserId == authService.getCurrentUserId()?.trim()?.lowercase())
                    .normalizedStorageUrls()
            })
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error loading shared videos for $cleanUserId", e)
            emit(emptyList<Video>())
        }
    }.flowOn(Dispatchers.IO)

    suspend fun hasPublicVideos(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/videos") {
                header("apikey", supabaseConfig.supabaseAnonKey)
                authService.getAccessToken()?.let { header("Authorization", "Bearer $it") }
                parameter("select", "id")
                parameter("is_public", "eq.true")
                parameter("limit", "1")
            }

            if (!response.status.isSuccess()) {
                return@withContext Result.failure(Exception("Failed to check public videos: ${response.status}"))
            }

            val rows: List<JsonObject> = response.body()
            Result.success(rows.isNotEmpty())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun Video.normalizedStorageUrls(): Video = copy(
        userProfileImage = UbuntuStorageUrl.rewrite(userProfileImage).orEmpty(),
        videoUrl = UbuntuStorageUrl.rewrite(videoUrl).orEmpty(),
        thumbnailUrl = UbuntuStorageUrl.rewrite(thumbnailUrl).orEmpty()
    )

    private fun VideoComment.normalizedStorageUrls(): VideoComment = copy(
        userProfileImage = UbuntuStorageUrl.rewrite(userProfileImage),
        mediaUrl = UbuntuStorageUrl.rewrite(mediaUrl),
        profiles = profiles?.copy(avatarUrl = UbuntuStorageUrl.rewrite(profiles.avatarUrl))
    )

    private suspend fun getUserLikedVideoIds(accessToken: String, userId: String): Set<String> {
        if (accessToken.isBlank() || userId.isBlank()) return emptySet()

        return try {
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/video_likes") {
                header("Authorization", "Bearer $accessToken")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("user_id", "eq.${userId.trim().lowercase()}")
                parameter("select", "video_id")
            }

            if (!response.status.isSuccess()) {
                emptySet()
            } else {
                val data: List<JsonObject> = response.body()
                data.mapNotNull { it["video_id"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() }
                    .filter { it.isNotBlank() }
                    .toSet()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private suspend fun getUserSharedContentIds(
        userId: String,
        contentType: String,
        accessToken: String? = authService.getAccessToken()
    ): Set<String> {
        var cleanUserId = userId.trim().lowercase()
        var token = accessToken?.trim().orEmpty()
        if (cleanUserId.isBlank() || token.isBlank()) return emptySet()

        return try {
            var response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/shares") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("user_id", "eq.$cleanUserId")
                parameter("select", "post_id,video_id")
            }

            if (!response.status.isSuccess()) {
                emptySet()
            } else {
                val data: List<JsonObject> = response.body()
                data.mapNotNull { row ->
                    if (contentType == "post") {
                        row["post_id"]?.jsonPrimitive?.contentOrNull
                    } else {
                        row["video_id"]?.jsonPrimitive?.contentOrNull
                    }?.trim()?.lowercase()
                }.filter { it.isNotBlank() }.toSet()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }
}





