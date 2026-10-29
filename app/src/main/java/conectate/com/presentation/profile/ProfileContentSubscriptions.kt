package conectate.com.presentation.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** One subscription per tab; old profiles and cancelled reads cannot repopulate another profile. */
internal class ProfileContentSubscriptions(private val scope: CoroutineScope, private val reads: ProfileReadScope) {
    private val jobs = mutableMapOf<String, Job>()
    fun cancelAll() { jobs.values.forEach { it.cancel() }; jobs.clear() }
    fun <T> observe(key: String, target: String, source: suspend () -> Flow<T>, publish: (T) -> Unit,
                    failed: (Exception) -> Unit) {
        val ticket = reads.snapshotFor(target) ?: return
        jobs.remove(key)?.cancel()
        jobs[key] = scope.launch {
            try {
                source().collect { value -> if (reads.isCurrent(ticket)) publish(value) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (reads.isCurrent(ticket)) failed(error) }
        }
    }
}
