package conectate.com.data.local.dao

data class VisibleReadCandidate(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val status: String,
    val deliveredAt: String?,
    val readAt: String?,
)
