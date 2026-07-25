package conectate.com.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.util.UUID

@Serializable
data class CommentAuthor(
    val username: String? = null,
    @SerialName("full_name")
    val fullName: String? = null,
    @SerialName("avatar_url")
    val avatarUrl: String? = null
)

@Serializable
data class VideoComment(
    val id: String = UUID.randomUUID().toString(),
    @SerialName("video_id")
    val videoId: String = "",
    @SerialName("user_id")
    val userId: String = "",
    @SerialName("user_name")
    val userName: String? = "",
    @SerialName("user_profile_image")
    val userProfileImage: String? = "",
    val content: String = "",
    val likes: Int = 0,
    val created_at: String = "",
    @SerialName("parent_comment_id")
    val parentId: String? = null,
    @SerialName("content_type")
    val contentType: String = "text",
    @SerialName("media_url")
    val mediaUrl: String? = null,
    val reactions: Map<String, Int> = emptyMap(),
    @SerialName("my_reaction")
    val myReaction: String? = null,
    @SerialName("updated_at")
    val updatedAt: String = "",
    // Add the join field
    val profiles: CommentAuthor? = null
)
