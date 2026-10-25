package conectate.com.presentation.video

import conectate.com.core.network.InteractiveReadDeadlines
import conectate.com.core.network.runReadWithDeadline
import conectate.com.data.model.Video
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A link must resolve its own video or a terminal error, never a different feed item. */
internal suspend fun readFocusedVideo(
    videoId: String,
    timeoutMs: Long = InteractiveReadDeadlines.FirstPageMillis,
    read: suspend (String) -> Result<Video>,
): Result<Video> = runReadWithDeadline(timeoutMs) {
    val target = videoId.trim().lowercase(java.util.Locale.ROOT)
    require(target.isNotBlank()) { "El enlace del video no es válido." }
    val video = read(target).getOrThrow()
    // HTTP Result wrappers may catch a deadline/navigation cancellation.
    currentCoroutineContext().ensureActive()
    check(video.id.equals(target, ignoreCase = true)) { "El servidor no devolvió el video solicitado." }
    check(video.videoUrl.isNotBlank()) { "El video todavía no tiene un archivo reproducible." }
    video
}
