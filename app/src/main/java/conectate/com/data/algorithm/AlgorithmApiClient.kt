package conectate.com.data.algorithm

import conectate.com.BuildConfig
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.model.AlgorithmCapabilities
import conectate.com.data.model.AlgorithmConfig
import conectate.com.data.model.AlgorithmDataPermissions
import conectate.com.data.model.AlgorithmProfile
import conectate.com.data.model.CreateAlgorithmProfileRequest
import conectate.com.data.model.CreateTemporaryAlgorithmPreferenceRequest
import conectate.com.data.model.UpdateAlgorithmDataPermissionsRequest
import conectate.com.data.model.UpdateAlgorithmFeedMixRequest
import conectate.com.data.model.UpdateAlgorithmPreferencesRequest
import conectate.com.data.model.UpdateAlgorithmProfileRequest
import conectate.com.data.recommendations.AuthoritativeRecommendationDecodeResult
import conectate.com.data.recommendations.AuthoritativeRecommendationPageJson
import conectate.com.data.recommendations.AuthoritativeRecommendationSurface
import conectate.com.data.recommendations.OpaqueRecommendationCursor
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
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
 * Narrow client for the explicit-algorithm API.
 *
 * It deliberately does not use the generic fallback client: the API is isolated
 * so that recommendation configuration cannot accidentally inherit legacy
 * client-side ranking or include private chat data in a request.
 */
