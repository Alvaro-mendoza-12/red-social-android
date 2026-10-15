package conectate.com.data.repository

import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class WebLoginPairingSession(
    @SerialName("pairId") val pairId: String,
    val status: String,
    @SerialName("deviceLabel") val deviceLabel: String? = null,
    @SerialName("approvedByUserId") val approvedByUserId: String? = null,
    @SerialName("approvedByEmail") val approvedByEmail: String? = null,
    @SerialName("expiresAt") val expiresAt: String? = null,
    @SerialName("approvedAt") val approvedAt: String? = null,
    @SerialName("rejectedAt") val rejectedAt: String? = null,
    @SerialName("consumedAt") val consumedAt: String? = null,
    @SerialName("qrUrl") val qrUrl: String? = null,
    @SerialName("accessToken") val accessToken: String? = null,
    @SerialName("refreshToken") val refreshToken: String? = null
)

@Serializable
data class WebLoginManagedSession(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("deviceLabel") val deviceLabel: String? = null,
    @SerialName("ipAddress") val ipAddress: String? = null,
    @SerialName("userAgent") val userAgent: String? = null,
    @SerialName("createdAt") val createdAt: String? = null,
    @SerialName("lastSeenAt") val lastSeenAt: String? = null,
    @SerialName("revokedAt") val revokedAt: String? = null
)

@Serializable
private data class WebLoginPairingRequest(
    val action: String,
    @SerialName("pairId") val pairId: String? = null,
    val nonce: String? = null,
    @SerialName("deviceLabel") val deviceLabel: String? = null,
    @SerialName("refreshToken") val refreshToken: String? = null
)

@Serializable
private data class WebLoginPairingError(
    val error: String? = null,
    val message: String? = null
)

@Serializable
private data class WebLoginSessionsResponse(
    val sessions: List<WebLoginManagedSession> = emptyList()
)

@Serializable
private data class WebLoginRevokeAllResponse(
    val ok: Boolean = false,
    val revoked: Int = 0,
    val sessions: List<WebLoginManagedSession> = emptyList()
)

@Singleton
class WebLoginPairingRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig
) {

    private val fallbackBaseUrl: String
        get() = RuntimeBackendEndpoint.baseUrl

    private val useUbuntuPrimary: Boolean
        get() = fallbackBaseUrl.startsWith("http://") || fallbackBaseUrl.startsWith("https://")

    val requiresRefreshTokenForApproval: Boolean
        get() = !useUbuntuPrimary

    private val functionUrl: String
        get() = if (useUbuntuPrimary) {
            "$fallbackBaseUrl/v1/auth/web-login-pairing"
        } else {
            "${supabaseConfig.supabaseUrl}/functions/v1/web-login-pairing"
        }

    private val webSessionsUrl: String
        get() = "$fallbackBaseUrl/v1/auth/web-sessions"

    suspend fun getPairingStatus(pairId: String, nonce: String): Result<WebLoginPairingSession> {
        return invoke(
            request = WebLoginPairingRequest(
                action = "status",
                pairId = pairId,
                nonce = nonce
            )
        )
    }

    suspend fun approvePairing(
        pairId: String,
        nonce: String,
        accessToken: String,
        refreshToken: String
    ): Result<WebLoginPairingSession> {
        return invoke(
            request = WebLoginPairingRequest(
                action = "approve",
                pairId = pairId,
                nonce = nonce,
                refreshToken = refreshToken
            ),
            accessToken = accessToken
        )
    }

    suspend fun rejectPairing(
        pairId: String,
        nonce: String,
        accessToken: String
    ): Result<WebLoginPairingSession> {
        return invoke(
            request = WebLoginPairingRequest(
                action = "reject",
                pairId = pairId,
                nonce = nonce
            ),
            accessToken = accessToken
        )
    }

    suspend fun listActiveWebSessions(accessToken: String): Result<List<WebLoginManagedSession>> = runCatching {
        ensureUbuntuWebSessions()
        val response = supabaseConfig.httpClient.get(webSessionsUrl) {
            ubuntuHeaders(accessToken)
        }
        val raw = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                parseErrorMessage(
                    raw = raw,
                    fallback = "No pudimos leer las sesiones web activas desde Ubuntu."
                )
            )
        }
        supabaseConfig.json.decodeFromString(WebLoginSessionsResponse.serializer(), raw).sessions
    }

    suspend fun revokeWebSession(
        sessionId: String,
        accessToken: String
    ): Result<Unit> = runCatching {
        ensureUbuntuWebSessions()
        val cleanSessionId = sessionId.trim()
        require(cleanSessionId.isNotBlank()) { "Sesión web inválida." }
        val response = supabaseConfig.httpClient.delete("$webSessionsUrl/$cleanSessionId") {
            ubuntuHeaders(accessToken)
        }
        val raw = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                parseErrorMessage(
                    raw = raw,
                    fallback = "No pudimos cerrar esa sesión web."
                )
            )
        }
    }

    suspend fun revokeAllWebSessions(accessToken: String): Result<Int> = runCatching {
        ensureUbuntuWebSessions()
        val response = supabaseConfig.httpClient.delete(webSessionsUrl) {
            ubuntuHeaders(accessToken)
        }
        val raw = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException(
                parseErrorMessage(
                    raw = raw,
                    fallback = "No pudimos cerrar las sesiones web."
                )
            )
        }
        supabaseConfig.json.decodeFromString(WebLoginRevokeAllResponse.serializer(), raw).revoked
    }

    private suspend fun invoke(
        request: WebLoginPairingRequest,
        accessToken: String? = null
    ): Result<WebLoginPairingSession> = runCatching {
        val response = supabaseConfig.httpClient.post(functionUrl) {
            if (useUbuntuPrimary) {
                header("ngrok-skip-browser-warning", "true")
            } else {
                header("apikey", supabaseConfig.supabaseAnonKey)
            }
            accessToken?.takeIf { it.isNotBlank() }?.let { bearer ->
                header("Authorization", "Bearer $bearer")
            }
            contentType(ContentType.Application.Json)
            setBody(supabaseConfig.json.encodeToString(request))
        }

        val raw = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val parsed = runCatching {
                supabaseConfig.json.decodeFromString(WebLoginPairingError.serializer(), raw)
            }.getOrNull()
            val message = parsed?.message
                ?: parsed?.error
                ?: if (useUbuntuPrimary) {
                    "No se pudo verificar el código. Comprueba tu conexión e inténtalo de nuevo."
                } else {
                    "No se pudo completar la operación QR segura."
                }
            throw IllegalStateException(message)
        }

        supabaseConfig.json.decodeFromString(WebLoginPairingSession.serializer(), raw)
    }

    private fun ensureUbuntuWebSessions() {
        if (!useUbuntuPrimary) {
            throw IllegalStateException(
                "Las sesiones web revocables necesitan FALLBACK_API_URL apuntando a Ubuntu."
            )
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.ubuntuHeaders(accessToken: String) {
        header("ngrok-skip-browser-warning", "true")
        accessToken.trim().takeIf { it.isNotBlank() }?.let { token ->
            header("Authorization", "Bearer $token")
        }
    }

    private fun parseErrorMessage(raw: String, fallback: String): String {
        val parsed = runCatching {
            supabaseConfig.json.decodeFromString(WebLoginPairingError.serializer(), raw)
        }.getOrNull()
        return parsed?.message
            ?: parsed?.error
            ?: raw.take(180).takeIf { it.isNotBlank() }
            ?: fallback
    }
}


