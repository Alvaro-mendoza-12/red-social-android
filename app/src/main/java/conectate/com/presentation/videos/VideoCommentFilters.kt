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
    val byId = comments.associateBy { it.id.trim().lowercase() }
    val roots = linkedMapOf<String, VideoComment>()
    val replies = mutableMapOf<String, MutableList<VideoComment>>()

    fun rootFor(comment: VideoComment): VideoComment {
        var cursor = comment
        val visited = mutableSetOf<String>()
        while (true) {
            val cursorId = cursor.id.trim().lowercase()
            if (!visited.add(cursorId)) return comment
            val parentId = cursor.parentId?.trim()?.lowercase()
                ?.takeUnless { it.isBlank() || it == "0" || it == "null" || it == "undefined" || it == "none" }
                ?: return cursor
            cursor = byId[parentId] ?: return comment
        }
    }

    comments.forEach { comment ->
        val root = rootFor(comment)
        val rootId = root.id.trim().lowercase()
        roots[rootId] = root
        if (!comment.id.equals(root.id, ignoreCase = true)) {
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
