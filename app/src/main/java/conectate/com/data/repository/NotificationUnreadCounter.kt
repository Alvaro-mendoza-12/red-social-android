package conectate.com.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A slow notification read must not restore another account or an older badge. */
internal class NotificationUnreadCounter(accountId: () -> String?) {
    private val session = SessionWriteGuard(accountId)
    private val count = MutableStateFlow(0)
    val unreadCount = count.asStateFlow()
    private var nextRequest = 0L
    private var appliedRequest = 0L

    data class Request(val session: SessionWriteGuard.Snapshot, val order: Long)

    @Synchronized
    fun begin(): Request = Request(session.capture(), ++nextRequest)

    @Synchronized
    fun isCurrent(request: Request): Boolean = request.session.accountId != null && session.isValid(request.session)

    @Synchronized
    fun publish(request: Request, unread: Int): Boolean {
        if (!isCurrent(request) || request.order < appliedRequest) return false
        appliedRequest = request.order
        count.value = unread.coerceAtLeast(0)
        return true
    }

    @Synchronized
    fun clear() {
        session.invalidate()
        appliedRequest = ++nextRequest
        count.value = 0
    }
}
