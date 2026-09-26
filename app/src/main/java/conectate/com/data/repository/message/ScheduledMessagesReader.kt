package conectate.com.data.repository.message

import conectate.com.core.network.runReadWithDeadline
import conectate.com.data.model.ScheduledMessageJob
import kotlinx.coroutines.CancellationException

/** One total deadline includes authentication, discovery, response body and cache publication. */
internal class ScheduledMessagesReader(
    private val store: ScheduledMessageStore,
    private val currentAccount: () -> String?,
    private val accessToken: suspend () -> String?,
    private val fetch: suspend (ScheduledMessagesKey, String) -> List<ScheduledMessageJob>,
    private val tokenOwner: (String) -> String? = { null },
    private val timeoutMillis: Long = 5_000L,
) {
    suspend fun refresh(key: ScheduledMessagesKey): Result<ScheduledMessagesSnapshot> = runReadWithDeadline(timeoutMillis) {
        checkAccount(key)
        val token = accessToken()?.takeIf { it.isNotBlank() } ?: error("Sesión no disponible")
        checkAccount(key)
        val revision = store.revision(key.accountId)
        val jobs = fetch(key, token)
        checkAccount(key)
        val subject = tokenOwner(token)
        val boundJobs = jobs.map { bindScheduledJobToAccount(it, key, subject) }
        store.replaceRemote(key, boundJobs, revision)
        store.snapshot(key)
    }

    private fun checkAccount(key: ScheduledMessagesKey) {
        if (currentAccount() != key.accountId) throw CancellationException("La cuenta activa cambió")
    }
}
