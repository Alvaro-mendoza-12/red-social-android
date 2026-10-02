package conectate.com.data.repository

internal enum class CallSetupOrigin(val includesRinging: Boolean) {
    OUTGOING(true), INCOMING(false), JOINING(false)
}

/** All elapsed values use one device's monotonic clock, never remote wall clocks. */
internal data class CallSetupTiming(val startedAtElapsedMs: Long, val origin: CallSetupOrigin) {
    fun elapsedMs(nowElapsedMs: Long): Long = (nowElapsedMs - startedAtElapsedMs).coerceAtLeast(0L)
}
