package conectate.com.data.localfallback

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class CollectionHistoryAccess(
    val isMember: Boolean = false,
    val allHistory: Boolean = false,
    val joinedAt: String? = null,
    val historyFrom: String? = null,
    val historyUntil: String? = null,
    val canReadHistory: Boolean = false,
    val retainedRanges: List<CollectionHistoryRange> = emptyList()
) {
    fun allows(createdAt: String): Boolean {
        if (!isMember && !canReadHistory) return false
        val at = instant(createdAt) ?: return false
        if (retainedRanges.any { range ->
                val until = instant(range.until)
                val from = instant(range.from)
                until != null && !at.isAfter(until) && (range.from == null || (from != null && !at.isBefore(from)))
            }) return true
        if (!isMember) return false
        if (allHistory) return true
        val joined = instant(joinedAt) ?: return false
        if (!at.isBefore(joined)) return true
        val from = instant(historyFrom) ?: return false
        val until = instant(historyUntil) ?: return false
        return !at.isBefore(from) && !at.isAfter(until)
    }

    private fun instant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }
}

@Serializable
data class CollectionHistoryRange(val from: String? = null, val until: String)

@Serializable
data class CollectionMembershipPolicy(
    val membersCanAdd: Boolean = true,
    val canAddMembers: Boolean = false,
    val canManagePolicy: Boolean = false,
    val historyAccess: CollectionHistoryAccess = CollectionHistoryAccess()
)
