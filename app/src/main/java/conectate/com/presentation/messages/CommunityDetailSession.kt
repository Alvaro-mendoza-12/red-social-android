package conectate.com.presentation.messages

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** One observer tree per community/account; closing it also cancels its network reads. */
internal class CommunityDetailSession(private val parent: CoroutineScope) {
    data class Ticket(val communityId: String, val accountId: String?, val scope: CoroutineScope)
    private var active: Ticket? = null

    fun open(communityId: String, accountId: String?): Ticket? {
        if (active?.let { it.communityId == communityId && it.accountId == accountId } == true) return null
        active?.scope?.cancel()
        return Ticket(communityId, accountId, CoroutineScope(parent.coroutineContext +
            SupervisorJob(parent.coroutineContext[Job]))).also { active = it }
    }

    fun current(): Ticket? = active
    fun isCurrent(ticket: Ticket, accountId: String?): Boolean = active === ticket && ticket.accountId == accountId
}
