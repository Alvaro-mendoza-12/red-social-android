package conectate.com.presentation.posts

import conectate.com.data.model.Post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Main-thread state: immediate feedback, serialized writes, and account-fenced reconciliation. */
internal class PostReactionWriter(
    private val scope: CoroutineScope,
    private val account: () -> String?,
    private val current: (String) -> Post?,
    private val write: suspend (String, String?, Boolean) -> Result<Unit>,
    private val refresh: suspend (String) -> Result<Post>,
    private val publish: (Post) -> Unit,
    private val failed: () -> Unit,
) {
    private data class Edit(var confirmed: Post, var desired: String?, var stamp: Long)
    private val jobs = mutableMapOf<String, Job>()
    private val edits = linkedMapOf<String, Edit>()
    private var revision = 0L
    private var epoch = 0L
    fun clear() {
        epoch++; revision++
        jobs.values.toList().forEach { it.cancel() }
        jobs.clear(); edits.clear()
    }

    /** Capture before starting a remote read, never after receiving its response. */
    fun readVersion(): Long = revision

    fun reconcile(fresh: Post, readVersion: Long): Post {
        val edit = edits[fresh.id] ?: return fresh
        if (fresh.id !in jobs && readVersion >= edit.stamp) {
            // Keep the latest accepted baseline for an older read still in flight.
            edit.confirmed = fresh
            edit.desired = fresh.reaction()
            edit.stamp = readVersion
            return fresh
        }
        return fresh.withReaction(edit.desired)
    }

    fun toggleLike(id: String) {
        val post = current(id) ?: return
        set(id, if (post.reaction() == null) "LIKE" else null)
    }

    /** An ACK from another screen also fences this screen's older remote reads. */
    fun acceptConfirmed(id: String, reaction: String?) {
        if (id in jobs) return
        val post = current(id) ?: return
        val confirmed = post.withReaction(reaction)
        edits[id] = Edit(confirmed, reaction, ++revision)
        publish(confirmed)
        prune()
    }

    fun set(id: String, requested: String?) {
        val owner = account() ?: return
        val before = current(id) ?: return
        if (id.isBlank()) return
        val normalized = requested?.trim()?.uppercase()?.takeIf { it in TYPES }
        if (requested != null && normalized == null) return
        val old = before.myReaction ?: if (before.likedByMe) "LIKE" else null
        val selected = normalized?.takeUnless { it == old }
        val edit = edits[id]?.takeIf { id in jobs }
            ?: Edit(before, selected, ++revision).also { edits[id] = it }
        edit.desired = selected
        edit.stamp = ++revision
        publish(before.withReaction(selected))
        if (id in jobs) return // The running writer sends the latest choice next.
        val version = epoch
        val job = scope.launch(start = CoroutineStart.LAZY) {
            fun isCurrent() = version == epoch && owner == account()
            try {
                while (isCurrent()) {
                    while (edit.confirmed.reaction() != edit.desired) {
                        val target = edit.desired
                        write(id, target, edit.confirmed.reaction() != null).getOrThrow()
                        if (!isCurrent()) return@launch
                        edit.confirmed = edit.confirmed.withReaction(target)
                        edit.stamp = ++revision
                    }
                    // An optional read cannot turn a saved reaction into a failed write.
                    val fresh = try { refresh(id).getOrNull() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    if (!isCurrent()) return@launch
                    if (fresh?.id == id) {
                        edit.confirmed = fresh.withReaction(edit.confirmed.reaction())
                        publish(fresh.withReaction(edit.desired))
                    }
                    if (edit.confirmed.reaction() == edit.desired) break
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (isCurrent()) {
                    edit.desired = edit.confirmed.reaction()
                    current(id)?.let { publish(it.copyReactionFrom(edit.confirmed)) }
                    failed()
                }
            } finally {
                if (version == epoch) {
                    jobs.remove(id)
                    edit.stamp = ++revision
                    if (!isCurrent()) edits.remove(id)
                    prune()
                }
            }
        }
        jobs[id] = job
        job.start()
    }
    private fun prune() {
        while (edits.size > 256) {
            val oldest = edits.keys.firstOrNull { it !in jobs } ?: break
            edits.remove(oldest)
        }
    }
    private fun Post.reaction() = myReaction ?: if (likedByMe) "LIKE" else null
    private companion object { val TYPES = setOf("LIKE", "LOVE", "HAHA", "WOW", "SAD", "ANGRY", "CARE", "FIRE") }
}

internal fun Post.copyReactionFrom(source: Post): Post = copy(likedByMe = source.likedByMe,
    myReaction = source.myReaction, likeCount = source.likeCount, reactionCounts = source.reactionCounts)
    .also { it.isSensitive = isSensitive }
