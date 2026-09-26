package conectate.com.presentation.video

internal data class VideoPlaylistEntry(
    val mediaId: String,
    val stableKey: String,
    // The cache identity may remain stable while a signed URL or runtime
    // backend host changes. Playback still has to receive the new URI.
    val playbackUri: String = stableKey,
)

internal sealed interface VideoPlaylistUpdate {
    data object None : VideoPlaylistUpdate
    data class Append(val fromIndex: Int) : VideoPlaylistUpdate
    data class Replace(
        val targetIndex: Int,
        val startPositionMs: Long,
    ) : VideoPlaylistUpdate
}

/**
 * Produces a playlist update that never restarts the visible video merely
 * because pagination appended items or mutable counters refreshed.
 */
internal fun reconcileVideoPlaylist(
    existing: List<VideoPlaylistEntry>,
    desired: List<VideoPlaylistEntry>,
    settledVideoId: String?,
    currentMediaId: String?,
    currentPositionMs: Long,
): VideoPlaylistUpdate {
    if (existing == desired) return VideoPlaylistUpdate.None
    if (
        existing.isNotEmpty() &&
        desired.size > existing.size &&
        desired.subList(0, existing.size) == existing
    ) {
        return VideoPlaylistUpdate.Append(existing.size)
    }

    val targetId = settledVideoId
        ?.takeIf { id -> desired.any { it.mediaId == id } }
        ?: currentMediaId?.takeIf { id -> desired.any { it.mediaId == id } }
    val targetIndex = desired.indexOfFirst { it.mediaId == targetId }.takeIf { it >= 0 } ?: 0
    val preservePosition = desired.getOrNull(targetIndex)?.mediaId == currentMediaId
    return VideoPlaylistUpdate.Replace(
        targetIndex = targetIndex,
        startPositionMs = if (preservePosition) currentPositionMs.coerceAtLeast(0L) else 0L,
    )
}
