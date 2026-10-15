package conectate.com.presentation.posts

import conectate.com.data.model.Post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Serializes each post's reaction and fences late reads/writes to the active account. */
internal class PostReactionWriter(
    private val scope: CoroutineScope,
    private val account: () -> String?,
    private val current: (String) -> Post?,
    private val write: suspend (String, String?, Boolean) -> Result<Unit>,
    private val refresh: suspend (String) -> Result<Post>,
    private val publish: (Post) -> Unit,
    private val failed: () -> Unit,
) {
    private val pending = mutableMapOf<String, Job>()
    private var revision = 0L
    fun clear() { revision++; pending.values.toList().forEach { it.cancel() }; pending.clear() }
    fun set(id: String, requested: String?) {
        val owner = account() ?: return
        val before = current(id) ?: return
        if (id.isBlank() || pending.containsKey(id)) return
        val normalized = requested?.trim()?.uppercase()?.takeIf { it in TYPES }
        if (requested != null && normalized == null) return
        val old = before.myReaction ?: if (before.likedByMe) "LIKE" else null
        val selected = normalized?.takeUnless { it == old }
        val version = revision
        publish(before.withReaction(selected))
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            fun isCurrent() = version == revision && owner == account()
            try {
                write(id, selected, old != null).getOrThrow()
                if (!isCurrent()) return@launch
                // A failed refresh does not undo an already persisted reaction.
                val fresh = try { refresh(id).getOrNull() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                if (isCurrent() && fresh?.id == id) publish(fresh)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (isCurrent()) { publish(before); failed() } }
            finally { if (version == revision) pending.remove(id) }
        }
        pending[id] = job
        job.start()
    }
    private companion object { val TYPES = setOf("LIKE", "LOVE", "HAHA", "WOW", "SAD", "ANGRY", "CARE", "FIRE") }
}
