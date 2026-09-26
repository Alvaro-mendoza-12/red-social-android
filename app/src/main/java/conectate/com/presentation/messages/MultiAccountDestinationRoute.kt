package conectate.com.presentation.messages

import conectate.com.data.model.MultiAccountPendingDestination
import conectate.com.presentation.navigation.Screen

/** Builds the exact destination restored after switching to another saved account. */
internal fun buildMultiAccountDestinationRoute(destination: MultiAccountPendingDestination): String {
    val conversationId = destination.normalizedConversationId
    val focusMessageId = destination.focusMessageId?.trim()?.takeIf { it.isNotBlank() }
    return when (destination.normalizedTargetType) {
        "community" -> Screen.CommunityChat.createRoute(conversationId, focusMessageId)
        "group" -> Screen.GroupChat.createRoute(conversationId, focusMessageId)
        else -> Screen.Chat.createRoute(
            userId = destination.normalizedUserId ?: conversationId,
            focusMessageId = focusMessageId,
        )
    }
}
