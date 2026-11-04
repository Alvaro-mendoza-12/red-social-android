package conectate.com.presentation.messages.agreements

import conectate.com.BuildConfig
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionLease
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Uses the existing Ubuntu transport, tokens and trust policy. Commands never auto-retry. */
@Singleton
internal class ChatAgreementApi internal constructor(
    private val client: HttpClient,
    private val accessToken: suspend (AuthSessionLease) -> String,
    private val baseUrl: () -> String,
) {
    @Inject constructor(auth: AuthService, config: SupabaseConfig) : this(config.httpClient, { lease ->
        auth.getAccessTokenForAccountAwait(checkNotNull(lease.accountId)) ?: throw IOException("Inicia sesión para continuar.")
    }, { RuntimeBackendEndpoint.baseUrl })
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun capabilities(lease: AuthSessionLease): AgreementCapability {
        val body = request(lease, "/v1/data/messages/agreements/capabilities", allowMissing = true) ?: return AgreementCapability()
        return json.decodeFromString<AgreementCapability>(body)
    }
    suspend fun load(lease: AuthSessionLease, chatId: String, archived: Boolean = false, cursor: String? = null, focus: String? = null): AgreementCatalog {
        val query = Parameters.build {
            append("limit", "25"); append("archived", archived.toString())
            cursor?.let { append("cursor", it) }; focus?.let { append("focus", it) }
        }.formUrlEncode()
        return json.decodeFromString<AgreementCatalog>(checkNotNull(request(lease, path(chatId) + "?" + query)))
            .validated(checkNotNull(lease.accountId), archived, focus)
    }

    suspend fun send(lease: AuthSessionLease, chatId: String, agreementId: String, command: AgreementCommand): ChatAgreement {
        val suffix = if (command.action == null) "" else "/${encoded(agreementId)}/actions"
        return json.decodeFromString<AgreementReceipt>(checkNotNull(request(lease, path(chatId) + suffix, command))).agreement.validated().also {
            require(it.id == agreementId && it.participants.any { member -> member.id == lease.accountId })
        }
    }
    suspend fun source(lease: AuthSessionLease, chatId: String, agreementId: String): AgreementSource? =
        json.decodeFromString<AgreementSourceReceipt>(checkNotNull(request(lease, path(chatId) + "/${encoded(agreementId)}/source"))).source

    private suspend fun request(lease: AuthSessionLease, path: String, command: AgreementCommand? = null, allowMissing: Boolean = false): String? {
        lease.requireCurrent()
        val token = accessToken(lease)
        lease.requireCurrent()
        val endpoint = baseUrl().trimEnd('/')
        check(endpoint.isNotBlank())
        val response = client.request(endpoint + path) {
            method = if (command == null) HttpMethod.Get else HttpMethod.Post
            header(HttpHeaders.Authorization, "Bearer $token")
            header(UbuntuStorageUrl.NGROK_SKIP_BROWSER_WARNING, "1")
            header("X-Novvin-App-Version", BuildConfig.VERSION_NAME)
            header("X-Novvin-Build-Commit", BuildConfig.BUILD_COMMIT)
            header("X-Request-ID", UUID.randomUUID().toString())
            timeout { requestTimeoutMillis = 12_000; connectTimeoutMillis = 5_000; socketTimeoutMillis = 10_000 }
            if (command != null) { contentType(ContentType.Application.Json); setBody(json.encodeToString(command)) }
        }
        val body = response.bodyAsText()
        lease.requireCurrent()
        if (allowMissing && response.status.value in setOf(404, 405)) return null
        if (!response.status.isSuccess()) {
            val code = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()
            if (code == "ACCOUNT_DELETED") conectate.com.data.localfallback.AccountDeletedEvents
                .notifyAccountDeletedFromAuthorization(response.call.request.headers[HttpHeaders.Authorization])
            throw AgreementApiException(response.status.value, code)
        }
        return body
    }
    private fun path(chatId: String) = "/v1/data/messages/chats/${encoded(chatId)}/agreements"
    private fun encoded(value: String) = value.encodeURLPathPart()
}
