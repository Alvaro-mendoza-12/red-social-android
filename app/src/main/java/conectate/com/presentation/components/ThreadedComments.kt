package conectate.com.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import conectate.com.data.model.Comment

data class CommentThreadData(
    val topLevelComments: List<Comment>,
    val repliesByParentId: Map<String, List<Comment>>,
    val commentsById: Map<String, Comment>,
    val descendantCountByCommentId: Map<String, Int>
)

fun buildCommentThreadData(comments: List<Comment>): CommentThreadData {
    val commentsById = comments.associateBy { it.id }
    val topLevelComments = comments.filter { it.parentId.isNullOrBlank() }
    val repliesByParentId = comments
        .mapNotNull { comment ->
            val parentId = comment.parentId?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            parentId to comment
        }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })

    val descendantCountByCommentId = mutableMapOf<String, Int>()
    fun countDescendants(commentId: String): Int {
        return descendantCountByCommentId.getOrPut(commentId) {
            repliesByParentId[commentId].orEmpty().sumOf { reply ->
                1 + countDescendants(reply.id)
            }
        }
    }

    topLevelComments.forEach { topLevel ->
        countDescendants(topLevel.id)
    }

    return CommentThreadData(
        topLevelComments = topLevelComments,
        repliesByParentId = repliesByParentId,
        commentsById = commentsById,
        descendantCountByCommentId = descendantCountByCommentId
    )
}

fun collectExpandedCommentAncestors(
    focusCommentId: String?,
    commentsById: Map<String, Comment>
): Set<String> {
    if (focusCommentId.isNullOrBlank()) return emptySet()
    val expandedIds = mutableSetOf<String>()
    var cursor = commentsById[focusCommentId]
    while (cursor?.parentId != null) {
        val parent = commentsById[cursor.parentId]
        if (parent != null) {
            expandedIds += parent.id
        }
        cursor = parent
    }
    return expandedIds
}

@Composable
fun ThreadedCommentItem(
    comment: Comment,
    repliesByParentId: Map<String, List<Comment>>,
    commentsById: Map<String, Comment>,
    descendantCountByCommentId: Map<String, Int>,
    isPostOwner: Boolean,
    onReplyClick: (Comment) -> Unit,
    onDeleteClick: (Comment) -> Unit,
    onProfileClick: (Comment) -> Unit,
    onEditClick: (Comment) -> Unit,
    onMentionClick: (String) -> Unit,
    onLikeClick: (Comment) -> Unit,
    onDislikeClick: (Comment) -> Unit,
    depth: Int = 0,
    initiallyExpanded: Boolean = false
) {
    var expanded by rememberSaveable(comment.id) { mutableStateOf(initiallyExpanded) }
    val directReplies = repliesByParentId[comment.id].orEmpty()
    val descendantCount = descendantCountByCommentId[comment.id] ?: 0
    val replyTargetName = remember(comment.parentId, commentsById) {
        comment.parentId
            ?.let { parentId -> commentsById[parentId] }
            ?.userName
            ?.takeIf { it.isNotBlank() }
    }

    Column {
        CommentItem(
            comment = comment,
            level = depth,
            isPostOwner = isPostOwner,
            replyingToUserName = replyTargetName,
            onReplyClick = { onReplyClick(comment) },
            onDeleteClick = { onDeleteClick(comment) },
            onProfileClick = { onProfileClick(comment) },
            onEditClick = { onEditClick(comment) },
            onMentionClick = onMentionClick,
            onLikeClick = { onLikeClick(comment) },
            onDislikeClick = { onDislikeClick(comment) },
        )

        if (descendantCount > 0) {
            Row(
                modifier = Modifier
                    .padding(start = (52 + (depth.coerceAtMost(3) * 16)).dp, top = 2.dp)
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (expanded) {
                        "Ocultar respuestas"
                    } else {
                        "Ver $descendantCount ${if (descendantCount == 1) "respuesta" else "respuestas"}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(top = 6.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
            ) {
                directReplies.forEach { reply ->
                    ThreadedCommentItem(
                        comment = reply,
                        repliesByParentId = repliesByParentId,
                        commentsById = commentsById,
                        descendantCountByCommentId = descendantCountByCommentId,
                        isPostOwner = isPostOwner,
                        onReplyClick = onReplyClick,
                        onDeleteClick = onDeleteClick,
                        onProfileClick = onProfileClick,
                        onEditClick = onEditClick,
                        onMentionClick = onMentionClick,
                        onLikeClick = onLikeClick,
                        onDislikeClick = onDislikeClick,
                        depth = depth + 1,
                        initiallyExpanded = true
                    )
                }
            }
        }
    }
}
