package conectate.com.domain.live

/** Device-local ownership of one visit to a live, separate from its public id. */
data class LiveForegroundSession(
    val sessionId: String,
    val liveId: String,
    val isHost: Boolean
)

interface LiveForegroundSessionController {
    fun currentForegroundSession(): LiveForegroundSession?
    suspend fun endLive(expectedSessionId: String): Result<Unit>
}
