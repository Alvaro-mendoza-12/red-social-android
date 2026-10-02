package conectate.com.data.repository

/** Keep the peer/initiator even when cancellation beats the first participant event. */
internal fun callEndSignalTargets(
    currentUserId: String,
    pendingInvitees: List<String>,
    participants: List<String>,
    peerUserId: String?,
    initiatorUserId: String?
): List<String> = (pendingInvitees + participants + listOfNotNull(peerUserId, initiatorUserId))
    .map { it.trim().lowercase() }
    .filter { it.isNotBlank() && !it.equals(currentUserId.trim(), ignoreCase = true) }
    .distinct()
