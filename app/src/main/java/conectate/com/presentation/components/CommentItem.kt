package conectate.com.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    onEditClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    onMentionClick: (String) -> Unit = {},
    onLikeClick: () -> Unit = {},
    onDislikeClick: () -> Unit = {},
    onReportClick: () -> Unit = {},
    showReportAction: Boolean = false
) {
    val isReply = level > 0
    val canEdit = remember(comment.createdAt) {
        runCatching {
            val created = java.time.OffsetDateTime.parse(comment.createdAt).toInstant()
            java.time.Duration.between(created, java.time.Instant.now()).toHours() < 5
        }.getOrDefault(false)
    }
    Row(modifier = Modifier.fillMaxWidth().padding(start = (level * 24 + 16).dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        CircularProfileImage(ownerId = comment.userId, 
            imageUrl = comment.userAvatar ?: "",
            size = if (isReply) 32.dp else 40.dp,
            fallbackName = comment.userName
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
                    
                    if (comment.mediaType != "sticker" && (comment.audioUrl.isNullOrEmpty() || comment.content != "[Audio]")) {
                        MentionText(comment.content, onMentionClick)
                    }
                    
                    // Audio Player for Comments
                    if (comment.mediaType in setOf("sticker", "image", "gif") && !comment.audioUrl.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        coil.compose.AsyncImage(
                            model = comment.audioUrl,
                            contentDescription = if (comment.mediaType == "sticker") "Sticker" else "Imagen del comentario",
                            modifier = Modifier.size(120.dp),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                        )
                    } else if (!comment.audioUrl.isNullOrEmpty()) {
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
                Spacer(modifier = Modifier.width(10.dp))
                IconButton(onClick = onLikeClick, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.ThumbUp,
                        contentDescription = "Me gusta",
                        tint = if (comment.myReaction == "LIKE") MaterialTheme.colorScheme.primary else Color.Gray,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text((comment.reactionCounts["LIKE"] ?: 0).toString(), style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(onClick = onDislikeClick, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Default.ThumbDown,
                        contentDescription = "No me gusta",
                        tint = if (comment.myReaction == "DISLIKE") MaterialTheme.colorScheme.error else Color.Gray,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text((comment.reactionCounts["DISLIKE"] ?: 0).toString(), style = MaterialTheme.typography.labelSmall)
            }
            Row(
                modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Responder",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray,
                    modifier = Modifier.clickable { onReplyClick() }
                )
                
                if (comment.isMine || isPostOwner) {
                    Spacer(modifier = Modifier.width(16.dp))
                    if (comment.isMine && canEdit) {
                        IconButton(onClick = onEditClick, modifier = Modifier.size(18.dp)) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Edit,
                                contentDescription = "Editar",
                                tint = Color.Gray,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    IconButton(onClick = onDeleteClick, modifier = Modifier.size(18.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete, 
                            contentDescription = "Eliminar",
                            tint = Color.Gray,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                } else if (showReportAction) {
                    Spacer(modifier = Modifier.width(16.dp))
                    IconButton(onClick = onReportClick, modifier = Modifier.size(18.dp)) {
                        Icon(
                            imageVector = Icons.Default.Report,
                            contentDescription = "Reportar",
                            tint = Color.Gray,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}


