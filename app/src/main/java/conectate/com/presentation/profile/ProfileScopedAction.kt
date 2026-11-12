package conectate.com.presentation.profile

/** Refreshes preserve in-flight actions; navigation and account changes invalidate them. */
internal class ProfileFriendActionScope(private val currentAccount: () -> String?) {
    internal data class Ticket(val owner: String, val route: String, val revision: Long)
    private var current: Ticket? = null
    private var revision = 0L

    fun enter(route: String): Boolean {
        val owner = currentAccount()
        if (owner == null) { current = null; revision++; return true }
        if (current?.owner != owner || current?.route != route) {
            current = Ticket(owner, route, ++revision)
            return true
        }
        return false
    }
    fun invalidate() { current = null; revision++ }
    fun snapshot(): Ticket? = current?.takeIf(::isCurrent)
    fun isCurrent(ticket: Ticket): Boolean = current == ticket && currentAccount() == ticket.owner
}

/** An action may finish after navigation; its result belongs only to its starting view. */
internal suspend fun <T> ProfileFriendActionScope.runCurrentAction(
    ticket: ProfileFriendActionScope.Ticket,
    onStart: () -> Unit,
    action: suspend () -> T,
    onResult: (T) -> Unit
) {
    if (!isCurrent(ticket)) return
    onStart()
    val result = action()
    if (isCurrent(ticket)) onResult(result)
}
