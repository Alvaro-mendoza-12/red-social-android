package conectate.com.data.socialization

import conectate.com.BuildConfig
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.model.SocializationCapabilities
import conectate.com.data.model.SocializationPreferencesResponse
import conectate.com.data.model.SocializationCardsResponse
import conectate.com.data.model.UpdateSocializationPreferencesRequest
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Narrow transport for explicit Socializar consent.
 *
 * It never sends messages, contacts, precise location or sensitive profile
 * fields. Every mutation is authenticated and revision checked by the server.
 */
@Singleton
class SocializationApiClient @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
) {
    suspend fun capabilities(accessToken: String): Result<SocializationCapabilities> = get(
        path = "/v1/data/socialization/capabilities",
        accessToken = accessToken,
    ) { body -> decodeEnvelope(body, "capabilities") }

    suspend fun preferences(accessToken: String): Result<SocializationPreferencesResponse> = get(
        path = "/v1/data/socialization/preferences",
        accessToken = accessToken,
    ) { body -> decodeEnvelope(body, "preferences") }

    suspend fun updatePreferences(
        accessToken: String,
        request: UpdateSocializationPreferencesRequest,
    ): Result<SocializationPreferencesResponse> = request(accessToken) { baseUrl ->
        supabaseConfig.httpClient.put("$baseUrl/v1/data/socialization/preferences") {
            socializationHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }.mapCatching { body -> decodeEnvelope(body, "preferences") }

    suspend fun cards(
        accessToken: String,
        surface: String,
        placement: String,
        limit: Int,
    ): Result<SocializationCardsResponse> = request(accessToken) { baseUrl ->
        supabaseConfig.httpClient.get("$baseUrl/v1/data/socialization/cards") {
            socializationHeaders(accessToken)
            parameter("surface", surface)
            parameter("placement", placement)
            parameter("limit", limit.coerceIn(1, 4))
        }
    }.mapCatching { body -> decodeEnvelope(body, "cards") }

    private suspend inline fun <reified T> get(
        path: String,
        accessToken: String,
        crossinline decode: (String) -> T,
    ): Result<T> = request(accessToken) { baseUrl ->
        supabaseConfig.httpClient.get("$baseUrl$path") { socializationHeaders(accessToken) }
    }.mapCatching(decode)

    private suspend fun request(
        accessToken: String,
        block: suspend (String) -> HttpResponse,
    ): Result<String> = runCatching {
        if (accessToken.isBlank()) throw SocializationApiException("AUTH_REQUIRED", 401)
        val baseUrl = RuntimeBackendEndpoint.ensureFreshEndpoint()
            ?: throw SocializationApiException("BACKEND_UNAVAILABLE", 503)
        val response = block(baseUrl.trimEnd('/'))
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw parseError(response, body)
        body
    }

    private fun HttpRequestBuilder.socializationHeaders(accessToken: String) {
        header("ngrok-skip-browser-warning", "1")
        header("X-Novvin-App-Version", BuildConfig.VERSION_NAME)
        header("X-Novvin-Version-Code", BuildConfig.VERSION_CODE.toString())
        header("X-Novvin-Build-Commit", BuildConfig.BUILD_COMMIT)
        header("X-Novvin-Environment", BuildConfig.BUILD_ENVIRONMENT)
        header("X-Request-ID", UUID.randomUUID().toString())
        header(HttpHeaders.Authorization, "Bearer ${accessToken.trim()}")
    }

    private inline fun <reified T> decodeEnvelope(body: String, key: String): T {
        val root = supabaseConfig.json.parseToJsonElement(body)
        val value = (root as? JsonObject)?.get(key) ?: root
        return supabaseConfig.json.decodeFromJsonElement(value)
    }

    private fun parseError(response: HttpResponse, body: String): SocializationApiException {
        val envelope = runCatching {
            supabaseConfig.json.parseToJsonElement(body).jsonObject
        }.getOrNull()
        val code = envelope?.get("error")?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "SOCIALIZATION_REQUEST_FAILED"
        val currentVersion = envelope?.get("currentVersion")?.jsonPrimitive?.contentOrNull
            ?.toLongOrNull()
        return SocializationApiException(code, response.status.value, currentVersion)
    }
}

class SocializationApiException(
    val code: String,
    val statusCode: Int,
    val currentVersion: Long? = null,
) : IllegalStateException(code)
