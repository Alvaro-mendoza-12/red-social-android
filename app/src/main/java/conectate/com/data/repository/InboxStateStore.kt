package conectate.com.data.repository

import android.content.SharedPreferences
import conectate.com.data.model.MultiAccountPendingDestination
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class InboxUnreadSection(val targetType: String) {
    DIRECT("chat"), GROUP("group"), COMMUNITY("community"),
}

internal data class InboxStateSnapshot(
    val accountId: String,
    val revision: Long,
    val cachedUnreadConversations: Int,
    val pendingConversationKeys: Set<String>,
    val latestPendingDestination: MultiAccountPendingDestination?,
)

/** Serializes this installation's hints. Network reads never run under this lock. */
internal class InboxStateStore(preferences: () -> SharedPreferences) {
    private val prefs by lazy(preferences)
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var nextRevision = 0L
    // Keep tombstones until the process ends, so pruning cannot revive an in-flight read.
    private val revisions = mutableMapOf<String, Long>()

    fun snapshot(accountId: String): InboxStateSnapshot = synchronized(lock) { read(accountId) }

    fun beginRefresh(accountId: String): InboxStateSnapshot = synchronized(lock) {
        // Two overlapping requests must not let the earlier one win just by finishing first.
        advance(accountId)
        read(accountId)
    }

    fun registerHint(accountId: String, destination: MultiAccountPendingDestination) = synchronized(lock) {
        val previous = read(accountId)
        val keys = previous.pendingConversationKeys + destination.conversationKey
        val latest = previous.latestPendingDestination?.takeIf {
            it.conversationKey in keys && it.receivedAt > destination.receivedAt
        } ?: destination
        advance(accountId)
        prefs.edit().putStringSet(pendingKey(accountId), keys)
            .putString(latestKey(accountId), json.encodeToString(latest)).apply()
    }

    fun acknowledge(accountId: String) = synchronized(lock) {
        // Even an empty acknowledgement invalidates a read that started before this visit.
        advance(accountId)
        prefs.edit().remove(pendingKey(accountId)).remove(latestKey(accountId)).apply()
    }

    fun persistActive(accountId: String, unreadConversations: Int) = synchronized(lock) {
        advance(accountId)
        prefs.edit().apply {
            putInt(cachedKey(accountId), unreadConversations.coerceAtLeast(0))
            if (unreadConversations <= 0) {
                remove(pendingKey(accountId))
                remove(latestKey(accountId))
            }
        }.apply()
    }

    fun redactLatest(accountId: String, kind: String, conversationId: String, messageId: String) = synchronized(lock) {
        // A deleted message can occur in a pending response even if another chat owns the
        // current preview (or there is no preview yet). Fence that response in every case.
        advance(accountId)
        val latest = read(accountId).latestPendingDestination ?: return@synchronized
        if (latest.normalizedTargetType != kind || latest.normalizedConversationId != conversationId ||
            latest.focusMessageId?.trim()?.lowercase(Locale.ROOT) != messageId) return@synchronized
        prefs.edit().putString(latestKey(accountId), json.encodeToString(latest.copy(preview = "Mensaje eliminado"))).apply()
    }

    fun applyRefresh(
        expected: InboxStateSnapshot,
        successfulSections: Map<InboxUnreadSection, InactiveAccountUnreadSnapshot>,
    ): Boolean = synchronized(lock) {
        if (successfulSections.isEmpty() || read(expected.accountId).revision != expected.revision) return@synchronized false
        val merged = mergeInactiveUnreadRefresh(expected, successfulSections)
        advance(expected.accountId)
        prefs.edit().apply {
            putInt(cachedKey(expected.accountId), merged.unreadConversationCount)
            putStringSet(pendingKey(expected.accountId), merged.pendingConversationKeys)
            val latest = merged.latestPendingDestination
            if (latest == null) remove(latestKey(expected.accountId))
            else putString(latestKey(expected.accountId), json.encodeToString(latest))
        }.apply()
        true
    }

