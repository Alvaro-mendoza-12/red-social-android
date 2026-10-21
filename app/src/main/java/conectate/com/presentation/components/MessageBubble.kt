package conectate.com.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.VideoLibrary
import coil.compose.SubcomposeAsyncImage
import android.media.MediaPlayer
import android.media.AudioManager
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import conectate.com.data.model.Message
import conectate.com.presentation.theme.*
import conectate.com.util.Constants
import conectate.com.presentation.components.AdminBadge
import conectate.com.presentation.components.isUserAdmin
import conectate.com.util.ChatContactPayload
import conectate.com.util.decodeContactPayload
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.layout.ContentScale
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import conectate.com.core.media.MediaImageRequestFactory
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.util.VideoCache
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
private fun ChatVideoPreview(
    url: String?,
    modifier: Modifier = Modifier,
    thumbnailUrl: String? = null,
) {
    val context = LocalContext.current
    LaunchedEffect(url) {
        val source = url?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        runCatching { VideoCache.preloadStart(context.applicationContext, source) }
    }
    val posterSource = thumbnailUrl?.takeIf(String::isNotBlank)
    val resolvedPoster = UbuntuStorageUrl.thumbnail(posterSource, width = 720)
        ?: url?.takeIf(String::isNotBlank)
    val posterRequest = remember(resolvedPoster, posterSource) {
        resolvedPoster?.let { source ->
            ImageRequest.Builder(context)
                .data(source)
                .apply {
                    if (posterSource.isNullOrBlank()) videoFrameMillis(1_000)
                }
                .size(720, 720)
                .memoryCacheKey(
                    UbuntuStorageUrl.stableCacheKey(posterSource ?: source)
                        ?.let { "chat-video-poster:$it" }
                )
                .diskCacheKey(
                    UbuntuStorageUrl.stableCacheKey(posterSource ?: source)
                        ?.let { "chat-video-poster:$it" }
                )
                .diskCachePolicy(CachePolicy.ENABLED)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .crossfade(false)
                .build()
        }
    }
    SubcomposeAsyncImage(
        model = posterRequest,
        contentDescription = "Vista previa del video",
        contentScale = ContentScale.Crop,
        modifier = modifier.background(Color.Black),
        loading = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
            }
        },
        error = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.VideoLibrary,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(38.dp)
                )
            }
        }
    )
}

@Composable
private fun AudioWaveform(
    active: Boolean,
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        if (!active) {
            frame = 0
            return@LaunchedEffect
        }
        while (true) {
            frame = (frame + 1) % 24
            delay(90)
        }
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(18) { index ->
            val normalizedIndex = index / 17f
            val played = normalizedIndex <= conectate.com.util.finiteAudioProgress(progress)
            val animatedStep = if (active) (index * 5 + frame * 3) % 11 else (index * 7) % 9
            val barHeight = (7 + animatedStep * 1.35f).dp
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(barHeight)
                    .clip(RoundedCornerShape(99.dp))
                    .background(color.copy(alpha = if (played || active) 0.95f else 0.38f)),
            )
        }
    }
}

private fun formatPlaybackSpeed(speed: Float): String = when (speed) {
    1f -> "1×"
    1.5f -> "1.5×"
    2f -> "2×"
    else -> "${speed}×"
}

