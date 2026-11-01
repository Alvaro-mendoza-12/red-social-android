package conectate.com.presentation.components

import androidx.compose.runtime.Composable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import conectate.com.data.model.Comment
import conectate.com.presentation.posts.POST_COMMENT_PAGE_SIZE
import conectate.com.presentation.posts.PostCommentDraftRevision
import conectate.com.presentation.posts.postCommentWindow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(
    comments: List<Comment>,
    onSendComment: (String, String?, () -> Unit) -> Unit,
    onDeleteComment: (String) -> Unit,
    onReportComment: (Comment) -> Unit,
    onEditComment: (Comment, String, () -> Unit) -> Unit,
    onReactComment: (Comment, String) -> Unit,
    onMentionClick: (String) -> Unit,
    onNavigateToProfile: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean = false,
    isSending: Boolean = false,
    isEditing: Boolean = false,
    error: String? = null,
    isPostOwner: Boolean = false,
    showReportAction: Boolean = false
) {
    var commentText by rememberSaveable { mutableStateOf("") }
    val draftRevision = remember { PostCommentDraftRevision() }
    var visibleLimit by rememberSaveable { mutableIntStateOf(POST_COMMENT_PAGE_SIZE) }
    var replyingTo by remember { mutableStateOf<Comment?>(null) }
    var editing by remember { mutableStateOf<Comment?>(null) }

    editing?.let { comment ->
        CommentEditDialog(comment, isEditing, onDismiss = { editing = null },
            onSave = { text -> onEditComment(comment, text) { editing = null } })
    }
    
    val window = remember(comments, visibleLimit) { postCommentWindow(comments, visibleLimit) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Header
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Comentarios",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
                
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Cerrar")
                }
            }
            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
        }

        // Comments List
        if (error != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry, enabled = !isLoading) { Text("Reintentar") }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            if (comments.isEmpty() && isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (comments.isEmpty() && error == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Sé el primero en comentar", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(window.rows, key = { it.first.id }) { (comment, level) ->
                        CommentItem(
                            comment = comment,
                            level = level,
                            isPostOwner = isPostOwner,
                            onReplyClick = { draftRevision.changed(); replyingTo = comment },
                            onDeleteClick = { onDeleteComment(comment.id) },
                            onEditClick = { editing = comment },
                            onLikeClick = { onReactComment(comment, "LIKE") },
                            onDislikeClick = { onReactComment(comment, "DISLIKE") },
                            onMentionClick = onMentionClick,
                            onReportClick = { onReportComment(comment) },
                            showReportAction = showReportAction,
                            onProfileClick = { onNavigateToProfile(comment.userId) },
                        )
                    }
                    if (window.hasMore) item(key = "more-comments") {
                        TextButton(onClick = { visibleLimit = window.nextLimit }, modifier = Modifier.fillMaxWidth()) {
                            Text("Ver más comentarios")
                        }
                    }
                }
            }
        }
        
        // Input Area
        Column {
            replyingTo?.let { reply ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Respondiendo a ${reply.userName}",
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    IconButton(onClick = { draftRevision.changed(); replyingTo = null }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancelar respuesta")
                    }
                }
            }
            HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { draftRevision.changed(); commentText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Escribe un comentario...") },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent
                    ),
                    maxLines = 4
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                IconButton(
                    onClick = { 
                        if (commentText.isNotBlank()) {
                            val submittedText = commentText
                            val submittedParent = replyingTo?.id
                            val submittedRevision = draftRevision.snapshot()
                            onSendComment(submittedText, submittedParent) {
                                // A late acknowledgement must not erase a newer draft.
                                if (draftRevision.acknowledge(submittedRevision)) {
                                    commentText = ""
                                    replyingTo = null
                                }
                            }
                        }
                    },
                    enabled = commentText.isNotBlank() && !isSending,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    if (isSending) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Filled.Send, "Enviar")
                }
            }
        }
    }
}


