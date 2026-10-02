package conectate.com.data.repository

/** One direct WebSocket attempt, then the compatible polling/upgrade path. */
internal data class CallSignalingTransportPlan(val pollingFallback: Boolean = false) {
    val transports: Array<String>
        get() = if (pollingFallback) arrayOf("polling", "websocket") else arrayOf("websocket")
    val timeoutMs: Long get() = if (pollingFallback) 10_000L else 2_500L
}
