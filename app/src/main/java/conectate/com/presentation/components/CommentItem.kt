package conectate.com.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import conectate.com.data.model.Comment

@Composable
fun CommentItem(
    comment: Comment, 
    isPostOwner: Boolean = false,
    level: Int = 0,
    replyingToUserName: String? = null,
    onReplyClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onProfileClick: () -> Unit = {}
) {
    val isReply = level > 0
    Row(modifier = Modifier.fillMaxWidth().padding(start = (level * 24 + 16).dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        CircularProfileImage(
            imageUrl = comment.userAvatar ?: "",
            size = if (isReply) 32.dp else 40.dp
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Column {
            Box(
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column {
                    Text(
                        text = comment.userName ?: "Usuario",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onProfileClick() }
                    )
                    
                    val annotatedContent = remember(comment.content) {
                        buildAnnotatedString {
                            val words = comment.content.split(" ")
                            words.forEachIndexed { index, word ->
                                if (word.startsWith("@") && word.length > 1) {
                                    pushStringAnnotation(tag = "MENTION", annotation = word)
                                    withStyle(style = SpanStyle(color = Color(0xFF2196F3), fontWeight = FontWeight.Bold)) {
                                        append(word)
                                    }
                                    pop()
                                } else {
                                    append(word)
                                }
                                if (index < words.size - 1) append(" ")
                            }
                        }
                    }

                    if (comment.audioUrl.isNullOrEmpty() || comment.content != "[Audio]") {
                        ClickableText(
                            text = annotatedContent,
                            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            onClick = { offset ->
                                annotatedContent.getStringAnnotations(tag = "MENTION", start = offset, end = offset)
                                    .firstOrNull()?.let {
                                        onProfileClick()
                                    }
                            }
                        )
                    }
                    
                    // Audio Player for Comments
                    if (!comment.audioUrl.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        CommentAudioPlayer(
                            audioUrl = comment.audioUrl,
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                        )
                    }
                }
            }
            
            Row(
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conectate.com.util.DateUtils.formatRelativeDate(comment.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Responder",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray,
                    modifier = Modifier.clickable { onReplyClick() }
                )
                
                if (comment.isMine || isPostOwner) {
                    Spacer(modifier = Modifier.width(16.dp))
                    IconButton(onClick = onDeleteClick, modifier = Modifier.size(18.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete, 
                            contentDescription = "Eliminar",
                            tint = Color.Gray,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}


