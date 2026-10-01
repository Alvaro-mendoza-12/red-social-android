package conectate.com.data.service

/** An asynchronous avatar must never resurrect an answered, cancelled or timed-out call. */
internal fun canRefreshIncomingCallAvatar(
    notificationStillVisible: Boolean,
    recentlyCancelled: Boolean,
    hasActiveCall: Boolean,
    elapsedMs: Long,
): Boolean = notificationStillVisible && !recentlyCancelled && !hasActiveCall && elapsedMs in 0 until 45_000L
