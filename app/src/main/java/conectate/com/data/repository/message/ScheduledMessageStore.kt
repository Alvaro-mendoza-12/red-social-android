package conectate.com.data.repository.message

import android.content.SharedPreferences
import conectate.com.data.model.ScheduledMessageJob
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

data class ScheduledMessagesKey(val accountId: String, val scope: String, val targetId: String) {
    companion object {
        fun create(accountId: String?, scope: String, targetId: String): ScheduledMessagesKey? {
            fun clean(value: String?) = value?.trim()?.lowercase(Locale.US).orEmpty()
            val account = clean(accountId)
            val target = clean(targetId)
            val type = clean(scope)
            if (account.isBlank() || target.isBlank() || type !in setOf("dm", "group", "community")) return null
            return ScheduledMessagesKey(account, type, target)
        }
    }
}

data class ScheduledMessagesSnapshot(
    val jobs: List<ScheduledMessageJob> = emptyList(),
    val hasRemoteSnapshot: Boolean = false,
    val legacyJobsNeedOwner: Boolean = false,
)

/** Account-bound pending jobs and read cache. Unowned legacy rows remain untouched, never replayed. */
internal class ScheduledMessageStore(private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val revisions = mutableMapOf<String, Long>()

    @Synchronized
    fun revision(account: String): Long = revisions[account] ?: 0L

    @Synchronized
    fun snapshot(key: ScheduledMessagesKey): ScheduledMessagesSnapshot {
        val remote = read(prefs.getString(cacheKey(key), null))
        val local = pending(key.accountId).filter { it.matches(key) }
        return ScheduledMessagesSnapshot(
            jobs = ((remote.orEmpty() + local).filter { it.matches(key) && it.isActive() })
                .distinctBy { it.id }.sortedBy { parseTime(it.scheduled_for) },
            hasRemoteSnapshot = remote != null,
            legacyJobsNeedOwner = allLocal().any { it.owner_id.isNullOrBlank() },
        )
    }

    @Synchronized
    fun replaceRemote(key: ScheduledMessagesKey, jobs: List<ScheduledMessageJob>, expectedRevision: Long) {
        // A response started before a cancellation/creation must not resurrect the old list.
        if (revision(key.accountId) != expectedRevision) return
        val safe = jobs.filter { it.matches(key) && it.isActive() }
        prefs.edit().putString(cacheKey(key), json.encodeToString(safe)).apply()
    }

    @Synchronized
    fun pending(account: String): List<ScheduledMessageJob> = allLocal().filter { it.owner_id == account }

    @Synchronized
    fun putLocal(account: String, job: ScheduledMessageJob) {
        require(account.isNotBlank())
        val updated = allLocal().filterNot { it.owner_id == account && it.id == job.id } + job.copy(owner_id = account)
        check(prefs.edit().putString(LOCAL_KEY, json.encodeToString(updated)).commit()) {
            "No se pudo guardar el mensaje programado en este dispositivo."
        }
        changed(account)
    }

    @Synchronized
    fun rememberRemote(key: ScheduledMessagesKey, job: ScheduledMessageJob) {
        val old = read(prefs.getString(cacheKey(key), null)).orEmpty()
        val merged = (old.filterNot { it.id == job.id } + job).filter { it.matches(key) && it.isActive() }
        prefs.edit().putString(cacheKey(key), json.encodeToString(merged)).apply()
        changed(key.accountId)
    }

    @Synchronized
    fun remove(account: String, id: String) {
        val editor = prefs.edit().putString(LOCAL_KEY, json.encodeToString(
            allLocal().filterNot { it.owner_id == account && it.id == id }
        ))
        prefs.all.keys.filter { it.startsWith("$CACHE_PREFIX$account:") }.forEach { key ->
            val cached = read(prefs.getString(key, null)) ?: return@forEach
            editor.putString(key, json.encodeToString(cached.filterNot { it.id == id }))
        }
        check(editor.commit()) { "No se pudo guardar la cancelación en este dispositivo." }
        changed(account)
    }

    private fun allLocal(): List<ScheduledMessageJob> {
        val raw = prefs.getString(LOCAL_KEY, null) ?: return emptyList()
        // Never overwrite corrupt pending data with an empty list during an unrelated mutation.
        return json.decodeFromString(raw)
    }

    private fun read(raw: String?): List<ScheduledMessageJob>? = raw?.let {
        runCatching { json.decodeFromString<List<ScheduledMessageJob>>(it) }.getOrNull()
    }

    private fun changed(account: String) { revisions[account] = revision(account) + 1 }
    private fun cacheKey(key: ScheduledMessagesKey) = "$CACHE_PREFIX${key.accountId}:${key.scope}:${key.targetId}"
    private fun ScheduledMessageJob.matches(key: ScheduledMessagesKey) =
        scope_type.trim().lowercase(Locale.US) == key.scope && target_id.trim().lowercase(Locale.US) == key.targetId &&
            (owner_id == null || owner_id.trim().lowercase(Locale.US) == key.accountId)
    private fun ScheduledMessageJob.isActive() = status in setOf("pending", "processing", "failed")
    private fun parseTime(value: String) = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

    companion object {
        const val LOCAL_KEY = "scheduled_local_json"
        private const val CACHE_PREFIX = "scheduled_remote_v1:"
    }
}
