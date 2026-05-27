package conectate.com.data.repository

import android.util.Log
import conectate.com.core.observability.AppMetrics
import conectate.com.data.local.dao.HashtagDao
import conectate.com.data.local.entity.HashtagEntity
import conectate.com.data.local.entity.PostHashtagEntity
import conectate.com.data.local.entity.VideoHashtagEntity
import conectate.com.data.model.Hashtag
import conectate.com.data.model.Post
import conectate.com.data.model.TrendingHashtag
import conectate.com.data.model.Video
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HashtagRepository @Inject constructor(
    private val hashtagDao: HashtagDao,
    private val appMetrics: AppMetrics,
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService
) {
    companion object {
        private const val TAG = "HashtagRepository"
        private const val MAX_HASHTAGS_PER_POST = 5
    }

    fun extractHashtagsFromText(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return Regex("#[a-zA-Z0-9_]+")
            .findAll(text)
            .map { normalizeHashtag(it.value) }
            .filter { it.length in 2..50 }
            .distinct()
            .take(MAX_HASHTAGS_PER_POST)
            .toList()
    }

    suspend fun attachHashtagsToPost(postId: String, hashtags: List<String>) = withContext(Dispatchers.IO) {
        val normalizedTags = hashtags.map(::normalizeHashtag).filter { it.isNotBlank() }.distinct()
        if (normalizedTags.isEmpty()) return@withContext
        runCatching {
            normalizedTags.forEachIndexed { index, tagName ->
                val tag = upsertHashtag(tagName = tagName, incrementPostCount = true)
                hashtagDao.insertPostHashtag(
                    PostHashtagEntity(
                        postId = postId,
                        hashtagId = tag.id,
                        position = index
                    )
                )
            }
            appMetrics.incrementCounter("hashtags_attached_to_post")
        }.onFailure {
            Log.e(TAG, "Error attaching hashtags to post=$postId", it)
        }
    }

    suspend fun attachHashtagsToVideo(videoId: String, hashtags: List<String>) = withContext(Dispatchers.IO) {
        val normalizedTags = hashtags.map(::normalizeHashtag).filter { it.isNotBlank() }.distinct()
        if (normalizedTags.isEmpty()) return@withContext
        runCatching {
            normalizedTags.forEachIndexed { index, tagName ->
                val tag = upsertHashtag(tagName = tagName, incrementVideoCount = true)
                hashtagDao.insertVideoHashtag(
                    VideoHashtagEntity(
                        videoId = videoId,
                        hashtagId = tag.id,
                        position = index
                    )
                )
            }
            appMetrics.incrementCounter("hashtags_attached_to_video")
        }.onFailure {
            Log.e(TAG, "Error attaching hashtags to video=$videoId", it)
        }
    }

    suspend fun searchHashtags(query: String, limit: Int = 10): List<Hashtag> {
        val normalized = normalizeHashtag(query)
        if (normalized.isBlank()) return emptyList()
        val safeLimit = limit.coerceIn(1, 50)

        remoteRpcRows(
            function = "search_hashtags",
            payload = buildJsonObject {
                put("p_query", normalized)
                put("p_limit", safeLimit)
            }
        ).getOrNull()
            ?.map(::jsonToHashtag)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return runCatching {
            hashtagDao.searchHashtags(normalized, safeLimit).map { it.toDomain() }
        }.getOrElse {
            Log.w(TAG, "Hashtag search falling back to empty list", it)
            emptyList()
        }
    }

    suspend fun getTrendingHashtags(daysBack: Int = 7, limit: Int = 20): List<TrendingHashtag> {
        val safeDaysBack = daysBack.coerceIn(1, 90)
        val safeLimit = limit.coerceIn(1, 100)

        remoteRpcRows(
            function = "get_trending_hashtags",
            payload = buildJsonObject {
                put("p_days_back", safeDaysBack)
                put("p_limit", safeLimit)
            }
        ).getOrNull()
            ?.map(::jsonToTrendingHashtag)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        return runCatching {
            hashtagDao.getTrendingHashtags(safeLimit).map {
                TrendingHashtag(
                    id = it.id,
                    name = it.name,
                    postCount = it.postCount,
                    videoCount = it.videoCount,
                    trendingScore = it.trendingScore
                )
            }
        }.getOrElse {
            Log.w(TAG, "Hashtag discovery falling back to empty trending list", it)
            emptyList()
        }
    }

    suspend fun getPostsByHashtag(
        hashtagName: String,
        limit: Int = 20,
        offset: Int = 0
    ): List<Post> {
        val normalized = normalizeHashtag(hashtagName)
        if (normalized.isBlank()) return emptyList()
        val safeLimit = limit.coerceIn(1, 50)
        val safeOffset = offset.coerceAtLeast(0)

        return remoteRpcRows(
            function = "get_posts_by_hashtag",
            payload = buildJsonObject {
                put("p_hashtag_name", normalized)
                put("p_limit", safeLimit)
                put("p_offset", safeOffset)
            }
        ).map { rows ->
            rows.map(::jsonToPost)
        }.getOrElse {
            Log.w(TAG, "Post hashtag page failed for #$normalized", it)
            emptyList()
        }
    }

    suspend fun getVideosByHashtag(
        hashtagName: String,
        limit: Int = 20,
        offset: Int = 0
    ): List<Video> {
        val normalized = normalizeHashtag(hashtagName)
        if (normalized.isBlank()) return emptyList()
        val safeLimit = limit.coerceIn(1, 50)
        val safeOffset = offset.coerceAtLeast(0)

        return remoteRpcRows(
            function = "get_videos_by_hashtag",
            payload = buildJsonObject {
                put("p_hashtag_name", normalized)
                put("p_limit", safeLimit)
                put("p_offset", safeOffset)
            }
        ).map { rows ->
            rows.map(::jsonToVideo)
        }.getOrElse {
            Log.w(TAG, "Video hashtag page failed for #$normalized", it)
            emptyList()
        }
    }

    private suspend fun upsertHashtag(
        tagName: String,
        incrementPostCount: Boolean = false,
        incrementVideoCount: Boolean = false
    ): HashtagEntity {
        val normalized = normalizeHashtag(tagName)
        val existing = hashtagDao.getHashtagByName(normalized)
        return if (existing == null) {
            HashtagEntity(
                id = UUID.randomUUID().toString(),
                name = normalized,
                postCount = if (incrementPostCount) 1 else 0,
                videoCount = if (incrementVideoCount) 1 else 0
            ).also { hashtagDao.insertHashtag(it) }
        } else {
            if (incrementPostCount) hashtagDao.incrementPostCount(existing.id)
            if (incrementVideoCount) hashtagDao.incrementVideoCount(existing.id)
            existing.copy(
                postCount = existing.postCount + if (incrementPostCount) 1 else 0,
                videoCount = existing.videoCount + if (incrementVideoCount) 1 else 0
            )
        }
    }

    private suspend fun remoteRpcRows(
        function: String,
        payload: JsonObject
    ): Result<List<JsonObject>> = withContext(Dispatchers.IO) {
        runCatching {
            val accessToken = authService.getAccessToken()
            val response = supabaseConfig.httpClient.post(
                "${supabaseConfig.supabaseUrl}/rest/v1/rpc/$function"
            ) {
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Authorization", "Bearer ${accessToken ?: supabaseConfig.supabaseAnonKey}")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }

            if (!response.status.isSuccess()) {
                throw IllegalStateException("$function failed with HTTP ${response.status.value}")
            }

            response.body<List<JsonObject>>()
        }
    }

    private fun jsonToHashtag(row: JsonObject): Hashtag = Hashtag(
        id = row.stringValue("id") ?: UUID.randomUUID().toString(),
        name = normalizeHashtag(row.stringValue("name")),
        postCount = row.intValue("post_count"),
        videoCount = row.intValue("video_count"),
        trendingScore = row.doubleValue("trending_score")
    )

    private fun jsonToTrendingHashtag(row: JsonObject): TrendingHashtag = TrendingHashtag(
        id = row.stringValue("id") ?: UUID.randomUUID().toString(),
        name = normalizeHashtag(row.stringValue("name")),
        postCount = row.intValue("post_count"),
        videoCount = row.intValue("video_count"),
        trendingScore = row.doubleValue("trending_score")
    )

    private fun jsonToPost(row: JsonObject): Post = Post(
        id = row.stringValue("id") ?: UUID.randomUUID().toString(),
        userId = row.stringValue("user_id").orEmpty(),
        userName = row.stringValue("user_name")
            ?: row.stringValue("display_name")
            ?: row.stringValue("username")
            ?: "Usuario",
        userProfileImage = row.stringValue("user_profile_image")
            ?: row.stringValue("avatar_url"),
        content = row.stringValue("content").orEmpty(),
        mediaUrls = row.stringListValue("media_urls"),
        mediaUrlLegacy = row.stringValue("media_url"),
        createdAt = row.stringValue("created_at").orEmpty(),
        likeCount = row.intValue("like_count"),
        commentCount = row.intValue("comment_count"),
        shareCount = row.intValue("share_count"),
        isVerified = row.booleanValue("is_verified"),
        visibility = row.stringValue("visibility") ?: "public"
    )

    private fun jsonToVideo(row: JsonObject): Video = Video(
        id = row.stringValue("id") ?: UUID.randomUUID().toString(),
        userId = row.stringValue("user_id").orEmpty(),
        userName = row.stringValue("user_name")
            ?: row.stringValue("display_name")
            ?: row.stringValue("username")
            ?: "Usuario",
        userProfileImage = row.stringValue("user_profile_image")
            ?: row.stringValue("avatar_url")
            ?: "",
        title = row.stringValue("title").orEmpty(),
        description = row.stringValue("description").orEmpty(),
        videoUrl = row.stringValue("video_url").orEmpty(),
        thumbnailUrl = row.stringValue("thumbnail_url").orEmpty(),
        duration = row.longValue("duration"),
        views = row.intValue("views", "view_count"),
        likeCount = row.intValue("like_count"),
        commentCount = row.intValue("comment_count"),
        shareCount = row.intValue("share_count"),
        country = row.stringValue("country").orEmpty(),
        city = row.stringValue("city").orEmpty(),
        created_at = row.stringValue("created_at").orEmpty(),
        updated_at = row.stringValue("updated_at").orEmpty(),
        isPublic = row.booleanValue("is_public", defaultValue = true),
        tags = row.stringListValue("tags"),
        isVerified = row.booleanValue("is_verified")
    )

    private fun normalizeHashtag(raw: String?): String = raw
        ?.trim()
        ?.removePrefix("#")
        ?.lowercase()
        ?.filter { it.isLetterOrDigit() || it == '_' }
        .orEmpty()

    private fun JsonObject.stringValue(vararg keys: String): String? {
        keys.forEach { key ->
            val element = this[key]
            if (element != null && element !is JsonNull) {
                val value = runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
                    ?: element.toString().trim('"')
                if (value.isNotBlank() && value != "null") return value
            }
        }
        return null
    }

    private fun JsonObject.intValue(vararg keys: String): Int =
        keys.firstNotNullOfOrNull { key ->
            val element = this[key] ?: return@firstNotNullOfOrNull null
            runCatching { element.jsonPrimitive.intOrNull }.getOrNull()
                ?: runCatching { element.jsonPrimitive.contentOrNull?.toIntOrNull() }.getOrNull()
        } ?: 0

    private fun JsonObject.longValue(vararg keys: String): Long =
        keys.firstNotNullOfOrNull { key ->
            val element = this[key] ?: return@firstNotNullOfOrNull null
            runCatching { element.jsonPrimitive.contentOrNull?.toLongOrNull() }.getOrNull()
        } ?: 0L

    private fun JsonObject.doubleValue(vararg keys: String): Double =
        keys.firstNotNullOfOrNull { key ->
            val element = this[key] ?: return@firstNotNullOfOrNull null
            runCatching { element.jsonPrimitive.contentOrNull?.toDoubleOrNull() }.getOrNull()
        } ?: 0.0

    private fun JsonObject.booleanValue(vararg keys: String, defaultValue: Boolean = false): Boolean =
        keys.firstNotNullOfOrNull { key ->
            val element = this[key] ?: return@firstNotNullOfOrNull null
            runCatching { element.jsonPrimitive.booleanOrNull }.getOrNull()
                ?: runCatching { element.jsonPrimitive.contentOrNull?.toBooleanStrictOrNull() }.getOrNull()
        } ?: defaultValue

    private fun JsonObject.stringListValue(key: String): List<String> {
        val element = this[key] ?: return emptyList()
        if (element is JsonNull) return emptyList()
        return when (element) {
            is JsonArray -> element.jsonArray.mapNotNull {
                runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
            }.filter { it.isNotBlank() }
            is JsonPrimitive -> element.contentOrNull
                ?.split(',')
                ?.map { it.trim().trim('[', ']', '"') }
                ?.filter { it.isNotBlank() && it != "null" }
                .orEmpty()
            else -> emptyList()
        }
    }
}
