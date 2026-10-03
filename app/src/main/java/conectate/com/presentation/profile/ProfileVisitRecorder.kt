package conectate.com.presentation.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Records a displayed profile even when it came from cache. A failed write stays retryable. */
internal class ProfileVisitRecorder(
    private val scope: CoroutineScope,
    private val currentAccount: () -> String?,
    private val record: suspend (String) -> Result<Unit>,
) {
    private class Entry { var job: Job? = null }
    private var owner: String? = null
    private val pending = mutableMapOf<String, Entry>()
    private val completed = LinkedHashSet<String>()

    fun displayed(profileId: String, allowsHistory: Boolean) {
        val account = currentAccount()?.trim()?.lowercase() ?: return
        val target = profileId.trim().lowercase()
        if (owner != account) {
            pending.values.forEach { it.job?.cancel() }
            pending.clear(); completed.clear(); owner = account
        }
        if (!allowsHistory || target.isBlank() || target == account || target in completed || target in pending) return
        val entry = Entry()
        pending[target] = entry
        entry.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = record(target)
                if (result.isSuccess && currentAccount()?.trim()?.lowercase() == account && pending[target] === entry) {
                    completed.add(target)
                    if (completed.size > 64) completed.remove(completed.first())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* An optional history write must not close the profile. */ }
            finally { if (pending[target] === entry) pending.remove(target) }
        }
        entry.job!!.start()
    }
}
