package conectate.com.presentation.live

internal const val MAX_VISIBLE_LIVE_GUESTS = 8

/** Two wide panels for a duet; three columns when seven or more people join. */
internal fun liveParticipantColumnCount(participants: Int): Int = when {
    participants <= 1 -> 1
    participants <= 6 -> 2
    else -> 3
}

internal fun matchingLiveParticipantIndex(userId: String, identities: List<String>): Int? {
    val id = userId.trim()
    if (id.isEmpty()) return null
    return identities.indexOfFirst { it.trim().equals(id, ignoreCase = true) }.takeIf { it >= 0 }
}
