package conectate.com.data.model

enum class CallConnectionState {
    IDLE,
    RINGING_OUTGOING,
    RINGING_INCOMING,
    CONNECTING,
    CONNECTED,
    ENDED
}

data class ActiveCall(
    val callId: String,
    val title: String,
    val initiatorUserId: String,
    val connectionState: CallConnectionState,
    val startedAtEpochMs: Long? = null,
    val contextType: String = "dm", // dm | group | community
    val contextId: String? = null,
    val peerUserId: String? = null,
    val peerName: String? = null,
    val peerAvatarUrl: String? = null
)

data class CallEndedSummary(
    val callId: String,
    val title: String,
    val peerName: String,
    val peerAvatarUrl: String? = null,
    val statusText: String,
    val canMessage: Boolean = false,
    val messageTargetId: String? = null
)

data class CallParticipant(
    val userId: String,
    val name: String,
    val avatarUrl: String? = null,
    val socketId: String,
    val isMuted: Boolean = false
)

data class CallInvite(
    val callId: String,
    val title: String,
    val callerUserId: String,
    val callerName: String,
    val callerAvatarUrl: String? = null,
    val contextType: String,
    val contextId: String? = null
)

data class CallReactionEvent(
    val id: String = "",
    val userId: String,
    val emoji: String
)
