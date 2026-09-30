package conectate.com.presentation.live

/** Only a completed attempt can offer recovery; a terminal event is consumed once. */
internal data class LiveCollaborationRetryState(
    val attempting: Boolean = false,
    val connected: Boolean = false,
    val terminalHandled: Boolean = false
) {
    fun beginAttempt() = LiveCollaborationRetryState(attempting = true)

    fun completeAttempt(success: Boolean) = LiveCollaborationRetryState(
        connected = success,
        terminalHandled = !success
    )

    fun shouldOfferAfterDisconnect(): Boolean = !attempting && connected && !terminalHandled

    fun consumeDisconnect() = copy(connected = false, terminalHandled = true)
}

internal fun canShowLiveCollaborationRetry(
    requested: Boolean,
    hasPlan: Boolean,
    leaving: Boolean,
    attempting: Boolean,
    transportBusy: Boolean
): Boolean = requested && hasPlan && !leaving && !attempting && !transportBusy