    fun prune(validAccountIds: Set<String>) = synchronized(lock) {
        val staleKeys = prefs.all.keys.filter { key -> accountIdForKey(key)?.let { it !in validAccountIds } == true }
        val removedAccounts = (revisions.keys + staleKeys.mapNotNull(::accountIdForKey)) - validAccountIds
        removedAccounts.forEach(::advance)
        if (staleKeys.isNotEmpty()) prefs.edit().apply { staleKeys.forEach(::remove) }.apply()
    }

    private fun read(accountId: String): InboxStateSnapshot = InboxStateSnapshot(
        accountId = accountId,
        revision = revisions.getOrPut(accountId) { ++nextRevision },
        cachedUnreadConversations = prefs.getInt(cachedKey(accountId), 0).coerceAtLeast(0),
        pendingConversationKeys = prefs.getStringSet(pendingKey(accountId), emptySet()).orEmpty()
            .mapNotNull { it.trim().lowercase(Locale.ROOT).takeIf(String::isNotBlank) }.toSet(),
        latestPendingDestination = prefs.getString(latestKey(accountId), null)?.let { raw ->
            runCatching { json.decodeFromString<MultiAccountPendingDestination>(raw) }.getOrNull()
        },
    )

    private fun advance(accountId: String) { revisions[accountId] = ++nextRevision }
    private fun cachedKey(id: String) = CACHED_PREFIX + id
    private fun pendingKey(id: String) = PENDING_PREFIX + id
    private fun latestKey(id: String) = LATEST_PREFIX + id
    private fun accountIdForKey(key: String): String? = PREFIXES.firstOrNull(key::startsWith)?.let(key::removePrefix)

    companion object {
        // Preserve the existing on-device contract; no migration or cache wipe is needed.
        private const val CACHED_PREFIX = "cached_direct_unread_"
        private const val PENDING_PREFIX = "pending_conversation_"
        private const val LATEST_PREFIX = "latest_destination_"
        private val PREFIXES = listOf(CACHED_PREFIX, PENDING_PREFIX, LATEST_PREFIX)
    }
}

/** Failed sections remain unknown, rather than being interpreted as an empty inbox. */
internal fun mergeInactiveUnreadRefresh(
    previous: InboxStateSnapshot,
    successfulSections: Map<InboxUnreadSection, InactiveAccountUnreadSnapshot>,
): InactiveAccountUnreadSnapshot {
    val refreshedTypes = successfulSections.keys.map { it.targetType }.toSet()
    val confirmed = successfulSections.map { (section, result) ->
        val keys = result.pendingConversationKeys.map { it.trim().lowercase(Locale.ROOT) }.filter {
            it.startsWith("${section.targetType}:") && it.substringAfter(':').isNotBlank()
        }.toSet()
        result.copy(pendingConversationKeys = keys,
            latestPendingDestination = result.latestPendingDestination?.takeIf { it.conversationKey in keys })
    }
    val retained = previous.pendingConversationKeys.filterTo(linkedSetOf()) { it.substringBefore(':') !in refreshedTypes }
    val keys = retained + confirmed.flatMap { it.pendingConversationKeys }
    val priorLatest = previous.latestPendingDestination?.takeIf { it.conversationKey in keys }
    val merged = mergeInactiveUnreadSnapshots(confirmed + InactiveAccountUnreadSnapshot(0, retained, priorLatest))
    return merged.copy(
        // Historical active counts have no per-section breakdown. A partial response cannot
        // prove that their missing conversations were read; a complete response can replace it.
        unreadConversationCount = if (successfulSections.size == InboxUnreadSection.entries.size) keys.size
            else maxOf(previous.cachedUnreadConversations, keys.size),
        latestPendingDestination = merged.latestPendingDestination?.takeIf { it.conversationKey in keys },
    )
}
