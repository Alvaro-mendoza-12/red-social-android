package conectate.com.data.repository.moment

import conectate.com.data.model.MomentHighlightItemPreview
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

// Requires a readable original Moment; never resurrect an orphaned snapshot.
internal fun JsonObject.toHighlightItemPreview(moment: JsonObject): MomentHighlightItemPreview? {
    val mediaUrl = moment.string("media_url").orEmpty()
    if (mediaUrl.isBlank()) return null
    return MomentHighlightItemPreview(
        id = string("id").orEmpty(),
        highlightId = string("highlight_id").orEmpty(),
        momentId = string("moment_id").orEmpty(),
        mediaUrl = mediaUrl,
        mediaType = moment.string("media_type") ?: "image",
        thumbnailUrl = moment.string("thumbnail_url"),
        caption = moment.string("caption"),
        overlayText = moment.string("overlay_text"),
        overlayColor = moment.string("overlay_color") ?: "#FFFFFFFF",
        overlayFont = moment.string("overlay_font") ?: "sans",
        overlayScale = moment.float("overlay_scale", 1f),
        overlayX = moment.float("overlay_x", 0.5f),
        overlayY = moment.float("overlay_y", 0.38f),
        musicUrl = moment.string("music_url"),
        musicAudioUrl = moment.string("music_audio_url"),
        musicId = moment.string("music_id"),
        musicTitle = moment.string("music_title"),
        musicArtist = moment.string("music_artist"),
        musicPresentation = conectate.com.data.model.MomentMusicPresentation.fromWire(moment.string("music_presentation")).wireValue,
        musicArtworkUrl = moment.string("music_artwork_url"),
        musicOverlayX = moment.float("music_overlay_x", 0.5f),
        musicOverlayY = moment.float("music_overlay_y", 0.5f),
        musicOverlayScale = moment.float("music_overlay_scale", 1f),
        musicOverlayRotation = moment.float("music_overlay_rotation", 0f),
        musicLyrics = moment.string("music_lyrics"),
        musicSource = moment.string("music_source"),
        musicSourceUrl = moment.string("music_source_url"),
        musicLicense = moment.string("music_license"),
        musicLicenseUrl = moment.string("music_license_url"),
        musicStartMs = moment.long("music_start_ms", 0L),
        musicDurationMs = moment.long("music_duration_ms", 6_000L),
        musicEndMs = moment.longOrNull("music_end_ms"),
        musicVolume = moment.float("music_volume", 1f),
        originalAudioVolume = moment.float("original_audio_volume", 1f),
        musicFadeInMs = moment.long("music_fade_in_ms", 0L),
        musicFadeOutMs = moment.long("music_fade_out_ms", 0L),
        duckOriginalAudio = moment.bool("duck_original_audio", false),
        musicEmbedded = moment.bool("music_embedded", false),
        displayDurationMs = moment.longOrNull("display_duration_ms"),
        expiresAt = moment.string("expires_at"),
        sortOrder = int("sort_order"),
        createdAt = moment.string("created_at"),
        userId = moment.string("user_id").orEmpty()
    )
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

private fun JsonObject.int(key: String): Int =
    (this[key] as? JsonPrimitive)?.intOrNull ?: 0

private fun JsonObject.float(key: String, default: Float): Float =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()?.takeIf { it.isFinite() } ?: default

private fun JsonObject.long(key: String, default: Long): Long = longOrNull(key) ?: default

private fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

private fun JsonObject.bool(key: String, default: Boolean): Boolean =
    (this[key] as? JsonPrimitive)?.booleanOrNull ?: default
