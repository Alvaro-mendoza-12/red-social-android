package conectate.com.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject
import java.util.UUID

@Serializable
data class Notification(
    @SerialName("id") val id: String = UUID.randomUUID().toString(),
    @SerialName("recipient_id") val recipientId: String,
    @SerialName("actor_id") val actorId: String,
    @SerialName("type") val type: String, // 'follow', 'like_post', 'comment_post', 'share_post', 'like_video', etc.
    @SerialName("target_id") val targetId: String? = null, // post_id, video_id, etc.
    @SerialName("target_type") val targetType: String? = null, // 'post', 'video', 'follow'
    @SerialName("content") val content: String? = null, // For comment notifications, stores the comment text
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    
    // Virtual fields for UI convenience (not in DB table, populated via join)
    @SerialName("actor_name") val actorName: String? = null,
    @SerialName("actor_avatar_url") val actorAvatarUrl: String? = null,
    @SerialName("extra_data") val extraData: JsonObject? = null
)
