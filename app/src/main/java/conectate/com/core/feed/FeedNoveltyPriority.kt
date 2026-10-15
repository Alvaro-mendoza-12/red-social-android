package conectate.com.core.feed

/** History affects priority, never public eligibility or permanent availability. */
internal fun <T> prioritizeFeedCandidates(
    items: Iterable<T>, seen: Set<String>, previousFirstId: String? = null,
    headBandSize: Int = Int.MAX_VALUE, recentIds: List<String> = emptyList(), id: (T) -> String,
): List<T> {
    val unique = items.distinctBy { canonicalFeedId(id(it)) }
        .filter { canonicalFeedId(id(it)) != null }
    val (fresh, previous) = unique.partition { canonicalFeedId(id(it)) !in seen }
    // Keep recently viewed items at the end, without permanently excluding
    // them or changing server eligibility. Leave at least one replayable item.
    val cooldownSize = minOf(6, maxOf(1, unique.size / 2), maxOf(0, previous.size - 1))
    val previousKeys = previous.mapNotNull { canonicalFeedId(id(it)) }.toSet()
    val coolingKeys = recentIds.mapNotNull(::canonicalFeedId).distinct()
        .filter { it in previousKeys }.take(cooldownSize).toSet()
    val (cooling, older) = previous.partition { canonicalFeedId(id(it)) in coolingKeys }
    val ordered = fresh + older + cooling
    if (ordered.size < 2 || canonicalFeedId(id(ordered.first())) != canonicalFeedId(previousFirstId)) return ordered
    // Swap only within the same novelty/relevance band. One unseen item stays
    // ahead of previously seen items even when it was the previous first item.
    val samePriority = if (fresh.isNotEmpty()) fresh.size else older.size
    if (minOf(samePriority,headBandSize) < 2) return ordered
    return listOf(ordered[1],ordered[0]) + ordered.drop(2)
}
