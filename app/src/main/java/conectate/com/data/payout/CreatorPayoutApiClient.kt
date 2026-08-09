package conectate.com.data.payout

import conectate.com.BuildConfig
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.model.CreatorPayoutAccountResponse
import conectate.com.data.model.CreatorPayoutRequestResponse
import conectate.com.data.model.CreatorPayoutSummary
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Singleton
class CreatorPayoutApiClient @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
) {
    suspend fun summary(accessToken: String): Result<CreatorPayoutSummary> = runCatching {
        val response = request(accessToken) { baseUrl ->
            supabaseConfig.httpClient.get("$baseUrl/v1/data/creator/payouts/summary") {
                payoutHeaders(accessToken)
            }
        }
        decode(response)
    }

    suspend fun updateAccount(
        accessToken: String,
        email: String,
        currency: String,
    ): Result<CreatorPayoutAccountResponse> = runCatching {
        val response = request(accessToken) { baseUrl ->
            supabaseConfig.httpClient.put("$baseUrl/v1/data/creator/payouts/account") {
                payoutHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("email", email.trim())
                    put("currency", currency.trim().uppercase())
                }.toString())
            }
        }
        decode(response)
    }

    suspend fun requestPayout(
        accessToken: String,
        amountMinor: Long,
        idempotencyKey: String,
    ): Result<CreatorPayoutRequestResponse> = runCatching {
        val response = request(accessToken) { baseUrl ->
            supabaseConfig.httpClient.post("$baseUrl/v1/data/creator/payouts/requests") {
                payoutHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("amountMinor", amountMinor)
                    put("idempotencyKey", idempotencyKey)
                }.toString())
            }
        }
        decode(response)
    }

    suspend fun cancel(
        accessToken: String,
        requestId: String,
    ): Result<CreatorPayoutRequestResponse> = runCatching {
        val cleanId = URLEncoder.encode(requestId.trim(), Charsets.UTF_8.name())
        val response = request(accessToken) { baseUrl ->
            supabaseConfig.httpClient.post("$baseUrl/v1/data/creator/payouts/requests/$cleanId/cancel") {
                payoutHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        }
        decode(response)
    }

    private suspend fun request(
        accessToken: String,
        block: suspend (String) -> HttpResponse,
    ): HttpResponse {
        if (accessToken.isBlank()) throw CreatorPayoutApiException("AUTH_REQUIRED", 401)
        val baseUrl = RuntimeBackendEndpoint.ensureFreshEndpoint()?.trimEnd('/')
            ?: throw CreatorPayoutApiException("BACKEND_UNAVAILABLE", 503)
        return block(baseUrl)
    }

    private suspend inline fun <reified T> decode(response: HttpResponse): T {
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw parseError(response, body)
        return supabaseConfig.json.decodeFromJsonElement(supabaseConfig.json.parseToJsonElement(body))
    }

    private fun HttpRequestBuilder.payoutHeaders(accessToken: String) {
        header("ngrok-skip-browser-warning", "1")
        header("X-Novvin-App-Version", BuildConfig.VERSION_NAME)
        header("X-Novvin-Version-Code", BuildConfig.VERSION_CODE.toString())
        header("X-Novvin-Build-Commit", BuildConfig.BUILD_COMMIT)
        header("X-Novvin-Environment", BuildConfig.BUILD_ENVIRONMENT)
        header("X-Request-ID", UUID.randomUUID().toString())
        header(HttpHeaders.Authorization, "Bearer ${accessToken.trim()}")
    }

    private fun parseError(response: HttpResponse, body: String): CreatorPayoutApiException {
        val objectBody = runCatching { supabaseConfig.json.parseToJsonElement(body).jsonObject }.getOrNull()
        val code = objectBody?.get("error")?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "CREATOR_PAYOUT_REQUEST_FAILED"
        return CreatorPayoutApiException(code, response.status.value)
    }
}

class CreatorPayoutApiException(
    val code: String,
    val statusCode: Int,
) : IllegalStateException(code)
