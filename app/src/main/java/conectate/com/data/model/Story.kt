package conectate.com.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Story(
    val id: String = UUID.randomUUID().toString(),
    @SerialName("user_id") val userId: String = "",
    @SerialName("user_name") val userName: String = "",
    @SerialName("user_profile_image") val userProfileImage: String? = null,
    @SerialName("media_url") val mediaUrl: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("is_video") val isVideo: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("view_count") val viewCount: Int = 0,
    @SerialName("seen_by_me") val seenByMe: Boolean = false
)
