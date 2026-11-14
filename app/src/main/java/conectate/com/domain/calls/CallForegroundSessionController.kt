package conectate.com.domain.calls

/** A device-local entry to one call; this identifier is never an authentication credential. */
data class CallForegroundSession(
    val sessionId: String,
    val callId: String,
    val title: String,
    val isMuted: Boolean,
    val isVideo: Boolean,
)

/** Implementations recheck the captured session when an action actually executes. */
interface CallForegroundSessionController {
    fun currentForegroundSession(): CallForegroundSession?
    suspend fun hangup(expectedSessionId: String): Result<Unit>
    fun setMuted(expectedSessionId: String, muted: Boolean): Boolean
}
