package conectate.com.util

/** Calls, invitations and room reactions may carry a chat_id without being a new message. */
internal fun shouldRecordUnreadPush(type: String, messageId: String?, senderId: String?, recipientId: String?): Boolean =
    !messageId.isNullOrBlank() && !senderId.isNullOrBlank() && !recipientId.isNullOrBlank() &&
        !senderId.trim().equals(recipientId.trim(), ignoreCase = true) &&
        type.trim().lowercase() in setOf("message", "new_message", "chat_message", "group_message", "community_message",
            "text", "image", "video", "audio", "sticker", "file", "document", "location", "contact")
