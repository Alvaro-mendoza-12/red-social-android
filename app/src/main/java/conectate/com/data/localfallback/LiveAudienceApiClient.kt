package conectate.com.data.localfallback

import conectate.com.BuildConfig
import conectate.com.data.model.LiveAudienceState
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LiveAudienceApiClient @Inject constructor(private val config: SupabaseConfig) {
    suspend fun request(
        token: String,
        liveId: String,
        path: String = "",
        method: HttpMethod = HttpMethod.Get,
        body: JsonObject? = null,
    ): Result<LiveAudienceState> = try {
        require(token.isNotBlank())
        val id = UUID.fromString(liveId).toString()
        val base = RuntimeBackendEndpoint.ensureFreshEndpoint()?.trimEnd('/')
            ?: throw LiveAudienceException("BACKEND_UNAVAILABLE")
        val response = config.httpClient.request("$base/v1/data/live/$id/audience$path") {
            this.method = method
            header(HttpHeaders.Authorization, "Bearer ${token.trim()}")
            header("X-Novvin-Version-Code", BuildConfig.VERSION_CODE.toString())
            header("X-Request-ID", UUID.randomUUID().toString())
            header("ngrok-skip-browser-warning", "1")
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        }
        val value = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val code = runCatching {
                config.json.parseToJsonElement(value).jsonObject["error"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            throw LiveAudienceException(code ?: "LIVE_AUDIENCE_UNAVAILABLE")
        }
        Result.success(config.json.decodeFromString<LiveAudienceState>(value))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}

class LiveAudienceException(val code: String) : IllegalStateException(code)
