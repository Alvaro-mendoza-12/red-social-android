package conectate.com.presentation.videos

import conectate.com.data.model.VideoComment

data class VideoCommentThread(
    val topLevelComments: List<VideoComment>,
    val repliesByParentId: Map<String, List<VideoComment>>,
    val descendantCountByCommentId: Map<String, Int>
)

fun buildVideoCommentThread(comments: List<VideoComment>): VideoCommentThread {
    val topLevel = comments.filter { it.parentId.isNullOrBlank() }
    val replies = comments
        .filter { !it.parentId.isNullOrBlank() }
        .groupBy { it.parentId.orEmpty() }

    val commentsById = comments.associateBy { it.id }
    val descendantCount = comments.associate { comment ->
        comment.id to countDescendants(comment.id, replies, commentsById)
    }

    return VideoCommentThread(
        topLevelComments = topLevel,
        repliesByParentId = replies,
        descendantCountByCommentId = descendantCount
    )
}

fun findVideoCommentRootId(commentId: String, commentsById: Map<String, VideoComment>): String {
    var currentId = commentId
    var current = commentsById[currentId]
    while (current?.parentId != null) {
        currentId = current.parentId.orEmpty()
        current = commentsById[currentId]
    }
    return currentId
}

private fun countDescendants(
    commentId: String,
    repliesByParentId: Map<String, List<VideoComment>>,
    commentsById: Map<String, VideoComment>
): Int {
    val directReplies = repliesByParentId[commentId].orEmpty()
    if (directReplies.isEmpty()) return 0
    return directReplies.size + directReplies.sumOf { reply ->
        countDescendants(reply.id, repliesByParentId, commentsById)
    }
}
