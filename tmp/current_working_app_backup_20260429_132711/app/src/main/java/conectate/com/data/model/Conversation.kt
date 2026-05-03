package conectate.com.data.model

data class Conversation(
    val id: String = "",
    val participants: List<String> = emptyList(),
    val lastMessage: String = "",
    val lastMessageTimestamp: String? = null,
    val lastMessageSenderId: String = "",
    val unreadCount: Map<String, Int> = emptyMap(),
    val createdAt: String? = null
)
