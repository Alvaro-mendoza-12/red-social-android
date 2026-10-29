package conectate.com.core.network

import java.net.InetAddress
import java.net.UnknownHostException

/** One backend hostname: concurrent failures share a short cooldown, including stale fallback. */
internal class BackendAddressCache(
    private val hostname: String,
    private val nowMs: () -> Long,
    private val ttlMs: Long = 5 * 60_000L,
    private val maxStaleMs: Long = 15 * 60_000L,
    private val failureCooldownMs: Long = 5_000L,
) {
    private var addresses: List<InetAddress> = emptyList()
    private var expiresAtMs = 0L
    private var lastFailureAtMs: Long? = null

    @Synchronized
    fun lookup(resolve: () -> List<InetAddress>): List<InetAddress> {
        val now = nowMs()
        if (addresses.isNotEmpty() && now < expiresAtMs) return addresses
        val sinceFailure = lastFailureAtMs?.let { now - it }
        if (sinceFailure == null || sinceFailure !in 0 until failureCooldownMs) {
            // An interrupted lookup propagates without caching cancellation as a DNS outage.
            val resolved = resolve()
            if (resolved.isNotEmpty()) {
                addresses = resolved.toList()
                expiresAtMs = nowMs() + ttlMs
                lastFailureAtMs = null
                return addresses
            }
            lastFailureAtMs = nowMs()
        }
        // Failed refreshes never extend the stale lease of a previously trusted answer.
        if (addresses.isNotEmpty() && nowMs() < expiresAtMs + maxStaleMs) return addresses
        throw UnknownHostException("No public address available for $hostname")
    }
}
