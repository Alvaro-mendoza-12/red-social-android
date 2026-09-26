package conectate.com.core.network

/**
 * Automatic transport retries are safe only for requests that do not mutate
 * server state. Commands use explicit idempotency keys/reconciliation instead.
 */
object AutomaticRetryPolicy {
    private val safeMethods = setOf("GET", "HEAD", "OPTIONS")

    fun mayRetry(method: String): Boolean = method.trim().uppercase() in safeMethods

    fun mayRetryResponse(method: String, statusCode: Int): Boolean =
        mayRetry(method) && (statusCode == 429 || statusCode >= 500)
}
