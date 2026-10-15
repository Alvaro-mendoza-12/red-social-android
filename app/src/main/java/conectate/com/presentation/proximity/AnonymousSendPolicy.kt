package conectate.com.presentation.proximity

/** Contract agreed with server: audio3, sticker6, photo9 accepted messages. */
internal fun anonymousMediaUnlocked(messageCount: Int, contentType: String): Boolean {
    val required = when (contentType) {
        "audio" -> 3
        "sticker" -> 6
        "image" -> 9
        else -> return false
    }
    return messageCount >= required
}

internal data class AnonymousSendIdentity(
    val accountId: String,
    val sessionId: String,
    val selectionGeneration: Long,
    val clientMessageId: String,
) {
    fun isCurrent(accountId: String?, sessionId: String?, status: String?, generation: Long): Boolean =
        this.accountId == accountId && this.sessionId == sessionId &&
            status == "active" && selectionGeneration == generation
}

internal fun shouldReceiveBottle(tabSelected: Boolean, resumed: Boolean, accountValid: Boolean,
    hasActiveConversation: Boolean): Boolean =
    tabSelected && resumed && accountValid && !hasActiveConversation
