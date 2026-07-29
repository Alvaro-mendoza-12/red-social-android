package conectate.com.presentation.videos

import conectate.com.data.model.VideoComment

internal fun mergeLatestVideoComments(
    current: List<VideoComment>,
    incoming: List<VideoComment>
): List<VideoComment> {
    val incomingIds = incoming
        .asSequence()
        .map { it.id.trim().lowercase() }
        .filter(String::isNotBlank)
        .toSet()
    return (incoming + current.filterNot { it.id.trim().lowercase() in incomingIds })
        .filter { it.id.isNotBlank() }
        .distinctBy { it.id.trim().lowercase() }
}

internal fun editVideoCommentImmediately(
    comments: List<VideoComment>,
    commentId: String,
    content: String
): List<VideoComment> = comments.map { comment ->
    if (comment.id.equals(commentId, ignoreCase = true)) {
        comment.copy(content = content.trim())
    } else {
        comment
    }
}

internal fun reactToVideoCommentImmediately(
    comments: List<VideoComment>,
    commentId: String,
    reaction: String?
): List<VideoComment> = comments.map { comment ->
    if (!comment.id.equals(commentId, ignoreCase = true)) {
        comment
    } else {
        val previousReaction = comment.myReaction?.trim()?.uppercase()?.takeIf(String::isNotBlank)
        val nextReaction = reaction?.trim()?.uppercase()?.takeIf(String::isNotBlank)
        val nextCounts = comment.reactions.toMutableMap()

        if (previousReaction != null) {
            val remaining = (nextCounts[previousReaction] ?: 0) - 1
            if (remaining > 0) nextCounts[previousReaction] = remaining
            else nextCounts.remove(previousReaction)
        }
        if (nextReaction != null) {
            nextCounts[nextReaction] = (nextCounts[nextReaction] ?: 0) + 1
        }

        comment.copy(
            reactions = nextCounts,
            myReaction = nextReaction
        )
    }
}

internal fun removeVideoCommentImmediately(
    comments: List<VideoComment>,
    commentId: String
): List<VideoComment> =
    comments.filterNot { it.id.equals(commentId, ignoreCase = true) }
