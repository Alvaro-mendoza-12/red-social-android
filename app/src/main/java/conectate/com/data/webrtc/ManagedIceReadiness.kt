package conectate.com.data.webrtc

/** An in-flight renewal must not stall media while the existing credentials are valid. */
internal fun shouldAwaitManagedIceRefresh(
    hasServers: Boolean,
    expiresAtEpochSeconds: Long,
    nowEpochSeconds: Long
): Boolean = !hasServers || (expiresAtEpochSeconds > 0L && expiresAtEpochSeconds <= nowEpochSeconds)
