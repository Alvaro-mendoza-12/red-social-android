package conectate.com.util

private val messageDeliveryTypes = setOf(
    "message", "new_message", "chat_message", "group_message", "community_message", "text", "image", "video",
    "audio", "sticker", "file", "document", "location", "contact",
)

/** A room ID on an activity/call is context, never permission to invent a local chat message. */
internal fun isMessageDeliveryPush(type: String, messageId: String?, senderId: String?, recipientId: String?): Boolean {
    fun valid(value: String?) = value?.trim()?.let {
        it.isNotEmpty() && it.length <= 256 && it.none(Char::isISOControl) && !it.equals("unknown", true)
    } == true
    return type.trim().lowercase() in messageDeliveryTypes && valid(messageId) && valid(senderId) && valid(recipientId)
}

/** A persisted target type wins over optional aliases; exactly one repository owns the delivery. */
internal fun pushConversationKind(data: Map<String, String>): String {
    when (data["target_type"]?.trim()?.lowercase()) {
        "community" -> return "community"
        "group" -> return "group"
        "chat", "dm" -> return "chat"
    }
    if (!(data["community_id"] ?: data["communityId"]).isNullOrBlank()) return "community"
    if (!(data["group_id"] ?: data["groupId"]).isNullOrBlank()) return "group"
    if (listOf(data["is_community"], data["isCommunity"]).any { it.equals("true", true) || it == "1" }) return "community"
    if (listOf(data["is_group"], data["isGroup"]).any { it.equals("true", true) || it == "1" }) return "group"
    return "chat"
}

internal fun pushConversationId(data: Map<String, String>): String? {
    fun first(vararg names: String) = names.firstNotNullOfOrNull { key ->
        data[key]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 && it.none(Char::isISOControl) }
    }
    val declared = data["target_type"]?.trim()?.lowercase()
    if (declared in setOf("community", "group", "chat", "dm")) {
        first("target_id", "targetId")?.let { return it }
    }
    return when (pushConversationKind(data)) {
        "community" -> first("community_id", "communityId", "chat_id", "chatId", "target_id", "targetId")
        "group" -> first("group_id", "groupId", "chat_id", "chatId", "target_id", "targetId")
        else -> first("chat_id", "chatId", "target_id", "targetId")
    }
}
