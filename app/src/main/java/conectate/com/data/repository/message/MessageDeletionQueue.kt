package conectate.com.data.repository.message

import androidx.room.withTransaction
import conectate.com.data.local.AppDatabase
import conectate.com.data.local.entity.MessageDeletionEntity
import conectate.com.data.model.Message
import conectate.com.domain.sync.PendingSyncRetryException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal sealed interface MessageDeleteResult {
    data class Applied(val message: Message? = null) : MessageDeleteResult
    data class Rejected(val reason: String) : MessageDeleteResult
    data object Retry : MessageDeleteResult
}

/** Durable, account-bound deletion intents, independent of the send outbox and UI lifecycle. */
internal class MessageDeletionQueue(
    private val database: AppDatabase,
    private val coordinator: MessageSendCoordinator,
    private val accessToken: suspend () -> Result<String>,
    private val remote: suspend (MessageDeletionEntity, String) -> MessageDeleteResult
) {
    private val messages get() = database.messageDao()
    private val deletions get() = database.messageDeletionDao()

    suspend fun enqueue(
        messageId: String,
        accountId: String,
        forEveryone: Boolean,
        isCurrentSession: () -> Boolean
    ): Result<Unit> = try {
        val actor = accountId.trim().lowercase()
        require(actor.isNotBlank()) { "No hay una cuenta activa" }
        // Persist immediately; only the remote replay waits for an in-flight send.
        database.withTransaction {
            requireSession(isCurrentSession)
            val message = messages.getMessageById(messageId) ?: error("Mensaje no encontrado")
            val chat = database.chatDao().getChatById(message.chat_id)?.toDomain()
            check(chat?.isAccessibleTo(actor) == true) { "No tienes acceso a este chat" }
            check(!forEveryone || message.sender_id.trim().lowercase() == actor) {
                "Solo puedes eliminar para todos tus propios mensajes"
            }
            requireSession(isCurrentSession)
            val existing = deletions.get(message.id, actor, forEveryone)
            if (existing == null || existing.state == "failed") {
                deletions.put(MessageDeletionEntity(
                    message_id = message.id, actor_id = actor, for_everyone = forEveryone,
                    chat_id = message.chat_id, revision = UUID.randomUUID().toString(),
                    created_at = System.currentTimeMillis()
                ))
            }
            // Do not upload/send deleted content. Still ask the server to delete: a
            // previous send could have committed before its response was lost.
            // Delete-for-me deliberately leaves an outgoing send intact.
            if (forEveryone) messages.cancelPendingSendForDeletion(message.id)
            requireSession(isCurrentSession)
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    suspend fun retry(accountId: String, maxBatch: Int, isCurrentSession: () -> Boolean): Result<Int> = try {
        val actor = accountId.trim().lowercase()
        requireSession(isCurrentSession)
        val pending = deletions.pending(actor, maxBatch.coerceAtLeast(1))
        requireSession(isCurrentSession)
        if (pending.isEmpty()) Result.success(0) else {
            val token = accessToken().getOrThrow()
            requireSession(isCurrentSession)
            if (token.isBlank()) throw PendingSyncRetryException("Deletion token unavailable")
            var count = 0
            for (item in pending) {
                coordinator.withMessage(item.message_id) {
                    requireSession(isCurrentSession)
                    val latest = deletions.get(item.message_id, actor, item.for_everyone)
                    if (latest?.revision != item.revision || latest.state != "pending") return@withMessage
                    val chat = database.chatDao().getChatById(item.chat_id)?.toDomain()
                    val message = messages.getMessageById(item.message_id)
                    requireSession(isCurrentSession)
                    val result = when {
                        chat?.isAccessibleTo(actor) != true -> MessageDeleteResult.Rejected("Ya no tienes acceso a este chat")
                        message != null && (message.chat_id != item.chat_id ||
                            (item.for_everyone && message.sender_id.trim().lowercase() != actor)) ->
                            MessageDeleteResult.Rejected("No puedes eliminar este mensaje para todos")
                        else -> remote(item, token)
                    }
                    requireSession(isCurrentSession)
                    database.withTransaction {
                        requireSession(isCurrentSession)
                        when (result) {
                            is MessageDeleteResult.Applied -> {
                                val canonical = result.message
                                if (canonical != null) {
                                    check(canonical.id == item.message_id && canonical.chat_id == item.chat_id) {
                                        "Deletion response belongs to another message"
                                    }
                                    // Preserve concurrent read receipts, reactions, uploads and edits.
                                    val local = messages.getMessageById(item.message_id)
                                    if (local != null) messages.insertMessage(local.copy(
                                        deleted_for_all = local.deleted_for_all || canonical.deleted_for_all,
                                        deleted_by_json = Json.encodeToString((local.toDomain().deleted_by + canonical.deleted_by).distinct())
                                    ))
                                }
                                deletions.finish(item.revision, "applied")
                                count++
                            }
                            is MessageDeleteResult.Rejected -> deletions.finish(item.revision, "failed", result.reason)
                            MessageDeleteResult.Retry -> Unit
                        }
                        requireSession(isCurrentSession)
                    }
                }
            }
            requireSession(isCurrentSession)
            if (deletions.pending(actor, 1).isNotEmpty()) throw PendingSyncRetryException("Deletions remain pending")
            requireSession(isCurrentSession)
            Result.success(count)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(if (error is PendingSyncRetryException) error else
            PendingSyncRetryException("Deletion sync could not finish", error))
    }

    private fun requireSession(isCurrentSession: () -> Boolean) {
        if (!isCurrentSession()) throw PendingSyncRetryException("Deletion session changed")
    }
}
