package conectate.com.data.points

import conectate.com.BuildConfig
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.model.LivePointRedemptionResponse
import conectate.com.data.model.LivePointRedemptionsResponse
import conectate.com.data.model.LivePointRewardResponse
import conectate.com.data.model.LivePointRewardsResponse
import conectate.com.data.model.LivePointsBalanceResponse
import conectate.com.data.model.LivePointsHeartbeat
import conectate.com.data.model.LivePointsSession
import conectate.com.data.model.LivePointsSettingsResponse
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
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
class LiveChannelPointsApiClient @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
) : LiveChannelPointsApi {
    override suspend fun startSession(token: String, liveId: String): Result<LivePointsSession> =
        post(token, "/v1/data/live/${encode(liveId)}/points/session", "{}")

    override suspend fun heartbeat(
        token: String,
        liveId: String,
        sessionToken: String,
        visible: Boolean,
        foreground: Boolean,
    ): Result<LivePointsHeartbeat> = post(
        token,
        "/v1/data/live/${encode(liveId)}/points/heartbeat",
        buildJsonObject {
            put("sessionToken", sessionToken)
            put("visible", visible)
            put("foreground", foreground)
        }.toString(),
    )

    override suspend fun balance(token: String, liveId: String): Result<LivePointsBalanceResponse> =
        get(token, "/v1/data/live/${encode(liveId)}/points/balance")

    override suspend fun rewards(token: String, liveId: String): Result<LivePointRewardsResponse> =
        get(token, "/v1/data/live/${encode(liveId)}/points/rewards")

    override suspend fun redemptions(token: String, liveId: String): Result<LivePointRedemptionsResponse> =
        get(token, "/v1/data/live/${encode(liveId)}/points/redemptions")

    override suspend fun redeem(token: String, liveId: String, rewardId: String, requestNonce: String): Result<LivePointRedemptionResponse> =
        post(
            token,
            "/v1/data/live/${encode(liveId)}/points/redeem",
            buildJsonObject {
                put("rewardId", rewardId)
                put("requestNonce", requestNonce)
            }.toString(),
        )

    override suspend fun updateSettings(
        token: String,
        liveId: String,
        enabled: Boolean,
        intervalSeconds: Int,
        pointsPerInterval: Int,
        dailyCap: Int,
    ): Result<LivePointsSettingsResponse> = request(token) { baseUrl ->
        supabaseConfig.httpClient.patch("$baseUrl/v1/data/live/${encode(liveId)}/points/settings") {
            headers(token)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("enabled", enabled)
                put("intervalSeconds", intervalSeconds)
                put("pointsPerInterval", pointsPerInterval)
                put("dailyCap", dailyCap)
            }.toString())
        }
    }

    override suspend fun createReward(
        token: String,
        liveId: String,
        title: String,
        description: String,
        cost: Int,
        stock: Int?,
        cooldownSeconds: Int,
    ): Result<LivePointRewardResponse> = post(
        token,
        "/v1/data/live/${encode(liveId)}/points/rewards",
        buildJsonObject {
            put("title", title.trim())
            put("description", description.trim())
            put("cost", cost)
            if (stock != null) put("stock", stock)
            put("cooldownSeconds", cooldownSeconds)
        }.toString(),
    )

    override suspend fun updateRedemption(
        token: String,
        liveId: String,
        redemptionId: String,
        status: String,
    ): Result<LivePointRedemptionResponse> = request(token) { baseUrl ->
        supabaseConfig.httpClient.patch(
            "$baseUrl/v1/data/live/${encode(liveId)}/points/redemptions/${encode(redemptionId)}"
        ) {
            headers(token)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("status", status) }.toString())
        }
    }

    private suspend inline fun <reified T> get(token: String, path: String): Result<T> = request(token) { baseUrl ->
        supabaseConfig.httpClient.get("$baseUrl$path") { headers(token) }
    }

    private suspend inline fun <reified T> post(token: String, path: String, body: String): Result<T> =
        request(token) { baseUrl ->
            supabaseConfig.httpClient.post("$baseUrl$path") {
                headers(token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend inline fun <reified T> request(
        token: String,
        block: suspend (String) -> HttpResponse,
    ): Result<T> = runCatching {
        if (token.isBlank()) throw LiveChannelPointsApiException("AUTH_REQUIRED", 401)
        val baseUrl = RuntimeBackendEndpoint.ensureFreshEndpoint()?.trimEnd('/')
            ?: throw LiveChannelPointsApiException("BACKEND_UNAVAILABLE", 503)
        val response = block(baseUrl)
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw parseError(response, body)
        supabaseConfig.json.decodeFromJsonElement(supabaseConfig.json.parseToJsonElement(body))
    }

    private fun HttpRequestBuilder.headers(token: String) {
        header("ngrok-skip-browser-warning", "1")
        header("X-Novvin-App-Version", BuildConfig.VERSION_NAME)
        header("X-Novvin-Version-Code", BuildConfig.VERSION_CODE.toString())
        header("X-Request-ID", UUID.randomUUID().toString())
        header(HttpHeaders.Authorization, "Bearer ${token.trim()}")
    }

    private fun parseError(response: HttpResponse, body: String): LiveChannelPointsApiException {
        val objectBody = runCatching { supabaseConfig.json.parseToJsonElement(body).jsonObject }.getOrNull()
        val code = objectBody?.get("error")?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotBlank() } ?: "LIVE_POINTS_REQUEST_FAILED"
        return LiveChannelPointsApiException(code, response.status.value)
    }

    private fun encode(value: String): String = URLEncoder.encode(value.trim(), Charsets.UTF_8.name())
}

class LiveChannelPointsApiException(val code: String, val statusCode: Int) : IllegalStateException(code)
