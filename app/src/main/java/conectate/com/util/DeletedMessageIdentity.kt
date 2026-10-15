package conectate.com.util

import java.security.MessageDigest

/** A deletion must never fall back to the active account or to a conversation alone. */
data class DeletedMessageIdentity(val accountId: String, val targetType: String, val conversationId: String, val messageId: String) {
    val conversationKey: String get() = digest(listOf(accountId, targetType, conversationId))
    val key: String get() = conversationKey + ":" + digest(listOf(messageId))
    companion object {
        fun create(accountId: String?, targetType: String?, conversationId: String?, messageId: String?): DeletedMessageIdentity? {
            val values = listOf(accountId, targetType, conversationId, messageId).map { it?.trim()?.lowercase().orEmpty() }
            if (values.any { it.isBlank() || it.length > 200 || '\u0000' in it } || values[1] !in setOf("chat", "group", "community")) return null
            return DeletedMessageIdentity(values[0], values[1], values[2], values[3])
        }
        private fun digest(values: List<String>) = MessageDigest.getInstance("SHA-256")
            .digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

data class DeletedMessageEvent(val identity: DeletedMessageIdentity, val channelId: String?, val createdAt: String?)

fun deletedMessageEvent(data: Map<String, String>): DeletedMessageEvent? {
    if (!data["type"].equals("message_deleted", true) || !data["deleted_for_all"].equals("true", true)) return null
    val type = data["target_type"]?.trim()?.lowercase()?.let { if (it == "direct") "chat" else it } ?: when {
        !data["community_id"].isNullOrBlank() -> "community"
        !data["group_id"].isNullOrBlank() -> "group"
        else -> "chat"
    }
    val conversation = when (type) {
        "community" -> data["community_id"] ?: data["chat_id"]
        "group" -> data["group_id"] ?: data["chat_id"]
        else -> data["chat_id"]
    }
    val identity = DeletedMessageIdentity.create(data["recipient_id"], type, conversation, data["message_id"]) ?: return null
    return DeletedMessageEvent(identity, data["channel_id"], data["message_created_at"] ?: data["created_at"])
}
