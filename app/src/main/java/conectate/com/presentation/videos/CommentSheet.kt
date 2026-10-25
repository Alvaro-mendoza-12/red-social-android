package conectate.com.presentation.videos

import androidx.compose.runtime.Composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import conectate.com.data.model.VideoComment
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.isUserAdmin
import conectate.com.presentation.components.AdminBadge
import conectate.com.presentation.components.CreatorBadge
import java.text.SimpleDateFormat
import java.util.*
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentSheet(
    comments: List<VideoComment>,
    onAddComment: (String) -> Unit,
    onDismiss: () -> Unit,
    mentionSuggestions: List<conectate.com.data.model.User> = emptyList(),
    onCommentDraftChanged: (String) -> Unit = {},
    onClearMentionSuggestions: () -> Unit = {},
    onPlayAudio: (VideoComment) -> Unit = {},
    currentPlayingCommentId: String? = null,
    audioProgress: Float = 0f,
    // New features
    currentUserId: String? = null,
    onDeleteComment: (VideoComment) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onMentionClick: (String) -> Unit = {},
    onSwipeForward: () -> Unit = {},
    onSwipeBackward: () -> Unit = {},
    videoCreatorId: String? = null,
    replyingTo: VideoComment? = null,
    onReplyClick: (VideoComment) -> Unit = {},
    cancelReply: () -> Unit = {},
    onAddSticker: (String, String) -> Unit = { _, _ -> },
    onReactComment: (VideoComment, String?) -> Unit = { _, _ -> },
    onEditComment: (VideoComment, String) -> Unit = { _, _ -> },
    onReportComment: (VideoComment) -> Unit = {},
    isPosting: Boolean = false,
    focusCommentId: String? = null,
) {
    var newCommentText by remember { mutableStateOf("") }
    var pendingSentText by remember { mutableStateOf<String?>(null) }
    var pendingExistingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var focusResolved by remember(focusCommentId) { mutableStateOf(focusCommentId.isNullOrBlank()) }
    var commentOrder by remember { mutableStateOf(VideoCommentOrder.RECENT) }
    var commentAuthorFilter by remember { mutableStateOf(VideoCommentAuthorFilter.ALL) }
    val haptic = LocalHapticFeedback.current
    var showStickerPanel by remember { mutableStateOf(false) }
    
    val visibleThreads = remember(comments, commentOrder, commentAuthorFilter, currentUserId, videoCreatorId) {
        visibleVideoCommentThreads(comments, commentOrder, commentAuthorFilter, currentUserId, videoCreatorId)
    }

    LaunchedEffect(comments, pendingSentText) {
        val pending = pendingSentText?.trim()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (comments.any { it.id !in pendingExistingIds && it.userId == currentUserId && it.content.trim() == pending }) {
            if (newCommentText.trim() == pending) newCommentText = ""
            pendingSentText = null
            onClearMentionSuggestions()
        }
    }
    LaunchedEffect(visibleThreads, focusCommentId, focusResolved) {
        if (focusResolved || focusCommentId.isNullOrBlank()) return@LaunchedEffect
        val index = visibleThreads.indexOfFirst { thread ->
            thread.root.id == focusCommentId || thread.replies.any { it.id == focusCommentId }
        }
        if (index >= 0) {
            listState.scrollToItem(index)
            focusResolved = true
        }
    }
    
    ModalBottomSheet(
        onDismissRequest = {
            onDismiss()
            onClearMentionSuggestions()
            cancelReply()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        contentWindowInsets = { WindowInsets.ime } // Keep the sheet above the keyboard.
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .pointerInput(Unit) {
                    var accumulatedHorizontalDrag = 0f
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, dragAmount ->
                            accumulatedHorizontalDrag += dragAmount
                        },
                        onDragEnd = {
                            when {
                                accumulatedHorizontalDrag < -80f -> onSwipeForward()
                                accumulatedHorizontalDrag > 80f -> onSwipeBackward()
                            }
                            accumulatedHorizontalDrag = 0f
                        },
                        onDragCancel = {
                            accumulatedHorizontalDrag = 0f
                        }
                    )
                }
                .padding(horizontal = 16.dp)
                .navigationBarsPadding() // Handled here
        ) {
            Text(
                text = "${comments.size} comentarios",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            VideoCommentFilterControls(
                order = commentOrder,
                authorFilter = commentAuthorFilter,
                onOrderChange = { commentOrder = it },
                onAuthorFilterChange = { commentAuthorFilter = it },
            )

            if (visibleThreads.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (comments.isEmpty()) "Sé el primero en comentar" else "No hay comentarios con este filtro",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(visibleThreads, key = { it.root.id }) { thread ->
                        CommentThreadItem(
                            comment = thread.root,
                            replies = thread.replies,
                            onPlayAudio = onPlayAudio,
                            isPlaying = currentPlayingCommentId == thread.root.id,
                            progress = if (currentPlayingCommentId == thread.root.id) audioProgress else 0f,
                            currentPlayingCommentId = currentPlayingCommentId,
                            audioProgress = audioProgress,
                            currentUserId = currentUserId,
                            onDeleteComment = onDeleteComment,
                            onProfileClick = onProfileClick,
                            onMentionClick = onMentionClick,
                            videoCreatorId = videoCreatorId,
                            onReplyClick = onReplyClick,
                            onReactComment = onReactComment,
                            onEditComment = onEditComment,
                            onReportComment = onReportComment
                        )
                    }
                }
            }

            // Text and sticker comments only. Existing audio comments remain playable below.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                AnimatedVisibility(visible = replyingTo != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Respondiendo a @${replyingTo?.userName ?: "Usuario"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(onClick = cancelReply, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, null)
                        }
                    }
                }

                if (mentionSuggestions.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 2.dp
                    ) {
                        Column(
                            modifier = Modifier.heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 6.dp)
                        ) {
                            mentionSuggestions.forEach { user ->
                                val username = conectate.com.data.model.profilePublicHandle(user)
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        newCommentText = replaceActiveMention(newCommentText, username)
                                        onClearMentionSuggestions()
                                    }.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProfileImage(
                                        ownerId = user.id,
                                        imageUrl = user.avatar_url ?: "",
                                        size = 26.dp,
                                        fallbackName = user.displayName
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("@$username", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    IconButton(onClick = { showStickerPanel = true }) {
                        Icon(Icons.Default.EmojiEmotions, contentDescription = "Emojis y stickers")
                    }
                    OutlinedTextField(
                        value = newCommentText,
                        onValueChange = {
                            newCommentText = it
                            onCommentDraftChanged(it)
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text(if (replyingTo != null) "Escribe una respuesta..." else "Añadir un comentario...")
                        },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 3,
                        visualTransformation = conectate.com.presentation.components.mentionInputTransformation
                    )
                    IconButton(
                        onClick = {
                            val text = newCommentText.trim()
                            if (text.isNotEmpty()) {
                                pendingSentText = text
                                pendingExistingIds = comments.mapTo(mutableSetOf()) { it.id }
                                onAddComment(text)
                            }
                        },
                        enabled = newCommentText.isNotBlank() && !isPosting
                    ) {
                        if (isPosting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar comentario")
                    }
                }
            }
        }
    }

    if (showStickerPanel) {
        conectate.com.presentation.messages.StickerPickerDialog(
            onDismiss = { showStickerPanel = false },
            onEmojiSelected = { emoji ->
                newCommentText += emoji
                onCommentDraftChanged(newCommentText)
                showStickerPanel = false
            },
            onStickerSelected = { sticker ->
                showStickerPanel = false
                val url = sticker.media_url.orEmpty()
                if (url.isNotBlank()) onAddSticker(url, sticker.title.ifBlank { "Sticker" })
            }
        )
    }
}

