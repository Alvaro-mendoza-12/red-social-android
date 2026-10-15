package conectate.com.data.repository.message

import conectate.com.core.media.readMediaBytesWithLimit

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import conectate.com.data.model.Chat
import conectate.com.data.model.Message
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.StorageService
import conectate.com.data.supabase.SupabaseConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRemoteStore @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService,
    private val storageService: StorageService,
    @param:ApplicationContext private val context: Context
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    suspend fun sendTextMessage(
        chatId: String,
        content: String,
        replyToMessageId: String? = null
    ): MessageRemoteResult<Message> {
        val cleanContent = content.trim()
        if (cleanContent.isBlank()) return MessageRemoteResult.Error("El mensaje no puede estar vacio.")

        return sendMessagePayload(
            chatId = chatId,
            content = cleanContent,
            contentType = "text",
            mediaUrl = null,
            mediaSize = 0L,
            replyToMessageId = replyToMessageId
        )
    }

    suspend fun sendMediaMessage(
        chatId: String,
        mediaUri: Uri,
        mimeType: String?,
        caption: String? = null
    ): MessageRemoteResult<Message> {
        val token = authService.getAccessToken()
            ?: return MessageRemoteResult.Error("No hay sesion activa.")
        val cleanChatId = chatId.trim().lowercase()
        if (cleanChatId.isBlank()) return MessageRemoteResult.Error("Chat invalido.")

        val normalizedMime = normalizeMimeType(mimeType ?: context.contentResolver.getType(mediaUri))
        val contentType = contentTypeFromMime(normalizedMime)
        val extension = extensionFromMime(normalizedMime, contentType)
        val filename = "$cleanChatId/${UUID.randomUUID()}$extension"
        val reportedSize = queryUriSize(mediaUri)
        var uploadedSize = reportedSize

        val upload = if (reportedSize > 0L) {
            storageService.uploadFileFromUri(
                bucketName = "chat-media",
                fileName = filename,
                sourceUri = mediaUri,
                sizeBytes = reportedSize,
                contentType = normalizedMime,
                allowOverwrite = true,
                onProgress = {}
            )
        } else {
            val bytes = runCatching {
                context.contentResolver.openInputStream(mediaUri)?.use { it.readMediaBytesWithLimit(60L * 1024 * 1024) }
            }.getOrNull() ?: return MessageRemoteResult.Error("No se pudo leer el archivo.")
            if (bytes.isEmpty()) return MessageRemoteResult.Error("El archivo esta vacio.")
            uploadedSize = bytes.size.toLong()
            storageService.uploadFile(
                bucketName = "chat-media",
                fileName = filename,
                bytes = bytes,
                contentType = normalizedMime
            )
        }

        if (upload.isFailure) {
            return MessageRemoteResult.Error(upload.exceptionOrNull()?.message ?: "No se pudo subir multimedia.")
        }
        val remoteKey = upload.getOrThrow().Key ?: filename
        val storedMediaUrl = if (remoteKey.startsWith("chat-media/", ignoreCase = true)) {
            remoteKey
        } else {
            "chat-media/$remoteKey"
        }

        val sendResult = sendMessagePayload(
            chatId = cleanChatId,
            content = caption?.trim().orEmpty(),
            contentType = contentType,
            mediaUrl = storedMediaUrl,
            mediaSize = uploadedSize,
            replyToMessageId = null,
            prevalidatedToken = token
        )
        if (sendResult is MessageRemoteResult.Error) {
            storageService.deleteFile("chat-media", remoteKey).onFailure { error ->
                android.util.Log.w("MessageRemoteStore", "No se pudo limpiar chat-media/$remoteKey", error)
            }
        }
        return sendResult
    }

    suspend fun editMessage(
        messageId: String,
        newContent: String
    ): MessageRemoteResult<Message> {
        val token = authService.getAccessToken()
            ?: return MessageRemoteResult.Error("No hay sesion activa.")
        val userId = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: return MessageRemoteResult.Error("No hay usuario activo.")
        val cleanMessageId = messageId.trim()
        val cleanContent = newContent.trim()
        if (cleanMessageId.isBlank()) return MessageRemoteResult.Error("Mensaje invalido.")
        if (cleanContent.isBlank()) return MessageRemoteResult.Error("El mensaje no puede estar vacio.")

        return runCatching {
            val response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/messages") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Prefer", "return=representation")
                contentType(ContentType.Application.Json)
                parameter("id", "eq.$cleanMessageId")
                parameter("sender_id", "eq.$userId")
                parameter("deleted_for_all", "eq.false")
                setBody(
                    buildJsonObject {
                        put("content", cleanContent)
                        put("is_edited", true)
                        put("updated_at", Instant.now().toString())
                    }
                )
            }

            if (!response.status.isSuccess()) {
                return MessageRemoteResult.Error("No se pudo editar el mensaje: ${response.status.value}.")
            }

            val updated = parseMessageResponse(response.bodyAsText())
                ?: return MessageRemoteResult.Error("No se recibio el mensaje editado.")
            MessageRemoteResult.Success(updated)
        }.getOrElse {
            MessageRemoteResult.Error(it.message ?: "Error editando mensaje.")
        }
    }

    suspend fun deleteMessage(
        messageId: String,
        deleteForEveryone: Boolean = false
    ): MessageRemoteResult<Unit> {
        val token = authService.getAccessToken()
            ?: return MessageRemoteResult.Error("No hay sesion activa.")
        val userId = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: return MessageRemoteResult.Error("No hay usuario activo.")
        val cleanMessageId = messageId.trim()
        if (cleanMessageId.isBlank()) return MessageRemoteResult.Error("Mensaje invalido.")

        return runCatching {
            val payload = if (deleteForEveryone) {
                buildJsonObject {
                    put("deleted_for_all", true)
                    put("updated_at", Instant.now().toString())
                }
            } else {
                val deletedBy = getDeletedBy(token, cleanMessageId)
                val merged = (deletedBy + userId).distinct()
                buildJsonObject {
                    put("deleted_by", buildJsonArray { merged.forEach { add(it) } })
                    put("updated_at", Instant.now().toString())
                }
            }

            val response = supabaseConfig.httpClient.patch("${supabaseConfig.supabaseUrl}/rest/v1/messages") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                contentType(ContentType.Application.Json)
                parameter("id", "eq.$cleanMessageId")
                if (deleteForEveryone) parameter("sender_id", "eq.$userId")
                setBody(payload)
            }

            if (response.status.isSuccess()) {
                MessageRemoteResult.Success(Unit)
            } else {
                MessageRemoteResult.Error("No se pudo eliminar el mensaje: ${response.status.value}.")
            }
        }.getOrElse {
            MessageRemoteResult.Error(it.message ?: "Error eliminando mensaje.")
        }
    }

    suspend fun markMessageRead(
        messageId: String,
        chatId: String
    ): MessageRemoteResult<Unit> {
        val token = authService.getAccessToken()
            ?: return MessageRemoteResult.Error("No hay sesion activa.")
        val cleanChatId = chatId.trim().lowercase()
        if (messageId.isBlank() || cleanChatId.isBlank()) {
            return MessageRemoteResult.Error("Mensaje o chat invalido.")
        }

        return runCatching {
            val rpcResponse = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_mark_messages_read") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("p_chat_id", cleanChatId) })
            }

            if (rpcResponse.status.isSuccess()) {
                MessageRemoteResult.Success(Unit)
            } else {
                MessageRemoteResult.Error("No se pudo marcar como leido: ${rpcResponse.status.value}.")
            }
        }.getOrElse {
            MessageRemoteResult.Error(it.message ?: "Error marcando lectura.")
        }
    }

    suspend fun loadChatHistory(
        chatId: String,
        limit: Int = 50,
        before: String? = null
    ): MessageRemoteResult<List<Message>> {
        val token = authService.getAccessToken()
            ?: return MessageRemoteResult.Error("No hay sesion activa.")
        val cleanChatId = chatId.trim().lowercase()
        if (cleanChatId.isBlank()) return MessageRemoteResult.Error("Chat invalido.")

        return runCatching {
            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/messages") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("chat_id", "eq.$cleanChatId")
                parameter("select", "*")
                parameter("order", "created_at.desc")
                parameter("limit", limit.coerceIn(1, 100).toString())
                before?.takeIf { it.isNotBlank() }?.let { parameter("created_at", "lt.$it") }
            }

            if (!response.status.isSuccess()) {
                return MessageRemoteResult.Error("No se pudo cargar historial: ${response.status.value}.")
            }

            MessageRemoteResult.Success(response.body<List<Message>>().asReversed())
        }.getOrElse {
            MessageRemoteResult.Error(it.message ?: "Error cargando historial.")
        }
    }

    suspend fun getUserChats(): MessageRemoteResult<List<Chat>> {
        val token = authService.getAccessToken()
            ?: return MessageRemoteResult.Error("No hay sesion activa.")
        val userId = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: return MessageRemoteResult.Error("No hay usuario activo.")

        return try {
            val participantResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/chat_participants") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("user_id", "eq.$userId")
                parameter("select", "chat_id")
            }
            if (!participantResponse.status.isSuccess()) {
                return MessageRemoteResult.Error("No se pudieron cargar chats: ${participantResponse.status.value}.")
            }

            val chatIds = participantResponse.body<List<JsonObject>>()
                .mapNotNull { it["chat_id"]?.jsonPrimitive?.contentOrNull }
                .distinct()

            if (chatIds.isEmpty()) return MessageRemoteResult.Success(emptyList())

            val chatsResponse = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/chats") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                parameter("id", "in.(${chatIds.joinToString(",")})")
                parameter("select", "*")
                parameter("order", "last_message_at.desc.nullslast")
            }
            if (!chatsResponse.status.isSuccess()) {
                return MessageRemoteResult.Error("No se pudieron cargar chats: ${chatsResponse.status.value}.")
            }

            MessageRemoteResult.Success(chatsResponse.body<List<Chat>>())
        } catch (e: Exception) {
            MessageRemoteResult.Error(e.message ?: "Error cargando chats.")
        }
    }

    private suspend fun sendMessagePayload(
        chatId: String,
        content: String,
        contentType: String,
        mediaUrl: String?,
        mediaSize: Long,
        replyToMessageId: String?,
        prevalidatedToken: String? = null
    ): MessageRemoteResult<Message> {
        val token = prevalidatedToken ?: authService.getAccessToken()
            ?: return MessageRemoteResult.Error("No hay sesion activa.")
        val userId = authService.getCurrentUserId()?.trim()?.lowercase()
            ?: return MessageRemoteResult.Error("No hay usuario activo.")
        val cleanChatId = chatId.trim().lowercase()
        if (cleanChatId.isBlank()) return MessageRemoteResult.Error("Chat invalido.")

        val localMessage = Message(
            id = UUID.randomUUID().toString(),
            chat_id = cleanChatId,
            sender_id = userId,
            content = content,
            content_type = contentType,
            media_url = mediaUrl,
            media_size = mediaSize,
            reply_to_id = replyToMessageId?.trim()?.takeIf { it.isNotBlank() },
            created_at = Instant.now().toString(),
            status = "sending"
        )

        return runCatching {
            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/secure_send_message") {
                header("Authorization", "Bearer $token")
                header("apikey", supabaseConfig.supabaseAnonKey)
                header("Prefer", "return=representation")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("p_chat_id", localMessage.chat_id)
                        put("p_message_id", localMessage.id)
                        put("p_content", localMessage.content)
                        put("p_content_type", localMessage.content_type)
                        put("p_media_url", localMessage.media_url)
                        put("p_media_size", localMessage.media_size)
                        put("p_reply_to_id", localMessage.reply_to_id)
                        put("p_is_forwarded", false)
                        put("p_forwarded_from_message_id", null as String?)
                        put("p_forwarded_from_user_id", null as String?)
                    }
                )
            }

            if (!response.status.isSuccess()) {
                val body = runCatching { response.bodyAsText() }.getOrDefault("")
                return MessageRemoteResult.Error(body.ifBlank { "No se pudo enviar mensaje: ${response.status.value}." })
            }

            val remote = parseMessageResponse(response.bodyAsText())
                ?: localMessage.copy(status = "sent")
            MessageRemoteResult.Success(remote.copy(status = "sent"))
        }.getOrElse {
            MessageRemoteResult.Error(it.message ?: "Error enviando mensaje.")
        }
    }

    private suspend fun getDeletedBy(token: String, messageId: String): List<String> {
        val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/messages") {
            header("Authorization", "Bearer $token")
            header("apikey", supabaseConfig.supabaseAnonKey)
            parameter("id", "eq.$messageId")
            parameter("select", "deleted_by")
            parameter("limit", "1")
        }
        if (!response.status.isSuccess()) return emptyList()
        val row = response.body<List<JsonObject>>().firstOrNull() ?: return emptyList()
        val element = row["deleted_by"] ?: return emptyList()
        if (element is JsonNull) return emptyList()
        return when (element) {
            is JsonArray -> element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            else -> emptyList()
        }
    }

    private fun parseMessageResponse(raw: String): Message? {
        if (raw.isBlank()) return null
        return runCatching { json.decodeFromString<Message>(raw) }.getOrNull()
            ?: runCatching { json.decodeFromString<List<Message>>(raw).firstOrNull() }.getOrNull()
    }

    private fun normalizeMimeType(mimeType: String?): String =
        mimeType?.substringBefore(";")?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"

    private fun contentTypeFromMime(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "image"
        mimeType.startsWith("video/") -> "video"
        mimeType.startsWith("audio/") -> "audio"
        else -> "document"
    }

    private fun extensionFromMime(mimeType: String, contentType: String): String {
        val extension = MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(mimeType)
            ?.takeIf { it.isNotBlank() }
        return when {
            extension != null -> ".$extension"
            contentType == "image" -> ".jpg"
            contentType == "video" -> ".mp4"
            contentType == "audio" -> ".m4a"
            else -> ".bin"
        }
    }

    private fun queryUriSize(uri: Uri): Long {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) {
                    val value = cursor.getLong(index)
                    if (value > 0L) return value
                }
            }
        }
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length > 0L) return descriptor.length
            }
        }
        return 0L
    }
}

sealed class MessageRemoteResult<out T> {
    data class Success<T>(val data: T) : MessageRemoteResult<T>()
    data class Error(val message: String) : MessageRemoteResult<Nothing>()
}