@Composable
@androidx.compose.foundation.ExperimentalFoundationApi
fun ImageMessageBubble(
    message: Message,
    isFromCurrentUser: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onResolveUrl: suspend (String) -> String? = { it }
) {
    val context = LocalContext.current
    val chatAccentColors = currentChatAccentColors()
    var resolvedUrl by remember(message.media_url) { mutableStateOf<String?>(null) }
    
    LaunchedEffect(message.media_url) {
        if (message.media_url != null) {
             resolvedUrl = onResolveUrl(message.media_url)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = if (isFromCurrentUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isFromCurrentUser) chatAccentColors.sentBackground else MessageReceivedBackground)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
                if (message.deleted_for_all) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Gray.copy(alpha=0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                         Text(
                             text = "Mensaje eliminado",
                             color = Color.White.copy(alpha=0.6f),
                             style = MaterialTheme.typography.bodySmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                         )
                    }
                } else {
                    val imageUrl = UbuntuStorageUrl.rewrite(resolvedUrl ?: message.media_url)
                    val imageRequest = remember(imageUrl) {
                        MediaImageRequestFactory.preview(context, imageUrl)
                    }
                    SubcomposeAsyncImage(
                        model = imageRequest,
                        contentDescription = "Imagen",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        loading = {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        },
                        error = {
                             Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.BrokenImage, "Error", tint = Color.Gray)
                            }
                        }
                    )
                }
                
                // Timestamp Overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                   Row(verticalAlignment = Alignment.CenterVertically) {
                        message.created_at?.let { timestamp ->
                             Text(
                                text = formatIsoTimestamp(timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                        if (isFromCurrentUser) {
                            Spacer(modifier = Modifier.width(4.dp))
                            val normalizedStatus = message.status.trim().lowercase()
                            val isRead = normalizedStatus == Constants.MESSAGE_STATUS_READ
                            val isDelivered = normalizedStatus == Constants.MESSAGE_STATUS_DELIVERED ||
                                normalizedStatus == "received"
                            Icon(
                                imageVector = when (normalizedStatus) {
                                    Constants.MESSAGE_STATUS_READ -> Icons.Default.DoneAll
                                    Constants.MESSAGE_STATUS_DELIVERED, "received" -> Icons.Default.DoneAll
                                    "sending" -> Icons.Default.AccessTime
                                    else -> Icons.Default.Done
                                },
                                contentDescription = "Status",
                                modifier = Modifier.size(10.dp),
                                tint = when {
                                    isRead -> Color(0xFF34B7F1)
                                    isDelivered -> Color(0xFFB0BEC5)
                                    else -> Color.White
                                }
                            )
                        }
                    }
                }
            }
        }
    }


