package conectate.com.presentation.profile

import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.data.model.Comment
import conectate.com.data.model.Post
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.presentation.components.CommentsSheet
import conectate.com.presentation.posts.PostDetailViewModel

/** Profile and publication detail use the same authenticated comment operations. */
@Composable
internal fun ProfilePostComments(
    post: Post,
    viewerId: String?,
    onDismiss: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onReport: (Comment) -> Unit,
    onCountChanged: (Int) -> Unit,
) {
    val viewModel: PostDetailViewModel = hiltViewModel(key = "profile-comments-$viewerId")
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sending by viewModel.commentPosting.collectAsStateWithLifecycle()
    val editing by viewModel.commentEditing.collectAsStateWithLifecycle()
    LaunchedEffect(post.id, viewerId) { viewModel.loadPost(post.id) }
    DisposableEffect(viewModel, post.id, viewerId) {
        onDispose { viewModel.closePost(post.id) }
    }
    val current = state.post?.takeIf { it.id == post.id }
    LaunchedEffect(current?.id, current?.commentCount) { current?.let { onCountChanged(it.commentCount) } }
    CommentsSheet(
        comments = if (current != null) state.comments else emptyList(),
        onSendComment = { text, parent, onSent -> viewModel.addComment(post.id, text, parent, onSent) },
        onDeleteComment = viewModel::deleteComment,
        onEditComment = { comment, text, onSaved -> viewModel.editComment(comment.id, text, onSaved) },
        onReactComment = viewModel::toggleCommentReaction,
        onMentionClick = { handle ->
            viewModel.resolvePublicMention(handle) { id ->
                if (id != null) onOpenProfile(id) else AppFeedbackBus.showError("No se pudo identificar esta mención.")
            }
        },
        onReportComment = onReport,
        onNavigateToProfile = onOpenProfile,
        onRetry = { viewModel.loadPost(post.id) },
        onDismiss = onDismiss,
        isLoading = state.isLoadingPost || state.isLoadingComments && state.error == null,
        isSending = sending,
        isEditing = editing,
        error = state.error ?: state.commentsError,
        isPostOwner = post.userId.equals(viewerId, ignoreCase = true),
        showReportAction = true,
    )
}
