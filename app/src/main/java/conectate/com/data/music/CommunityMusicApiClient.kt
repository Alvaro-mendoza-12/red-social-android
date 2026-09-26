package conectate.com.data.music

import conectate.com.BuildConfig
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class CommunityMusicTerms(
    val version: String,
    val draft: Boolean = true,
    val legalReviewRequired: Boolean = true,
    val summary: String,
)

@Singleton
class CommunityMusicApiClient @Inject constructor(
    private val config: SupabaseConfig,
) {
    suspend fun terms(token: String): Result<CommunityMusicTerms> = request(token, "/v1/data/music/terms")

    suspend fun myUploads(token: String, cursor: String?): Result<CommunityMusicPage> = execute(token) { base ->
        config.httpClient.get("$base/v1/data/music/my") {
            musicHeaders(token)
            parameter("limit", 20)
            cursor?.takeIf(String::isNotBlank)?.let { parameter("cursor", it) }
        }
    }.decode()

    suspend fun deleteOwnUpload(token: String, trackId: String): Result<CommunityMusicDeleteResponse> = execute(token) { base ->
        config.httpClient.delete("$base/v1/data/music/${encode(trackId)}") { musicHeaders(token) }
    }.decode()

    suspend fun search(token: String, query: String, category: String?, cursor: String?): Result<CommunityMusicPage> =
        execute(token) { base ->
            config.httpClient.get("$base/v1/data/music/search") {
                musicHeaders(token)
                if (query.isNotBlank()) parameter("q", query.take(100))
                category?.takeIf { it.isNotBlank() && !it.equals("Todo", true) }?.let { parameter("genre", it) }
                cursor?.takeIf(String::isNotBlank)?.let { parameter("cursor", it) }
                parameter("limit", 20)
            }
        }.decode()

    suspend fun recommendations(token: String): Result<CommunityMusicPage> = execute(token) { base ->
        config.httpClient.get("$base/v1/data/music/recommendations") {
            musicHeaders(token)
            parameter("limit", 20)
        }
    }.decode()

    suspend fun catalogSearch(
        token: String,
        query: String,
        category: String?,
        section: String,
        cursor: String?,
    ): Result<ApprovedMusicCatalogPage> = execute(token) { base ->
        config.httpClient.get("$base/v1/data/music/catalog/search") {
            musicHeaders(token)
            if (query.isNotBlank()) parameter("q", query.take(100))
            category?.takeIf { it.isNotBlank() && !it.equals("Todo", true) }?.let { parameter("category", it) }
            parameter("section", section.ifBlank { "search" })
            cursor?.takeIf(String::isNotBlank)?.let { parameter("cursor", it) }
            parameter("limit", 20)
        }
    }.decode()

    suspend fun catalogCategories(token: String): Result<MusicCatalogCategories> = request(
        token,
        "/v1/data/music/catalog/categories",
    )

    suspend fun setCatalogFavorite(token: String, trackId: String, favorite: Boolean): Result<Boolean> = execute(token) { base ->
        val url = "$base/v1/data/music/catalog/${encode(trackId)}/favorite"
        if (favorite) config.httpClient.post(url) { musicHeaders(token) }
        else config.httpClient.delete(url) { musicHeaders(token) }
    }.decode<MusicFavoriteResponse>().map { it.favorite }

    suspend fun reportCatalogTrack(
        token: String,
        trackId: String,
        reason: String,
        details: String? = null,
    ): Result<Unit> = execute(token) { base ->
        config.httpClient.post("$base/v1/data/music/catalog/${encode(trackId)}/report") {
            musicHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(mapOf("reason" to reason.take(80), "details" to details.orEmpty().take(2_000)))
        }
    }.decode<MusicReportResponse>().map { Unit }

    suspend fun markCatalogNotInterested(token: String, trackId: String): Result<Unit> = execute(token) { base ->
        config.httpClient.post("$base/v1/data/music/catalog/${encode(trackId)}/not-interested") {
            musicHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(mapOf("reason" to "not_interested"))
        }
    }.decode<MusicNotInterestedResponse>().map { Unit }

    suspend fun recordCatalogPlay(
        token: String,
        trackId: String,
        eventType: String = "preview_started",
        listenedMs: Long = 0L,
    ): Result<Unit> = execute(token) { base ->
        config.httpClient.post("$base/v1/data/music/catalog/${encode(trackId)}/play-events") {
            musicHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "eventType" to eventType,
                    "listenedMs" to listenedMs.toString(),
                    "context" to "moment_picker",
                ),
            )
        }
    }.map { Unit }

    suspend fun create(token: String, request: CreateCommunityMusicRequest): Result<CommunityMusicTrack> = execute(token) { base ->
        config.httpClient.post("$base/v1/data/music/upload") {
            musicHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }.decode<CommunityMusicTrackEnvelope>().map { it.track }

    suspend fun submitForReview(token: String, trackId: String): Result<CommunityMusicTrack> = execute(token) { base ->
        config.httpClient.post("$base/v1/data/music/${encode(trackId)}/publish") { musicHeaders(token) }
    }.decode<CommunityMusicTrackEnvelope>().map { it.track }

    suspend fun favorite(token: String, trackId: String, favorite: Boolean): Result<Unit> = execute(token) { base ->
        if (favorite) config.httpClient.post("$base/v1/data/music/${encode(trackId)}/favorite") { musicHeaders(token) }
        else config.httpClient.delete("$base/v1/data/music/${encode(trackId)}/favorite") { musicHeaders(token) }
    }.map { Unit }

    private suspend inline fun <reified T> request(token: String, path: String): Result<T> = execute(token) { base ->
        config.httpClient.get("$base$path") { musicHeaders(token) }
    }.decode()

    private suspend fun execute(token: String, call: suspend (String) -> HttpResponse): Result<String> = runCatching {
        require(token.isNotBlank()) { "AUTH_REQUIRED" }
        val base = RuntimeBackendEndpoint.ensureFreshEndpoint()?.trimEnd('/')
            ?: error("BACKEND_UNAVAILABLE")
        val response = call(base)
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val code = runCatching {
                config.json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            error(when (code) {
                "MUSIC_TRIM_TO_4_30_REQUIRED", "VALID_AUDIO_DURATION_REQUIRED" -> CommunityMusicPolicy.DURATION_HELP
                else -> code ?: "COMMUNITY_MUSIC_REQUEST_FAILED_${response.status.value}"
            })
        }
        body
    }.onFailure { error ->
        if (error is CancellationException) throw error
    }

    private inline fun <reified T> Result<String>.decode(): Result<T> = mapCatching { body ->
        config.json.decodeFromJsonElement(config.json.parseToJsonElement(body))
    }

    private fun HttpRequestBuilder.musicHeaders(token: String) {
        header("ngrok-skip-browser-warning", "1")
        header("X-Novvin-App-Version", BuildConfig.VERSION_NAME)
        header("X-Novvin-Version-Code", BuildConfig.VERSION_CODE.toString())
        header("X-Request-ID", UUID.randomUUID().toString())
        header(HttpHeaders.Authorization, "Bearer ${token.trim()}")
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
