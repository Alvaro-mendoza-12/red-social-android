package conectate.com.data.localfallback

import conectate.com.BuildConfig
import conectate.com.data.model.Message
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.call.body
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LocalFallbackUser(
    val id: String = "",
    val email: String = "",
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val supabaseUserId: String? = null,
    val syncSource: String? = null,
    val syncStatus: String? = null,
    val syncError: String? = null,
    val localPasswordSetAt: String? = null,
    val passwordChangedByUserAt: String? = null,
    val supabaseSyncedAt: String? = null,
    val accountCreatedAt: String? = null,
    val passwordChangeRequired: Boolean = false,
    val createdAt: String? = null,
    val lastLoginAt: String? = null
)

@Serializable
data class LocalFallbackAuthResponse(
    val user: LocalFallbackUser,
    val accessToken: String = "",
    val tokenType: String = "Bearer",
    val expiresIn: String = "7d"
)

@Serializable
data class LocalFallbackInboxItem(
    val id: String = "",
    val title: String = "",
    val subtitle: String = "",
    val updatedAt: String = "",
    val kind: String = "chat",
    val avatarUrl: String = "",
    val profileId: String? = null,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isMuted: Boolean = false,
    val isArchived: Boolean = false,
    val isFavorite: Boolean = false,
    val folders: List<String> = emptyList(),
    val openPath: String? = null
)

@Serializable
data class LocalFallbackInboxResponse(
    val items: List<LocalFallbackInboxItem> = emptyList()
)

@Serializable
data class LocalFallbackParticipant(
    val id: String = "",
    val name: String = "",
    val avatarUrl: String = "",
    val lastReadAt: String = ""
)

@Serializable
data class LocalFallbackChatSettings(
    val isMuted: Boolean = false,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val isFavorite: Boolean = false,
    val translateEnabled: Boolean = false,
    val targetLang: String = "es"
)

@Serializable
data class LocalFallbackChatResponse(
    val chat: JsonObject? = null,
    val title: String = "",
    val otherParticipant: LocalFallbackParticipant? = null,
    val messages: List<JsonObject> = emptyList(),
    val settings: LocalFallbackChatSettings = LocalFallbackChatSettings()
)

@Serializable
data class LocalFallbackStorageObject(
    val id: String = "",
    val bucket: String = "",
    val path: String = "",
    val publicUrl: String = "",
    val url: String = "",
    val contentType: String = "application/octet-stream",
    val size: Long = 0L,
    val ownerId: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
private data class LocalFallbackStorageResponse(
    val `object`: LocalFallbackStorageObject
)

@Serializable
private data class LocalFallbackLoginRequest(
    val email: String,
    val password: String
)

@Serializable
private data class LocalFallbackRegisterRequest(
    val email: String,
    val password: String,
    val displayName: String? = null
)

@Serializable
private data class LocalFallbackChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)

@Serializable
private data class LocalFallbackCompleteMigrationRequest(
    val migrationToken: String,
    val newPassword: String
)

@Serializable
private data class LocalFallbackSendMessageRequest(
    val content: String,
    val contentType: String,
    val mediaUrl: String = "",
    val mediaSize: Long = 0L,
    val replyToId: String = ""
)

@Serializable
private data class LocalFallbackErrorResponse(
    val error: String? = null,
    val message: String? = null,
    val migrationToken: String? = null,
    val email: String? = null,
    val displayName: String? = null
)

@Serializable
private data class LocalFallbackMessageResponse(
    val message: Message
)

class LocalFallbackMigratedPasswordResetRequiredException(
    val migrationToken: String,
    val email: String,
    val displayName: String
) : Exception("Tu cuenta fue migrada al servidor local. Cambia tu contraseña para continuar.")

