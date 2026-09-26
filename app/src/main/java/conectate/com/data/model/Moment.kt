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
    @SerialName("display_duration_ms") val displayDurationMs: Long? = null,
    @SerialName("duration_hours") val durationHours: Int = 24,
    @SerialName("duration_type") val durationType: String = "24h",
    val visibility: String = "all", // legacy field
    @SerialName("visibility_type") val visibilityType: String = "public",
    @SerialName("excluded_ids") val excludedIds: List<String> = emptyList(),
    @SerialName("audience_ids") val audienceIds: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("caption") val caption: String? = null,
    @SerialName("allow_replies") val allowReplies: Boolean = true,
    @SerialName("overlay_text") val overlayText: String? = null,
    @SerialName("overlay_color") val overlayColor: String = "#FFFFFFFF",
    @SerialName("overlay_font") val overlayFont: String = "sans",
    @SerialName("overlay_scale") val overlayScale: Float = 1f,
    @SerialName("overlay_x") val overlayX: Float = 0.5f,
    @SerialName("overlay_y") val overlayY: Float = 0.38f,
    @SerialName("music_url") val musicUrl: String? = null,
    @SerialName("music_audio_url") val musicAudioUrl: String? = null,
    @SerialName("music_id") val musicId: String? = null,
    @SerialName("music_title") val musicTitle: String? = null,
    @SerialName("music_artist") val musicArtist: String? = null,
    @SerialName("music_source") val musicSource: String? = null,
    @SerialName("music_source_url") val musicSourceUrl: String? = null,
    @SerialName("music_license") val musicLicense: String? = null,
    @SerialName("music_license_url") val musicLicenseUrl: String? = null,
    @SerialName("music_start_ms") val musicStartMs: Long = 0L,
    @SerialName("music_duration_ms") val musicDurationMs: Long = 6_000L,
    @SerialName("music_end_ms") val musicEndMs: Long? = null,
    @SerialName("music_volume") val musicVolume: Float = 1f,
    @SerialName("original_audio_volume") val originalAudioVolume: Float = 1f,
    @SerialName("music_fade_in_ms") val musicFadeInMs: Long = 0L,
    @SerialName("music_fade_out_ms") val musicFadeOutMs: Long = 0L,
    @SerialName("duck_original_audio") val duckOriginalAudio: Boolean = false,
    @SerialName("music_embedded") val musicEmbedded: Boolean = false,
    
    // UI transformation fields
    val username: String? = null,
    val userDisplayName: String? = null,
    val userAvatarUrl: String? = null,
    val isVerified: Boolean = false,
    val viewCount: Int = 0,
    val isViewed: Boolean = false,
    @SerialName("reaction_count") val reactionCount: Int = 0,
    @SerialName("reacted_by_me") val isLikedByMe: Boolean = false
)

val Moment.resolvedMusicAudioUrl: String?
    get() = musicAudioUrl?.takeIf { it.isNotBlank() } ?: musicUrl?.takeIf { it.isNotBlank() }

val Moment.requiresSeparateMusicPlayback: Boolean
    get() = !musicEmbedded && !resolvedMusicAudioUrl.isNullOrBlank()

val Moment.resolvedMusicEndMs: Long
    get() = musicEndMs?.takeIf { it > musicStartMs }
        ?: (musicStartMs + musicDurationMs.coerceAtLeast(1_000L))

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
