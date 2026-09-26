package conectate.com.data.model

import kotlinx.serialization.Serializable

@Serializable
data class UpdateProfileRequest(
    val display_name: String? = null,
    val username: String? = null,
    val bio: String? = null,
    val country: String? = null,
    val city: String? = null,
    val gender: String? = null,
    val birth_date: String? = null,
    val status: String? = null,
    val interests: List<String>? = null,
    val hobbies: List<String>? = null,
    val linked_games: List<String>? = null,
    val game_logo_urls: Map<String, String>? = null,
    val favorite_music_url: String? = null,
    val favorite_book: String? = null,
    val favorite_sport: String? = null,
    val career: String? = null,
    val availability_schedule: String? = null,
    val profile_section_order: List<String>? = null,
    val profile_section_visibility: Map<String, String>? = null,
    val social_links: Map<String, String>? = null,
    val avatar_url: String? = null,
    val avatar_media_type: String? = null,
    val avatar_video_muted: Boolean? = null,
    val avatar_duration_ms: Long? = null,
    val cover_url: String? = null,
    val remove_avatar: Boolean = false,
    val remove_cover: Boolean = false,
    val profile_theme: String? = null,
    val profile_effect: String? = null,
    val profile_welcome: String? = null,
    val last_name_change: String? = null,
    val is_private: Boolean? = null,
    val favorites_public: Boolean? = null,
    val break_mode: String? = null,
    val profile_visibility: String? = null,
    val profile_view_history_enabled: Boolean? = null,
    val last_seen_visibility: String? = null,
    val online_status_visibility: String? = null,
    val balance: Double? = null
)

