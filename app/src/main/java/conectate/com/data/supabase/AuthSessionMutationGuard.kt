package conectate.com.data.supabase

internal class AuthSessionLease internal constructor(
    val accountId: String?,
    private val ownerGuard: AuthSessionMutationGuard? = null,
    private val generation: Long? = null,
    private val matches: () -> Boolean,
) {
    internal fun sameVisit(other: AuthSessionLease): Boolean = this === other ||
        (ownerGuard != null && ownerGuard === other.ownerGuard && generation != null &&
            generation == other.generation && accountId == other.accountId)
    internal fun isOwnedBy(guard: AuthSessionMutationGuard): Boolean = ownerGuard === guard
    fun isCurrent(): Boolean = accountId != null && matches()
    fun requireCurrent() {
        if (!isCurrent()) throw kotlinx.coroutines.CancellationException("La sesión cambió. Vuelve a abrir este apartado.")
    }
}

/** Serializes the final local session write with an immediate logout/account switch. */
internal class AuthSessionMutationGuard {
    private val lock = Any()
    private var generation = 0L

    fun captureAccountLease(currentAccount: () -> String?): AuthSessionLease = synchronized(lock) {
        val expected = generation
        val account = currentAccount()?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf(String::isNotBlank)
        AuthSessionLease(account, this, expected) {
            commitIfCurrent(expected, eligible = { account != null && account.equals(currentAccount()?.trim(), ignoreCase = true) }) {}
        }
    }

    /** Local non-suspending writes share the same lock as a login/logout, including ABA checks. */
    fun commitForLease(lease: AuthSessionLease, mutation: () -> Unit): Boolean = synchronized(lock) {
        if (!lease.isOwnedBy(this) || !lease.isCurrent()) return@synchronized false
        mutation()
        true
    }

    internal class Transition internal constructor(
        internal val guard: AuthSessionMutationGuard,
        internal var generation: Long,
        internal var closed: Boolean = false,
    )

    fun openTransition(): Transition = synchronized(lock) { Transition(this, generation) }

    fun isCurrent(transition: Transition): Boolean = synchronized(lock) {
        transition.guard === this && !transition.closed && transition.generation == generation
    }

    /** Only this transition's own commits advance its lease; another login/logout invalidates it. */
    fun advanceTransition(
        transition: Transition,
        beforeMutation: () -> Unit = {},
        mutation: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (!isCurrent(transition)) return@synchronized false
        beforeMutation() // A failed durable snapshot must leave the outgoing account active.
        generation += 1
        transition.generation = generation
        mutation()
        true
    }

    fun closeTransition(transition: Transition) = synchronized(lock) {
        if (transition.guard === this) transition.closed = true
    }

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
