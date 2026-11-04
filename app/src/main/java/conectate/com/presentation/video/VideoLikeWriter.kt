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
    private data class Edit(var confirmed: Boolean, var desired: Boolean, var stamp: Long)
    private val jobs = mutableMapOf<String, Job>()
    private val edits = linkedMapOf<String, Edit>()
    private var epoch = 0L
    private var revision = 0L

    fun clear() {
        epoch++; revision++
        jobs.values.toList().forEach { it.cancel() }
        jobs.clear()
        edits.clear()
    }

    fun readVersion(): Long = revision

    fun reconcile(video: Video, readVersion: Long = -1L): Video {
        val edit = edits[video.id] ?: return video
        if (video.id !in jobs && readVersion >= edit.stamp) {
            edit.confirmed = video.likedByMe
            edit.desired = video.likedByMe
            edit.stamp = readVersion
            return video
        }
        return video.withVideoLike(edit.desired)
    }

    fun toggle(id: String) {
        val owner = account() ?: return
        val video = current(id) ?: return
        if (id.isBlank()) return
        val edit = edits[id]?.takeIf { id in jobs }
            ?: Edit(video.likedByMe, video.likedByMe, ++revision).also { edits[id] = it }
        edit.desired = !edit.desired
        edit.stamp = ++revision
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
                    edit.stamp = ++revision
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent()) {
                    // Change only this video's reaction. Keep new comments, views and appended pages.
                    publish(id, edit.confirmed)
                    edit.desired = edit.confirmed
                    failed()
                }
            } finally {
                if (version == epoch) {
                    jobs.remove(id)
                    edit.stamp = ++revision
                    if (!isCurrent()) edits.remove(id)
                    while (edits.size > 256) {
                        val oldest = edits.keys.firstOrNull { it !in jobs } ?: break
                        edits.remove(oldest)
                    }
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
