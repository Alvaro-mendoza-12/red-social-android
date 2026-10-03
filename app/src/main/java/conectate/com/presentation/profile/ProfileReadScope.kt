package conectate.com.presentation.profile

/** A response belongs to one displayed profile and one account, including badge enrichment. */
internal class ProfileReadScope(private val currentAccount: () -> String?) {
    internal data class Ticket(val target: String, val owner: String?, val revision: Long)
    private var latest: Ticket? = null
    private var revision = 0L

    fun begin(target: String): Ticket = Ticket(target, currentAccount(), ++revision).also { latest = it }
    fun isCurrent(ticket: Ticket): Boolean = latest == ticket && ticket.owner == currentAccount()
    fun snapshotFor(target: String): Ticket? = latest?.takeIf { it.target == target && isCurrent(it) }
}
