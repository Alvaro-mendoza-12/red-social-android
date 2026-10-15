package conectate.com.presentation.social

import android.net.Uri
import conectate.com.data.music.ApprovedMomentMusic
import conectate.com.presentation.components.PreparedAudioClip

internal const val NOVVIN_LOCAL_MOMENT_MUSIC_SOURCE = "novvin_moment_local"
private const val MAX_LOCAL_MOMENT_MUSIC_MS = 60_000L

internal fun localMomentMusicId(uri: Uri): String =
    "device:${uri.toString().hashCode().toUInt().toString(16)}"

/**
 * Mantiene la duración de la canción original para buscar cualquier fragmento.
 * Solo la selección, no la pista fuente, se limita a la duración del Momento.
 */
internal fun localMomentMusicDurations(sourceDurationMs: Long, initialExcerptDurationMs: Long): Pair<Long, Long> {
    val source = sourceDurationMs.coerceAtLeast(1_000L)
    val excerpt = initialExcerptDurationMs.coerceIn(1_000L, minOf(source, MAX_LOCAL_MOMENT_MUSIC_MS))
    return source to excerpt
}

internal fun PreparedAudioClip.toMomentMusicSelection(initialExcerptDurationMs: Long = 6_000L): MomentMusicSelection {
    val (safeDuration, excerptDuration) = localMomentMusicDurations(durationMs, initialExcerptDurationMs)
    val safeTitle = title.trim()
        .ifBlank { displayName.substringBeforeLast('.', displayName) }
        .ifBlank { "Audio del dispositivo" }
        .take(140)
    val safeArtist = artist.trim()
        .takeUnless { it.equals("<unknown>", ignoreCase = true) }
        .orEmpty()
        .ifBlank { "Audio del dispositivo" }
        .take(120)
    return MomentMusicSelection(
        track = ApprovedMomentMusic(
            id = localMomentMusicId(uri),
            title = safeTitle,
            artist = safeArtist,
            durationMs = safeDuration,
            audioUrl = uri.toString(),
            sourcePageUrl = "",
            source = NOVVIN_LOCAL_MOMENT_MUSIC_SOURCE,
            license = "Uso aportado por el usuario",
            provider = "Mi dispositivo",
            allowUseInMoments = true,
            allowRemix = false,
        ),
        startMs = 0L,
        endMs = excerptDuration,
        musicVolume = 1f,
        originalAudioVolume = 1f,
        localUri = uri,
    )
}
