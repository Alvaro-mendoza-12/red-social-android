package conectate.com.presentation.messages

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Owned by the screen's main-thread scope; no history-wide or background polling. */
internal class VisiblePollRefreshController<T>(
    private val scope: CoroutineScope,
    private val isCurrentAccount: () -> Boolean,
    private val fetch: suspend (String) -> Result<List<T>>,
    private val apply: (String, List<T>) -> Unit,
    private val intervalMs: Long = 5_000,
) {
    private class Entry {
        val wake = Channel<Unit>(Channel.CONFLATED)
        var job: Job? = null
        var revision = 0L
    }

    private val entries = linkedMapOf<String, Entry>()
    private val pendingVotes = mutableSetOf<String>()
    private var roomKey: String? = null
    private var generation = 0L

    fun update(active: Boolean, room: String, visiblePollIds: Set<String>) {
        val nextRoom = room.takeIf { active && it.isNotBlank() && isCurrentAccount() }
        if (roomKey != nextRoom) {
            stop()
            roomKey = nextRoom
        }
        val targets = if (nextRoom == null) emptySet() else visiblePollIds.take(6).toSet()
        entries.keys.toList().filter { it !in targets }.forEach { id ->
            entries.remove(id)?.let { it.job?.cancel(); it.wake.close() }
        }
        targets.forEach { id ->
            if (entries[id]?.job?.isActive == true) return@forEach
            entries.remove(id)?.wake?.close()
            val entry = Entry()
            val requestGeneration = generation
            entries[id] = entry
            entry.job = scope.launch {
                var misses = 0
                while (isActive && isCurrentAccount()) {
                    val revision = entry.revision
                    val state = if (id in pendingVotes) null else try {
                        fetch(id).getOrNull()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (!state.isNullOrEmpty() && generation == requestGeneration &&
                        entries[id] === entry && revision == entry.revision &&
                        id !in pendingVotes && isCurrentAccount()
                    ) {
                        apply(id, state)
                    }
                    if (!isActive || generation != requestGeneration || entries[id] !== entry ||
                        !isCurrentAccount()
                    ) return@launch
                    // A realtime event wakes this immediately; the timer repairs missed events.
                    val waitMs = if (state.isNullOrEmpty()) {
                        (1_000L shl misses.coerceAtMost(5)).coerceAtMost(30_000).also { misses++ }
                    } else { misses = 0; intervalMs }
                    withTimeoutOrNull(waitMs) {
                        entry.wake.receiveCatching()
                    }
                }
            }
        }
    }

    fun requestRefresh(pollId: String) { entries[pollId]?.wake?.trySend(Unit) }

    fun voteStarted(pollId: String) {
        pendingVotes += pollId
        entries[pollId]?.let { it.revision++ }
    }

    fun voteFinished(pollId: String) {
        pendingVotes -= pollId
        entries[pollId]?.let { it.revision++; it.wake.trySend(Unit) }
    }

    fun stop() {
        generation++
        entries.values.forEach { it.job?.cancel(); it.wake.close() }
        entries.clear()
        roomKey = null
    }
}

internal fun visiblePollIds(messages: List<Pair<String, String?>>, visibleMessages: Set<String>): Set<String> =
    messages.asSequence().filter { it.first in visibleMessages }
        .mapNotNull { it.second?.trim()?.takeIf(String::isNotBlank) }.toCollection(linkedSetOf())
