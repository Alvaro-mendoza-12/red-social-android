package conectate.com.data.repository

import conectate.com.BuildConfig
import conectate.com.data.supabase.SupabaseConfig
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

@Singleton
class WebLoginPairingRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig
) {

    private val fallbackBaseUrl: String
        get() = BuildConfig.FALLBACK_API_URL.trim().trimEnd('/')

    private val useUbuntuPrimary: Boolean
        get() = fallbackBaseUrl.startsWith("http://") || fallbackBaseUrl.startsWith("https://")

    private val functionUrl: String
        get() = if (useUbuntuPrimary) {
            "$fallbackBaseUrl/v1/auth/web-login-pairing"
        } else {
            "${supabaseConfig.supabaseUrl}/functions/v1/web-login-pairing"
        }

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
                    "No se pudo completar el QR con Ubuntu. Verifica que la VM, Docker y ngrok estén encendidos."
                } else {
                    "No se pudo completar la operación QR segura."
                }
            throw IllegalStateException(message)
        }

        supabaseConfig.json.decodeFromString(WebLoginPairingSession.serializer(), raw)
    }
}


