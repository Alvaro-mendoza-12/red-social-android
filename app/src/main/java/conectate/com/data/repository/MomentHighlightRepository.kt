package conectate.com.data.repository

import conectate.com.data.model.Moment
import conectate.com.data.model.MomentHighlight
import conectate.com.data.model.MomentHighlightItemPreview
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
        @SerialName("moment_id") val momentId: String
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

            val counts = fetchItemCounts(token, highlights.map { it.id })
            highlights.map { it.copy(itemCount = counts[it.id] ?: 0) }
        }
    }

    suspend fun getHighlightItems(highlightId: String): Result<List<MomentHighlightItemPreview>> = withContext(Dispatchers.IO) {
        runCatching {
            val token = authService.getAccessToken() ?: throw IllegalStateException("No token")
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moment_highlight_items") {
                authHeaders(token)
                parameter("highlight_id", "eq.$highlightId")
                parameter(
                    "select",
                    "id,highlight_id,moment_id,sort_order,created_at,moment:moments(id,media_url,media_type,thumbnail_url,caption,created_at)"
                )
                parameter("order", "sort_order.asc,created_at.asc")
            }

            if (!response.status.isSuccess()) {
                throw IllegalStateException("No se pudieron cargar momentos destacados: ${response.status}")
            }

            decodeObjects(response.bodyAsText()).mapNotNull { it.toHighlightItemPreview() }
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
                setBody(CreateHighlightItemRequest(highlight.id, moment.id))
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

    private suspend fun fetchItemCounts(token: String, highlightIds: List<String>): Map<String, Int> {
        val filter = highlightIds.joinToString(prefix = "in.(", postfix = ")")
        val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/moment_highlight_items") {
            authHeaders(token)
            parameter("highlight_id", filter)
            parameter("select", "highlight_id")
        }
        if (!response.status.isSuccess()) return emptyMap()
        return decodeObjects(response.bodyAsText())
            .mapNotNull { it.string("highlight_id") }
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

    private fun JsonObject.toHighlightItemPreview(): MomentHighlightItemPreview? {
        val moment = childObject("moment") ?: return null
        val mediaUrl = moment.string("media_url").orEmpty()
        if (mediaUrl.isBlank()) return null
        return MomentHighlightItemPreview(
            id = string("id").orEmpty(),
            highlightId = string("highlight_id").orEmpty(),
            momentId = string("moment_id").orEmpty(),
            mediaUrl = mediaUrl,
            mediaType = moment.string("media_type") ?: "image",
            thumbnailUrl = moment.string("thumbnail_url"),
            caption = moment.string("caption"),
            sortOrder = int("sort_order"),
            createdAt = string("created_at") ?: moment.string("created_at")
        )
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

    private fun JsonObject.bool(key: String, default: Boolean): Boolean {
        return this[key]?.jsonPrimitive?.booleanOrNull ?: default
    }
}
