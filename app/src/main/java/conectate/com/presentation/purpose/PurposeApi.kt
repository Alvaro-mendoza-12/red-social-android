package conectate.com.presentation.purpose

import conectate.com.BuildConfig
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionLease
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class PurposeApi internal constructor(
    private val client: HttpClient, private val token: suspend (AuthSessionLease) -> String, private val endpoint: () -> String,
) {
    @Inject constructor(auth: AuthService, config: SupabaseConfig) : this(config.httpClient, {
        auth.getAccessTokenForAccountAwait(checkNotNull(it.accountId)) ?: throw java.io.IOException("Inicia sesión para continuar.")
    }, { RuntimeBackendEndpoint.baseUrl })
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun available(lease: AuthSessionLease): Boolean {
        val body = request(lease, "$PURPOSE_BASE/capabilities", missingAllowed = true) ?: return false
        val capability = json.decodeFromJsonElement<PurposeCapability>(body)
        return capability.supported && capability.schema == 1
    }
    suspend fun read(lease: AuthSessionLease, target: PurposeTarget) = checkNotNull(request(lease, target.readPath))
    suspend fun send(lease: AuthSessionLease, pending: PurposePending) = checkNotNull(request(lease, pending.path, pending.command))
    private suspend fun request(lease: AuthSessionLease, path: String, command: JsonObject? = null, missingAllowed: Boolean = false): JsonObject? {
        require(path.startsWith("$PURPOSE_BASE/") && path.length < 300 && !path.contains(".."))
        lease.requireCurrent()
        val access = token(lease)
        lease.requireCurrent()
        val base = endpoint().trimEnd('/')
        check(base.isNotBlank())
        val response = client.request(base + path) {
            method = if (command == null) HttpMethod.Get else HttpMethod.Post
            header(HttpHeaders.Authorization, "Bearer $access")
            header(UbuntuStorageUrl.NGROK_SKIP_BROWSER_WARNING, "1")
            header("X-Novvin-Build-Commit", BuildConfig.BUILD_COMMIT)
            header("X-Request-ID", UUID.randomUUID().toString())
            timeout { requestTimeoutMillis = 12_000; connectTimeoutMillis = 5_000; socketTimeoutMillis = 10_000 }
            if (command != null) { contentType(ContentType.Application.Json); setBody(command.toString()) }
        }
        val raw = response.bodyAsText()
        lease.requireCurrent()
        if (missingAllowed && response.status.value in setOf(404, 405)) return null
        val body = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        if (!response.status.isSuccess()) {
            val code = body?.get("error")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (code == "ACCOUNT_DELETED") conectate.com.data.localfallback.AccountDeletedEvents.notifyAccountDeletedFromAuthorization(response.call.request.headers[HttpHeaders.Authorization])
            throw PurposeApiException(response.status.value, code)
        }
        return body ?: throw java.io.IOException("No se pudo leer la respuesta. Actualiza para intentarlo de nuevo.")
    }
}
