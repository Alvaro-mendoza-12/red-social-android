package conectate.com.data.analytics

import conectate.com.BuildConfig
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.model.CreatorAnalyticsCatalog
import conectate.com.data.model.CreatorAnalyticsRange
import conectate.com.data.model.LiveAnalyticsReport
import conectate.com.data.model.VideoAnalyticsReport
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CreatorAnalyticsApiClient @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
) {
    suspend fun content(accessToken: String): Result<CreatorAnalyticsCatalog> =
        get("/v1/data/creator/analytics/content", accessToken)

    suspend fun video(
        accessToken: String,
        contentId: String,
        range: CreatorAnalyticsRange,
    ): Result<VideoAnalyticsReport> =
        get("/v1/data/creator/analytics/video/${encodePath(contentId)}${rangeQuery(range)}", accessToken)

    suspend fun live(
        accessToken: String,
        contentId: String,
        range: CreatorAnalyticsRange,
    ): Result<LiveAnalyticsReport> =
        get("/v1/data/creator/analytics/live/${encodePath(contentId)}${rangeQuery(range)}", accessToken)

    private fun rangeQuery(range: CreatorAnalyticsRange): String =
        "?from=${encodePath(range.from)}&to=${encodePath(range.to)}"

    private suspend inline fun <reified T> get(path: String, accessToken: String): Result<T> = runCatching {
        if (accessToken.isBlank()) throw CreatorAnalyticsApiException("AUTH_REQUIRED", 401)
        val baseUrl = RuntimeBackendEndpoint.ensureFreshEndpoint()
            ?: throw CreatorAnalyticsApiException("BACKEND_UNAVAILABLE", 503)
        try {
            request<T>(baseUrl, path, accessToken)
        } catch (error: Throwable) {
            if (!error.isRecoverableAnalyticsFailure()) throw error
            val refreshed = RuntimeBackendEndpoint.refreshAfterEndpointFailure(baseUrl)
            val retryBase = RuntimeBackendEndpoint.baseUrl
            if (!refreshed || retryBase.isBlank() || retryBase == baseUrl) throw error
            request<T>(retryBase, path, accessToken)
        }
    }

    private suspend inline fun <reified T> request(
        baseUrl: String,
        path: String,
        accessToken: String,
    ): T {
        val response = supabaseConfig.httpClient.get("${baseUrl.trimEnd('/')}$path") {
            analyticsHeaders(accessToken)
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw parseError(response, body)
        return supabaseConfig.json.decodeFromJsonElement(supabaseConfig.json.parseToJsonElement(body))
    }

    private fun HttpRequestBuilder.analyticsHeaders(accessToken: String) {
        header("ngrok-skip-browser-warning", "1")
        header("X-Novvin-App-Version", BuildConfig.VERSION_NAME)
        header("X-Novvin-Version-Code", BuildConfig.VERSION_CODE.toString())
        header("X-Novvin-Build-Commit", BuildConfig.BUILD_COMMIT)
        header("X-Novvin-Environment", BuildConfig.BUILD_ENVIRONMENT)
        header("X-Request-ID", UUID.randomUUID().toString())
        header(HttpHeaders.Authorization, "Bearer ${accessToken.trim()}")
    }

    private fun parseError(response: HttpResponse, body: String): CreatorAnalyticsApiException {
        val objectBody = runCatching { supabaseConfig.json.parseToJsonElement(body).jsonObject }.getOrNull()
        val code = objectBody?.get("error")?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "CREATOR_ANALYTICS_REQUEST_FAILED"
        return CreatorAnalyticsApiException(code, response.status.value)
    }

    private fun encodePath(value: String): String =
        URLEncoder.encode(value.trim(), Charsets.UTF_8.name())

    private fun Throwable.isRecoverableAnalyticsFailure(): Boolean {
        val apiError = this as? CreatorAnalyticsApiException
        return apiError?.statusCode in setOf(502, 503, 504) ||
            this is java.io.IOException
    }
}

class CreatorAnalyticsApiException(
    val code: String,
    val statusCode: Int,
) : IllegalStateException(code)
