package conectate.com.data.repository.message

import conectate.com.data.local.entity.ChatWithUserProfile
import conectate.com.data.local.entity.MessageDeletionEntity
import conectate.com.data.local.entity.MessageEntity
import conectate.com.data.model.ChatWithLastMessage
import conectate.com.data.model.Message
import conectate.com.data.model.parseMessageInstant
import java.time.Instant
import kotlinx.serialization.json.Json

/** Derives presentation only; underlying history and files are never erased. */
internal fun visibleInboxMessage(
    message: Message?,
    chatId: String,
    clearedAt: String?,
    deletions: List<MessageDeletionEntity>,
    account: String,
): Message? {
    if (message == null || message.chat_id != chatId) return null
    if (!isAfterInboxClear(message.created_at, clearedAt)) return null
    return applyMessageDeletions(listOf(message), deletions, account).singleOrNull()
}

internal fun isAfterInboxClear(createdAt: String?, clearedAt: String?): Boolean {
    if (clearedAt.isNullOrBlank()) return true
    val cutoff = inboxTime(clearedAt) ?: return false
    val created = inboxTime(createdAt) ?: return false
    return created > cutoff
}

internal fun MessageEntity.toSafeInboxMessage(): Message? {
    // A malformed deletion list must not be interpreted as permission to show the content.
    val owners = runCatching { Json.decodeFromString<List<String>>(deleted_by_json) }.getOrNull() ?: return null
    return toDomain().copy(deleted_by = owners)
}

internal fun cachedInboxMessage(entity: ChatWithUserProfile, deletions: List<MessageDeletionEntity>, account: String): Message? {
    val chat = entity.chat
    if (chat.last_message_preview == null) return null
    val id = chat.last_message_id?.trim().orEmpty()
    if (id.isBlank() && deletions.any {
            it.chat_id == chat.id && it.state != "failed" && (it.for_everyone || it.actor_id == account)
        }) return null // This unidentifiable summary cannot disprove a local deletion.
    return Message(
        id = id.ifBlank { "inbox-summary:${chat.id}" },
        chat_id = chat.id,
        sender_id = chat.last_message_sender_id.orEmpty(),
        content = chat.last_message_preview,
        created_at = chat.last_message_timestamp ?: chat.last_message_at,
    )
}

internal fun newestInboxMessage(first: Message?, second: Message?): Message? {
    if (first == null) return second
    if (second == null) return first
    // Room's full row takes precedence over a metadata-only summary at the same instant.
    return if ((inboxTime(second.created_at) ?: Instant.MIN) > (inboxTime(first.created_at) ?: Instant.MIN)) second else first
}

internal fun buildVisibleInboxConversation(entity: ChatWithUserProfile, preview: Message?): ChatWithLastMessage {
    val base = entity.toDomainWithDetails()
    val summary = if (preview?.deleted_for_all == true) "Mensaje eliminado" else preview?.content
    return base.copy(
        lastMessage = preview,
        chat = base.chat.copy(
            last_message = summary,
            last_message_id = preview?.id,
            last_message_sender_id = preview?.sender_id,
            last_message_at = preview?.created_at ?: entity.chat.cleared_at,
        ),
    )
}

private fun inboxTime(value: String?): Instant? {
    return value?.takeIf(String::isNotBlank)?.let(::parseMessageInstant)
}
