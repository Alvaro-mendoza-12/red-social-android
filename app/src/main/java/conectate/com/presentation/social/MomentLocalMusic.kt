package conectate.com.presentation.social

import android.net.Uri
import conectate.com.data.music.ApprovedMomentMusic
import conectate.com.presentation.components.PreparedAudioClip

internal const val NOVVIN_LOCAL_MOMENT_MUSIC_SOURCE = "novvin_moment_local"
private const val MAX_LOCAL_MOMENT_MUSIC_MS = 60_000L

internal fun localMomentMusicId(uri: Uri): String =
    "device:${uri.toString().hashCode().toUInt().toString(16)}"

/**
 * Convierte el recorte ya preparado por el selector del dispositivo en una
 * selección lista para el editor. Publicarlo en el catálogo comunitario es un
 * flujo distinto y explícito.
 */
internal fun PreparedAudioClip.toMomentMusicSelection(): MomentMusicSelection {
    val safeDuration = durationMs.coerceIn(1_000L, MAX_LOCAL_MOMENT_MUSIC_MS)
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
        endMs = safeDuration,
        musicVolume = 1f,
        originalAudioVolume = 1f,
        localUri = uri,
    )
}
