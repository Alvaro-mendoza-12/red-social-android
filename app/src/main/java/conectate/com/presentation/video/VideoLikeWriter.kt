package conectate.com.presentation.video

import conectate.com.data.model.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One writer per video: a second tap changes the target without cancelling an accepted request. */
internal class VideoLikeWriter(
    private val scope: CoroutineScope,
    private val account: () -> String?,
    private val current: (String) -> Video?,
    private val write: suspend (String, Boolean) -> Result<Unit>,
    private val publish: (String, Boolean) -> Unit,
    private val failed: () -> Unit,
) {
    private data class Edit(var confirmed: Boolean, var desired: Boolean)
    private val jobs = mutableMapOf<String, Job>()
    private val edits = mutableMapOf<String, Edit>()
    private var epoch = 0L

    fun clear() {
        epoch++
        jobs.values.toList().forEach { it.cancel() }
        jobs.clear()
        edits.clear()
    }

    fun reconcile(video: Video): Video = edits[video.id]?.let { video.withVideoLike(it.desired) } ?: video

    fun toggle(id: String) {
        val owner = account() ?: return
        val video = current(id) ?: return
        if (id.isBlank()) return
        val edit = edits.getOrPut(id) { Edit(video.likedByMe, video.likedByMe) }
        edit.desired = !edit.desired
        publish(id, edit.desired)
        if (id in jobs) return
        val version = epoch
        val job = scope.launch(start = CoroutineStart.LAZY) {
            fun isCurrent() = version == epoch && owner == account()
            try {
                while (isCurrent() && edit.confirmed != edit.desired) {
                    val target = edit.desired
                    write(id, target).getOrThrow()
                    if (!isCurrent()) return@launch
                    edit.confirmed = target
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent()) {
                    // Change only this video's reaction. Keep new comments, views and appended pages.
                    publish(id, edit.confirmed)
                    failed()
                }
            } finally {
                if (version == epoch) {
                    jobs.remove(id)
                    edits.remove(id)
                }
            }
        }
        jobs[id] = job
        job.start()
    }
}

internal fun Video.withVideoLike(liked: Boolean): Video = if (likedByMe == liked) this else
    copy(likedByMe = liked, likeCount = (likeCount + if (liked) 1 else -1).coerceAtLeast(0))
        .also { it.isSensitive = isSensitive }
