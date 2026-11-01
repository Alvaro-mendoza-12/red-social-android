package conectate.com.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.time.LocalDateTime
import java.util.UUID

@Serializable
data class VideoChapter(
    @SerialName("start_ms") val startMs: Long,
    val title: String,
)

@Serializable
data class Video(
    @SerialName("id") val id: String = UUID.randomUUID().toString(),
    @SerialName("user_id") val userId: String = "",
    @SerialName("user_name") val userName: String = "",
    @SerialName("user_profile_image") val userProfileImage: String = "",
    @SerialName("avatar_media_type") val avatarMediaType: String? = null,
    @SerialName("avatar_video_muted") val avatarVideoMuted: Boolean = true,
    @SerialName("title") val title: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("video_url") val videoUrl: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String = "",
    @SerialName("duration") val duration: Long = 0, 
    @SerialName("views") val views: Int = 0,
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("comment_count") val commentCount: Int = 0,
    @SerialName("share_count") val shareCount: Int = 0,
    @SerialName("country") val country: String = "",
    @SerialName("city") val city: String = "",
    @SerialName("created_at") val created_at: String = "",
    @SerialName("updated_at") val updated_at: String = "",
    @SerialName("is_public") val isPublic: Boolean = true,
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("liked_by_me") val likedByMe: Boolean = false,
    @SerialName("shared_by_me") val sharedByMe: Boolean = false,
    @SerialName("favorite_count") val favoriteCount: Int = 0,
    @SerialName("favorited_by_me") val favoritedByMe: Boolean = false,
    @SerialName("is_verified") val isVerified: Boolean = false,
    @SerialName("is_admin") val isAdmin: Boolean = false,
    @SerialName("sound_id") val soundId: String? = null,
    @SerialName("sound_source_video_id") val soundSourceVideoId: String? = null,
    @SerialName("sound_title") val soundTitle: String? = null,
    @SerialName("sound_artist") val soundArtist: String? = null,
    @SerialName("sound_url") val soundUrl: String? = null,
    @SerialName("chapters") val chapters: List<VideoChapter> = emptyList(),
    @SerialName("allow_clip_sharing") val allowClipSharing: Boolean = true,
    @SerialName("owned_by_me") val ownedByMe: Boolean = false,
    @SerialName("is_sensitive") var isSensitive: Boolean = false,
)