@Singleton
class AlgorithmApiClient @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
) {
    suspend fun capabilities(accessToken: String): Result<AlgorithmCapabilities> =
        get("/v1/data/algorithm/capabilities", accessToken) { body ->
            decodeEnvelope(body, "capabilities")
        }

    suspend fun profiles(accessToken: String): Result<List<AlgorithmProfile>> =
        get("/v1/data/algorithm/profiles", accessToken) { body ->
            decodeListEnvelope(body, "profiles")
        }

    suspend fun config(accessToken: String, profileId: String? = null): Result<AlgorithmConfig> {
        val suffix = profileId?.trim()?.takeIf { it.isNotBlank() }
            ?.let { "/v1/data/algorithm/profiles/${encodePath(it)}/config" }
            ?: "/v1/data/algorithm/config"
        return get(suffix, accessToken) { body -> decodeEnvelope(body, "config") }
    }

    /**
     * Loads one server-ranked page without ever interpreting its opaque cursor
     * or changing the order selected by the backend.
     */
    suspend fun recommendations(
        accessToken: String,
        surface: AuthoritativeRecommendationSurface,
        profileId: String,
        cursor: OpaqueRecommendationCursor? = null,
        limit: Int = 20,
    ): Result<AuthoritativeRecommendationDecodeResult> = request(accessToken) { baseUrl ->
        supabaseConfig.httpClient.get("$baseUrl/v1/data/recommendations") {
            algorithmHeaders(accessToken)
            parameter("surface", surface.wireValue)
            parameter("profileId", profileId.trim())
            parameter("limit", limit.coerceIn(1, MAX_RECOMMENDATION_PAGE_SIZE))
            cursor?.let { parameter("cursor", it.asRequestValue()) }
        }
    }.mapCatching { body ->
        AuthoritativeRecommendationPageJson.decode(body, surface)
    }

    suspend fun createProfile(
        accessToken: String,
        request: CreateAlgorithmProfileRequest,
    ): Result<AlgorithmProfile> = post("/v1/data/algorithm/profiles", accessToken, request) { body ->
        decodeEnvelope(body, "profile")
    }

    suspend fun updateProfile(
        accessToken: String,
        profileId: String,
        request: UpdateAlgorithmProfileRequest,
    ): Result<AlgorithmProfile> = patch(
        "/v1/data/algorithm/profiles/${encodePath(profileId)}",
        accessToken,
        request,
    ) { body -> decodeEnvelope(body, "profile") }

    suspend fun replacePreferences(
        accessToken: String,
        profileId: String,
        request: UpdateAlgorithmPreferencesRequest,
    ): Result<AlgorithmProfile> = put(
        "/v1/data/algorithm/profiles/${encodePath(profileId)}/preferences",
        accessToken,
        request,
    ) { body -> decodeEnvelope(body, "profile") }

    suspend fun deletePreference(
        accessToken: String,
        profileId: String,
        preferenceId: String,
        expectedVersion: Long,
    ): Result<Unit> = delete(
        "/v1/data/algorithm/profiles/${encodePath(profileId)}/preferences/${encodePath(preferenceId)}",
        accessToken,
        RevisionRequest(expectedVersion),
    )

    suspend fun updateFeedMix(
        accessToken: String,
        profileId: String,
        surface: String,
        request: UpdateAlgorithmFeedMixRequest,
    ): Result<AlgorithmProfile> = put(
        "/v1/data/algorithm/profiles/${encodePath(profileId)}/feed-mix/${encodePath(surface)}",
        accessToken,
        request,
    ) { body -> decodeEnvelope(body, "profile") }

    suspend fun updateDataPermissions(
        accessToken: String,
        profileId: String,
        request: UpdateAlgorithmDataPermissionsRequest,
    ): Result<AlgorithmProfile> = put(
        "/v1/data/algorithm/profiles/${encodePath(profileId)}/data-permissions",
        accessToken,
        request,
    ) { body -> decodeEnvelope(body, "profile") }

    suspend fun createTemporaryPreference(
        accessToken: String,
        profileId: String,
        request: CreateTemporaryAlgorithmPreferenceRequest,
    ): Result<AlgorithmProfile> = post(
        "/v1/data/algorithm/profiles/${encodePath(profileId)}/temporary-preferences",
        accessToken,
        request,
    ) { body -> decodeEnvelope(body, "profile") }

    suspend fun deleteTemporaryPreference(
        accessToken: String,
        profileId: String,
        preferenceId: String,
        expectedVersion: Long,
    ): Result<Unit> = delete(
        "/v1/data/algorithm/profiles/${encodePath(profileId)}/temporary-preferences/${encodePath(preferenceId)}",
        accessToken,
        RevisionRequest(expectedVersion),
    )

    private suspend inline fun <reified T> get(
        path: String,
        accessToken: String,
        decode: (String) -> T,
    ): Result<T> = request(accessToken) { baseUrl ->
        supabaseConfig.httpClient.get("$baseUrl$path") { algorithmHeaders(accessToken) }
    }.mapCatching(decode)

    private suspend inline fun <reified T, reified B> post(
        path: String,
        accessToken: String,
        request: B,
        decode: (String) -> T,
    ): Result<T> = request(accessToken) { baseUrl ->
        supabaseConfig.httpClient.post("$baseUrl$path") {
            algorithmHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }.mapCatching(decode)

    private suspend inline fun <reified T, reified B> put(
        path: String,
        accessToken: String,
        request: B,
        decode: (String) -> T,
    ): Result<T> = request(accessToken) { baseUrl ->
        supabaseConfig.httpClient.put("$baseUrl$path") {
            algorithmHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }.mapCatching(decode)

    private suspend inline fun <reified T, reified B> patch(
        path: String,
        accessToken: String,
        request: B,
        decode: (String) -> T,
    ): Result<T> = request(accessToken) { baseUrl ->
        supabaseConfig.httpClient.patch("$baseUrl$path") {
            algorithmHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }.mapCatching(decode)

    private suspend inline fun <reified B> delete(
        path: String,
        accessToken: String,
        request: B,
    ): Result<Unit> = request(accessToken) { baseUrl ->
        supabaseConfig.httpClient.delete("$baseUrl$path") {
            algorithmHeaders(accessToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }.map { Unit }

    private suspend fun request(
        accessToken: String,
        block: suspend (String) -> HttpResponse,
    ): Result<String> = runCatching {
        if (accessToken.isBlank()) throw AlgorithmApiException("AUTH_REQUIRED", 401)
        val baseUrl = RuntimeBackendEndpoint.ensureFreshEndpoint()
            ?: throw AlgorithmApiException("BACKEND_UNAVAILABLE", 503)
        val response = block(baseUrl.trimEnd('/'))
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw parseError(response, body)
        body
    }

    private fun HttpRequestBuilder.algorithmHeaders(accessToken: String) {
        header("ngrok-skip-browser-warning", "1")
        header("X-Novvin-App-Version", BuildConfig.VERSION_NAME)
        header("X-Novvin-Version-Code", BuildConfig.VERSION_CODE.toString())
        header("X-Novvin-Build-Commit", BuildConfig.BUILD_COMMIT)
        header("X-Novvin-Environment", BuildConfig.BUILD_ENVIRONMENT)
        header("X-Request-ID", UUID.randomUUID().toString())
        header(HttpHeaders.Authorization, "Bearer ${accessToken.trim()}")
    }

    private inline fun <reified T> decodeEnvelope(body: String, key: String): T {
        val element = supabaseConfig.json.parseToJsonElement(body)
        val value = (element as? JsonObject)?.get(key) ?: element
        return supabaseConfig.json.decodeFromJsonElement(value)
    }

    private inline fun <reified T> decodeListEnvelope(body: String, key: String): List<T> {
        val element = supabaseConfig.json.parseToJsonElement(body)
        val value = (element as? JsonObject)?.get(key) ?: element
        return supabaseConfig.json.decodeFromJsonElement(value)
    }

    private fun parseError(response: HttpResponse, body: String): AlgorithmApiException {
        val objectBody = runCatching { supabaseConfig.json.parseToJsonElement(body).jsonObject }.getOrNull()
        val code = objectBody?.get("error")?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "ALGORITHM_REQUEST_FAILED"
        val message = objectBody?.get("message")?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val currentRevision = objectBody?.get("currentRevision")?.jsonPrimitive?.contentOrNull
            ?.toLongOrNull()
        return AlgorithmApiException(code, response.status.value, message, currentRevision)
    }

    private fun encodePath(value: String): String = java.net.URLEncoder.encode(value.trim(), Charsets.UTF_8.name())

    private companion object {
        const val MAX_RECOMMENDATION_PAGE_SIZE = 60
    }
}

@kotlinx.serialization.Serializable
private data class RevisionRequest(val expectedVersion: Long)

class AlgorithmApiException(
    val code: String,
    val statusCode: Int,
    override val message: String? = null,
    val currentRevision: Long? = null,
) : IllegalStateException(message ?: code)
