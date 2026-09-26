package conectate.com.core.media

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Capture before loading an image; check under the same write lock used to clear its account. */
internal class ProfileIdentityWriteFence {
    private val generations = ConcurrentHashMap<String, Long>()
    private fun key(accountId: String?) = accountId?.trim()?.lowercase(Locale.ROOT).orEmpty()
    fun capture(accountId: String?): Long = generations[key(accountId)] ?: 0L
    fun isCurrent(accountId: String?, generation: Long): Boolean = capture(accountId) == generation
    fun invalidate(accountId: String) { generations.merge(key(accountId), 1L, Long::plus) }
}
