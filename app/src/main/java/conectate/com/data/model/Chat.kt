package conectate.com.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.util.UUID

@Serializable
data class Chat(
    val id: String = UUID.randomUUID().toString(),
    val participants: List<String> = emptyList(),
    val created_at: String = "",
    val updated_at: String = "",
    val last_message: String? = null,
    val last_message_at: String? = null,
    val last_message_sender_id: String? = null,
    val last_message_id: String? = null,
    val is_group: Boolean = false,
    val group_name: String? = null,
    val group_image: String? = null,
    val nickname: String? = null, // Private nickname for the other user
    val is_muted: Boolean = false,
    val is_pinned: Boolean = false,
    val is_archived: Boolean = false,
    val is_favorite: Boolean = false,
    val cleared_at: String? = null
) {
    val safeLastMessageAt: String
        get() = (last_message_at ?: created_at).let { if (it.isEmpty()) "1970-01-01T00:00:00Z" else it }.replace(" ", "T")
}

@Serializable
data class ChatParticipant(
    val chat_id: String,
    val user_id: String,
    val joined_at: String = "",
    val last_read_message_id: String? = null,
    val last_read_at: String? = null, // Added for unread count
    val is_muted: Boolean = false,
    val is_pinned: Boolean = false,
    val is_archived: Boolean = false,
    val is_favorite: Boolean = false,
    val cleared_at: String? = null // Timestamp when the user cleared the chat
)

@Serializable
data class ChatWithParticipants(
    val chat: Chat,
    val participants: List<User>
)

@Serializable
data class CreateGroupRequest(
    val is_group: Boolean = true,
    val group_name: String,
    val participants: List<String>,
    val created_at: String
)
