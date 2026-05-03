package conectate.com.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class Moment(
    val id: String = "",
    @SerialName("user_id") val userId: String,
    @SerialName("media_url") val mediaUrl: String,
    @SerialName("media_type") val mediaType: String, // 'image', 'video'
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("duration_hours") val durationHours: Int = 24,
    @SerialName("duration_type") val durationType: String = "24h",
    val visibility: String = "all", // legacy field
    @SerialName("visibility_type") val visibilityType: String = "public",
    @SerialName("excluded_ids") val excludedIds: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("caption") val caption: String? = null,
    @SerialName("allow_replies") val allowReplies: Boolean = true,
    
    // UI transformation fields
    val username: String? = null,
    val userDisplayName: String? = null,
    val userAvatarUrl: String? = null,
    val isVerified: Boolean = false,
    val viewCount: Int = 0,
    val isViewed: Boolean = false
)

@Serializable
data class MomentView(
    val id: String = "",
    @SerialName("moment_id") val momentId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("viewed_at") val viewedAt: String? = null,
    
    // UI transformation fields
    val username: String? = null,
    val userDisplayName: String? = null,
    val userAvatarUrl: String? = null,
    val isVerified: Boolean = false
)
