package conectate.com.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Group(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val image: String? = null,
    val description: String? = null,
    val created_at: String,
    val creator_id: String,
    val last_message: String? = null,
    val last_message_at: String? = null,
    val metadata: Map<String, String>? = null,
    val is_deleted: Boolean = false,
    val is_member: Boolean = true,
    val only_admins_can_talk: Boolean = false,
    val rest_until: String? = null
)

@Serializable
data class GroupMember(
    val group_id: String,
    val user_id: String,
    val role: String = "member", // "admin", "member"
    val joined_at: String,
    val userName: String? = null,
    val userImage: String? = null
)

@Serializable
data class GroupWithLastMessage(
    val group: Group,
    val lastMessage: GroupMessage? = null,
    val unreadCount: Int = 0
)
