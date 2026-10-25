package conectate.com.presentation.social

import conectate.com.data.model.User
import kotlinx.coroutines.CancellationException

/** Local identity is usable while the feed and remote profile are still loading. */
internal suspend fun loadSocialCurrentUser(
    cached: suspend () -> User?,
    refresh: suspend () -> Result<User?>,
    isCurrent: () -> Boolean,
    apply: (User) -> Unit,
) {
    try {
        cached()?.let { if (isCurrent()) apply(it) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A broken local snapshot must not prevent its remote replacement.
    }
    if (!isCurrent()) return
    refresh().getOrNull()?.let { if (isCurrent()) apply(it) }
}
