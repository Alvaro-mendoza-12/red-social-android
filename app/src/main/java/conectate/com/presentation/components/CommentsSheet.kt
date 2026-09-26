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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import conectate.com.data.model.Comment
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
    onSendComment: (String, String?) -> Unit, // Added parentId
    onDeleteComment: (String) -> Unit,
    onReportComment: (Comment) -> Unit = {},
    onNavigateToProfile: (String) -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean = false,
    isPostOwner: Boolean = false,
    showReportAction: Boolean = false
) {
    var commentText by remember { mutableStateOf("") }
    var replyingTo by remember { mutableStateOf<Comment?>(null) }
    
    // Group comments by parent
    val parentComments = remember(comments) { comments.filter { it.parentId == null } }
    val repliesMap = remember(comments) { comments.filter { it.parentId != null }.groupBy { it.parentId!! } }

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
        Box(modifier = Modifier.weight(1f)) {
            if (comments.isEmpty() && !isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Sé el primero en comentar", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    parentComments.forEach { parent ->
                        item(key = parent.id) {
                            CommentItem(
                                comment = parent,
                                isPostOwner = isPostOwner,
                                onReplyClick = { 
                                    replyingTo = parent
                                    commentText = "@${parent.userName ?: "Usuario"} "
                                },
                                onDeleteClick = { onDeleteComment(parent.id) },
                                onReportClick = { onReportComment(parent) },
                                showReportAction = showReportAction,
                                onProfileClick = { parent.userId.let { onNavigateToProfile(it) } }
                            )
                        }
                        
                        // Show replies
                        val replies = repliesMap[parent.id] ?: emptyList()
                        items(replies, key = { it.id }) { reply ->
                            Row(modifier = Modifier.padding(start = 32.dp, top = 8.dp)) {
                                CommentItem(
                                    comment = reply,
                                    isPostOwner = isPostOwner,
                                    level = 1,
                                    onReplyClick = {
                                        replyingTo = parent // Keep same thread
                                        commentText = "@${reply.userName ?: "Usuario"} "
                                    },
                                    onDeleteClick = { onDeleteComment(reply.id) },
                                    onReportClick = { onReportComment(reply) },
                                    showReportAction = showReportAction,
                                    onProfileClick = { reply.userId.let { onNavigateToProfile(it) } }
                                )
                            }
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
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    IconButton(onClick = { replyingTo = null }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
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
                    onValueChange = { commentText = it },
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
                            onSendComment(commentText, replyingTo?.id)
                            commentText = ""
                            replyingTo = null
                        }
                    },
                    enabled = commentText.isNotBlank(),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Enviar")
                }
            }
        }
    }
}


