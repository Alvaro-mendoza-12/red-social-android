package conectate.com.data.repository.message

/**
 * Observable state for the account-scoped inbox refresh. Room remains the
 * source rendered by the UI; this state tracks its first read and remote refresh.
 */
data class InboxSyncState(
    val hasLoadedLocalSnapshot: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasCompletedInitialSync: Boolean = false,
    val lastError: String? = null,
    val lastCompletedAtMillis: Long = 0L,
)

/** A completed local read is usable even when the remote list is still refreshing. */
fun inboxNeedsInitialLoading(state: InboxSyncState): Boolean =
    !state.hasLoadedLocalSnapshot && !state.hasCompletedInitialSync

/**
 * Keeps duplicate collectors, lifecycle resumes and fallback polling from
 * generating parallel inbox reads.
 */
object InboxSyncPolicy {
    const val DuplicateSuppressionMillis = 5_000L
    const val PeriodicRefreshMillis = 60_000L

    fun shouldStart(
        force: Boolean,
        isRefreshing: Boolean,
        nowMillis: Long,
        lastCompletedAtMillis: Long,
    ): Boolean {
        if (isRefreshing) return false
        if (force || lastCompletedAtMillis <= 0L) return true
        return nowMillis - lastCompletedAtMillis >= DuplicateSuppressionMillis
    }

    /** Remote authentication is a refresh concern, never a prerequisite for
     * opening an inbox that was already persisted for the active account. */
    fun <T> cachedOrFailure(cached: List<T>, remoteFailure: Throwable): Result<List<T>> =
        if (cached.isNotEmpty()) Result.success(cached) else Result.failure(remoteFailure)
}