@Singleton
class LocalFallbackApiClient @Inject constructor(
    private val supabaseConfig: SupabaseConfig
) {
    val baseUrl: String = BuildConfig.FALLBACK_API_URL.trim().replace(Regex("/+$"), "")
    val isConfigured: Boolean = baseUrl.startsWith("http", ignoreCase = true)

    private val json = supabaseConfig.json

    suspend fun login(email: String, password: String): Result<LocalFallbackAuthResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        val normalizedEmail = email.trim().lowercase()
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/auth/login") {
                localHeaders()
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackLoginRequest(normalizedEmail, password))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString(body))
            } else {
                val error = decodeError(body)
                if (
                    error.error == "MIGRATED_ACCOUNT_PASSWORD_RESET_REQUIRED" &&
                    !error.migrationToken.isNullOrBlank() &&
                    password.length >= 8
                ) {
                    return completeMigratedPasswordReset(error.migrationToken, password)
                }
                Result.failure(toException(response, error))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun register(
        email: String,
        password: String,
        displayName: String?
    ): Result<LocalFallbackAuthResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/auth/register") {
                localHeaders()
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackRegisterRequest(
                        email = email.trim().lowercase(),
                        password = password,
                        displayName = displayName?.trim()?.takeIf { it.isNotBlank() }
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString(body))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun changePassword(
        accessToken: String,
        currentPassword: String,
        newPassword: String
    ): Result<LocalFallbackAuthResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/auth/change-password") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackChangePasswordRequest(currentPassword, newPassword))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString(body))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun loadMessagesInbox(accessToken: String, tab: String = "chats"): Result<LocalFallbackInboxResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson("$baseUrl/v1/data/messages/inbox?tab=${urlEncode(tab)}", accessToken)
    }

    suspend fun loadChat(accessToken: String, chatId: String): Result<LocalFallbackChatResponse> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return requestJson("$baseUrl/v1/data/messages/chats/${urlEncode(chatId)}", accessToken)
    }

    suspend fun sendChatMessage(
        accessToken: String,
        chatId: String,
        content: String,
        contentType: String,
        mediaUrl: String?,
        mediaSize: Long,
        replyToId: String?
    ): Result<Message> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/data/messages/chats/${urlEncode(chatId)}/messages") {
                localHeaders(accessToken)
                contentType(ContentType.Application.Json)
                setBody(
                    LocalFallbackSendMessageRequest(
                        content = content,
                        contentType = contentType,
                        mediaUrl = mediaUrl ?: "",
                        mediaSize = mediaSize,
                        replyToId = replyToId ?: ""
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackMessageResponse>(body).message)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun uploadFile(
        accessToken: String,
        bucketName: String,
        objectPath: String,
        bytes: ByteArray,
        contentType: String,
        fileName: String
    ): Result<LocalFallbackStorageObject> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/storage/upload") {
                localHeaders(accessToken)
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("bucket", bucketName)
                            append("path", objectPath)
                            append(
                                "file",
                                bytes,
                                Headers.build {
                                    append(HttpHeaders.ContentType, contentType)
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        "form-data; name=\"file\"; filename=\"${safeMultipartFileName(fileName)}\""
                                    )
                                }
                            )
                        }
                    )
                )
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString<LocalFallbackStorageResponse>(body).`object`)
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun downloadPublicFile(url: String): Result<ByteArray> {
        if (!isConfigured) return Result.failure(Exception("Servidor Ubuntu no configurado."))
        return try {
            val response = supabaseConfig.httpClient.get(url) {
                localHeaders()
            }
            if (response.status.isSuccess()) {
                Result.success(response.body())
            } else {
                Result.failure(toException(response, decodeError(response.bodyAsText())))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    fun publicObjectUrl(bucketName: String, objectPath: String): String {
        if (!isConfigured) return ""
        val encodedPath = objectPath.trim().replace("\\", "/")
            .split("/")
            .filter { it.isNotBlank() }
            .joinToString("/") { urlEncode(it) }
        return "$baseUrl/storage/v1/object/public/${urlEncode(bucketName)}/$encodedPath"
    }

    private suspend fun completeMigratedPasswordReset(
        migrationToken: String,
        newPassword: String
    ): Result<LocalFallbackAuthResponse> {
        return try {
            val response = supabaseConfig.httpClient.post("$baseUrl/v1/auth/complete-migrated-password-reset") {
                localHeaders()
                contentType(ContentType.Application.Json)
                setBody(LocalFallbackCompleteMigrationRequest(migrationToken, newPassword))
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString(body))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend inline fun <reified T> requestJson(url: String, accessToken: String): Result<T> {
        return try {
            val response = supabaseConfig.httpClient.get(url) {
                localHeaders(accessToken)
            }
            val body = response.bodyAsText()
            if (response.status.isSuccess()) {
                Result.success(json.decodeFromString(body))
            } else {
                Result.failure(toException(response, decodeError(body)))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.localHeaders(accessToken: String? = null) {
        headers {
            append("ngrok-skip-browser-warning", "true")
            accessToken?.trim()?.takeIf { it.isNotBlank() }?.let {
                append(HttpHeaders.Authorization, "Bearer $it")
            }
        }
    }

    private fun decodeError(body: String): LocalFallbackErrorResponse {
        if (body.isBlank()) return LocalFallbackErrorResponse()
        return runCatching { json.decodeFromString<LocalFallbackErrorResponse>(body) }
            .getOrElse {
                LocalFallbackErrorResponse(message = body.take(240))
            }
    }

    private fun toException(response: HttpResponse, error: LocalFallbackErrorResponse): Exception {
        val code = (error.error ?: error.message).orEmpty().trim()
        return when (code) {
            "INVALID_CREDENTIALS" -> Exception("Correo o contraseña inválidos en el servidor Ubuntu.")
            "EMAIL_ALREADY_EXISTS" -> Exception("Ese correo ya existe en el servidor Ubuntu.")
            "EMAIL_AND_PASSWORD_REQUIRED" -> Exception("Escribe correo y contraseña.")
            "MIGRATION_TOKEN_AND_PASSWORD_REQUIRED" -> Exception("Tu cuenta migrada necesita una contraseña de al menos 8 caracteres.")
            "MIGRATED_ACCOUNT_PASSWORD_RESET_REQUIRED" -> LocalFallbackMigratedPasswordResetRequiredException(
                migrationToken = error.migrationToken.orEmpty(),
                email = error.email.orEmpty(),
                displayName = error.displayName.orEmpty()
            )
            else -> Exception(code.ifBlank { "Servidor Ubuntu respondió ${response.status.value}." })
        }
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun safeMultipartFileName(value: String): String =
        value.replace("\\", "_")
            .replace("\"", "_")
            .replace("\r", "_")
            .replace("\n", "_")
            .ifBlank { "upload.bin" }
}
