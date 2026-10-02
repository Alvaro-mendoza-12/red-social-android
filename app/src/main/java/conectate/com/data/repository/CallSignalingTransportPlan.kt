package conectate.com.data.repository

/** One direct WebSocket attempt, then the compatible polling/upgrade path. */
internal data class CallSignalingTransportPlan(
    val pollingFallback: Boolean = false,
    val preparationFailed: Boolean = false,
) {
    val usesPollingFallback: Boolean get() = pollingFallback || preparationFailed
    val transports: Array<String>
        get() = if (usesPollingFallback) arrayOf("polling", "websocket") else arrayOf("websocket")
    val timeoutMs: Long get() = if (usesPollingFallback) 10_000L else 2_500L
}
