package conectate.com.presentation.posts

import conectate.com.data.model.Comment

internal const val POST_COMMENT_PAGE_SIZE = 50

internal data class PostCommentWindow(
    val rows: List<Pair<Comment, Int>>,
    val hasMore: Boolean,
    val nextLimit: Int,
)

/** Page the complete server snapshot without losing children or a notification target. */
internal fun postCommentWindow(
    comments: List<Comment>,
    visibleLimit: Int,
    focusCommentId: String? = null,
): PostCommentWindow {
    val ordered = organizeThreaded(comments)
    val focusIndex = if (focusCommentId.isNullOrBlank()) -1 else
        ordered.indexOfFirst { it.first.id.equals(focusCommentId, ignoreCase = true) }
    val count = maxOf(visibleLimit.coerceAtLeast(1), focusIndex + 1).coerceAtMost(ordered.size)
    return PostCommentWindow(
        rows = ordered.take(count),
        hasMore = count < ordered.size,
        nextLimit = (count.toLong() + POST_COMMENT_PAGE_SIZE).coerceAtMost(ordered.size.toLong()).toInt(),
    )
}
