package conectate.com.presentation.gaming

import conectate.com.data.model.PlayMatchConnection
import conectate.com.data.model.PlayMatchMatch
import conectate.com.data.model.PlayMatchProfile

internal enum class PlayMatchStart {
    LOADING, NO_PROFILE, CONFIRM_AGE, ENABLE_DISCOVERY, ADD_GAME, READY,
}

internal fun playMatchStart(profile: PlayMatchProfile?, loading: Boolean): PlayMatchStart = when {
    loading && profile == null -> PlayMatchStart.LOADING
    profile == null -> PlayMatchStart.NO_PROFILE
    profile.adultConfirmed != true -> PlayMatchStart.CONFIRM_AGE
    profile.games.isEmpty() -> PlayMatchStart.ADD_GAME
    !profile.discoverable -> PlayMatchStart.ENABLE_DISCOVERY
    else -> PlayMatchStart.READY
}

internal data class PlayMatchActivity(
    val requests: List<PlayMatchConnection>,
    val sent: List<PlayMatchConnection>,
    val conversations: List<PlayMatchConnection>,
    val decisions: List<PlayMatchMatch>,
) {
    val pending: Int get() = requests.size + decisions.size
    val isEmpty: Boolean get() = pending == 0 && sent.isEmpty() && conversations.isEmpty()
}

internal fun playMatchActivity(
    connections: List<PlayMatchConnection>,
    matches: List<PlayMatchMatch>,
): PlayMatchActivity {
    val distinctConnections = connections.distinctBy { it.id }
    return PlayMatchActivity(
        requests = distinctConnections.filter { it.status == "PENDING" && it.direction == "INCOMING" },
        sent = distinctConnections.filter { it.status == "PENDING" && it.direction == "OUTGOING" },
        conversations = distinctConnections.filter { it.status == "ACCEPTED" && it.otherUserId.isNotBlank() },
        decisions = matches.filter { it.status == "PENDING" }.distinctBy { it.id },
    )
}
