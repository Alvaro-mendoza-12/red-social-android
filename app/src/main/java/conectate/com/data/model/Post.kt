package conectate.com.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.util.UUID

@Serializable
data class Post(
    @SerialName("id") val id: String = UUID.randomUUID().toString(),
    @SerialName("user_id") val userId: String,
    @SerialName("user_name") val userName: String,
    @SerialName("user_profile_image") val userProfileImage: String? = null,
    @SerialName("content") val content: String = "",
    @SerialName("media_urls") val mediaUrls: List<String> = emptyList(), // Changed from single mediaUrl
    @SerialName("media_url") val mediaUrlLegacy: String? = null, // Backend might still send this, or we migrate appropriately
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("share_count") val shareCount: Int = 0,
    @SerialName("liked_by_me") val likedByMe: Boolean = false,
    @SerialName("shared_by_me") val sharedByMe: Boolean = false,
    @SerialName("my_reaction") val myReaction: String? = null,
    @SerialName("reaction_counts") val reactionCounts: Map<String, Int> = emptyMap(),
    @SerialName("is_verified") val isVerified: Boolean = false,
    @SerialName("visibility") val visibility: String = "public"
)
