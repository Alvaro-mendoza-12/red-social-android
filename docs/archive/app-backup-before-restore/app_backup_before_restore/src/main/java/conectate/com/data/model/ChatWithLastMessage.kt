package conectate.com.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatWithLastMessage(
    val chat: Chat,
    val lastMessage: Message? = null,
    val unreadCount: Int = 0,
    val otherUserName: String? = null,
    val otherUserImage: String? = null,
    val otherUserId: String? = null,
    val otherUserVerified: Boolean = false,
    val otherUserIsAdmin: Boolean = false,
    val nickname: String? = null,
    val clearedAt: String? = null
)