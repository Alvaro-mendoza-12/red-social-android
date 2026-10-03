package conectate.com.data.signaling

import java.util.concurrent.atomic.AtomicBoolean

/** Start directly; use the compatible polling/upgrade path after a failed handshake. */
internal data class MediaSignalingTransportPlan(
    val pollingFallback: Boolean = false,
    val preparationFailed: Boolean = false,
) {
    val usesPollingFallback: Boolean get() = pollingFallback || preparationFailed
    val transports: Array<String>
        get() = if (usesPollingFallback) arrayOf("polling", "websocket") else arrayOf("websocket")
    val timeoutMs: Long get() = if (usesPollingFallback) 10_000L else 2_500L
}

/** Several queued errors from one socket must schedule only one replacement. */
internal class MediaSignalingFallback(private val plan: MediaSignalingTransportPlan) {
    private val claimed = AtomicBoolean(false)

    fun claim(isCurrentAttempt: Boolean): Boolean =
        isCurrentAttempt && !plan.usesPollingFallback && claimed.compareAndSet(false, true)
}

internal fun isCurrentMediaSignalingSession(
    requestedSessionId: String,
    activeSessionId: String?,
    requestedUserId: String,
    activeUserId: String?,
): Boolean = requestedSessionId.isNotBlank() && requestedUserId.isNotBlank() &&
    requestedSessionId.trim().equals(activeSessionId?.trim(), ignoreCase = true) &&
    requestedUserId.trim().equals(activeUserId?.trim(), ignoreCase = true)