private fun replaceActiveMention(currentText: String, username: String): String {
    val atIndex = currentText.lastIndexOf('@')
    if (atIndex < 0) return "$currentText @$username "

    val suffix = currentText.substring(atIndex + 1)
    if (suffix.any { it.isWhitespace() }) return "$currentText@$username "

    return currentText.substring(0, atIndex + 1) + username + " "
}

private fun String?.isRootCommentParent(): Boolean {
    return normalizedCommentParent() == null
}

private fun String?.normalizedCommentParent(): String? {
    val clean = this?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    return clean.takeUnless { it == "0" || it == "null" || it == "undefined" || it == "none" }
}

@Composable
fun CommentThreadItem(
    comment: VideoComment,
    replies: List<VideoComment>,
    onPlayAudio: (VideoComment) -> Unit,
    isPlaying: Boolean,
    progress: Float,
    currentPlayingCommentId: String?,
    audioProgress: Float,
    currentUserId: String?,
    onDeleteComment: (VideoComment) -> Unit,
    onProfileClick: (String) -> Unit,
    onMentionClick: (String) -> Unit,
    videoCreatorId: String?,
    onReplyClick: (VideoComment) -> Unit,
    onReactComment: (VideoComment, String?) -> Unit = { _, _ -> },
    onEditComment: (VideoComment, String) -> Unit = { _, _ -> },
    onReportComment: (VideoComment) -> Unit = {},
    initiallyExpanded: Boolean = false
) {
    var expanded by remember(comment.id, initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    val currentUserNormalized = currentUserId?.trim()?.lowercase()
    val videoCreatorNormalized = videoCreatorId?.trim()?.lowercase()
    val canDeleteParent = currentUserNormalized != null &&
        (currentUserNormalized == comment.userId.trim().lowercase() || currentUserNormalized == videoCreatorNormalized)

    Column {
        // Parent Comment
        VideoCommentItem(
            comment = comment,
            onPlayAudio = onPlayAudio,
            isPlaying = isPlaying,
            progress = progress,
            isOwnComment = currentUserId == comment.userId,
            canDelete = canDeleteParent,
            onDelete = { onDeleteComment(comment) },
            onProfileClick = { onProfileClick(comment.userId) },
            onMentionClick = onMentionClick,
            isCreator = comment.userId == videoCreatorId,
            onReplyClick = { onReplyClick(comment) },
            onReaction = { reaction -> onReactComment(comment, reaction) },
            onEdit = { content -> onEditComment(comment, content) },
            onReport = { onReportComment(comment) }
        )

        // Replies Toggle
        if (replies.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(start = 52.dp, top = 4.dp)
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
                     text = if (expanded) "Ocultar respuestas" else "Ver ${replies.size} respuestas",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     fontWeight = FontWeight.Bold
                 )
            }
        }

        // Nested Replies
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(start = 44.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                replies.forEach { reply ->
                    val canDeleteReply = currentUserNormalized != null &&
                        (currentUserNormalized == reply.userId.trim().lowercase() || currentUserNormalized == videoCreatorNormalized)
                    VideoCommentItem(
                        comment = reply,
                        onPlayAudio = onPlayAudio,
                        isPlaying = currentPlayingCommentId == reply.id,
                        progress = if (currentPlayingCommentId == reply.id) audioProgress else 0f,
                        isOwnComment = currentUserId == reply.userId,
                        canDelete = canDeleteReply,
                        onDelete = { onDeleteComment(reply) },
                        onProfileClick = { onProfileClick(reply.userId) },
                        onMentionClick = onMentionClick,
                        isCreator = reply.userId == videoCreatorId,
                        onReplyClick = { onReplyClick(reply) }, // Reply to reply? Usually replies to original parent or flat structure. Let's keep it simple.
                        isReply = true,
                        onReaction = { reaction -> onReactComment(reply, reaction) },
                        onEdit = { content -> onEditComment(reply, content) },
                        onReport = { onReportComment(reply) }
                    )
                }
            }
        }
    }
}


