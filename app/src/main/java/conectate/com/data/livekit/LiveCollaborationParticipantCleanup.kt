package conectate.com.data.livekit

/** Same account in the same LIVE keeps its new confirmed participant state. */
internal fun shouldClearPreviousCollaborationParticipant(
    previousLiveId: String,
    previousUserId: String,
    currentLiveId: String?,
    currentUserId: String?
): Boolean = previousLiveId != currentLiveId || previousUserId != currentUserId
