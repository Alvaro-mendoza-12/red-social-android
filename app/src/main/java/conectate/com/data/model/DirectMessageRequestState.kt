package conectate.com.data.model

import kotlinx.serialization.Serializable

@Serializable
data class DirectMessageRequestState(
    val status: String = "loading",
    val senderId: String? = null,
    val recipientId: String? = null,
) {
    val permitsSending: Boolean get() = status in setOf("open", "new", "pending_incoming")
    // An unavailable request read must not disable the durable local outbox.
    // Server authorization is still enforced when queued rows are delivered.
    val permitsLocalQueue: Boolean get() = permitsSending || status in setOf("loading", "error", "offline")
    fun afterVerificationFailure(): DirectMessageRequestState =
        if (status in setOf("open", "new", "pending_incoming", "pending_outgoing", "rejected")) this
        else copy(status = "offline")
    val needsFirstMessageWarning: Boolean get() = status == "new"
}
