package conectate.com.data.webrtc

/**
 * Pure policy used by the Android network callback.
 *
 * The first callback after construction is seeded with the current default
 * network, so a different handle means a real handoff. When the device was
 * offline, a null previous handle also needs recovery if media peers exist.
 */
object NetworkHandoffPolicy {
    fun shouldRecover(
        previousNetworkHandle: Long?,
        currentNetworkHandle: Long,
        hasActivePeers: Boolean
    ): Boolean {
        return hasActivePeers && previousNetworkHandle != currentNetworkHandle
    }
}
