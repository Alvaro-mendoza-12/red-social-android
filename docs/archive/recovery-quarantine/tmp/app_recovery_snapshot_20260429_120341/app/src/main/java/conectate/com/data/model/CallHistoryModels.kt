package conectate.com.data.model

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class CallHistoryDirection {
    OUTGOING,
    INCOMING
}

@Serializable
enum class CallHistoryStatus {
    ANSWERED,
    REJECTED,
    CANCELLED,
    NO_ANSWER,
    ENDED
}

@Serializable
data class CallHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val callId: String,
    val title: String,
    val subtitle: String,
    val contextType: String,
    val contextId: String? = null,
    val peerUserId: String? = null,
    val peerName: String? = null,
    val peerAvatarUrl: String? = null,
    val direction: CallHistoryDirection = CallHistoryDirection.OUTGOING,
    val status: CallHistoryStatus = CallHistoryStatus.ENDED,
    val statusLabel: String,
    val isVideo: Boolean = false,
    val timestampEpochMs: Long = System.currentTimeMillis()
)
