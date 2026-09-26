package conectate.com.presentation.social

import conectate.com.data.model.Post

/** Refresh fields without moving posts the reader is already looking at. */
internal fun reconcileSocialFeed(
    current: List<Post>,
    incoming: List<Post>,
    preserveVisibleOrder: Boolean,
): List<Post> {
    val latest = incoming.distinctBy { it.id }
    if (!preserveVisibleOrder || current.isEmpty()) return latest
    val byId = latest.associateBy { it.id }
    val existingIds = current.map { it.id }.toSet()
    // Only retain rows still present in the server snapshot, including current
    // visibility/moderation decisions. Never resurrect a deleted cached post.
    return current.mapNotNull { byId[it.id] }.distinctBy { it.id } +
        latest.filterNot { it.id in existingIds }
}

internal fun socialPostIdFromListKey(key: Any?): String? =
    (key as? String)?.takeIf { it.startsWith("post:") }
        ?.removePrefix("post:")?.takeIf { it.isNotBlank() }
