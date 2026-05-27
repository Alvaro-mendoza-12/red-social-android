package conectate.com.presentation.videos

import conectate.com.data.model.VideoComment

data class VideoCommentThread(
    val topLevelComments: List<VideoComment>,
    val repliesByParentId: Map<String, List<VideoComment>>,
    val descendantCountByCommentId: Map<String, Int>
)

fun buildVideoCommentThread(comments: List<VideoComment>): VideoCommentThread {
    val commentsById = comments.associateBy { it.id }
    val repliesByParentId = comments
        .filter { !it.parentId.isNullOrBlank() && commentsById.containsKey(it.parentId) }
        .groupBy { it.parentId.orEmpty() }
        .mapValues { (_, replies) -> replies.sortedBy { it.created_at } }

    val topLevel = comments
        .filter { it.parentId.isNullOrBlank() || !commentsById.containsKey(it.parentId) }
        .sortedBy { it.created_at }

    val descendantCounts = mutableMapOf<String, Int>()
    fun countDescendants(commentId: String): Int {
        return descendantCounts.getOrPut(commentId) {
            repliesByParentId[commentId].orEmpty().sumOf { reply ->
                1 + countDescendants(reply.id)
            }
        }
    }

    comments.forEach { countDescendants(it.id) }

    return VideoCommentThread(
        topLevelComments = topLevel,
        repliesByParentId = repliesByParentId,
        descendantCountByCommentId = descendantCounts
    )
}

fun findVideoCommentRootId(
    commentId: String,
    commentsById: Map<String, VideoComment>
): String {
    var currentId = commentId
    val visited = mutableSetOf<String>()

    while (visited.add(currentId)) {
        val current = commentsById[currentId] ?: return currentId
        val parentId = current.parentId?.takeIf { it.isNotBlank() } ?: return currentId
        if (!commentsById.containsKey(parentId)) return currentId
        currentId = parentId
    }

    return currentId
}
