package conectate.com.data.raffles

import conectate.com.BuildConfig
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

class RaffleApiException(val code: String, val status: Int) : IllegalStateException(raffleErrorMessage(code))

fun raffleErrorMessage(code: String): String = when (code) {
    "RAFFLE_ACCESS_DENIED", "USER_NOT_FOUND", "AUTH_REQUIRED" -> "Ya no tienes acceso a los sorteos de este espacio."
    "RAFFLE_ADMIN_REQUIRED" -> "Solo propietarios y administradores pueden gestionar sorteos."
    "RAFFLE_MANAGEMENT_REQUIRED" -> "Puedes gestionar los sorteos que creaste. Los administradores pueden gestionar los demás."
    "RAFFLE_STATE_CHANGED", "SPIN_NOT_READY" -> "El sorteo cambió o el giro aún no está listo. Se actualizará el estado."
    "MEMBERS_CHANGED" -> "La membresía cambió. Actualiza los participantes antes de crear el sorteo."
    "DUPLICATE_MANUAL_NAME" -> "Ya agregaste un participante manual con ese nombre."
    "INVALID_RAFFLE_NAME" -> "Escribe un nombre de hasta 120 caracteres y una descripción de hasta 500."
    "PARTICIPANT_LIMIT" -> "Selecciona entre 1 y 1.000 participantes."
    "RAFFLE_NOT_FOUND" -> "El sorteo no está disponible en este espacio."
    "RAFFLE_CANCELLED" -> "Este sorteo fue cancelado."
    "RAFFLE_CREATE_LIMIT" -> "Has creado muchos sorteos. Intenta de nuevo más tarde."
    "AUTOMATIC_RUNNING" -> "Pausa el modo automático antes de girar manualmente."
    "ROUND_COMPLETED", "ROUND_NOT_RUNNING", "ROUND_STATE_INVALID" -> "Esta operación no corresponde al estado actual de la ronda."
    "IDEMPOTENCY_CONFLICT" -> "La operación ya se utilizó con otros datos. Actualiza el sorteo."
    "BACKEND_UNAVAILABLE" -> "No se pudo conectar con el servidor. Revisa tu conexión y reintenta."
    "RAFFLES_NOT_DEPLOYED" -> "El servidor todavía no tiene disponible Sorteos. Debe actualizarse para habilitar esta función."
    else -> "No se pudo completar la operación del sorteo. Actualiza y reintenta."
}

@Singleton
class RaffleRepository @Inject constructor(
    private val config: SupabaseConfig,
    private val auth: AuthService,
) {
    private fun path(kind: String, roomId: String) = "/v1/data/raffles/${encode(kind)}/${encode(roomId)}"
    suspend fun list(kind: String, roomId: String, cursor: String? = null): Result<RafflePage> =
        request(path(kind, roomId) + (cursor?.let { "?cursor=${encode(it)}" } ?: ""))
    suspend fun members(kind: String, roomId: String, offset: Int = 0): Result<RaffleMemberPage> =
        request(path(kind, roomId) + "/members?offset=$offset&limit=500")
    suspend fun detail(kind: String, roomId: String, id: String, beforeRound: Int? = null): Result<RaffleDetail> =
        request(path(kind, roomId) + "/${encode(id)}" + (beforeRound?.let { "?beforeRound=$it" } ?: ""))
    suspend fun create(kind: String, roomId: String, body: CreateRaffleRequest): Result<RaffleDetail> =
        request(path(kind, roomId), body)
    suspend fun command(kind: String, roomId: String, id: String, action: String, body: RaffleCommandRequest): Result<RaffleDetail> =
        request(path(kind, roomId) + "/${encode(id)}/${encode(action)}", command = body)

    private suspend inline fun <reified T> request(
        route: String, create: CreateRaffleRequest? = null, command: RaffleCommandRequest? = null,
    ): Result<T> = withContext(Dispatchers.IO) {
        try {
            val token = auth.ensureValidAccessToken().getOrThrow()
            val base = RuntimeBackendEndpoint.ensureFreshEndpoint()?.trimEnd('/')
                ?: throw RaffleApiException("BACKEND_UNAVAILABLE", 503)
            val response = if (create != null || command != null) config.httpClient.post("$base$route") {
                raffleHeaders(token)
                contentType(ContentType.Application.Json)
                if (create != null) setBody(create) else setBody(requireNotNull(command))
            } else config.httpClient.get("$base$route") { raffleHeaders(token) }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                val code = runCatching { config.json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                val fallback = if (response.status.value == 404 && code == null) "RAFFLES_NOT_DEPLOYED" else "RAFFLE_REQUEST_FAILED"
                throw RaffleApiException(code ?: fallback, response.status.value)
            }
            Result.success(config.json.decodeFromString<T>(body))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { Result.failure(error) }
    }

    private fun HttpRequestBuilder.raffleHeaders(token: String) {
        header(HttpHeaders.Authorization, "Bearer ${token.trim()}")
        header("ngrok-skip-browser-warning", "1")
        header("X-Novvin-App-Version", BuildConfig.VERSION_NAME)
        header("X-Novvin-Environment", BuildConfig.BUILD_ENVIRONMENT)
    }
    private fun encode(value: String) = java.net.URLEncoder.encode(value.trim(), "UTF-8").replace("+", "%20")
}
