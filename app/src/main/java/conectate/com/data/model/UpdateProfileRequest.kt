package conectate.com.data.model

import kotlinx.serialization.Serializable

@Serializable
data class UpdateProfileRequest(
    val display_name: String? = null,
    val bio: String? = null,
    val country: String? = null,
    val city: String? = null,
    val status: String? = null,
    val interests: List<String>? = null,
    val linked_games: List<String>? = null,
    val game_logo_urls: Map<String, String>? = null,
    val favorite_music_url: String? = null,
    val favorite_book: String? = null,
    val favorite_sport: String? = null,
    val career: String? = null,
    val availability_schedule: String? = null,
    val avatar_url: String? = null,
    val avatar_media_type: String? = null,
    val avatar_video_muted: Boolean? = null,
    val avatar_duration_ms: Long? = null,
    val cover_url: String? = null,
    val last_name_change: String? = null,
    val is_private: Boolean? = null,
    val profile_visibility: String? = null,
    val last_seen_visibility: String? = null,
    val online_status_visibility: String? = null,
    val balance: Double? = null
)

