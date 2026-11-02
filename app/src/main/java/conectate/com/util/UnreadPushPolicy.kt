package conectate.com.util

/** Calls, invitations and room reactions may carry a chat_id without being a new message. */
internal fun shouldRecordUnreadPush(type: String, messageId: String?, senderId: String?, recipientId: String?): Boolean =
    isMessageDeliveryPush(type, messageId, senderId, recipientId) &&
        !senderId!!.trim().equals(recipientId!!.trim(), ignoreCase = true)
