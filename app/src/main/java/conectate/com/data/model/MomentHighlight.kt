package conectate.com.data.model

import java.time.Instant
import java.time.OffsetDateTime

data class MomentHighlight(
    val id: String,
    val userId: String,
    val title: String,
    val coverMediaUrl: String? = null,
    val sortOrder: Int = 0,
    val isPublic: Boolean = true,
    val itemCount: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

data class MomentHighlightItemPreview(
    val id: String,
    val highlightId: String,
    val momentId: String,
    val mediaUrl: String,
    val mediaType: String,
    val thumbnailUrl: String? = null,
    val caption: String? = null,
    val overlayText: String? = null,
    val overlayColor: String = "#FFFFFFFF",
    val overlayFont: String = "sans",
    val overlayScale: Float = 1f,
    val overlayX: Float = 0.5f,
    val overlayY: Float = 0.38f,
    val musicUrl: String? = null,
    val musicAudioUrl: String? = null,
    val musicId: String? = null,
    val musicTitle: String? = null,
    val musicArtist: String? = null,
    val musicSource: String? = null,
    val musicSourceUrl: String? = null,
    val musicLicense: String? = null,
    val musicLicenseUrl: String? = null,
    val musicStartMs: Long = 0L,
    val musicDurationMs: Long = 6_000L,
    val musicEndMs: Long? = null,
    val musicVolume: Float = 1f,
    val originalAudioVolume: Float = 1f,
    val musicFadeInMs: Long = 0L,
    val musicFadeOutMs: Long = 0L,
    val duckOriginalAudio: Boolean = false,
    val musicEmbedded: Boolean = false,
    val displayDurationMs: Long? = null,
    val expiresAt: String? = null,
    val sortOrder: Int = 0,
    val createdAt: String? = null,
    val userId: String = "",
    val username: String? = null,
    val userDisplayName: String? = null,
    val userAvatarUrl: String? = null,
)

fun MomentHighlightItemPreview.isActiveAt(now: Instant = Instant.now()): Boolean {
    val expiration = expiresAt?.trim()?.takeIf { it.isNotEmpty() } ?: return true
    val instant = runCatching { Instant.parse(expiration) }
        .recoverCatching { OffsetDateTime.parse(expiration).toInstant() }
        .getOrNull()
        ?: return false
    return instant.isAfter(now)
}
