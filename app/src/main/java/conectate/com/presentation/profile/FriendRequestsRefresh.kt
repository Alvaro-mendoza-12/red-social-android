package conectate.com.presentation.profile

import conectate.com.data.model.User
import kotlinx.coroutines.CancellationException

internal data class FriendRequestsRefresh(
    val users: List<User>,
    val confirmations: Map<String, Long>,
    val recentFailed: Boolean,
)

/** A failed independent recent-friends read must not erase confirmed people from this account. */
internal fun mergeFriendRequests(
    pending: List<User>,
    recent: Result<List<Pair<User, String>>>?,
    previousUsers: List<User>,
    previousConfirmations: Map<String, Long>,
    dismissedIds: Set<String>,
    now: () -> Long = System::currentTimeMillis,
): FriendRequestsRefresh {
    recent?.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    val pendingRows = pending.distinctBy { it.id.lowercase() }
    val pendingIds = pendingRows.map { it.id.lowercase() }.toSet()
    val dismissed = dismissedIds.map { it.lowercase() }.toSet()
    val confirmed = if (recent == null || recent.isFailure) {
        previousUsers.mapNotNull { user -> previousConfirmations[user.id]?.let { user to it } }
    } else {
        recent.getOrThrow().map { (user, date) ->
            user to runCatching { java.time.Instant.parse(date).toEpochMilli() }.getOrElse { now() }
        }
    }.distinctBy { it.first.id.lowercase() }
        .filter { it.first.id.lowercase() !in pendingIds && it.first.id.lowercase() !in dismissed }
    return FriendRequestsRefresh(
        users = pendingRows + confirmed.map { it.first },
        confirmations = confirmed.associate { it.first.id to it.second },
        recentFailed = recent?.isFailure == true,
    )
}
