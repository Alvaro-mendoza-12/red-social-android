package conectate.com.presentation.videos

import conectate.com.data.model.VideoComment
import java.time.Instant
import java.time.OffsetDateTime

enum class VideoCommentOrder(val label: String) {
    RECENT("Recientes"),
    RELEVANT("Relevantes"),
    CREATOR_REPLIES("Respondidos por el creador"),
}

enum class VideoCommentAuthorFilter(val label: String) {
    ALL("Todos"),
    MINE("Mis comentarios"),
    CREATOR("Del creador"),
}

data class VisibleVideoCommentThread(
    val root: VideoComment,
    val replies: List<VideoComment>,
)

/** Keeps a parent visible when a matching reply needs its conversation context. */
fun visibleVideoCommentThreads(
    comments: List<VideoComment>,
    order: VideoCommentOrder,
    authorFilter: VideoCommentAuthorFilter,
    currentUserId: String?,
    creatorId: String?,
): List<VisibleVideoCommentThread> {
    val byId = linkedMapOf<String, VideoComment>()
    for (comment in comments) {
        val id = comment.id.trim().lowercase()
        if (id.isNotBlank()) byId.putIfAbsent(id, comment)
    }
    val roots = linkedMapOf<String, VideoComment>()
    val replies = mutableMapOf<String, MutableList<VideoComment>>()
    val resolvedRoots = mutableMapOf<String, String>()

    fun rootFor(commentId: String): String {
        resolvedRoots[commentId]?.let { return it }
        var current = commentId
        val path = linkedSetOf<String>()
        while (current !in resolvedRoots && path.add(current)) {
            val parentId = byId.getValue(current).parentId?.trim()?.lowercase()
                ?.takeUnless { it.isBlank() || it == "0" || it == "null" || it == "undefined" || it == "none" }
            if (parentId == null || parentId !in byId) {
                // A partial page still shows the oldest available comment in this chain.
                resolvedRoots[current] = current
                break
            }
            current = parentId
        }
        // For a cycle, choose the same root regardless of which reply led to it.
        val root = resolvedRoots[current] ?: path.asSequence().dropWhile { it != current }.minOrNull() ?: current
        for (id in path) resolvedRoots[id] = root
        return root
    }

    for ((id, comment) in byId) {
        val rootId = rootFor(id)
        roots.getOrPut(rootId) { byId.getValue(rootId) }
        if (id != rootId) {
            replies.getOrPut(rootId) { mutableListOf() }.add(comment)
        }
    }

    val selectedAuthor = when (authorFilter) {
        VideoCommentAuthorFilter.ALL -> null
        VideoCommentAuthorFilter.MINE -> currentUserId?.trim()?.lowercase()
        VideoCommentAuthorFilter.CREATOR -> creatorId?.trim()?.lowercase()
    }
    if (authorFilter != VideoCommentAuthorFilter.ALL && selectedAuthor.isNullOrBlank()) return emptyList()

    return roots.mapNotNull { (rootId, root) ->
        val allReplies = replies[rootId].orEmpty()
        val creatorReplies = allReplies.filter { it.userId.equals(creatorId, ignoreCase = true) }
        if (order == VideoCommentOrder.CREATOR_REPLIES && creatorReplies.isEmpty()) return@mapNotNull null
        val rootMatches = selectedAuthor == null || root.userId.equals(selectedAuthor, ignoreCase = true)
        val matchingReplies = if (selectedAuthor == null) allReplies else allReplies.filter {
            it.userId.equals(selectedAuthor, ignoreCase = true)
        }
        if (!rootMatches && matchingReplies.isEmpty()) return@mapNotNull null
        VisibleVideoCommentThread(
            root = root,
            replies = (if (rootMatches) allReplies else matchingReplies)
                .sortedWith(compareBy<VideoComment> { commentTime(it.created_at) }.thenBy { it.id }),
        )
    }.sortedWith(
        compareByDescending<VisibleVideoCommentThread> { thread ->
            val allReplies = replies[thread.root.id.trim().lowercase()].orEmpty()
            when (order) {
                VideoCommentOrder.RECENT -> commentTime(thread.root.created_at)
                VideoCommentOrder.RELEVANT ->
                    (thread.root.likes + thread.root.reactions.values.sum() + allReplies.size * 2).toLong()
                VideoCommentOrder.CREATOR_REPLIES -> allReplies
                    .filter { it.userId.equals(creatorId, ignoreCase = true) }
                    .maxOfOrNull { commentTime(it.created_at) } ?: 0L
            }
        }.thenByDescending { commentTime(it.root.created_at) }.thenBy { it.root.id }
    )
}

private fun commentTime(value: String): Long = runCatching { Instant.parse(value).toEpochMilli() }
    .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
    .getOrDefault(0L)
