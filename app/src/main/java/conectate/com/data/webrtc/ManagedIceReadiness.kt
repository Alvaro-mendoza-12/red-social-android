package conectate.com.data.webrtc

/** An in-flight renewal must not stall media while the existing credentials are valid. */
internal fun shouldAwaitManagedIceRefresh(
    hasServers: Boolean,
    expiresAtEpochSeconds: Long,
    nowEpochSeconds: Long
): Boolean = !hasServers || (expiresAtEpochSeconds > 0L && expiresAtEpochSeconds <= nowEpochSeconds)

/** Renew absent/expired credentials; a forced refresh still supports network recovery. */
internal fun shouldRefreshManagedIce(
    force: Boolean,
    hasServers: Boolean,
    expiresAtEpochSeconds: Long,
    nowEpochSeconds: Long,
): Boolean = force || shouldAwaitManagedIceRefresh(hasServers, expiresAtEpochSeconds, nowEpochSeconds)

internal fun shouldDiscardManagedIceForAccountChange(previousUserId: String, nextUserId: String): Boolean =
    previousUserId.isNotBlank() && !previousUserId.trim().equals(nextUserId.trim(), ignoreCase = true)

internal fun acceptsManagedIceResponse(requestGeneration: Long, activeGeneration: Long,
    requestToken: String, activeToken: String): Boolean =
    requestGeneration == activeGeneration && requestToken.isNotBlank() && requestToken == activeToken
