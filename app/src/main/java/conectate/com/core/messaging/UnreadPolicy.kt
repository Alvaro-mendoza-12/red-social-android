package conectate.com.core.messaging

object UnreadPolicy {

    fun shouldIncrementUnread(
        senderId: String,
        currentUserId: String,
        isConversationVisible: Boolean
    ): Boolean {
        val cleanSender = senderId.trim().lowercase()
        val cleanCurrentUser = currentUserId.trim().lowercase()
        return cleanSender.isNotBlank() &&
            cleanCurrentUser.isNotBlank() &&
            cleanSender != cleanCurrentUser &&
            !isConversationVisible
    }

    fun isEligibleStatusForReadTransition(status: String): Boolean {
        return MessageStatusNormalizer.isEligibleForReadTransition(status)
    }
}
