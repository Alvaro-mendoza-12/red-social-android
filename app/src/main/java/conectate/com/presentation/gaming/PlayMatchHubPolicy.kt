package conectate.com.presentation.gaming

import conectate.com.data.model.PlayMatchConnection
import conectate.com.data.model.PlayMatchMatch
import conectate.com.data.model.PlayMatchProfile
import java.time.Instant
import java.time.OffsetDateTime

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
    val now = Instant.now()
    val distinctConnections = connections.distinctBy { it.id }
    return PlayMatchActivity(
        requests = distinctConnections.filter { it.status == "PENDING" && it.direction == "INCOMING" },
        sent = distinctConnections.filter { it.status == "PENDING" && it.direction == "OUTGOING" },
        conversations = distinctConnections.filter { it.status == "ACCEPTED" && it.otherUserId.isNotBlank() },
        decisions = matches.filter { it.status == "PENDING" && matchHasNotExpired(it, now) }.distinctBy { it.id },
    )
}

private fun matchHasNotExpired(match: PlayMatchMatch, now: Instant): Boolean {
    // Older payloads omit the deadline; retain their existing server-controlled behavior.
    val raw = match.expiresAt ?: return true
    if (raw.isBlank() || raw.length > 64) return false
    val expires = runCatching { OffsetDateTime.parse(raw.trim()).toInstant() }.getOrNull() ?: return false
    return expires.isAfter(now)
}
