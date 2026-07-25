package conectate.com.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.util.UUID

@Serializable
data class Comment(
    @SerialName("id") val id: String = UUID.randomUUID().toString(),
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("content") val content: String,
    @SerialName("created_at") val createdAt: String,
    
    // User details (fetched via join)
    @SerialName("user_name") val userName: String? = null,
    @SerialName("user_avatar") val userAvatar: String? = null,
    @SerialName("is_verified") val isVerified: Boolean = false,
    
    // For UI
    @SerialName("is_mine") val isMine: Boolean = false,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("media_url") val audioUrl: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("reaction_counts") val reactionCounts: Map<String, Int> = emptyMap(),
    @SerialName("my_reaction") val myReaction: String? = null
)
