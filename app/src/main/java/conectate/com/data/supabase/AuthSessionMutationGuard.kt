package conectate.com.data.supabase

/** Serializes the final local session write with an immediate logout/account switch. */
internal class AuthSessionMutationGuard {
    private val lock = Any()
    private var generation = 0L

    fun snapshot(): Long = synchronized(lock) { generation }

    fun <T> readCurrent(read: () -> T): T = synchronized(lock) { read() }

    fun invalidate(mutation: () -> Unit) = synchronized(lock) {
        generation += 1
        mutation()
    }

    fun commitIfCurrent(
        expectedGeneration: Long,
        eligible: () -> Boolean = { true },
        mutation: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (expectedGeneration != generation || !eligible()) return@synchronized false
        mutation()
        true
    }

    fun commitForAccount(
        accountId: String?,
        accessToken: String?,
        currentAccount: () -> String?,
        currentAccessToken: () -> String?,
        mutation: () -> Unit,
    ): Boolean {
        val owner = accountId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return commitIfCurrent(snapshot(), eligible = {
            owner.equals(currentAccount(), ignoreCase = true) &&
                (accessToken == null || accessToken == currentAccessToken())
        }, mutation = mutation)
    }
}
