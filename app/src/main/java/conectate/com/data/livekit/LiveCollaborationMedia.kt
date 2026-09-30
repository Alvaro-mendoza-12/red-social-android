package conectate.com.data.livekit

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Separate SDK source locks allow audio/video to start together. Both finish
 * or cancel before the caller may release the room and its native objects. */
internal suspend fun applyCollaborationMedia(
    audio: suspend () -> Unit,
    video: suspend () -> Unit
) = coroutineScope {
    awaitAll(async { audio() }, async { video() })
    Unit
}
