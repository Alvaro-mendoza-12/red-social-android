package conectate.com.data.cache

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-local TTL cache. The historical class name is kept to avoid changing
 * repository contracts, but no remote credential or user data leaves the app.
 */
@Singleton
class UpstashCacheService @Inject constructor() {
    private data class Entry(
        val value: String,
        val expiresAtMs: Long
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun get(key: String): String? {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) return null
        val entry = entries[normalizedKey] ?: return null
        if (entry.expiresAtMs <= System.currentTimeMillis()) {
            entries.remove(normalizedKey, entry)
            return null
        }
        return entry.value
    }

    suspend fun set(key: String, value: String, exSeconds: Int = DEFAULT_TTL_SECONDS): Boolean {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) return false
        removeExpiredEntries()
        if (entries.size >= MAX_ENTRIES && !entries.containsKey(normalizedKey)) {
            entries.entries.minByOrNull { it.value.expiresAtMs }?.let { oldest ->
                entries.remove(oldest.key, oldest.value)
            }
        }
        val ttlMs = exSeconds.coerceIn(1, MAX_TTL_SECONDS) * 1_000L
        entries[normalizedKey] = Entry(value, System.currentTimeMillis() + ttlMs)
        return true
    }

    suspend fun delete(key: String): Boolean {
        val normalizedKey = key.trim()
        return normalizedKey.isNotEmpty() && entries.remove(normalizedKey) != null
    }

    suspend fun increment(key: String, amount: Int = 1): Long? {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) return null
        return synchronized(entries) {
            val now = System.currentTimeMillis()
            val current = entries[normalizedKey]
                ?.takeIf { it.expiresAtMs > now }
                ?.value
                ?.toLongOrNull()
                ?: 0L
            val updated = runCatching { Math.addExact(current, amount.toLong()) }.getOrNull()
                ?: return@synchronized null
            entries[normalizedKey] = Entry(updated.toString(), now + DEFAULT_TTL_SECONDS * 1_000L)
            updated
        }
    }

    private fun removeExpiredEntries() {
        val now = System.currentTimeMillis()
        entries.entries.removeIf { it.value.expiresAtMs <= now }
    }

    private companion object {
        const val DEFAULT_TTL_SECONDS = 300
        const val MAX_TTL_SECONDS = 86_400
        const val MAX_ENTRIES = 256
    }
}