@Composable
fun VideoCommentItem(
    comment: VideoComment,
    onPlayAudio: (VideoComment) -> Unit = {},
    isPlaying: Boolean = false,
    progress: Float = 0f,
    isOwnComment: Boolean = false,
    canDelete: Boolean = false,
    onDelete: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    onMentionClick: (String) -> Unit = {},
    isCreator: Boolean = false,
    onReplyClick: () -> Unit = {},
    isReply: Boolean = false,
    onReaction: (String?) -> Unit = {},
    onEdit: (String) -> Unit = {},
    onReport: () -> Unit = {}
) {
    var showEditDialog by remember(comment.id) { mutableStateOf(false) }
    var editText by remember(comment.id) { mutableStateOf(comment.content) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Avatar
        CircularProfileImage(ownerId = comment.userId, 
            imageUrl = comment.userProfileImage,
            size = if(isReply) 28.dp else 40.dp,
            fallbackName = comment.userName,
            modifier = Modifier
                .padding(top = 2.dp)
                .clickable { onProfileClick() }
        )
        
        Column(modifier = Modifier.weight(1f)) {
             // Bubble
             Surface(
                 shape = RoundedCornerShape(18.dp),
                 color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                 modifier = Modifier.wrapContentWidth()
             ) {
                 Column(
                     modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                 ) {
                     // Name
                     Row(verticalAlignment = Alignment.CenterVertically) {
                         Text(
                            text = if (!comment.userName.isNullOrBlank()) comment.userName else "Usuario",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.clickable { onProfileClick() }
                         )
                         
                         if (isUserAdmin(comment.userName)) {
                             Spacer(modifier = Modifier.width(4.dp))
                             AdminBadge(showVerifiedLine = false)
                         } else if (isCreator) {
                             Spacer(modifier = Modifier.width(4.dp))
                             CreatorBadge()
                         }
                     }
                     
                     // Content
                     if (comment.contentType == "audio") {
                         AudioCommentPlayer(
                             comment = comment,
                             onPlay = { onPlayAudio(comment) },
                             isPlaying = isPlaying,
                             progress = progress
                         )
                     } else if (comment.contentType == "sticker" && !comment.mediaUrl.isNullOrBlank()) {
                         AsyncImage(
                             model = comment.mediaUrl,
                             contentDescription = "Sticker",
                             contentScale = ContentScale.Fit,
                             modifier = Modifier.size(112.dp)
                         )
                     } else {
                         conectate.com.presentation.components.MentionText(comment.content, onMentionClick)
                     }
                 }
             }

             Row(
                 modifier = Modifier
                     .padding(start = 8.dp, top = 4.dp)
                     .horizontalScroll(rememberScrollState()),
                 horizontalArrangement = Arrangement.spacedBy(4.dp)
             ) {
                 listOf("LIKE" to "👍", "LOVE" to "❤️", "LAUGH" to "😂", "WOW" to "😮", "SAD" to "😢")
                     .forEach { (type, emoji) ->
                         val selected = comment.myReaction == type
                         val count = comment.reactions[type] ?: 0
                         Text(
                             text = if (count > 0) "$emoji $count" else emoji,
                             modifier = Modifier
                                 .clip(CircleShape)
                                 .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                 .clickable { onReaction(if (selected) null else type) }
                                 .padding(horizontal = 6.dp, vertical = 3.dp),
                             style = MaterialTheme.typography.labelSmall
                         )
                     }
             }
             
             // Footer (Time, Reply, Delete)
             Row(
                 verticalAlignment = Alignment.CenterVertically,
                 modifier = Modifier
                     .padding(start = 12.dp, top = 2.dp)
                     .horizontalScroll(rememberScrollState())
             ) {
                 Text(
                    text = conectate.com.util.localCommentDateLabel(comment.created_at),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                 )
                 
                 Spacer(modifier = Modifier.width(16.dp))
                 
                 Text(
                     text = "Responder",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     fontWeight = FontWeight.Bold,
                     modifier = Modifier
                         .clip(RoundedCornerShape(4.dp))
                         .clickable { onReplyClick() }
                         .padding(horizontal = 8.dp, vertical = 4.dp)
                 )
                 
                 if (canDelete) {
                     Spacer(modifier = Modifier.width(8.dp))
                     Text(
                         text = "Eliminar",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.error,
                         modifier = Modifier
                             .clip(RoundedCornerShape(4.dp))
                             .clickable { onDelete() }
                             .padding(horizontal = 8.dp, vertical = 4.dp)
                     )
                 }
                 if (isOwnComment && comment.contentType == "text" && canEditVideoComment(comment.created_at)) {
                     Text(
                         text = "Editar",
                         style = MaterialTheme.typography.labelSmall,
                         modifier = Modifier.clickable { showEditDialog = true }.padding(horizontal = 8.dp, vertical = 4.dp)
                     )
                 } else if (!isOwnComment) {
                     Text(
                         text = "Reportar",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.error,
                         modifier = Modifier.clickable(onClick = onReport).padding(horizontal = 8.dp, vertical = 4.dp)
                     )
                 }
             }
        }
    }

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Editar comentario") },
            text = { OutlinedTextField(value = editText, onValueChange = { editText = it }, maxLines = 5) },
            confirmButton = {
                TextButton(onClick = {
                    val clean = editText.trim()
                    if (clean.isNotBlank()) onEdit(clean)
                    showEditDialog = false
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { showEditDialog = false }) { Text("Cancelar") } }
        )
    }
}

private fun canEditVideoComment(createdAt: String): Boolean = runCatching {
    val created = java.time.Instant.parse(createdAt)
    java.time.Duration.between(created, java.time.Instant.now()).toHours() < 5
}.getOrDefault(false)

@Composable
fun AudioCommentPlayer(
    comment: VideoComment,
    onPlay: () -> Unit,
    isPlaying: Boolean,
    progress: Float
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
            .clickable { onPlay() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        )
    }
}
