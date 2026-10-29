package conectate.com.data.repository

import conectate.com.data.model.Moment
import java.time.Instant

/** Preserve concurrent feed updates; restore only the rejected deletion, never a whole stale list. */
internal fun restoreFailedMomentDeletion(current: List<Moment>, original: Moment?, now: Instant = Instant.now()): List<Moment> {
    if (original == null || current.any { it.id.equals(original.id, true) }) return current
    val expiry = original.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (expiry != null && !expiry.isAfter(now)) return current
    return (current + original).sortedByDescending { it.createdAt }
}
