package conectate.com.data.repository

import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Server permission records are created before notifications or media joins. */
@Singleton
class CallControlClient @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
) {
    private suspend fun request(path: String, payload: kotlinx.serialization.json.JsonObject): Result<Unit> = try {
        withTimeout(5_000L) {
            val token = authService.getAccessTokenAwait()?.trim().orEmpty()
            require(token.isNotBlank()) { "Sesión no válida." }
            val base = RuntimeBackendEndpoint.baseUrl.trimEnd('/')
            val response = supabaseConfig.httpClient.post("$base/v1/data/calls/$path") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
            if (!response.status.isSuccess()) {
                val code = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                val message = when (code) {
                    "CALL_ACCOUNT_BUSY" -> "Ya hay otra llamada activa."
                    "CALL_PARTICIPANT_LIMIT_REACHED" -> "La llamada ya está llena."
                    "CALL_REQUEST_INVALID" -> "Revisa los participantes de la llamada."
                    else -> "Esta llamada no está disponible. Intenta nuevamente."
                }
                throw IllegalStateException(message)
            }
            Unit
        }
        Result.success(Unit)
    } catch (error: TimeoutCancellationException) { Result.failure(IllegalStateException("No se pudo confirmar la llamada. Intenta nuevamente.", error)) }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { Result.failure(error) }

    suspend fun prepare(callId: String, contextType: String, contextId: String?, inviteeIds: List<String>): Result<Unit> =
        request("prepare", buildJsonObject {
            put("callId", callId); put("contextType", contextType); put("contextId", contextId.orEmpty())
            put("inviteeIds", JsonArray(inviteeIds.map(::JsonPrimitive)))
        })

    suspend fun accept(callId: String): Result<Unit> = request("$callId/accept", buildJsonObject {})
    suspend fun leave(callId: String, action: String = "end"): Result<Unit> = request("$callId/leave", buildJsonObject { put("action", action) })
}
