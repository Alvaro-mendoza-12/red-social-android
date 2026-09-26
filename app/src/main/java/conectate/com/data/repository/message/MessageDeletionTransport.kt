package conectate.com.data.repository.message

import conectate.com.data.local.entity.MessageDeletionEntity
import conectate.com.data.localfallback.ChatMessageDeleteException
import conectate.com.data.localfallback.LocalFallbackApiClient
import conectate.com.data.model.Message
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal class MessageDeletionTransport(
    private val localFallback: LocalFallbackApiClient,
    private val supabase: SupabaseConfig,
    private val useUbuntu: () -> Boolean
) {
    suspend fun delete(item: MessageDeletionEntity, token: String): MessageDeleteResult {
        return try {
            if (useUbuntu()) {
                val result = localFallback.deleteChatMessage(token, item.chat_id, item.message_id, item.for_everyone)
                val error = result.exceptionOrNull()
                when {
                    result.isSuccess -> confirmed(item, result.getOrThrow())
                    error is CancellationException -> throw error
                    error is ChatMessageDeleteException && error.statusCode == 404 && error.errorCode == "MESSAGE_NOT_FOUND" -> MessageDeleteResult.Applied()
                    error is ChatMessageDeleteException && error.statusCode in setOf(400, 403) ->
                        MessageDeleteResult.Rejected("El servidor no permitió eliminar este mensaje. Puedes volver a intentarlo desde su menú.")
                    else -> MessageDeleteResult.Retry
                }
            } else {
                // Bind every request to the token captured for the queue's actor. Never
                // let a mutable auth singleton select the next account mid-request.
                val current = supabase.httpClient.get("${supabase.supabaseUrl}/rest/v1/messages") {
                    header("Authorization", "Bearer $token")
                    header("apikey", supabase.supabaseAnonKey)
                    parameter("id", "eq.${item.message_id}")
                    parameter("chat_id", "eq.${item.chat_id}")
                }
                if (!current.status.isSuccess()) return classify(current.status.value)
                val existing = current.body<List<Message>>().singleOrNull()
                    ?: return MessageDeleteResult.Applied()
                val response = supabase.httpClient.patch("${supabase.supabaseUrl}/rest/v1/messages") {
                    header("Authorization", "Bearer $token")
                    header("apikey", supabase.supabaseAnonKey)
                    header("Prefer", "return=representation")
                    contentType(ContentType.Application.Json)
                    parameter("id", "eq.${item.message_id}")
                    parameter("chat_id", "eq.${item.chat_id}")
                    if (item.for_everyone) parameter("sender_id", "eq.${item.actor_id}")
                    setBody(buildJsonObject {
                        if (item.for_everyone) put("deleted_for_all", true) else
                            put("deleted_by", buildJsonArray {
                                (existing.deleted_by + item.actor_id).distinct().forEach { add(it) }
                            })
                    })
                }
                if (!response.status.isSuccess()) return classify(response.status.value)
                val updated = response.body<List<Message>>().singleOrNull()
                    ?: return MessageDeleteResult.Rejected("No se pudo eliminar el mensaje: no hay permisos o cambios confirmados.")
                confirmed(item, updated)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            MessageDeleteResult.Retry
        }
    }

    private fun confirmed(item: MessageDeletionEntity, message: Message): MessageDeleteResult {
        if (message.id != item.message_id || message.chat_id != item.chat_id) return MessageDeleteResult.Retry
        val applied = if (item.for_everyone) message.deleted_for_all else
            message.deleted_by.any { it.trim().lowercase() == item.actor_id }
        return if (applied) MessageDeleteResult.Applied(message) else MessageDeleteResult.Retry
    }

    private fun classify(status: Int): MessageDeleteResult = if (status in setOf(400, 403))
        MessageDeleteResult.Rejected("No se pudo eliminar el mensaje: el servidor rechazó la operación.")
    else MessageDeleteResult.Retry
}