@Composable
@androidx.compose.foundation.ExperimentalFoundationApi
fun VideoMessageBubble(
    message: Message,
    isFromCurrentUser: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
     Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = if (isFromCurrentUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
                if (message.deleted_for_all) {
                     Box(
                        modifier = Modifier.fillMaxSize().background(Color.Gray.copy(alpha=0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                         Text(
                             text = "Mensaje eliminado",
                             color = Color.White.copy(alpha=0.6f),
                             style = MaterialTheme.typography.bodySmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                         )
                    }
                } else {
                    ChatVideoPreview(
                        url = message.media_url,
                        thumbnailUrl = message.thumbnail_url,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f/9f)
                    )
                    
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Reproducir",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }
                
                 // Timestamp Overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                   Row(verticalAlignment = Alignment.CenterVertically) {
                        message.created_at?.let { timestamp ->
                             Text(
                                text = formatIsoTimestamp(timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                        if (isFromCurrentUser) {
                            Spacer(modifier = Modifier.width(4.dp))
                            val normalizedStatus = message.status.trim().lowercase()
                            val isRead = normalizedStatus == Constants.MESSAGE_STATUS_READ
                            val isDelivered = normalizedStatus == Constants.MESSAGE_STATUS_DELIVERED ||
                                normalizedStatus == "received"
                            Icon(
                                imageVector = when (normalizedStatus) {
                                    Constants.MESSAGE_STATUS_READ -> Icons.Default.DoneAll
                                    Constants.MESSAGE_STATUS_DELIVERED, "received" -> Icons.Default.DoneAll
                                    "sending" -> Icons.Default.AccessTime
                                    else -> Icons.Default.Done
                                },
                                contentDescription = "Status",
                                modifier = Modifier.size(10.dp),
                                tint = when {
                                    isRead -> Color(0xFF34B7F1)
                                    isDelivered -> Color(0xFFB0BEC5)
                                    else -> Color.White
                                }
                            )
                        }
                   }
                }
            }
        }
    }

@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Suppress("ModifierParameter")
fun MessageBubble(
    message: Message,
    isFromCurrentUser: Boolean,
    isSelected: Boolean = false,
    onLongClick: () -> Unit = {},
    onClick: (String?) -> Unit = {},
    onPlayAudio: (Message) -> Unit = {},
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    isAudioActive: Boolean = false,
    progress: Float = 0f,
    duration: String = "0:00",
    totalAudioDurationMs: Long? = null,
    playbackSpeed: Float = 1f,
    onSeekAudio: (Message, Float) -> Unit = { _, _ -> },
    onChangeAudioSpeed: (Message) -> Unit = {},
    onResolveUrl: suspend (String) -> String? = { it },
    senderName: String? = null,
    senderEmail: String? = null,
    isAdmin: Boolean = false,
    isVerified: Boolean = false,
    userBadges: List<conectate.com.data.model.Badge> = emptyList(),
    onUrlClick: (String) -> Unit = {},
    onSwipeReply: (Message) -> Unit = {},
    onReplyClick: (String) -> Unit = {},
    onSenderClick: (String) -> Unit = {},
    onMentionClick: ((String) -> Unit)? = null,
    onCollectionInvitationClick: ((String) -> Unit)? = null,
    currentUserId: String? = null,
    onReactionClick: (String) -> Unit = {},
    onOpenContactChat: (ChatContactPayload) -> Unit = {},
    onAddToDeviceContacts: (ChatContactPayload) -> Unit = {},
    uploadProgress: Float? = null,
    onRetrySend: (() -> Unit)? = null,
    replySenderName: String? = null,
    replyMessage: Message? = null,
    selectionMode: Boolean = false,
) {
    val context = LocalContext.current
    var showDocument by remember(message.id, message.media_url) { mutableStateOf(false) }
    if (showDocument && !message.deleted_for_all && !message.media_url.isNullOrBlank()) {
        conectate.com.presentation.documents.InternalDocumentDialog(
            source = android.net.Uri.parse(message.media_url),
            suggestedName = message.content,
            resolveSource = onResolveUrl,
            onDismiss = { showDocument = false },
        )
    }
    var offsetX by remember { mutableStateOf(0f) }
    val replyThreshold = 100f
    
    var resolvedUrl by remember(message.media_url) { mutableStateOf<String?>(null) }
    
    LaunchedEffect(message.media_url) {
        if (message.media_url != null) {
            resolvedUrl = onResolveUrl(message.media_url) ?: message.media_url
        }
    }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val isStickerMessage = message.content_type == "sticker" && !message.deleted_for_all
    val chatAccentColors = currentChatAccentColors()
    
    // Theme-aware colors
    val sentBgColor = chatAccentColors.sentBackground
    val receivedBgColor = if (isDark) MessageReceivedBackgroundDark else MessageReceivedBackground
    val receivedTextColor = if (isDark) MessageReceivedTextColorDark else MessageReceivedTextColorLight
    
    // Selection highlight color
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
    } else {
        if (isFromCurrentUser) sentBgColor else receivedBgColor
    }
    val bubbleShape = RoundedCornerShape(
        topStart = 10.dp,
        topEnd = 10.dp,
        bottomStart = if (isFromCurrentUser) 10.dp else 3.dp,
        bottomEnd = if (isFromCurrentUser) 3.dp else 10.dp
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .messageSelectionTap(selectionMode) { onClick(null) }
            .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .offset { IntOffset(x = (offsetX / 3f).roundToInt(), y = 0) }
            .then(
                if (message.isAudio) {
                    // The horizontal gesture belongs to the seek bar. Audio messages can
                    // still be replied to from the long-press actions menu.
                    Modifier
                } else {
                    Modifier.pointerInput(message.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (kotlin.math.abs(offsetX) > replyThreshold) {
                                    onSwipeReply(message)
                                }
                                offsetX = 0f
                            },
                            onHorizontalDrag = { change: androidx.compose.ui.input.pointer.PointerInputChange, dragAmount: Float ->
                                change.consume()
                                offsetX += dragAmount
                            }
                        )
                    }
                }
            ),
        horizontalArrangement = if (isFromCurrentUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = if (isStickerMessage) 124.dp else 340.dp)
                .then(
                    if (isStickerMessage) {
                        Modifier
                    } else {
                        Modifier
                            .clip(bubbleShape)
                            .background(color = backgroundColor)
                    }
                )
                .combinedClickable(
                    onClick = { 
                        when {
                            selectionMode -> onClick(null)
                            message.isAudio -> onPlayAudio(message)
                            isStickerMessage -> onLongClick()
                            message.content_type in listOf("document", "file") && !message.deleted_for_all -> showDocument = true
                            else -> {
                                // Pass the resolved URL back (useful for fullscreen dialogs)
                                onClick(resolvedUrl ?: message.media_url)
                            }
                        }
                    },
                    onLongClick = onLongClick
                )
                .padding(
                    horizontal = if (isStickerMessage) 4.dp else 11.dp,
                    vertical = if (isStickerMessage) 1.dp else 5.dp
                )
        ) {
            Column {
                // Sender Name and Badge (For Group/Community or own messages if name provided)
                if (senderName != null && !isFromCurrentUser) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (isFromCurrentUser) Arrangement.End else Arrangement.Start,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                    ) {
                        Text(
                            text = senderName,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isStickerMessage) {
                                if (isFromCurrentUser) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
                            } else {
                                if (isFromCurrentUser) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.primary
                            },
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.combinedClickable(
                                onClick = { onSenderClick(message.sender_id) },
                                onLongClick = onLongClick
                            )
                        )
                        
                        if (isVerified) {
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.CheckCircle,
                                contentDescription = "Verificado",
                                tint = Color(0xFF1DA1F2),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        
                        // Prioritize Admin Badge
                        if (isAdmin || isUserAdmin(senderName, senderEmail) || userBadges.any(::isAdminBadge)) {
                            Spacer(modifier = Modifier.width(4.dp))
                            AdminBadge(showVerifiedLine = false)
                        }
                        
                        // Then render other Badges if any
                        if (userBadges.isNotEmpty()) {
                            // Filter out "Admin" if it's already in userBadges list to avoid duplicates
                            val filteredBadges = userBadges.filter { 
                                it.name.lowercase() != "admin" && it.name.lowercase() != "administrador" 
                            }
                            if (filteredBadges.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    filteredBadges.forEach { badge ->
                                        ChatBadgeItem(badge)
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Quote Section
                (message.reply_to ?: replyMessage ?: message.reply_to_id?.takeIf(String::isNotBlank)?.let {
                    Message(id = it, content = "Mensaje anterior", sender_name = "Respuesta")
                })?.let { quoted ->
                    Row(
                        modifier = Modifier
                            .padding(top = 4.dp, bottom = 4.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isStickerMessage) {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                } else {
                                    if (isFromCurrentUser) Color.Black.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.2f)
                                }
                            )
                            .height(IntrinsicSize.Min) 
                            .padding(8.dp)
                            .clickable {
                                val originalId = quoted.id.ifBlank {
                                    message.reply_to_id.orEmpty()
                                }
                                if (originalId.isNotBlank()) onReplyClick(originalId)
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            val displayName = quotedMessageAuthor(
                                quotedSenderId = quoted.sender_id,
                                currentUserId = currentUserId,
                                quotedSenderName = replySenderName ?: quoted.sender_name,
                                messageSenderId = message.sender_id,
                                messageSenderName = senderName ?: message.sender_name,
                            )
                            
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                            Text(
                                text = conectate.com.util.quotedMessagePreview(quoted),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = if (isFromCurrentUser) chatAccentColors.sentContent.copy(alpha = 0.7f) else receivedTextColor.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                if (message.is_forwarded && !message.deleted_for_all) {
                    Text(
                        text = "Reenviado",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isFromCurrentUser) {
                            chatAccentColors.sentContent.copy(alpha = 0.75f)
                        } else {
                            receivedTextColor.copy(alpha = 0.75f)
                        },
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                if (message.deleted_for_all) {
                    val deletedText = if (isFromCurrentUser) "Eliminaste este mensaje." else "Este mensaje fue eliminado."
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                         Icon(
                            imageVector = Icons.Default.Block,
                            contentDescription = null,
                            tint = if (isFromCurrentUser) chatAccentColors.sentContent.copy(alpha=0.6f) else receivedTextColor.copy(alpha=0.6f),
                            modifier = Modifier.size(16.dp).padding(end = 4.dp)
                         )
                         Text(
                             text = deletedText,
                             color = if (isFromCurrentUser) chatAccentColors.sentContent.copy(alpha=0.6f) else receivedTextColor.copy(alpha=0.6f),
                             style = MaterialTheme.typography.bodyMedium.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                         )
                    }
                } else {
                    var mediaRequestAttempt by remember(message.media_url) { mutableStateOf(0) }
                    var mediaRequestFailed by remember(message.media_url, mediaRequestAttempt) { mutableStateOf(false) }
                    LaunchedEffect(mediaRequestFailed, mediaRequestAttempt) {
                        if (mediaRequestFailed && mediaRequestAttempt == 0) { kotlinx.coroutines.delay(350); mediaRequestAttempt++ }
                    }
                    val previewSource = MediaImageRequestFactory.chatPreviewSource(message.media_url, resolvedUrl)
                    val mediaPreviewRequest = remember(
                        mediaRequestAttempt,
                        previewSource,
                        message.media_url,
                        message.content_type
                    ) {
                        val source = previewSource
                        if (message.content_type == "sticker" || message.content_type == "gif") {
                            MediaImageRequestFactory.animated(context, source)
                        } else {
                            (if (mediaRequestAttempt == 0) MediaImageRequestFactory.preview(context, source)
                            else MediaImageRequestFactory.fullScreen(context, source))
                                ?.newBuilder()?.setParameter("chat_image_retry", mediaRequestAttempt)?.build()
                        }
                    }
                    when (message.content_type) {
                    "view_once_image", "view_once_video" -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(vertical = 6.dp)
                                .widthIn(max = 280.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = null,
                                tint = if (isFromCurrentUser) chatAccentColors.sentContent else MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (message.content_type == "view_once_video") "Video ver una vez" else "Foto ver una vez",
                                    color = if (isFromCurrentUser) chatAccentColors.sentContent else receivedTextColor,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "Se abrirá solo una vez",
                                    color = if (isFromCurrentUser) chatAccentColors.sentContent.copy(alpha = 0.75f) else receivedTextColor.copy(alpha = 0.75f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    "birthday_card" -> {
                        val letter = conectate.com.data.model.BirthdayCard.decode(message.content)
                        if (letter != null) BirthdayLetter(letter) else Text("Tarjeta de cumpleaños", color = receivedTextColor)
                    }
                    "text" -> {
                        val videoReference = remember(message.content) { conectate.com.data.model.sharedVideoReference(message.content) }
                        if (videoReference != null) {
                            SharedVideoMessageCard(videoReference, onUrlClick)
                            if (videoReference.caption.isNotBlank()) Text(videoReference.caption,
                                color = if (isFromCurrentUser) chatAccentColors.sentContent else receivedTextColor,
                                style = MaterialTheme.typography.bodyMedium)
                        } else ExpandableMessageText(
                            text = message.content,
                            backgroundColor = backgroundColor,
                            color = if (isFromCurrentUser) chatAccentColors.sentContent else receivedTextColor,
                            fontSize = 15.sp,
                            onUrlClick = onUrlClick,
                            onMentionClick = onMentionClick,
                            onCollectionInvitationClick = onCollectionInvitationClick
                        )
                    }
                    "image", "gif" -> {
                        Box(modifier = Modifier.clip(RoundedCornerShape(8.dp))) {
                            SubcomposeAsyncImage(
                                model = mediaPreviewRequest,
                                onError = { mediaRequestFailed = true },
                                contentDescription = "Imagen",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .wrapContentHeight()
                                    .heightIn(max = 420.dp),
                                contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                                loading = {
                                    Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    }
                                },
                                error = {
                                    Box(modifier = Modifier.fillMaxWidth().height(200.dp).clickable(onClickLabel = "Volver a cargar imagen") { mediaRequestAttempt++ }, contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.BrokenImage,
                                            contentDescription = "Error al cargar imagen",
                                            tint = Color.Gray,
                                            modifier = Modifier.size(48.dp)
                                        )
                                    }
                                }
                            )
                            if (message.content_type == "gif") {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(8.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "GIF",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                    "sticker" -> {
                        Box(
                            modifier = Modifier.size(112.dp).clip(RoundedCornerShape(10.dp)).testTag("sticker_artwork"),
                            contentAlignment = Alignment.Center,
                        ) {
                        if (!message.media_url.isNullOrBlank()) {
                                StickerArtwork(
                                    url = previewSource,
                                    thumbnailUrl = message.thumbnail_url,
                                    description = "Sticker",
                                    modifier = Modifier.fillMaxSize(),
                                )
                        } else {
                            Text(
                                text = message.content.ifBlank { "🧩" },
                                fontSize = 34.sp
                            )
                        }
                        if (isFromCurrentUser && onRetrySend != null) {
                            conectate.com.presentation.messages.StickerDeliveryOverlay(message.status, uploadProgress, onRetrySend)
                        }
                        }
                    }
                    "video" -> {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.clip(RoundedCornerShape(8.dp))) {
                            ChatVideoPreview(
                                url = resolvedUrl ?: message.media_url,
                                thumbnailUrl = message.thumbnail_url,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f/9f)
                            )
                            Icon(
                                imageVector = Icons.Default.PlayCircleOutline,
                                contentDescription = "Reproducir",
                                tint = Color.White,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }

                    "audio" -> {
                        var localDuration by remember(message.id, message.media_url, message.content) {
                            mutableStateOf(conectate.com.util.audioDurationMillis(message.content))
                        }
                        LaunchedEffect(message.id, resolvedUrl, totalAudioDurationMs) {
                            if (totalAudioDurationMs != null && totalAudioDurationMs > 0) {
                                localDuration = totalAudioDurationMs
                            } else if (localDuration == null) {
                                localDuration = conectate.com.util.localAudioDurationMs(context, resolvedUrl)
                                // The existing offline prefetch may complete after this row first appears.
                                // Re-resolve a few times; the metadata reader itself never downloads media.
                                for (waitMs in listOf(500L, 1500L, 3000L)) {
                                    if (localDuration != null || message.media_url.isNullOrBlank()) break
                                    delay(waitMs)
                                    val cachedSource = onResolveUrl(message.media_url)
                                    localDuration = conectate.com.util.localAudioDurationMs(context, cachedSource)
                                }
                            }
                        }
                        Column(
                            modifier = Modifier
                                .width(232.dp)
                                .combinedClickable(
                                    onClick = { onPlayAudio(message) },
                                    onLongClick = onLongClick
                                )
                        ) {
                            val audioTint = if (isFromCurrentUser) {
                                chatAccentColors.sentContent
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { onPlayAudio(message) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Pausar audio" else "Reproducir audio",
                                        tint = audioTint,
                                    )
                                }
                                Box(modifier = Modifier.weight(1f).height(36.dp), contentAlignment = Alignment.Center) {
                                    AudioWaveform(
                                        active = isPlaying,
                                        progress = if (isAudioActive) progress else 0f,
                                        color = audioTint,
                                        modifier = Modifier.fillMaxWidth().height(26.dp),
                                    )
                                    Slider(
                                        value = if (isAudioActive) conectate.com.util.finiteAudioProgress(progress) else 0f,
                                        onValueChange = { value -> onSeekAudio(message, value) },
                                        enabled = isAudioActive,
                                        modifier = Modifier.fillMaxSize().semantics { contentDescription = "Posición del audio" },
                                        colors = SliderDefaults.colors(
                                            thumbColor = audioTint,
                                            activeTrackColor = Color.Transparent,
                                            inactiveTrackColor = Color.Transparent,
                                            disabledActiveTrackColor = Color.Transparent,
                                            disabledInactiveTrackColor = Color.Transparent,
                                            disabledThumbColor = Color.Transparent,
                                        ),
                                    )
                                }
                                Text(
                                    text = formatPlaybackSpeed(playbackSpeed),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = audioTint,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onChangeAudioSpeed(message) }
                                        .padding(horizontal = 7.dp, vertical = 5.dp),
                                )
                            }
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = conectate.com.util.audioBubbleTimeLabel(isAudioActive, duration, localDuration),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isFromCurrentUser) chatAccentColors.sentContent else receivedTextColor,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = conectate.com.util.audioMessageCaption(message.content),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isFromCurrentUser) chatAccentColors.sentContent else receivedTextColor,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    "document", "file" -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(vertical = 4.dp)
                                .widthIn(max = 280.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                tint = if (isFromCurrentUser) chatAccentColors.sentContent else MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = message.content.ifBlank { "Archivo adjunto" },
                                color = if (isFromCurrentUser) chatAccentColors.sentContent else receivedTextColor,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    "contact" -> {
                        val payload = decodeContactPayload(message.content)
                        if (payload == null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(vertical = 4.dp)
                                    .widthIn(max = 280.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = if (isFromCurrentUser) chatAccentColors.sentContent else MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = message.content.ifBlank { "Contacto" },
                                    color = if (isFromCurrentUser) chatAccentColors.sentContent else receivedTextColor,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .widthIn(max = 280.dp)
                                    .padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = if (isFromCurrentUser) chatAccentColors.sentContent else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = payload.normalizedName.ifBlank { "Contacto" },
                                        color = if (isFromCurrentUser) chatAccentColors.sentContent else receivedTextColor,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                                payload.normalizedPhone?.let { phone ->
                                    Text(
                                        text = "📞 $phone",
                                        color = if (isFromCurrentUser) chatAccentColors.sentContent else receivedTextColor,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                payload.normalizedEmail?.let { email ->
                                    Text(
                                        text = "✉️ $email",
                                        color = if (isFromCurrentUser) chatAccentColors.sentContent else receivedTextColor,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    TextButton(
                                        onClick = { onOpenContactChat(payload) },
                                        enabled = payload.hasReachableIdentity()
                                    ) {
                                        Text("Enviar mensaje")
                                    }
                                    TextButton(onClick = { onAddToDeviceContacts(payload) }) {
                                        Text("Añadir a contactos")
                                    }
                                }
                            }
                        }
                    }
                    "location" -> {
                        val mapsUrl = when {
                            !message.media_url.isNullOrBlank() -> message.media_url
                            message.content.contains("http", ignoreCase = true) -> {
                                val token = message.content
                                    .split(" ", "\n", "\t")
                                    .firstOrNull { it.startsWith("http", ignoreCase = true) }
                                token
                            }
                            else -> {
                                val query = message.content.trim().replace(" ", "+")
                                if (query.isBlank()) null else "https://maps.google.com/?q=$query"
                            }
                        }
                        Column(
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .padding(vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = if (isFromCurrentUser) chatAccentColors.sentContent else MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Ubicación",
                                    color = if (isFromCurrentUser) chatAccentColors.sentContent else receivedTextColor,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            LinkText(
                                backgroundColor = backgroundColor,
                                text = if (mapsUrl != null) {
                                    val normalizedContent = message.content
                                        .replace(mapsUrl, "")
                                        .trim()
                                        .ifBlank { "Abrir en mapa" }
                                    "$normalizedContent\n$mapsUrl"
                                } else {
                                    message.content.ifBlank { "Ubicación compartida" }
                                },
                                color = if (isFromCurrentUser) chatAccentColors.sentContent else receivedTextColor,
                                fontSize = 15.sp,
                                onUrlClick = onUrlClick
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = message.content,
                            color = if (isFromCurrentUser) chatAccentColors.sentContent else receivedTextColor
                        )
                    }
                    }
                } // End if deleted_for_all check

                if (shouldShowMediaCaption(message)) {
                    Text(
                        text = message.content.trim(),
                        color = if (isFromCurrentUser) chatAccentColors.sentContent else receivedTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                if (!message.deleted_for_all && message.reactions.any { it.key.isNotBlank() && it.value.isNotEmpty() }) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        maxItemsInEachRow = 3,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .fillMaxWidth()
                    ) {
                        val sortedReactions = message.reactions.entries
                            .map { it.key to it.value.distinct() }
                            .filter { it.first.isNotBlank() && it.second.isNotEmpty() }
                            .sortedByDescending { it.second.size }
                            .take(6)

                        sortedReactions.forEach { (emoji, users) ->
                            val reactedByMe = currentUserId?.let { uid ->
                                users.any { it.equals(uid, ignoreCase = true) }
                            } == true
                            androidx.compose.material3.Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (reactedByMe) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                },
                                border = androidx.compose.foundation.BorderStroke(
                                    width = 1.dp,
                                    color = if (reactedByMe) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                    } else {
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                                    }
                                ),
                                modifier = Modifier.clickable { onReactionClick(emoji) }
                            ) {
                                Text(
                                    text = "$emoji ${users.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    color = if (reactedByMe) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(2.dp))
                
                // Timestamp and status
                if (isFromCurrentUser && !isStickerMessage && !message.deleted_for_all && onRetrySend != null) {
                    conectate.com.presentation.messages.MessageDeliveryControls(message.status, uploadProgress, onRetrySend)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    val metadataColor = if (isStickerMessage) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
                    } else if (isFromCurrentUser) {
                        chatAccentColors.sentContent.copy(alpha = 0.7f)
                    } else {
                        receivedTextColor.copy(alpha = 0.6f)
                    }
                    message.created_at?.let { timestamp ->
                        // Simple timestamp parsing/display logic
                         Text(
                            text = formatIsoTimestamp(timestamp), // HH:mm (Local)
                            style = MaterialTheme.typography.bodySmall,
                            color = metadataColor
                        )
                    }
                    
                    if (message.is_edited && !message.deleted_for_all) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Editado",
                            style = MaterialTheme.typography.labelSmall,
                            color = metadataColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    
                    if (isFromCurrentUser) {
                        Spacer(modifier = Modifier.width(4.dp))

                        val normalizedStatus = message.status.trim().lowercase()
                        val (icon, tint) = when (normalizedStatus) {
                                    Constants.MESSAGE_STATUS_READ -> Icons.Default.DoneAll to Color(0xFF34B7F1)
                                    Constants.MESSAGE_STATUS_DELIVERED, "received" -> Icons.Default.DoneAll to Color(0xFFB0BEC5)
                                    Constants.MESSAGE_STATUS_SENT -> Icons.Default.Done to metadataColor
                                    "failed" -> Icons.Default.Warning to MaterialTheme.colorScheme.error
                                    "sending", "pending" -> Icons.Default.AccessTime to metadataColor
                                    else -> Icons.Default.Done to metadataColor
                        }
                        
                        Icon(
                            imageVector = icon,
                            contentDescription = normalizedStatus,
                            modifier = Modifier.size(14.dp),
                            tint = tint
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBadgeItem(badge: conectate.com.data.model.Badge) {
    val badgeColor = try {
        Color(android.graphics.Color.parseColor(badge.color))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }

    androidx.compose.material3.Surface(
        color = badgeColor.copy(alpha = 0.2f),
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.5f)),
        modifier = Modifier.padding(start = 2.dp)
    ) {
        Text(
            text = badge.icon,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

private fun formatIsoTimestamp(isoString: String): String {
    return try {
        val instant = java.time.Instant.parse(isoString)
        val zoneId = java.time.ZoneId.systemDefault()
        val localTime = instant.atZone(zoneId)
        val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        localTime.format(formatter)
    } catch (e: Exception) {
        // Fallback to old substring method if parsing fails
        isoString.substringAfter("T").substringBefore(".").take(5)
    }
}

private fun shouldShowMediaCaption(message: Message): Boolean {
    val caption = message.content.trim()
    if (caption.isBlank() || message.deleted_for_all) return false
    // El título del sticker pertenece al catálogo/selector, no a la burbuja enviada.
    // Los stickers de texto ya se renderizan dentro de la rama "sticker".
    if (message.content_type == "sticker") return false

    val normalized = caption.lowercase(Locale.getDefault())
    val defaults = when (message.content_type) {
        "image" -> setOf("📷 imagen", "🖼️ imagen", "foto", "imagen", "archivo")
        "video" -> setOf("🎥 video", "video", "archivo")
        "view_once_image" -> setOf("📷 foto ver una vez", "👁️ foto ver una vez", "foto ver una vez")
        "view_once_video" -> setOf("🎥 video ver una vez", "👁️ video ver una vez", "video ver una vez")
        "gif" -> setOf("🌀 gif", "gif")
        else -> emptySet()
    }

    return message.content_type in setOf("image", "video", "view_once_image", "view_once_video", "gif") &&
        normalized !in defaults
}

private fun formatMessageTime(date: Date): String {
    val now = Date()
    val calendar = Calendar.getInstance()
    val messageCalendar = Calendar.getInstance().apply { time = date }
    
    return when {
        // Same day - show only time
        calendar.get(Calendar.DAY_OF_YEAR) == messageCalendar.get(Calendar.DAY_OF_YEAR) &&
        calendar.get(Calendar.YEAR) == messageCalendar.get(Calendar.YEAR) -> {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
        }
        // Yesterday
        calendar.get(Calendar.DAY_OF_YEAR) - messageCalendar.get(Calendar.DAY_OF_YEAR) == 1 &&
        calendar.get(Calendar.YEAR) == messageCalendar.get(Calendar.YEAR) -> {
            "Ayer ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)}"
        }
        // Other - show date and time
        else -> {
            SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(date)
        }
    }
}
