package conectate.com.presentation.videos

import androidx.compose.runtime.Composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.platform.LocalContext
import conectate.com.data.model.VideoComment
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.isUserAdmin
import conectate.com.presentation.components.AdminBadge
import conectate.com.presentation.components.CreatorBadge
import java.text.SimpleDateFormat
import java.util.*
import conectate.com.presentation.messages.VoiceState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentSheet(
    comments: List<VideoComment>,
    onAddComment: (String) -> Unit,
    onDismiss: () -> Unit,
    mentionSuggestions: List<conectate.com.data.model.User> = emptyList(),
    onCommentDraftChanged: (String) -> Unit = {},
    onClearMentionSuggestions: () -> Unit = {},
    // Audio Support
    voiceState: VoiceState = VoiceState.IDLE,
    recordingDuration: Long = 0L,
    onStartRecording: () -> Unit = {},
    onPauseRecording: () -> Unit = {},
    onResumeRecording: () -> Unit = {},
    onStopRecording: (Boolean) -> Unit = {}, // true = send, false = review
    onCancelRecording: () -> Unit = {},
    // Draft Preview
    isDraftPlaying: Boolean = false,
    draftProgress: Float = 0f,
    draftDuration: String = "00:00",
    onPlayDraft: () -> Unit = {},
    onPauseDraft: () -> Unit = {},
    onSeekDraft: (Float) -> Unit = {},
    
    onPlayAudio: (VideoComment) -> Unit = {},
    currentPlayingCommentId: String? = null,
    audioProgress: Float = 0f,
    // New features
    currentUserId: String? = null,
    onDeleteComment: (VideoComment) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onSwipeForward: () -> Unit = {},
    onSwipeBackward: () -> Unit = {},
    videoCreatorId: String? = null,
    replyingTo: VideoComment? = null,
    onReplyClick: (VideoComment) -> Unit = {},
    cancelReply: () -> Unit = {}
) {
    var newCommentText by remember { mutableStateOf("") }
    var pendingSentText by remember { mutableStateOf<String?>(null) }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    
    // Group comments
    val commentIds = remember(comments) { comments.map { it.id.trim().lowercase() }.toSet() }
    val topLevelComments = remember(comments, commentIds) {
        comments.filter { comment ->
            val parent = comment.parentId.normalizedCommentParent()
            parent == null || parent !in commentIds
        }
    }
    val replies = remember(comments) {
        val ids = comments.map { it.id.trim().lowercase() }.toSet()
        comments
            .mapNotNull { comment ->
                val parent = comment.parentId.normalizedCommentParent()
                if (parent != null && parent in ids) parent to comment else null
            }
            .groupBy({ it.first }, { it.second })
    }

    LaunchedEffect(comments, pendingSentText) {
        val pending = pendingSentText?.trim()?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (comments.any { it.content.trim() == pending }) {
            newCommentText = ""
            pendingSentText = null
            onClearMentionSuggestions()
        }
    }
    
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            onStartRecording()
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
        windowInsets = WindowInsets.ime // This is key for the sheet itself moving up
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

            if (comments.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Sé el primero en comentar",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(topLevelComments) { comment ->
                        CommentThreadItem(
                            comment = comment,
                            replies = replies[comment.id.trim().lowercase()] ?: emptyList(),
                            onPlayAudio = onPlayAudio,
                            isPlaying = currentPlayingCommentId == comment.id,
                            progress = if (currentPlayingCommentId == comment.id) audioProgress else 0f,
                            currentPlayingCommentId = currentPlayingCommentId,
                            audioProgress = audioProgress,
                            currentUserId = currentUserId,
                            onDeleteComment = onDeleteComment,
                            onProfileClick = onProfileClick,
                            videoCreatorId = videoCreatorId,
                            onReplyClick = onReplyClick
                        )
                    }
                }
            }

            // Input Area Container
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding() // Push up with keyboard
                    .navigationBarsPadding() // Add navigation bar padding
                    .padding(bottom = 16.dp) 
            ) {
                 // Replying Banner
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
                            Icon(androidx.compose.material.icons.Icons.Default.Close, null)
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (voiceState != VoiceState.IDLE) {
                        // Recording / Review UI
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when (voiceState) {
                                VoiceState.RECORDING -> {
                                    Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val minutes = (recordingDuration / 1000) / 60
                                    val seconds = (recordingDuration / 1000) % 60
                                    Text(
                                        text = "Grabando... " + String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                VoiceState.PAUSED -> {
                                    Text("Pausado", color = MaterialTheme.colorScheme.error)
                                }
                                VoiceState.REVIEWING -> {
                                    // Player UI
                                    IconButton(onClick = { if (isDraftPlaying) onPauseDraft() else onPlayDraft() }) {
                                        Icon(if (isDraftPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                                    }
                                    Slider(
                                        value = draftProgress,
                                        onValueChange = { onSeekDraft(it) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(draftDuration, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start=8.dp))
                                }
                                else -> {}
                            }
                        }
                    } else {
                        Column(modifier = Modifier.weight(1f)) {
                            if (mentionSuggestions.isNotEmpty()) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    tonalElevation = 2.dp
                                ) {
                                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                        mentionSuggestions.forEach { user ->
                                            val username = user.username ?: return@forEach
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        newCommentText = replaceActiveMention(newCommentText, username)
                                                        onClearMentionSuggestions()
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                CircularProfileImage(
                                                    imageUrl = user.avatar_url ?: "",
                                                    size = 26.dp,
                                                    fallbackName = user.displayName
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("@$username", fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = newCommentText,
                                onValueChange = {
                                    newCommentText = it
                                    onCommentDraftChanged(it)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(if (replyingTo != null) "Escribe una respuesta..." else "Añadir un comentario...") },
                                shape = RoundedCornerShape(24.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                ),
                                maxLines = 3
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // Actions
                    if (voiceState != VoiceState.IDLE) {
                        when (voiceState) {
                            VoiceState.RECORDING -> {
                                IconButton(onClick = onPauseRecording) {
                                    Icon(Icons.Default.Pause, "Pausar", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { onStopRecording(true) }) {
                                    Icon(Icons.Default.Send, "Enviar", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            VoiceState.PAUSED -> {
                                IconButton(onClick = onResumeRecording) {
                                    Icon(Icons.Default.Mic, "Reanudar", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { onStopRecording(false) }) {
                                    Icon(Icons.Default.Stop, "Revisar", tint = MaterialTheme.colorScheme.secondary)
                                }
                            }
                            VoiceState.REVIEWING -> {
                                IconButton(onClick = onCancelRecording) {
                                     Icon(Icons.Default.Delete, "Cancelar", tint = MaterialTheme.colorScheme.error)
                                }
                                IconButton(onClick = onResumeRecording) {
                                    Icon(Icons.Default.Mic, "Reanudar", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { onStopRecording(true) }) {
                                    Icon(Icons.Default.Send, "Enviar", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            else -> {}
                        }
                    } else {
                        if (newCommentText.isEmpty()) {
                            IconButton(
                                onClick = { 
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        onStartRecording()
                                    } else {
                                        recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(Icons.Default.Mic, "Grabar")
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    if (newCommentText.isNotBlank()) {
                                        val textToSend = newCommentText.trim()
                                        pendingSentText = textToSend
                                        onAddComment(textToSend)
                                    }
                                },
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(Icons.Default.Send, "Enviar")
                            }
                        }
                    }
            }
            }
        }
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
    videoCreatorId: String?,
    onReplyClick: (VideoComment) -> Unit,
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
            isCreator = comment.userId == videoCreatorId,
            onReplyClick = { onReplyClick(comment) }
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
                        isCreator = reply.userId == videoCreatorId,
                        onReplyClick = { onReplyClick(reply) }, // Reply to reply? Usually replies to original parent or flat structure. Let's keep it simple.
                        isReply = true
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
    isCreator: Boolean = false,
    onReplyClick: () -> Unit = {},
    isReply: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Avatar
        CircularProfileImage(
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
                     } else {
                         Text(
                            text = comment.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                         )
                     }
                 }
             }
             
             // Footer (Time, Reply, Delete)
             Row(
                 verticalAlignment = Alignment.CenterVertically,
                 modifier = Modifier.padding(start = 12.dp, top = 2.dp)
             ) {
                 Text(
                    text = comment.created_at.take(10), // You might want a relative time formatter here (e.g. "2h")
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                 )
                 
                 Spacer(modifier = Modifier.width(16.dp))
                 
                 Text(
                     text = "Responder",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant,
                     fontWeight = FontWeight.Bold,
                     modifier = Modifier.clickable { onReplyClick() }
                 )
                 
                 if (canDelete) {
                     Spacer(modifier = Modifier.width(16.dp))
                     Text(
                         text = "Eliminar",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.error,
                         modifier = Modifier.clickable { onDelete() }
                     )
                 }
             }
        }
    }
}

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
            progress = progress,
            modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        )
    }
}
