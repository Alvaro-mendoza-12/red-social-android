package conectate.com.data.repository

/** Binds an optional, unjoined socket to one invitation and authenticated session. */
internal data class PreparedCallSignalingContext(
    val callId: String,
    val userId: String,
    val endpoint: String,
    private val accessToken: String,
    val preparedAtMs: Long,
) {
    fun matches(callId: String, userId: String, endpoint: String, token: String, nowMs: Long): Boolean =
        this.callId.equals(callId, ignoreCase = true) && this.userId == userId &&
            this.endpoint == endpoint && accessToken == token && token.isNotBlank() &&
            nowMs - preparedAtMs in 0 until LIFETIME_MS

    // Avoid data-class diagnostic output exposing the session token.
    override fun toString(): String = "PreparedCallSignalingContext"

    companion object { const val LIFETIME_MS = 45_000L }
}
