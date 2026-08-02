package conectate.com.data.repository

/**
 * A Room user row is intentionally a compact identity/contact snapshot. It is
 * safe to prefer it for the signed-in user's optimistic profile mutations, but
 * it must not replace another user's complete, privacy-filtered profile.
 */
internal object ProfileReadPolicy {
    fun canUseCompactCacheBeforeRemote(
        forceNetwork: Boolean,
        requestedUserId: String,
        currentUserId: String?
    ): Boolean {
        if (forceNetwork || currentUserId == null) return false
        return requestedUserId.equals(currentUserId, ignoreCase = true)
    }
}
