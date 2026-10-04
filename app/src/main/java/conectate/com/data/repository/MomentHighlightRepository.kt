package conectate.com.data.repository

import conectate.com.data.model.Moment
import conectate.com.data.model.MomentHighlight
import conectate.com.data.model.MomentHighlightItemPreview
import conectate.com.data.model.isActiveAt
import conectate.com.data.repository.moment.toHighlightItemPreview
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MomentHighlightRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService
) {
    @Serializable
    private data class CreateHighlightRequest(
        @SerialName("user_id") val userId: String,
        val title: String,
        @SerialName("cover_media_url") val coverMediaUrl: String?,
        @SerialName("is_public") val isPublic: Boolean = true
    )

    @Serializable
    private data class CreateHighlightItemRequest(
        @SerialName("highlight_id") val highlightId: String,
        @SerialName("moment_id") val momentId: String,
        @SerialName("media_url") val mediaUrl: String,
        @SerialName("media_type") val mediaType: String,
        @SerialName("thumbnail_url") val thumbnailUrl: String?,
        val caption: String?,
        @SerialName("overlay_text") val overlayText: String?,
        @SerialName("overlay_color") val overlayColor: String,
        @SerialName("overlay_font") val overlayFont: String,
        @SerialName("overlay_scale") val overlayScale: Float,
        @SerialName("overlay_x") val overlayX: Float,
        @SerialName("overlay_y") val overlayY: Float,
        @SerialName("music_url") val musicUrl: String?,
        @SerialName("music_audio_url") val musicAudioUrl: String?,
        @SerialName("music_id") val musicId: String?,
        @SerialName("music_title") val musicTitle: String?,
        @SerialName("music_artist") val musicArtist: String?,
        @SerialName("music_source") val musicSource: String?,
        @SerialName("music_source_url") val musicSourceUrl: String?,
        @SerialName("music_license") val musicLicense: String?,
        @SerialName("music_license_url") val musicLicenseUrl: String?,
        @SerialName("music_start_ms") val musicStartMs: Long,
        @SerialName("music_duration_ms") val musicDurationMs: Long,
        @SerialName("music_end_ms") val musicEndMs: Long?,
        @SerialName("music_volume") val musicVolume: Float,
        @SerialName("original_audio_volume") val originalAudioVolume: Float,
        @SerialName("music_fade_in_ms") val musicFadeInMs: Long,
        @SerialName("music_fade_out_ms") val musicFadeOutMs: Long,
        @SerialName("duck_original_audio") val duckOriginalAudio: Boolean,
        @SerialName("music_embedded") val musicEmbedded: Boolean,
        @SerialName("music_presentation") val musicPresentation: String = "card",
        @SerialName("music_artwork_url") val musicArtworkUrl: String? = null,
        @SerialName("music_overlay_x") val musicOverlayX: Float = .5f,
        @SerialName("music_overlay_y") val musicOverlayY: Float = .5f,
        @SerialName("music_overlay_scale") val musicOverlayScale: Float = 1f,
        @SerialName("music_overlay_rotation") val musicOverlayRotation: Float = 0f,
        @SerialName("music_lyrics") val musicLyrics: String? = null,
    )

    suspend fun getHighlightsForUser(userId: String): Result<List<MomentHighlight>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = authService.getAccessToken() ?: throw IllegalStateException("No token")
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moment_highlights") {
                authHeaders(token)
                parameter("user_id", "eq.$userId")
                parameter("select", "id,user_id,title,cover_media_url,sort_order,is_public,created_at,updated_at")
                parameter("order", "sort_order.asc,created_at.desc")
            }

            if (!response.status.isSuccess()) {
                throw IllegalStateException("No se pudieron cargar destacados: ${response.status}")
            }

            val highlights = decodeObjects(response.bodyAsText()).map { it.toHighlight(itemCount = 0) }
            if (highlights.isEmpty()) return@runCatching emptyList()

            val counts = fetchActiveItemCounts(token, highlights.map { it.id })
            highlights
                .map { it.copy(itemCount = counts[it.id] ?: 0) }
                .filter { it.itemCount > 0 }
        }
    }

    suspend fun getHighlightItems(highlightId: String): Result<List<MomentHighlightItemPreview>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = authService.getAccessToken() ?: throw IllegalStateException("No token")
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moment_highlight_items") {
                authHeaders(token)
                parameter("highlight_id", "eq.$highlightId")
                parameter("select", "id,highlight_id,moment_id,sort_order,created_at")
                parameter("order", "sort_order.asc,created_at.asc")
            }

            if (!response.status.isSuccess()) {
                throw IllegalStateException("No se pudieron cargar momentos destacados: ${response.status}")
            }

            val itemRows = decodeObjects(response.bodyAsText())
            val momentIds = itemRows
                .mapNotNull { it.string("moment_id") }
                .distinct()
            if (momentIds.isEmpty()) return@runCatching emptyList()

            // Resolve every Moment explicitly. The compatibility endpoint does not
            // expand nested relations, and expired/deleted Moments must not survive
            // as orphaned highlights on the profile.
            val momentResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moments") {
                authHeaders(token)
                parameter("id", momentIds.joinToString(prefix = "in.(", postfix = ")"))
                parameter("select", "id,user_id,media_url,media_type,thumbnail_url,caption,overlay_text,overlay_color,overlay_font,overlay_scale,overlay_x,overlay_y,music_url,music_audio_url,music_id,music_title,music_artist,music_presentation,music_artwork_url,music_overlay_x,music_overlay_y,music_overlay_scale,music_overlay_rotation,music_lyrics,music_source,music_source_url,music_license,music_license_url,music_start_ms,music_duration_ms,music_end_ms,music_volume,original_audio_volume,music_fade_in_ms,music_fade_out_ms,duck_original_audio,music_embedded,display_duration_ms,expires_at,created_at")
            }
            if (!momentResponse.status.isSuccess()) {
                throw IllegalStateException("No se pudieron cargar los momentos del destacado: ${momentResponse.status}")
            }
            val momentsById = decodeObjects(momentResponse.bodyAsText())
                .associateBy { it.string("id").orEmpty().lowercase() }

            itemRows.mapNotNull { row ->
                val momentId = row.string("moment_id") ?: return@mapNotNull null
                val original = momentsById[momentId.lowercase()] ?: return@mapNotNull null
                row.toHighlightItemPreview(original)
            }
                .filter { it.isActiveAt() }
                .distinctBy { it.id.ifBlank { "${it.highlightId}:${it.momentId}" } }
                .sortedBy { it.sortOrder }
        }
    }

    suspend fun saveMomentToHighlight(
        moment: Moment,
        title: String
    ): Result<MomentHighlight> = withContext(Dispatchers.IO) {
        runCatching {
            val userId = authService.getCurrentUserId() ?: throw IllegalStateException("No user logged in")
            val token = authService.getAccessToken() ?: throw IllegalStateException("No token")
            val cleanTitle = title.trim().take(60)
            if (cleanTitle.isBlank()) throw IllegalArgumentException("Ponle un nombre al destacado.")
            if (moment.userId != userId) {
                throw IllegalArgumentException("Solo puedes guardar tus propios momentos en destacados.")
            }

            val highlight = findHighlightByTitle(token, userId, cleanTitle)
                ?: createHighlight(token, userId, cleanTitle, moment.thumbnailUrl ?: moment.mediaUrl)

            val itemResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/moment_highlight_items") {
                authHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(
                    CreateHighlightItemRequest(
                        highlightId = highlight.id,
                        momentId = moment.id,
                        mediaUrl = moment.mediaUrl,
                        mediaType = moment.mediaType,
                        thumbnailUrl = moment.thumbnailUrl,
                        caption = moment.caption,
                        overlayText = moment.overlayText,
                        overlayColor = moment.overlayColor,
                        overlayFont = moment.overlayFont,
                        overlayScale = moment.overlayScale,
                        overlayX = moment.overlayX,
                        overlayY = moment.overlayY,
                        musicUrl = moment.musicUrl,
                        musicAudioUrl = moment.musicAudioUrl,
                        musicId = moment.musicId,
                        musicTitle = moment.musicTitle,
                        musicArtist = moment.musicArtist,
                        musicSource = moment.musicSource,
                        musicSourceUrl = moment.musicSourceUrl,
                        musicLicense = moment.musicLicense,
                        musicLicenseUrl = moment.musicLicenseUrl,
                        musicStartMs = moment.musicStartMs,
                        musicDurationMs = moment.musicDurationMs,
                        musicEndMs = moment.musicEndMs,
                        musicVolume = moment.musicVolume,
                        originalAudioVolume = moment.originalAudioVolume,
                        musicFadeInMs = moment.musicFadeInMs,
                        musicFadeOutMs = moment.musicFadeOutMs,
                        duckOriginalAudio = moment.duckOriginalAudio,
                        musicEmbedded = moment.musicEmbedded,
                        musicPresentation = moment.musicPresentation,
                        musicArtworkUrl = moment.musicArtworkUrl,
                        musicOverlayX = moment.musicOverlayX,
                        musicOverlayY = moment.musicOverlayY,
                        musicOverlayScale = moment.musicOverlayScale,
                        musicOverlayRotation = moment.musicOverlayRotation,
                        musicLyrics = moment.musicLyrics,
                    )
                )
            }

            if (!itemResponse.status.isSuccess() && itemResponse.status != HttpStatusCode.Conflict) {
                throw IllegalStateException("No se pudo guardar el momento: ${itemResponse.status}")
            }

            if (highlight.coverMediaUrl.isNullOrBlank()) {
                updateHighlightCover(token, highlight.id, moment.thumbnailUrl ?: moment.mediaUrl)
            }

            highlight.copy(itemCount = highlight.itemCount + 1)
        }
    }

    private suspend fun fetchActiveItemCounts(token: String, highlightIds: List<String>): Map<String, Int> {
        val filter = highlightIds.joinToString(prefix = "in.(", postfix = ")")
        val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moment_highlight_items") {
            authHeaders(token)
            parameter("highlight_id", filter)
            parameter("select", "highlight_id,moment_id")
        }
        if (!response.status.isSuccess()) return emptyMap()
        val itemRows = decodeObjects(response.bodyAsText())
        val momentIds = itemRows.mapNotNull { it.string("moment_id") }.distinct()
        if (momentIds.isEmpty()) return emptyMap()

        val momentsResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moments") {
            authHeaders(token)
            parameter("id", momentIds.joinToString(prefix = "in.(", postfix = ")"))
            parameter("select", "id,expires_at")
        }
        if (!momentsResponse.status.isSuccess()) return emptyMap()
        val activeMomentIds = decodeObjects(momentsResponse.bodyAsText())
            .mapNotNull { moment ->
                val id = moment.string("id") ?: return@mapNotNull null
                val probe = MomentHighlightItemPreview(
                    id = id,
                    highlightId = "",
                    momentId = id,
                    mediaUrl = "active-check",
                    mediaType = "image",
                    expiresAt = moment.string("expires_at"),
                )
                id.lowercase().takeIf { probe.isActiveAt() }
            }
            .toSet()

        return itemRows
            .mapNotNull { row ->
                val momentId = row.string("moment_id")?.lowercase() ?: return@mapNotNull null
                row.string("highlight_id")?.takeIf { momentId in activeMomentIds }
            }
            .groupingBy { it }
            .eachCount()
    }

    private suspend fun findHighlightByTitle(
        token: String,
        userId: String,
        title: String
    ): MomentHighlight? {
        val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moment_highlights") {
            authHeaders(token)
            parameter("user_id", "eq.$userId")
            parameter("title", "eq.$title")
            parameter("select", "id,user_id,title,cover_media_url,sort_order,is_public,created_at,updated_at")
            parameter("limit", "1")
        }
        if (!response.status.isSuccess()) return null
        return decodeObjects(response.bodyAsText()).firstOrNull()?.toHighlight(itemCount = 0)
    }

    private suspend fun createHighlight(
        token: String,
        userId: String,
        title: String,
        coverMediaUrl: String?
    ): MomentHighlight {
        val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/moment_highlights") {
            authHeaders(token)
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Prefer, "return=representation")
            setBody(CreateHighlightRequest(userId, title, coverMediaUrl))
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException("No se pudo crear el destacado: ${response.status}")
        }
        return decodeObjects(response.bodyAsText()).first().toHighlight(itemCount = 0)
    }

    private suspend fun updateHighlightCover(token: String, highlightId: String, coverMediaUrl: String) {
        supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/moment_highlights") {
            authHeaders(token)
            contentType(ContentType.Application.Json)
            parameter("id", "eq.$highlightId")
            setBody(buildJsonObject {
                put("cover_media_url", coverMediaUrl)
            })
        }
    }

    private suspend fun fetchHighlightCover(token: String, highlightId: String): String? {
        val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moment_highlights") {
            authHeaders(token)
            parameter("id", "eq.$highlightId")
            parameter("select", "cover_media_url")
            parameter("limit", "1")
        }
        if (!response.status.isSuccess()) return null
        return decodeObjects(response.bodyAsText()).firstOrNull()?.string("cover_media_url")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authHeaders(token: String) {
        header("apikey", supabaseConfig.supabaseAnonKey)
        header("Authorization", "Bearer $token")
    }

    private fun decodeObjects(body: String): List<JsonObject> {
        if (body.isBlank()) return emptyList()
        return supabaseConfig.json.decodeFromString<JsonArray>(body).mapNotNull { it as? JsonObject }
    }

    private fun JsonObject.toHighlight(itemCount: Int): MomentHighlight {
        return MomentHighlight(
            id = string("id").orEmpty(),
            userId = string("user_id").orEmpty(),
            title = string("title").orEmpty(),
            coverMediaUrl = string("cover_media_url"),
            sortOrder = int("sort_order"),
            isPublic = bool("is_public", default = true),
            itemCount = itemCount,
            createdAt = string("created_at"),
            updatedAt = string("updated_at")
        )
    }


    private fun JsonObject.toCoverFallbackPreview(coverUrl: String): MomentHighlightItemPreview {
        val itemId = string("id").orEmpty()
        val highlightId = string("highlight_id").orEmpty()
        val momentId = string("moment_id").orEmpty()
        return MomentHighlightItemPreview(
            id = itemId,
            highlightId = highlightId,
            momentId = momentId,
            mediaUrl = coverUrl,
            mediaType = inferMediaType(coverUrl),
            thumbnailUrl = coverUrl,
            caption = null,
            sortOrder = int("sort_order"),
            createdAt = string("created_at")
        )
    }

    private fun inferMediaType(url: String): String {
        val clean = url.substringBefore('?').lowercase()
        return if (clean.endsWith(".mp4") || clean.endsWith(".webm") || clean.endsWith(".mov")) {
            "video"
        } else {
            "image"
        }
    }

    private fun JsonObject.childObject(key: String): JsonObject? {
        return when (val value = this[key]) {
            is JsonObject -> value
            is JsonArray -> value.firstOrNull() as? JsonObject
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.contentOrNull
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun JsonObject.int(key: String): Int = this[key]?.jsonPrimitive?.intOrNull ?: 0

    private fun JsonObject.float(key: String, default: Float): Float =
        this[key]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: default

    private fun JsonObject.long(key: String, default: Long): Long =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: default

    private fun JsonObject.longOrNull(key: String): Long? =
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private fun JsonObject.bool(key: String, default: Boolean): Boolean {
        return this[key]?.jsonPrimitive?.booleanOrNull ?: default
    }
}
