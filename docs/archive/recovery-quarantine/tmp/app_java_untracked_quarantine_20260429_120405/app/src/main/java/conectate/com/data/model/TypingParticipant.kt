package conectate.com.data.model

enum class TypingConversationScope(
    val channelPrefix: String,
    val targetType: String
) {
    DIRECT_MESSAGE(channelPrefix = "typing-chat", targetType = "chat"),
    GROUP(channelPrefix = "typing-group", targetType = "group"),
    COMMUNITY(channelPrefix = "typing-community", targetType = "community")
}

data class TypingParticipant(
    val userId: String,
    val displayName: String,
    val updatedAtMs: Long
)
