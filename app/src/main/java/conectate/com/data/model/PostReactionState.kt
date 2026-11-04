package conectate.com.data.model

/** Apply one account's desired reaction without changing the rest of the post. */
internal fun Post.withPostReaction(selected: String?): Post {
    val previous = myReaction ?: if (likedByMe) "LIKE" else null
    val counts = reactionCounts.toMutableMap()
    if (counts.isEmpty() && likeCount > 0) counts["LIKE"] = likeCount
    previous?.let { counts[it] = ((counts[it] ?: 0) - 1).coerceAtLeast(0) }
    selected?.let { counts[it] = (counts[it] ?: 0) + 1 }
    return copy(myReaction = selected, likedByMe = selected != null,
        reactionCounts = counts.filterValues { it > 0 }, likeCount = counts.values.sum())
}
