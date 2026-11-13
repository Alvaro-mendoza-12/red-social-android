package conectate.com.presentation.wallet

internal data class PendingPlayPurchase(
    val accountId: String,
    val productId: String,
    val purchaseToken: String,
)

/**
 * Serializes Google Play purchase verifications in FIFO order per account so
 * multiple owned or concurrent purchases are never silently dropped while an
 * earlier verification request is in flight.
 */
internal class PlayBillingVerificationQueue {
    private val lock = Any()
    private val queued = ArrayDeque<PendingPlayPurchase>()
    private var inFlight: PendingPlayPurchase? = null

    fun enqueue(accountId: String, productId: String, purchaseToken: String): Boolean {
        val cleanAccount = accountId.trim()
        val cleanProduct = productId.trim()
        val cleanToken = purchaseToken.trim()
        if (cleanAccount.isBlank() || cleanProduct.isBlank() || cleanToken.isBlank()) return false
        val candidate = PendingPlayPurchase(cleanAccount, cleanProduct, cleanToken)
        synchronized(lock) {
            if (isSamePurchase(inFlight, candidate) || queued.any { isSamePurchase(it, candidate) }) {
                return false
            }
            queued.addLast(candidate)
            return true
        }
    }

    fun drainNext(activeAccountId: String): PendingPlayPurchase? {
        val normalizedActive = activeAccountId.trim()
        synchronized(lock) {
            if (inFlight != null) return null
            while (queued.isNotEmpty()) {
                val head = queued.removeFirst()
                if (normalizedActive.isNotBlank() && head.accountId == normalizedActive) {
                    inFlight = head
                    return head
                }
            }
            return null
        }
    }

    fun complete(entry: PendingPlayPurchase) {
        synchronized(lock) {
            if (isSamePurchase(inFlight, entry)) {
                inFlight = null
            }
        }
    }

    fun hasPendingForAccount(activeAccountId: String): Boolean {
        val normalizedActive = activeAccountId.trim()
        synchronized(lock) {
            return (inFlight?.accountId == normalizedActive) ||
                queued.any { it.accountId == normalizedActive }
        }
    }

    fun clearForOtherAccount(activeAccountId: String?) {
        val normalizedActive = activeAccountId?.trim().orEmpty()
        synchronized(lock) {
            val iterator = queued.iterator()
            while (iterator.hasNext()) {
                if (normalizedActive.isBlank() || iterator.next().accountId != normalizedActive) {
                    iterator.remove()
                }
            }
            if (inFlight != null && (normalizedActive.isBlank() || inFlight?.accountId != normalizedActive)) {
                inFlight = null
            }
        }
    }

    private fun isSamePurchase(first: PendingPlayPurchase?, second: PendingPlayPurchase): Boolean =
        first != null &&
            first.accountId == second.accountId &&
            first.productId == second.productId &&
            first.purchaseToken == second.purchaseToken
}
